package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskStateCanceling
import platform.Foundation.NSURLSessionTaskStateCompleted
import platform.Foundation.NSURLSessionTaskStateSuspended
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.darwin.NSObject

private const val BACKGROUND_SESSION_IDENTIFIER = "com.nuvio.app.downloads"
private const val NS_URL_ERROR_CANCELLED = -999L
private const val RESUME_DATA_KEY = "NSURLSessionDownloadTaskResumeData"
private const val PROGRESS_INTERVAL_SECONDS = 0.5
private const val PROGRESS_BYTE_DELTA = 512L * 1024L

/**
 * Downloads that the system keeps running while the app is in the background or closed.
 *
 * Every download is a task of one background `NSURLSession`. The system moves the data, wakes the
 * app when something finishes, and keeps going after the app is suspended. The app reconnects to
 * the session (and so to its tasks) on every launch. A download is paused by cancelling its task
 * while keeping the resume data, and resumed from that data.
 *
 * A file only gets its final name once it is completely downloaded, which is how a download that
 * finished while the app was closed is recognised at the next start (see `restoreItem`).
 */
@OptIn(ExperimentalForeignApi::class)
internal object BackgroundDownloads {
    private class Callbacks(
        val onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        val onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        val onFailure: (message: String) -> Unit,
    )

    private class Progress(var bytes: Long = -1L, var timestamp: Double = 0.0)

    private val lock = SynchronizedObject()
    private val callbacks = mutableMapOf<String, Callbacks>()
    private val progress = mutableMapOf<String, Progress>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val delegate = BackgroundDownloadDelegate()
    private val session: NSURLSession by lazy {
        val configuration = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(
            BACKGROUND_SESSION_IDENTIFIER,
        ).apply {
            sessionSendsLaunchEvents = true
            discretionary = false
            allowsCellularAccess = true
            allowsExpensiveNetworkAccess = true
            allowsConstrainedNetworkAccess = true
        }
        NSURLSession.sessionWithConfiguration(
            configuration = configuration,
            delegate = delegate,
            delegateQueue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 },
        )
    }

    fun ensureSession() {
        session
    }

    fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle {
        val item = request.item
        synchronized(lock) {
            callbacks[item.id] = Callbacks(onProgress, onSuccess, onFailure)
            progress.remove(item.id)
        }
        val handle = BackgroundTaskHandle(item.id, item.fileName)
        handle.job = scope.launch {
            try {
                val destination = "${downloadsDirectoryPath()}/${item.fileName}"
                DownloadSubtitles.prepare(item, NSURL.fileURLWithPath(destination).absoluteString!!)
                attachOrCreateTask(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(item.id, error.message ?: "Download failed")
            }
        }
        return handle
    }

    /** Stops the transfer but keeps what was downloaded so it can be resumed. */
    fun pause(itemId: String, fileName: String) {
        synchronized(lock) {
            callbacks.remove(itemId)
            progress.remove(itemId)
        }
        scope.launch {
            val description = downloadTaskDescription(itemId, fileName)
            allDownloadTasks().filter { it.taskDescription == description }.forEach { task ->
                task.cancelByProducingResumeData { data ->
                    data?.writeToFile(resumeDataPath(fileName), atomically = true)
                }
            }
        }
    }

    /** Cancels any task of the download with this file name and forgets its resume data. */
    fun discard(fileName: String) {
        scope.launch {
            allDownloadTasks()
                .filter { parseDescription(it.taskDescription)?.second == fileName }
                .forEach { it.cancel() }
            removePathIfExists(resumeDataPath(fileName))
        }
    }

    private suspend fun attachOrCreateTask(request: DownloadPlatformRequest) {
        val item = request.item
        val description = downloadTaskDescription(item.id, item.fileName)
        val existing = allDownloadTasks().firstOrNull { task ->
            task.taskDescription == description &&
                task.state != NSURLSessionTaskStateCanceling &&
                task.state != NSURLSessionTaskStateCompleted
        }
        if (existing != null) {
            // Already running in the system (the app was relaunched): just follow it again.
            if (existing.state == NSURLSessionTaskStateSuspended) existing.resume()
            reportProgress(item.id, existing.countOfBytesReceived, existing.countOfBytesExpectedToReceive.takeIf { it > 0L })
            return
        }

        val resumePath = resumeDataPath(item.fileName)
        val resumeData = NSData.dataWithContentsOfFile(resumePath)
        val task = if (resumeData != null) {
            // Consumed: if it is unusable, the next attempt starts from the beginning.
            removePathIfExists(resumePath)
            session.downloadTaskWithResumeData(resumeData)
        } else {
            session.downloadTaskWithRequest(buildRequest(request))
        }
        task.taskDescription = description
        reportProgress(item.id, 0L, null, force = true)
        task.resume()
    }

    private fun buildRequest(request: DownloadPlatformRequest): NSMutableURLRequest {
        val nativeRequest = NSMutableURLRequest(
            uRL = NSURL(string = request.sourceUrl),
            cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
            timeoutInterval = 60.0,
        )
        nativeRequest.setHTTPMethod("GET")
        nativeRequest.setAllowsCellularAccess(true)
        request.sourceHeaders.forEach { (key, value) ->
            nativeRequest.setValue(value, forHTTPHeaderField = key)
        }
        return nativeRequest
    }

    private suspend fun allDownloadTasks(): List<NSURLSessionDownloadTask> =
        suspendCoroutine { continuation ->
            session.getAllTasksWithCompletionHandler { tasks ->
                continuation.resume((tasks as? List<*>).orEmpty().filterIsInstance<NSURLSessionDownloadTask>())
            }
        }

    // ---- events from the delegate ---------------------------------------------------------

    fun reportProgress(itemId: String, downloadedBytes: Long, totalBytes: Long?, force: Boolean = false) {
        // Several updates a second arrive; only pass on one every half second or 512 KB.
        val target: Callbacks? = synchronized(lock) {
            val callback = callbacks[itemId]
            if (callback == null) {
                null
            } else {
                val state = progress.getOrPut(itemId) { Progress() }
                val now = NSDate().timeIntervalSince1970
                val reachedEnd = totalBytes != null && downloadedBytes >= totalBytes
                val skip = !force && !reachedEnd && state.bytes >= 0L &&
                    downloadedBytes - state.bytes < PROGRESS_BYTE_DELTA &&
                    now - state.timestamp < PROGRESS_INTERVAL_SECONDS
                if (skip) {
                    null
                } else {
                    state.bytes = downloadedBytes
                    state.timestamp = now
                    callback
                }
            }
        }
        target?.onProgress(downloadedBytes.coerceAtLeast(0L), totalBytes)
    }

    fun succeed(itemId: String, destinationPath: String) {
        val target = synchronized(lock) {
            progress.remove(itemId)
            callbacks.remove(itemId)
        } ?: return
        val uri = NSURL.fileURLWithPath(destinationPath).absoluteString ?: "file://$destinationPath"
        target.onSuccess(uri, fileSizeOrNull(destinationPath))
    }

    fun fail(itemId: String, message: String) {
        val target = synchronized(lock) {
            progress.remove(itemId)
            callbacks.remove(itemId)
        } ?: return
        target.onFailure(message)
    }

    fun finishBackgroundEvents(identifier: String) {
        val handler = backgroundSessionCompletionHandlers.remove(identifier) ?: return
        // The system requires this to be called on the main thread.
        NSOperationQueue.mainQueue.addOperationWithBlock { handler() }
    }
}

internal fun downloadTaskDescription(itemId: String, fileName: String): String = "$itemId\n$fileName"

internal fun parseDescription(description: String?): Pair<String, String>? {
    val parts = description?.split('\n', limit = 2) ?: return null
    return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0] to parts[1] else null
}

internal fun resumeDataPath(fileName: String): String = "${downloadsDirectoryPath()}/$fileName.resumedata"

private class BackgroundTaskHandle(
    private val itemId: String,
    private val fileName: String,
) : DownloadsTaskHandle {
    var job: Job? = null

    override fun cancel() {
        job?.cancel()
        BackgroundDownloads.pause(itemId, fileName)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class BackgroundDownloadDelegate : NSObject(), NSURLSessionDownloadDelegateProtocol {

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long,
    ) {
        val (itemId, _) = parseDescription(downloadTask.taskDescription) ?: return
        BackgroundDownloads.reportProgress(
            itemId = itemId,
            downloadedBytes = totalBytesWritten,
            totalBytes = totalBytesExpectedToWrite.takeIf { it > 0L },
        )
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: platform.Foundation.NSURL,
    ) {
        val (itemId, fileName) = parseDescription(downloadTask.taskDescription) ?: return
        val status = (downloadTask.response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 200
        if (status !in 200..299) {
            BackgroundDownloads.fail(itemId, "Download failed: HTTP $status")
            return
        }
        // The temporary file disappears when this method returns, so it is moved right here.
        val source = didFinishDownloadingToURL.path
        val destination = "${downloadsDirectoryPath()}/$fileName"
        if (source == null) {
            BackgroundDownloads.fail(itemId, "Could not read the downloaded file")
            return
        }
        removePathIfExists(destination)
        val moved = NSFileManager.defaultManager.moveItemAtPath(
            srcPath = source,
            toPath = destination,
            error = null,
        )
        if (!moved) {
            BackgroundDownloads.fail(itemId, "Could not save the downloaded file")
            return
        }
        removePathIfExists(resumeDataPath(fileName))
        BackgroundDownloads.succeed(itemId, destination)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        val error = didCompleteWithError ?: return
        val (itemId, fileName) = parseDescription(task.taskDescription) ?: return

        // Keep whatever can be resumed, also when the system cancelled the task (for example after
        // the app was force-quit).
        (error.userInfo[RESUME_DATA_KEY] as? NSData)?.writeToFile(resumeDataPath(fileName), atomically = true)

        // Our own pause/cancel; the repository already knows.
        if (error.code == NS_URL_ERROR_CANCELLED) return
        BackgroundDownloads.fail(itemId, error.localizedDescription)
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        val identifier = session.configuration.identifier ?: return
        BackgroundDownloads.finishBackgroundEvents(identifier)
    }
}
