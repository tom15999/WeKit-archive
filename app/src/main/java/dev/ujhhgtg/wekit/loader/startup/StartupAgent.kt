package dev.ujhhgtg.wekit.loader.startup

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import android.os.Process
import dev.ujhhgtg.reflekt.utils.ReflectionClassLoader
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import dev.ujhhgtg.wekit.loader.entry.zygisk.ArtHookBridge
import dev.ujhhgtg.wekit.loader.utils.HybridClassLoader
import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Field
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

object StartupAgent {

    private const val TAG = "StartupAgent"

    private val startupLock = Any()

    @Volatile
    private var initialized = false

    @OptIn(ExperimentalPathApi::class)
    fun startup(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        application: Application
    ) = synchronized(startupLock) {
        val process = runCatching { Application.getProcessName() }.getOrDefault("unknown")
        val identity = "process=$process pid=${Process.myPid()} tid=${Process.myTid()}"
        WeLogger.i(TAG, "stage=startup-enter $identity")
        if (initialized) {
            WeLogger.i(TAG, "stage=already-initialized $identity")
            return@synchronized
        }

        val realClassLoader = application.baseContext.classLoader
        HybridClassLoader.hostClassLoader = realClassLoader
        ReflectionClassLoader.value = realClassLoader
        WeLogger.i(TAG, "stage=class-loaders-configured $identity loader=$realClassLoader")
        if (R.string.res_inject_success ushr 24 == 0x7f) {
            throw AssertionError("module resource package ID must not be 0x7f")
        }
        StartupInfo.modulePath = modulePath
        StartupInfo.loaderService = loaderService
        StartupInfo.hookBridge = hookBridge

        WeLogger.i(TAG, "stage=hidden-api-check-begin $identity sdk=${Build.VERSION.SDK_INT}")
        ensureHiddenApiAccess()
        WeLogger.i(TAG, "stage=hidden-api-check-complete $identity")
        checkWriteXorExecuteForModulePath(modulePath)

        WeLogger.i(TAG, "stage=host-info-init-begin $identity")
        HostInfo.init(application)
        WeLogger.i(
            TAG,
            "stage=host-info-init-complete $identity " +
                "package=${HostInfo.packageName} version=${HostInfo.versionName}(${HostInfo.versionCode})"
        )

        WeLogger.i(TAG, "stage=native-loader-init-begin $identity")
        NativeLoader.init(application)
        WeLogger.i(TAG, "stage=native-loader-init-complete $identity")
        if (hookBridge is ArtHookBridge) {
            WeLogger.i(TAG, "stage=native-library-hide-begin $identity")
            runCatching { hookBridge.hideLoadedModuleLibraries() }
                .onSuccess { hidden ->
                    WeLogger.i(
                        TAG,
                        "stage=native-library-hide-complete $identity success=$hidden"
                    )
                    if (!hidden) WeLogger.w(TAG, "module native-library hiding was incomplete")
                }
                .onFailure {
                    WeLogger.e(TAG, "stage=native-library-hide-failed $identity", it)
                }
        }
        // FIXME: some people have hiding on, which causes false positives in signature verifier
//        SignatureVerifier.verify(application)
        WeLogger.i(TAG, "stage=launcher-init-begin $identity")
        WeLauncher.init(application)
        WeLogger.i(TAG, "stage=launcher-init-complete $identity")

        WeLogger.i(TAG, "stage=qqprotect-cleanup-begin $identity")
        runCatching {
            application.dataDir.toPath().resolve("app_qqprotect").deleteRecursively()
        }.onSuccess {
            WeLogger.i(TAG, "stage=qqprotect-cleanup-complete $identity")
        }.onFailure { WeLogger.e(TAG, "failed to delete app_qqprotect ($identity)", it) }

        // Only commit after every required startup phase completes. The caller
        // already logs a thrown failure, and a later lifecycle callback can retry.
        initialized = true
        WeLogger.i(TAG, "stage=startup-complete $identity")
    }

    private fun checkWriteXorExecuteForModulePath(modulePath: String) {
        val moduleFile = File(modulePath)
        if (moduleFile.canWrite()) {
            WeLogger.w(TAG, "module path is writable: $modulePath\nThis may cause issues on Android 15+, please check your Xposed framework")
        }
    }

    private fun ensureHiddenApiAccess() {
        if (!isHiddenApiAccessible()) {
            WeLogger.w(
                TAG,
                "hidden api is not accessible, SDK_INT is ${Build.VERSION.SDK_INT}"
            )
            HiddenApiBypass.setHiddenApiExemptions("L")
        }
    }

    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    fun isHiddenApiAccessible(): Boolean {
        val kContextImpl = runCatching {
            Class.forName("android.app.ContextImpl")
        }.getOrElse { return false }

        var mActivityToken: Field? = null
        var mToken: Field? = null

        try {
            mActivityToken = kContextImpl.getDeclaredField("mActivityToken")
        } catch (_: NoSuchFieldException) {
        }
        try {
            mToken = kContextImpl.getDeclaredField("mToken")
        } catch (_: NoSuchFieldException) {
        }

        return mActivityToken != null || mToken != null
    }
}
