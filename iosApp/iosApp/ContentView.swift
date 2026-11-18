import AVKit
import UIKit
import ComposeApp

let nuvioBackgroundColor = UIColor(
    red: 0.051,
    green: 0.051,
    blue: 0.051,
    alpha: 1.0
)

enum NuvioComposeHost {
    static let registerPlayerBridge: Void = {
        NuvioPlayerRegistration.register()
    }()

    static func wrap(
        _ contentController: UIViewController,
        backgroundColor: UIColor = nuvioBackgroundColor,
        disablesInteractiveContentPopGesture: Bool = false,
        verticalBar: VerticalBar? = nil,
        hinge: Hinge? = nil,
        onTabBarControllerAvailable: ((UITabBarController) -> Void)? = nil
    ) -> RootComposeViewController {
        _ = registerPlayerBridge
        contentController.view.backgroundColor = backgroundColor
        return RootComposeViewController(
            contentController: contentController,
            backgroundColor: backgroundColor,
            disablesInteractiveContentPopGesture: disablesInteractiveContentPopGesture,
            verticalBar: verticalBar,
            hinge: hinge,
            onTabBarControllerAvailable: onTabBarControllerAvailable
        )
    }
}

/// A navigation-neutral container for Compose. The MPV player is nested below the
/// Compose controller, so UIKit's immersive-system-UI queries need to be forwarded
/// to the deepest child that requests them.
final class RootComposeViewController: UIViewController {
    private let contentController: UIViewController
    private let backgroundColor: UIColor
    private let disablesInteractiveContentPopGesture: Bool
    private let verticalBar: VerticalBar?
    private let hinge: Hinge?
    private let onTabBarControllerAvailable: ((UITabBarController) -> Void)?
    private var leadingConstraint: NSLayoutConstraint?
    private var trailingConstraint: NSLayoutConstraint?
    private var isHalfOpen = false
    private var foldBoundsSize = CGSize.zero

    init(
        contentController: UIViewController,
        backgroundColor: UIColor,
        disablesInteractiveContentPopGesture: Bool,
        verticalBar: VerticalBar?,
        hinge: Hinge?,
        onTabBarControllerAvailable: ((UITabBarController) -> Void)?
    ) {
        self.contentController = contentController
        self.backgroundColor = backgroundColor
        self.disablesInteractiveContentPopGesture = disablesInteractiveContentPopGesture
        self.verticalBar = verticalBar
        self.hinge = hinge
        self.onTabBarControllerAvailable = onTabBarControllerAvailable
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = backgroundColor
        view.isOpaque = backgroundColor != .clear
        contentController.view.backgroundColor = backgroundColor

        addChild(contentController)
        view.addSubview(contentController.view)
        contentController.view.translatesAutoresizingMaskIntoConstraints = false
        let leadingConstraint = contentController.view.leadingAnchor.constraint(equalTo: view.leadingAnchor)
        let trailingConstraint = contentController.view.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        NSLayoutConstraint.activate([
            contentController.view.topAnchor.constraint(equalTo: view.topAnchor),
            contentController.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            leadingConstraint,
            trailingConstraint,
        ])
        self.leadingConstraint = leadingConstraint
        self.trailingConstraint = trailingConstraint
        // NUVIO_NO_IOS27_SDK: set when building with an Xcode whose SDK predates the iOS 27.1 APIs below.
        #if !NUVIO_NO_IOS27_SDK
        if #available(iOS 27.1, *) {
            registerForTraitChanges(UITraitCollection.systemTraitsAffectingVerticalBarEdge) {
                (controller: RootComposeViewController, _: UITraitCollection) in
                controller.updateVerticalBar()
            }
            if hinge != nil {
                view.addInteraction(UIHingeInteraction { [weak self] _, update in
                    self?.isHalfOpen = update.hinge?.status == .partiallyOpen
                    self?.updateFold()
                })
            }
        }
        #endif
        contentController.didMove(toParent: self)
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        updateVerticalBar()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard view.bounds.size != foldBoundsSize else { return }
        foldBoundsSize = view.bounds.size
        updateFold()
    }

    private func updateFold() {
        guard let hinge else { return }
        #if !NUVIO_NO_IOS27_SDK
        guard #available(iOS 27.1, *), isHalfOpen,
              let fold = view.reservedRegions(kind: .division).first(where: { $0.frame.width > $0.frame.height })
        else {
            hinge.clear()
            return
        }
        hinge.update(top: Double(fold.frame.minY), bottom: Double(fold.frame.maxY))
        #else
        hinge.clear()
        #endif
    }

    private func updateVerticalBar() {
        let insets = verticalBarInsets
        if let verticalBar {
            verticalBar.update(start: Double(insets.leading), end: Double(insets.trailing))
        } else {
            leadingConstraint?.constant = insets.leading
            trailingConstraint?.constant = -insets.trailing
        }
    }

    private var verticalBarInsets: NSDirectionalEdgeInsets {
        #if NUVIO_NO_IOS27_SDK
        return .zero
        #else
        guard #available(iOS 27.1, *) else { return .zero }
        let insets = view.safeAreaInsets
        let isRightToLeft = view.effectiveUserInterfaceLayoutDirection == .rightToLeft
        switch traitCollection.verticalBarEdge {
        case .leading:
            return NSDirectionalEdgeInsets(top: 0, leading: isRightToLeft ? insets.right : insets.left, bottom: 0, trailing: 0)
        case .trailing:
            return NSDirectionalEdgeInsets(top: 0, leading: 0, bottom: 0, trailing: isRightToLeft ? insets.left : insets.right)
        default:
            return .zero
        }
        #endif
    }

    override var childForHomeIndicatorAutoHidden: UIViewController? {
        nil
    }

    override var childForScreenEdgesDeferringSystemGestures: UIViewController? {
        nil
    }

    override var childForStatusBarHidden: UIViewController? {
        nil
    }

    override var prefersHomeIndicatorAutoHidden: Bool {
        SystemUI.shared.activePlayer?.prefersHomeIndicatorAutoHidden
            ?? immersiveController(in: contentController)?.prefersHomeIndicatorAutoHidden
            ?? false
    }

    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        SystemUI.shared.activePlayer?.preferredScreenEdgesDeferringSystemGestures
            ?? immersiveController(in: contentController)?.preferredScreenEdgesDeferringSystemGestures
            ?? []
    }

    override var prefersStatusBarHidden: Bool {
        SystemUI.shared.activePlayer?.prefersStatusBarHidden
            ?? immersiveController(in: contentController)?.prefersStatusBarHidden
            ?? false
    }

    override var preferredStatusBarUpdateAnimation: UIStatusBarAnimation {
        .fade
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        configureBackGestures(isVisible: true)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        configureBackGestures(isVisible: true)
        if let tabBarController {
            onTabBarControllerAvailable?(tabBarController)
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        configureBackGestures(isVisible: false)
        super.viewWillDisappear(animated)
    }

    func refreshImmersiveSystemUI() {
        setNeedsUpdateOfHomeIndicatorAutoHidden()
        setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
        setNeedsStatusBarAppearanceUpdate()
    }

    private func configureBackGestures(isVisible: Bool) {
        if #available(iOS 26.0, *) {
            navigationController?.interactiveContentPopGestureRecognizer?.isEnabled = false
        }
        navigationController?.interactivePopGestureRecognizer?.isEnabled =
            isVisible ? !disablesInteractiveContentPopGesture : true
    }

    private func immersiveController(in controller: UIViewController?) -> UIViewController? {
        guard let controller else { return nil }

        if controller.prefersHomeIndicatorAutoHidden ||
            !controller.preferredScreenEdgesDeferringSystemGestures.isEmpty ||
            controller.prefersStatusBarHidden {
            return controller
        }

        if let presented = immersiveController(in: controller.presentedViewController) {
            return presented
        }

        for child in controller.children.reversed() {
            if let immersiveChild = immersiveController(in: child) {
                return immersiveChild
            }
        }

        return nil
    }
}
