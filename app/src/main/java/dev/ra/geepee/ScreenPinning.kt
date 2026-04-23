package dev.ra.geepee

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

internal fun requestScreenPinning(context: Context): Boolean {
    val activity = context.findActivity() ?: return false
    activity.startLockTask()
    return true
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
