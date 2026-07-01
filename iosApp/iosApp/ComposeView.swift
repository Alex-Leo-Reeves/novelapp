import SwiftUI
import UIKit

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        UIHostingController(rootView: ContentView())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
