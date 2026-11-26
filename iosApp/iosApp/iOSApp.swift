import SwiftUI
import UIKit
import Darwin
import Shared

// MARK: - 崩溃现场记录
//
// 装到真机上最怕的是「打开一瞬间退回桌面」，什么信息都没有。这里在进程启动最早期
// 就把路径塞进一块预分配的 C 缓冲区，然后挂上 SIGSEGV/SIGABRT 等信号处理器和
// Objective-C 异常处理器，出事时把信号号直接写进 Documents/crash.log。
// UIFileSharingEnabled 已经打开，可以从「文件 → 我的 iPhone → 很太之家」取日志。

private let kCrashLogName = "crash.log"
private let kBuildTag = "1.15.23-ios15-release"
private let kPathCap = 4096
/// 预分配，signal handler 里不能再 malloc
private let gCrashPath = UnsafeMutablePointer<CChar>.allocate(capacity: kPathCap)

private func documentsDirectory() -> URL {
    FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
}

private func crashLogURL() -> URL {
    documentsDirectory().appendingPathComponent(kCrashLogName)
}

private func primeCrashPath() {
    let path = crashLogURL().path
    path.withCString { src in
        let n = min(strlen(src), kPathCap - 1)
        for i in 0..<n { gCrashPath[i] = src[i] }
        gCrashPath[n] = 0
    }
}

/// 只用 POSIX open/write，不走 Foundation
private func appendRaw(_ text: String) {
    let fd = open(gCrashPath, O_WRONLY | O_CREAT | O_APPEND, 0o644)
    guard fd >= 0 else { return }
    text.withCString { _ = write(fd, $0, strlen($0)) }
    close(fd)
}

private func handleFatalSignal(_ sig: Int32) {
    let name: String
    switch sig {
    case SIGSEGV: name = "SIGSEGV(11) 段错误"
    case SIGABRT: name = "SIGABRT(6) 异常中止"
    case SIGBUS:  name = "SIGBUS(10) 总线错误"
    case SIGILL:  name = "SIGILL(4) 非法指令"
    case SIGFPE:  name = "SIGFPE(8) 算术异常"
    case SIGTRAP: name = "SIGTRAP(5) 陷阱"
    default:      name = "signal \(sig)"
    }
    appendRaw("\n=== 崩溃信号 \(name) ===\n")
    signal(sig, SIG_DFL)
    raise(sig)
}

/// Objective-C 异常走这里，可以正常用 Foundation
private func handleUncaughtException(_ exception: NSException) {
    let body = """
    === 未捕获异常 ===
    name: \(exception.name.rawValue)
    reason: \(exception.reason ?? "nil")
    \(exception.callStackSymbols.joined(separator: "\n"))
    """
    appendRaw(body)
}

private func installCrashHandlers() {
    primeCrashPath()
    for s: Int32 in [SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP] {
        signal(s, handleFatalSignal)
    }
    NSSetUncaughtExceptionHandler(handleUncaughtException)
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        installCrashHandlers()
        appendRaw("\n--- 启动 \(Date()) 构建 \(kBuildTag) ---\n")
        return true
    }
}

// MARK: - 启动

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

struct RootView: View {
    @State private var showCompose = CrashReportStore.report() == nil

    var body: some View {
        if showCompose {
            ComposeView()
                .ignoresSafeArea(.all)
        } else {
            CrashReportView {
                CrashReportStore.clear()
                showCompose = true
            }
        }
    }
}

/// 上一次启动留下的崩溃记录。有就不急着进 Compose，先把现场摆出来。
enum CrashReportStore {
    static func report() -> String? {
        guard let raw = try? String(contentsOf: crashLogURL(), encoding: .utf8) else { return nil }
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        // 只有启动标记、没有崩溃痕迹，说明上次是正常退出，不拦
        if text.contains("崩溃信号") || text.contains("未捕获异常") { return text }
        return nil
    }

    static func clear() {
        try? FileManager.default.removeItem(at: crashLogURL())
    }
}

struct CrashReportView: View {
    let onContinue: () -> Void
    @State private var logText: String = CrashReportStore.report() ?? ""

    var body: some View {
        // 用 NavigationView 而不是 NavigationStack：后者是 iOS 16 才有的 API，
        // 部署目标降到 15.0 后编译期就不让用了，而且 iOS 15 设备上 NavigationStack
        // 这个符号根本不存在，一进这个界面就崩。
        NavigationView {
            ScrollView {
                Text(logText)
                    .font(.system(size: 11, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
            }
            .navigationTitle("上次崩溃的现场")
            .safeAreaInset(edge: .bottom) {
                Button {
                    onContinue()
                } label: {
                    Text("清空日志，继续进入")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.borderedProminent)
                .padding()
                .background(.regularMaterial)
            }
        }
        .navigationViewStyle(.stack)
    }
}

// MARK: - Compose 桥接

/// 把 Kotlin 侧 Compose 生成的 UIViewController 塞进 SwiftUI。
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
