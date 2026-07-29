package ltechnologies.onionphone.securemessenger.ui.applock

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity

/** Unwrap Compose/theme wrappers to the hosting [FragmentActivity] (needed for BiometricPrompt). */
tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
