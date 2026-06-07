package dev.ujhhgtg.wekit.hooks.core

import com.highcapable.kavaref.resolver.ConstructorResolver
import com.highcapable.kavaref.resolver.MethodResolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.dexkit.dsl.DexConstructorDelegate
import dev.ujhhgtg.wekit.dexkit.dsl.DexDelegateBase
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.utils.HookAction
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import java.lang.reflect.Executable
import kotlin.reflect.KClass

abstract class BaseHookItem {

    var name: String = ""
    var categories: List<String> = emptyList()

    var description: String = ""

    open fun startup() {
        error("You shouldn't inherit BaseHookItem")
    }

    var hasEnabled: Boolean = false
        private set

    fun enable() {
        if (hasEnabled) return

        runCatching {
            hasEnabled = true
            onEnable()
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to enable item", e)
            // ensure transaction is fully discarded
            unhookAll()
            hasEnabled = false
        }
    }

    fun disable() {
        if (!hasEnabled) return

        runCatching {
            hasEnabled = false
            unhookAll()
            onDisable()
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to disable item", e)
            hasEnabled = true
        }
    }

    open fun onEnable() {}

    open fun onDisable() {}

    private val _dexDelegates = mutableListOf<DexDelegateBase>()
    val dexDelegates: List<DexDelegateBase> get() = _dexDelegates
    internal fun registerDexDelegate(d: DexDelegateBase) {
        _dexDelegates += d
    }

    internal val unhooks = mutableListOf<XC_MethodHook.Unhook>()
    internal fun registerUnhook(u: XC_MethodHook.Unhook) {
        unhooks += u
    }
    internal fun unhookAll() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
    }

    // --- hookBefore ---

    internal inline fun Executable.hookBefore(
        priority: Int = 50,
        crossinline action: HookAction
    ) = registerUnhook(XposedBridge.hookMethod(
        this,
        object :
            XC_MethodHook(priority) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                executeHookAction(param, action)
            }
        }
    ))

    @JvmName("hookBefore2")
    internal inline fun MethodResolver<*>.hookBefore(
        priority: Int = 50,
        crossinline action: HookAction
    ) = this.self.hookBefore(priority, action)

    @JvmName("hookBefore3")
    internal inline fun ConstructorResolver<*>.hookBefore(
        priority: Int = 50,
        crossinline action: HookAction
    ) = this.self.hookBefore(priority, action)

    internal inline fun Class<*>.hookBeforeOnCreate(
        crossinline action: HookAction
    ) = this.asResolver().firstMethod { name = "onCreate" }.hookBefore(50, action)

    internal inline fun Class<*>.hookAfterOnCreate(
        crossinline action: HookAction
    ) = this.asResolver().firstMethod { name = "onCreate" }.hookAfter(50, action)

    internal inline fun KClass<*>.hookBeforeOnCreate(
        crossinline action: HookAction
    ) = this.asResolver().firstMethod { name = "onCreate" }.hookBefore(50, action)

    internal inline fun KClass<*>.hookAfterOnCreate(
        crossinline action: HookAction
    ) = this.asResolver().firstMethod { name = "onCreate" }.hookAfter(50, action)

    // --- end hookBefore ---

    // --- hookAfter ---

    internal inline fun Executable.hookAfter(
        priority: Int = 50,
        crossinline action: HookAction
    ) = registerUnhook(XposedBridge.hookMethod(
        this,
        object :
            XC_MethodHook(priority) {
            override fun afterHookedMethod(param: MethodHookParam) {
                executeHookAction(param, action)
            }
        }
    ))

    @JvmName("hookAfter2")
    internal inline fun MethodResolver<*>.hookAfter(
        priority: Int = 50,
        crossinline action: HookAction
    ) = this.self.hookAfter(priority, action)

    @JvmName("hookAfter3")
    internal inline fun ConstructorResolver<*>.hookAfter(
        priority: Int = 50,
        crossinline action: HookAction
    ) = this.self.hookAfter(priority, action)

    // --- end hookAfter ---

    // --- dex delegate ---

    internal inline fun DexMethodDelegate.hookBefore(
        priority: Int = 50,
        crossinline action: HookAction
    ) = method.hookBefore(priority, action)

    internal inline fun DexMethodDelegate.hookAfter(
        priority: Int = 50,
        crossinline action: HookAction
    ) = method.hookAfter(priority, action)

    internal inline fun DexConstructorDelegate.hookBefore(
        priority: Int = 50,
        crossinline action: HookAction
    ) = constructor.hookBefore(priority, action)

    internal inline fun DexConstructorDelegate.hookAfter(
        priority: Int = 50,
        crossinline action: HookAction
    ) = constructor.hookAfter(priority, action)

    // --- end dex delegate ---

    internal inline fun executeHookAction(param: XC_MethodHook.MethodHookParam, action: HookAction) {
        runCatching {
            action(param)
        }.onFailure { e -> WeLogger.e("executeHookAction", "failed to execute hook of $name", e) }
    }

    companion object {
        private val TAG = nameOf(BaseHookItem::class)
    }
}
