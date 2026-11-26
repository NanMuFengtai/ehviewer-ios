// 用 iOS 26 SDK 编译 Kotlin/Native 的 cinterop 时，platform.CoreGraphics 会把
// 高版本才引入的常量生成为强引用。装到较低版本的 iOS 上，dyld 启动阶段解析不到
// 这些数据符号就直接 abort —— 表现就是「点开黑屏一下退回桌面」。
//
// 在可执行文件里给它们提供 weak 兜底定义：符号在链接期就被满足，dyld 不再去
// 系统库里找，老系统上不会崩。代价是新系统上这些常量在这里也变成 NULL，
// 所以只对「本项目不会真正使用」的符号这么做，且逐个确认。

#include <CoreFoundation/CoreFoundation.h>

// iOS 26 CoreGraphics 新增，CGColorSpace 的 HDR 位深键。
// 本项目不创建 HDR 色彩空间，只为满足链接。
__attribute__((weak, visibility("default")))
const void *const kCGAdaptiveMaximumBitDepth = 0;
