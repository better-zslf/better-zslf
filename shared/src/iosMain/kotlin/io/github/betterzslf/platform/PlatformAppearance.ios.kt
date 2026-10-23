@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.betterzslf.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.uikit.LocalUIViewController
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationState
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIWindowLevelNormal
import platform.UIKit.UIWindowSceneSessionRoleApplication

/** Applies the manual theme only to the window hosting this Compose controller. */
@Composable
actual fun PlatformAppearance(dark: Boolean?) {
    val controller = LocalUIViewController.current
    val style = when (dark) {
        true -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
        false -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
        // Never resolve null to the current dark/light value: that would pin System mode.
        null -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
    }
    SideEffect {
        // Do not fall back to another scene's key window while this controller is detached.
        val window = controller.viewIfLoaded?.window ?: return@SideEffect
        val scene = window.windowScene ?: return@SideEffect
        if (!UIApplication.sharedApplication.connectedScenes.contains(scene)) return@SideEffect
        if (scene.session.role != UIWindowSceneSessionRoleApplication) return@SideEffect
        if (scene.activationState != UISceneActivationState.UISceneActivationStateForegroundActive &&
            scene.activationState != UISceneActivationState.UISceneActivationStateForegroundInactive
        ) return@SideEffect
        if (!scene.windows.contains(window) || window.hidden || window.windowLevel != UIWindowLevelNormal ||
            window.rootViewController == null
        ) return@SideEffect

        if (window.overrideUserInterfaceStyle != style) {
            window.overrideUserInterfaceStyle = style
            // The default Compose controller delegates status bar style to UIKit; it does not pin
            // light/dark content. Refresh both the child and the SwiftUI/root hosting controller.
            controller.setNeedsStatusBarAppearanceUpdate()
            window.rootViewController?.setNeedsStatusBarAppearanceUpdate()
        }
    }
}
