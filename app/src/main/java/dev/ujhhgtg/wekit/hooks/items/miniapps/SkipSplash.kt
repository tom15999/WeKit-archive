package dev.ujhhgtg.wekit.hooks.items.miniapps

import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.TargetProcesses
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "小程序/跳过启动页面", description = "跳过小程序启动页面, 变相去广告 (实验性)")
object SkipSplash : SwitchHookItem(), IResolvesDex {

    private val methodShowSplash by dexMethod()

    override fun startup() {
        if (!TargetProcesses.isInMain && TargetProcesses.currentType != TargetProcesses.PROC_APPBRAND) return
        _isEnabled = WePrefs.getBoolOrFalse(path)
        if (_isEnabled) enable()
    }

    override fun onEnable() {
        methodShowSplash.hookBefore { result = null }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodShowSplash.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.appbrand")
            matcher {
                declaredClass = "com.tencent.mm.plugin.appbrand.AppBrandRuntime"
                returnType = "void"
                paramCount = 0
                usingEqStrings(
                    "public:prepare",
                    "Loading页展示",
                    "MicroMsg.AppBrandRuntime",
                    "showSplash[AppBrandSplashAd], appId:%s, splash:%s"
                )
            }
        }
    }
}
