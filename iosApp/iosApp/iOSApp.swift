import SwiftUI
import UIKit
import Shared

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
        }
    }
}

/// 把 Kotlin 侧 Compose 生成的 UIViewController 塞进 SwiftUI。
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
