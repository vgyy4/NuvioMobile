import UIKit
import Network
import UserNotifications
import ComposeApp

private let lockPlayerToLandscapeNotification = Notification.Name("NuvioPlayerLockLandscape")
private let unlockPlayerOrientationNotification = Notification.Name("NuvioPlayerUnlockOrientation")

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

@UIApplicationMain
final class OrientationLockAppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    // iOS 12 has no scenes: the app owns its window.
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        OrientationLockCoordinator.shared.start()
        NetworkPathBridge.shared.start()
        DownloadsLiveActivityManager.shared.start()
        UNUserNotificationCenter.current().delegate = self

        let window = UIWindow(frame: UIScreen.main.bounds)
        window.backgroundColor = nuvioBackgroundColor
        window.rootViewController = NuvioComposeHost.wrap(MainViewControllerKt.MainViewController())
        window.makeKeyAndVisible()
        self.window = window
        return true
    }

    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        OrientationLockCoordinator.shared.supportedOrientations
    }

    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        AppUrlBridgeKt.handleAppUrl(url: url.absoluteString)
        return true
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        NotificationCenter.default.post(name: playerSceneDidActivateNotification, object: nil)
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
        DownloadsPlatformDownloader_iosKt.pauseDownloadsForAppBackground()
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        if #available(iOS 14.0, *) {
            completionHandler([.banner, .list, .sound])
        } else {
            completionHandler([.alert, .sound])
        }
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
        if #available(iOS 13.0, *),
           let sceneOrientation = UIApplication.shared.connectedScenes
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
