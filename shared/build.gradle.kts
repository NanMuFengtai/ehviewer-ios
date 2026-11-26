import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    // iOS 目标只能在 macOS 上编译，所以用和 core 模块同一个开关控制
    if (providers.gradleProperty("enableIosTargets").isPresent) {
        iosArm64()
        iosSimulatorArm64()
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "Shared"
            isStatic = true
            // Compose 的运行时需要这些导出，Swift 侧才能在需要时访问
            binaryOption("bundleId", "moe.tarsin.ehviewer.shared")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.common)
            implementation(projects.core.ui)
            implementation(projects.core.i18n)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
