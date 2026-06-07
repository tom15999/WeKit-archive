package dev.ujhhgtg.wekit.loader.startup

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.hooks.core.HookItemsLoader
import dev.ujhhgtg.wekit.loader.utils.ActivityProxy
import dev.ujhhgtg.wekit.loader.utils.ParcelableFixer
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookAfterDirectly
import dev.ujhhgtg.wekit.utils.reflection.resolve

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        ParcelableFixer.init()

        DexCacheManager.init(HostInfo.versionCode.toString())

        if (TargetProcesses.isInMain) {
            val appContext = context.applicationContext ?: context
            ActivityProxy.init(appContext)

            initMainProcessHooks()
        }

        runCatching {
            HookItemsLoader.loadHookItems()
        }.onFailure { WeLogger.e(TAG, "failed to load hooks", it) }
    }

    private fun initMainProcessHooks() {
        LauncherUI::class.resolve().apply {
            // FIXME: see BasePrefsScreen line 298
//            firstMethod { name = "onCreate" }.hookBeforeDirectly {
//                Handler(Looper.getMainLooper()).post {
//                    while (true) {
//                        try {
//                            Looper.loop()
//                        } catch (e: Throwable) {
//                            if (e is NullPointerException && e.message?.contains("android.view.InputEventCompatProcessor.processInputEventForCompatibility(android.view.InputEvent)") == true) {
//                                WeLogger.e("FuckYouGoogle", "fuck you google", e)
//                            } else {
//                                throw e
//                            }
//                        }
//                    }
//                }
//            }

            firstMethod {
                name = "onCreate"
                parameters(Bundle::class)
            }.hookAfterDirectly {
                val activity = thisObject as Activity
                val sharedPreferences =
                    activity.getSharedPreferences("${PackageNames.WECHAT}_preferences", 0)
                RuntimeConfig.mmPrefs = sharedPreferences
            }
        }
    }

    private val TAG = This.Class.simpleName
}
