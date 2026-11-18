import UIKit
import ComposeApp

/// Tracks the full-screen player so the root controller can hide the home indicator, the status bar
/// and the screen-edge gestures. iOS 12 build: no SwiftUI or Combine, plain properties.
final class SystemUI {
    static let shared = SystemUI()

    private init() {}

    private(set) weak var activePlayer: UIViewController?
    private(set) var isPlayerImmersive = false

    func playerDidBecomeVisible(_ player: UIViewController) {
        guard activePlayer !== player else { return }
        activePlayer = player
        isPlayerImmersive = true
        refresh()
    }

    func playerDidBecomeHidden(_ player: UIViewController) {
        guard activePlayer === player || activePlayer == nil else { return }
        activePlayer = nil
        isPlayerImmersive = false
        refresh()
    }

    /// Picture in picture is not offered on this build, so there is no placeholder to show.
    func setPictureInPicturePlaceholder(_ player: UIViewController, visible: Bool) {}

    private func refresh() {
        for window in UIApplication.shared.windows {
            guard let root = window.rootViewController else { continue }
            markNeedsUpdate(root)
        }
    }

    private func markNeedsUpdate(_ controller: UIViewController) {
        controller.setNeedsUpdateOfHomeIndicatorAutoHidden()
        controller.setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
        controller.setNeedsStatusBarAppearanceUpdate()

        for child in controller.children {
            markNeedsUpdate(child)
        }
        if let presented = controller.presentedViewController {
            markNeedsUpdate(presented)
        }
    }
}
