package com.rayo.isladinamica

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service, guide §4 (v1.11). Two jobs, nothing else:
 *
 * 1. Instant foreground-app detection: pushes the package in front to
 *    IslandService the moment it changes (TYPE_WINDOW_STATE_CHANGED /
 *    TYPE_WINDOWS_CHANGED), replacing the 2 s UsageStats polling for the
 *    launcher-aware clock. Only the package name is read — no window
 *    content is inspected or stored.
 * 2. When it is connected, the island overlay switches to
 *    TYPE_ACCESSIBILITY_OVERLAY, which sits above the status bar, so the
 *    bar can never steal the island's touches at y = 0.
 *
 * The user enables it by hand in Settings → Accessibility → Downloaded
 * apps (a button in MainActivity opens that screen). It is fully
 * optional: without it the island keeps working with UsageStats polling
 * and a normal application overlay. On Android 13+ a sideloaded app may
 * need "Allow restricted settings" first (app info → ⋮).
 *
 * Never enable it with `settings put secure enabled_accessibility_services`
 * from Shizuku: that would disable any other accessibility service the
 * user has.
 */
class FrenteService : AccessibilityService() {

    companion object {
        var instance: FrenteService? = null
    }

    override fun onServiceConnected() {
        instance = this
        Log.i("IslaDinamica", "FrenteService connected")
        IslandService.onAccessibilityChanged(true)
        IslandService.recrearOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i("IslaDinamica", "FrenteService disconnected")
        IslandService.onAccessibilityChanged(false)
        IslandService.recrearOverlay()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // No active window yet: ignore the event.
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return
        IslandService.onAppAlFrente(pkg)
    }

    override fun onInterrupt() {}
}
