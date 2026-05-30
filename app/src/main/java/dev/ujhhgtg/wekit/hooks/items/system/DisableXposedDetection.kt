package dev.ujhhgtg.wekit.hooks.items.system

import android.content.Context
import androidx.compose.material3.Text
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "系统与隐私/禁止微信检测 Xposed", description = "防止微信检测 Xposed 框架是否存在")
object DisableXposedDetection : SwitchHookItem(), IResolvesDex {

    private val methodCheckStackTraceElements by dexMethod()

    override fun onEnable() {
        if (methodCheckStackTraceElements.isPlaceholder) return

        methodCheckStackTraceElements.hookBefore {
            result = false
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodCheckStackTraceElements.find(dexKit, allowFailure = true) {
            searchPackages("com.tencent.mm.app")
            matcher {
                usingEqStrings(
                    "de.robv.android.xposed.XposedBridge",
                    "com.zte.heartyservice.SCC.FrameworkBridge"
                )
            }
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState && HostInfo.isHostGooglePlay) {
            showComposeDialog(HostInfo.application) {
                AlertDialogContent(
                    title = { Text("禁止微信检测 Xposed") },
                    text = {
                        Text("Google Play 版微信无此检测, 开启可能导致闪退, 已关闭功能!")
                    })
            }
        }

        return false
    }
}
