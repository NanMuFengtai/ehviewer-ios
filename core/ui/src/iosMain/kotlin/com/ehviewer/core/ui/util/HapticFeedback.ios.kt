package com.ehviewer.core.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

@Composable
actual fun rememberHapticFeedback(): HapticFeedback = remember { IosHapticFeedback() }

class IosHapticFeedback : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        val style = when (hapticFeedbackType) {
            HapticFeedbackType.START -> UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium
            HapticFeedbackType.MOVE -> UIImpactFeedbackStyle.UIImpactFeedbackStyleLight
            HapticFeedbackType.END -> UIImpactFeedbackStyle.UIImpactFeedbackStyleRigid
        }
        UIImpactFeedbackGenerator(style).apply {
            prepare()
            impactOccurred()
        }
    }
}
