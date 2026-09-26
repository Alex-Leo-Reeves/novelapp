import SwiftUI
import UIKit
import ComposeApp

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ZStack {
                // Status-bar / notch strip stays pure black behind the app so the
                // UI is never drawn underneath the Dynamic Island or notch.
                Color.black.ignoresSafeArea()
                // ComposeView is intentionally NOT `.ignoresSafeArea()`: the shared
                // UI must sit inside the safe area so the search bar and bottom bar
                // fit the screen exactly like they do on Android.
                ComposeView()
            }
            // Dark app → white status-bar glyphs over the black strip.
            .preferredColorScheme(.dark)
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController()

        // Manga-reader immersive mode: MangaReaderSystemUiEffect (Kotlin) reports
        // full-screen on/off through IosSystemUiBridge; ImmersiveBridge applies it.
        context.coordinator.immersive.host = controller
        IosSystemUiBridge.shared.setListener { [immersive = context.coordinator.immersive] on in
            // Kotlin/Native boxes a Kotlin `Boolean` lambda parameter as
            // `KotlinBoolean` (an NSNumber subclass) when exporting to Swift,
            // so `on` is NOT a Swift `Bool`. Use `boolValue` — the compiler's
            // suggested `as! Bool` fix-it would trap at runtime.
            immersive.apply(on.boolValue)
        }

        // iOS-standard "swipe from the left edge to go back" gesture. Forwards
        // into the shared Compose navigation stack through IosBackBridge; it is
        // a no-op whenever there is nothing to pop (e.g. on the root screen).
        let edgeGesture = UIScreenEdgePanGestureRecognizer(
            target: context.coordinator.backTarget,
            action: #selector(EdgeBackTarget.handleEdgePan(_:))
        )
        edgeGesture.edges = .left
        controller.view.addGestureRecognizer(edgeGesture)
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    final class Coordinator {
        // Retained for the lifetime of the representable so the gesture target
        // and the system-UI listener are never deallocated while on screen.
        let backTarget = EdgeBackTarget()
        let immersive = ImmersiveBridge()
    }
}

/// iOS equivalent of Android's immersive manga-reader mode (MangaReaderSystemUi
/// on Android): collapses the safe area so pages run edge-to-edge, and lifts the
/// window above the status bar so the status bar disappears while reading
/// full-screen. Restored automatically when the reader closes.
final class ImmersiveBridge: NSObject {
    weak var host: UIViewController?

    func apply(_ on: Bool) {
        guard let host = host else { return }
        if on {
            host.additionalSafeAreaInsets = UIEdgeInsets(top: -1000, left: 0, bottom: -1000, right: 0)
            host.view.window?.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.statusBar.rawValue + 1)
        } else {
            host.additionalSafeAreaInsets = .zero
            host.view.window?.windowLevel = .normal
        }
    }
}

final class EdgeBackTarget: NSObject {
    private var didTrigger = false

    @objc func handleEdgePan(_ gesture: UIScreenEdgePanGestureRecognizer) {
        switch gesture.state {
        case .began, .changed:
            if !didTrigger, let view = gesture.view, gesture.translation(in: view).x > 20 {
                didTrigger = true
                _ = IosBackBridge.shared.triggerBack()
            }
        case .ended, .cancelled, .failed, .possible:
            didTrigger = false
        @unknown default:
            break
        }
    }
}
