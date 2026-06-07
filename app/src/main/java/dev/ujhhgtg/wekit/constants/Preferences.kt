package dev.ujhhgtg.wekit.constants

import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption

object Preferences {

    const val VERBOSE_LOG = "verbose_log"
    const val NO_DEX_RESOLVE = "no_dex_resolve"
    const val SHOW_STARTUP_TOAST = "toast_startup"
    const val USE_ACTIVITY_INSTEAD_OF_DIALOG = "use_activity"

    var verboseLog by prefOption(VERBOSE_LOG, false)
    var noDexResolve by prefOption(NO_DEX_RESOLVE, false)
    var showStartupToast  by prefOption(SHOW_STARTUP_TOAST, false)
    var useActivityInsteadOfDialog by prefOption(USE_ACTIVITY_INSTEAD_OF_DIALOG, false)

    // use this when Google fucked up itself again
//    var useActivityInsteadOfDialog: Boolean
//        get() = false
//        set(value) { WePrefs.putBool(USE_ACTIVITY_INSTEAD_OF_DIALOG, value) }
}
