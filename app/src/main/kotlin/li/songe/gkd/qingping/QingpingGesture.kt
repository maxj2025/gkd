package li.songe.gkd.qingping

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import li.songe.gkd.service.A11yService

fun validQingpingPoint(node: AccessibilityNodeInfo, rect: Rect, x: Float, y: Float): Boolean =
    QingpingActionBounds.allows(rect.left, rect.top, rect.right, rect.bottom, node.isVisibleToUser, x, y)

suspend fun dispatchQingpingGesture(gesture: GestureDescription, durationMs: Long): Boolean {
    val service = A11yService.instance ?: return false
    return awaitQingpingGesture(durationMs.coerceAtMost(60_000) + 1500) { done ->
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = done(false)
        }, null)
    }
}
