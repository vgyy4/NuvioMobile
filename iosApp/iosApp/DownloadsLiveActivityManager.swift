import Foundation

private let downloadsLiveStatusUpdatedNotification = Notification.Name("NuvioDownloadsLiveStatusUpdated")
private let downloadsLiveStatusPayloadKey = "nuvio.downloads.live_status.payload"

final class DownloadsLiveActivityManager {
    static let shared = DownloadsLiveActivityManager()

    private var observer: NSObjectProtocol?

    private init() {}

    func start() {
        guard observer == nil else { return }

        observer = NotificationCenter.default.addObserver(
            forName: downloadsLiveStatusUpdatedNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.syncFromPayloadStore()
        }

        syncFromPayloadStore()
    }

    // Live Activities need iOS 16.1 and Swift concurrency, neither exists on iOS 12.
    private func syncFromPayloadStore() {}
}
