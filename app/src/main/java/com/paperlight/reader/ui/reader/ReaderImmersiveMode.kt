package com.paperlight.reader.ui.reader

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
internal fun ReaderImmersiveMode(dark: Boolean = false) {
    val context = LocalContext.current
    val mainView = LocalView.current
    SideEffect {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        activity?.let { WindowCompat.getInsetsController(it.window, mainView).isAppearanceLightStatusBars = !dark }
    }
    val readerLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(mainView, readerLifecycle) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, mainView) }
        val oldBehavior = controller?.systemBarsBehavior
        val oldLightStatus = controller?.isAppearanceLightStatusBars
        val window = activity?.window
        val oldContrast = if (android.os.Build.VERSION.SDK_INT >= 29) window?.isStatusBarContrastEnforced else null
        val oldNavContrast = if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
        val oldStatusColor = window?.statusBarColor
        val oldNavigationColor = window?.navigationBarColor
        if (android.os.Build.VERSION.SDK_INT >= 29) window?.isStatusBarContrastEnforced = false
        if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced = false
        window?.statusBarColor = android.graphics.Color.TRANSPARENT
        window?.navigationBarColor = android.graphics.Color.TRANSPARENT
        val oldCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 28)
            window?.attributes?.layoutInDisplayCutoutMode else null
        if (android.os.Build.VERSION.SDK_INT >= 28 && window != null) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        fun immerse() {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.show(WindowInsetsCompat.Type.statusBars())
            controller?.hide(WindowInsetsCompat.Type.navigationBars())
        }
        val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) immerse() }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) immerse()
        }
        mainView.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        readerLifecycle.addObserver(observer)
        immerse()
        onDispose {
            mainView.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            readerLifecycle.removeObserver(observer)
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (oldBehavior != null) controller?.systemBarsBehavior = oldBehavior
            if (android.os.Build.VERSION.SDK_INT >= 29 && oldContrast != null)
                window?.isStatusBarContrastEnforced = oldContrast
            if (android.os.Build.VERSION.SDK_INT >= 29 && oldNavContrast != null)
                window?.isNavigationBarContrastEnforced = oldNavContrast
            if (oldStatusColor != null) window?.statusBarColor = oldStatusColor
            if (oldNavigationColor != null) window?.navigationBarColor = oldNavigationColor
            if (oldLightStatus != null) controller?.isAppearanceLightStatusBars = oldLightStatus
            if (android.os.Build.VERSION.SDK_INT >= 28 && window != null && oldCutoutMode != null) {
                window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = oldCutoutMode }
            }
        }
    }
}
