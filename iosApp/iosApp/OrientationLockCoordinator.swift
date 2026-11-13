import UIKit
import AVFoundation
import Network
import UserNotifications
import ComposeApp

private let lockPlayerToLandscapeNotification = Notification.Name("NuvioPlayerLockLandscape")
private let unlockPlayerOrientationNotification = Notification.Name("NuvioPlayerUnlockOrientation")

/// Keeps the app running while the screen is locked so torrent downloads (which need this app and
/// its engine, unlike regular downloads that the system continues by itself) keep going. It plays
/// silence with the app's `audio` background mode, mixed with other audio, and only while a torrent
/// download is running. If the system takes the audio away (a call, an alarm) the downloads are
/// paused instead, and they resume when the app is opened again.
final class DownloadKeepAlive {
    static let shared = DownloadKeepAlive()
    private var player: AVAudioPlayer?
    private var timer: Timer?
    private var observingInterruptions = false

    /// Starts the silent playback. Returns false when it could not start.
    @discardableResult
    func start() -> Bool {
        observeInterruptionsOnce()
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
        } catch {
            return false
        }
        if player == nil {
            player = DownloadKeepAlive.makeSilentPlayer()
        }
        guard let player = player else { return false }
        player.numberOfLoops = -1
        if !player.isPlaying {
            guard player.play() else { return false }
        }
        startWatchdog()
        return true
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        player?.stop()
    }

    // While running in the background: stop when no torrent download is left, and start the
    // playback again if something else stopped it.
    private func startWatchdog() {
        guard timer == nil else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            if !DownloadsPlatformDownloader_iosKt.hasActiveP2pDownloads() {
                self.stop()
                return
            }
            if let player = self.player, !player.isPlaying {
                _ = self.start()
            }
        }
    }

    private func observeInterruptionsOnce() {
        guard !observingInterruptions else { return }
        observingInterruptions = true
        NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw),
                  type == .began else { return }
            if UIApplication.shared.applicationState != .active {
                DownloadsPlatformDownloader_iosKt.pauseDownloadsForAppBackground()
            }
        }
    }

    // One second of silence (mono, 8 kHz, 16-bit WAV), played in a loop.
    private static func makeSilentPlayer() -> AVAudioPlayer? {
        let sampleRate: UInt32 = 8000
        let dataSize = UInt32(sampleRate * 2)
        var wav = Data()
        func append32(_ value: UInt32) {
            var little = value.littleEndian
            wav.append(Data(bytes: &little, count: 4))
        }
        func append16(_ value: UInt16) {
            var little = value.littleEndian
            wav.append(Data(bytes: &little, count: 2))
        }
        wav.append(Data("RIFF".utf8)); append32(36 + dataSize); wav.append(Data("WAVE".utf8))
        wav.append(Data("fmt ".utf8)); append32(16); append16(1); append16(1)
        append32(sampleRate); append32(sampleRate * 2); append16(2); append16(16)
        wav.append(Data("data".utf8)); append32(dataSize); wav.append(Data(count: Int(dataSize)))
        return try? AVAudioPlayer(data: wav)
    }
}

/// Reports connectivity changes from the system to the shared code. The system calls this when
/// the connection comes or goes, so nothing polls.
final class NetworkPathBridge {
    static let shared = NetworkPathBridge()
    private let monitor = NWPathMonitor()
    private var started = false

    func start() {
        guard !started else { return }
        started = true
        monitor.pathUpdateHandler = { path in
            NetworkPathMonitor_iosKt.reportNativeNetworkPath(connected: path.status == .satisfied)
        }
        monitor.start(queue: DispatchQueue(label: "com.nuvio.app.network-path"))
    }
}

final class OrientationLockAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        OrientationLockCoordinator.shared.start()
        NetworkPathBridge.shared.start()
        DownloadsLiveActivityManager.shared.start()
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        OrientationLockCoordinator.shared.supportedOrientations
    }

    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        DownloadsPlatformDownloader_iosKt.handleDownloadsBackgroundEvents(
            identifier: identifier,
            completionHandler: completionHandler
        )
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        // Torrent downloads need the app to keep running: keep it alive with silent audio while
        // they run. If that is not possible they are paused and resume when the app is opened.
        if DownloadsPlatformDownloader_iosKt.hasActiveP2pDownloads() {
            if !DownloadKeepAlive.shared.start() {
                DownloadsPlatformDownloader_iosKt.pauseDownloadsForAppBackground()
            }
        }
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        DownloadKeepAlive.shared.stop()
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if let deepLink = response.notification.request.content.userInfo["deeplink"] as? String {
            AppUrlBridgeKt.handleAppUrl(url: deepLink)
        }
        completionHandler()
    }
}

final class OrientationLockCoordinator {
    static let shared = OrientationLockCoordinator()

    private(set) var supportedOrientations: UIInterfaceOrientationMask = .allButUpsideDown
    private var observers: [NSObjectProtocol] = []

    private init() {}

    func start() {
        guard observers.isEmpty else { return }

        let center = NotificationCenter.default
        observers.append(
            center.addObserver(forName: lockPlayerToLandscapeNotification, object: nil, queue: .main) { [weak self] _ in
                self?.setLandscapeLock(enabled: true)
            }
        )
        observers.append(
            center.addObserver(forName: unlockPlayerOrientationNotification, object: nil, queue: .main) { [weak self] _ in
                self?.setLandscapeLock(enabled: false)
            }
        )
    }

    private func setLandscapeLock(enabled: Bool) {
        let nextOrientations: UIInterfaceOrientationMask = enabled ? .landscape : .allButUpsideDown
        supportedOrientations = nextOrientations
        requestOrientationUpdate(for: nextOrientations, forceRotate: enabled)
    }

    private func requestOrientationUpdate(for mask: UIInterfaceOrientationMask, forceRotate: Bool) {
        if #available(iOS 16.0, *) {
            let preferences = UIWindowScene.GeometryPreferences.iOS(interfaceOrientations: mask)
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .forEach { scene in
                    scene.requestGeometryUpdate(preferences) { error in
                        print("[OrientationLockCoordinator] Geometry update failed: \(error.localizedDescription)")
                    }
                }
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .forEach { window in
                    window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
                }
        } else {
            if forceRotate {
                UIDevice.current.setValue(preferredLandscapeOrientation.rawValue, forKey: "orientation")
            } else if UIDevice.current.orientation.isPortrait {
                UIDevice.current.setValue(UIInterfaceOrientation.portrait.rawValue, forKey: "orientation")
            }
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }

    private var preferredLandscapeOrientation: UIInterfaceOrientation {
        if let sceneOrientation = UIApplication.shared.connectedScenes
            .compactMap({ ($0 as? UIWindowScene)?.interfaceOrientation })
            .first(where: \.isLandscape) {
            return sceneOrientation
        }

        switch UIDevice.current.orientation {
        case .landscapeLeft:
            return .landscapeRight
        case .landscapeRight:
            return .landscapeLeft
        default:
            return .landscapeRight
        }
    }
}
