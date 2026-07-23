@file:Suppress("unused")

package dev.ujhhgtg.wekit.loader.entry.zygisk

import android.annotation.SuppressLint
import android.util.Log
import androidx.annotation.Keep
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.loader.entry.common.ModuleLoader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zygisk JVM entry point.
 *
 * Called from C++ postAppSpecialize via:
 *   JNI FindClass("dev/ujhhgtg/wekit/loader/entry/zygisk/ZygiskEntry")
 *   GetStaticMethodID("init", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V")
 *   CallStaticVoidMethod(apkPath, dataDir, zygiskSoPath, targetPackage)
 *
 * The C++ side has already:
 *   1. Copied the companion payload into a sealed process-private memfd.
 *   2. Loaded that APK through a PathClassLoader and registered the
 *      native methods used by ZygiskHookBridge.
 */
@Keep
object ZygiskEntry {

    private const val TAG = "ZygiskEntry"
    private val entryLock = Any()
    private val moduleStarted = AtomicBoolean(false)
    private var loaderService: ZygiskLoaderService? = null
    private var hookBridge: ZygiskHookBridge? = null
    private var hostDataDir: String = ""
    private var modulePath: String = ""

    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    @Keep
    @JvmStatic
    fun init(
        apkPath: String,
        dataDir: String,
        zygiskSoPath: String,
        targetPackage: String,
    ) {
        if (!PackageNames.isWeChat(targetPackage)) {
            Log.w(TAG, "ignoring unsupported Zygisk target: $targetPackage")
            return
        }
        synchronized(entryLock) {
            if (hookBridge != null) return

            try {
                Log.i(TAG, "ZygiskEntry.init: apk=$apkPath dataDir=$dataDir target=$targetPackage")
                val service = ZygiskLoaderService(
                    modulePath = apkPath,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                )
                val bridge = ZygiskHookBridge()
                hostDataDir = dataDir
                modulePath = apkPath
                loaderService = service
                hookBridge = bridge

                // Waits for LoadedApk.createAppFactory: its ClassLoader
                // argument is the real host loader, unlike the loader that
                // loaded this bootstrap DEX.
                val loadedApk = Class.forName("android.app.LoadedApk")
                val createAppFactory = loadedApk.getDeclaredMethod(
                    "createAppFactory",
                    android.content.pm.ApplicationInfo::class.java,
                    ClassLoader::class.java,
                ).apply { isAccessible = true }

                bridge.hookMethod(createAppFactory, object : dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookCallback {
                    override fun beforeHookedMember(param: dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookParam) {
                        val appInfo = param.args.getOrNull(0) as? android.content.pm.ApplicationInfo
                        val hostClassLoader = param.args.getOrNull(1) as? ClassLoader
                        if (appInfo?.packageName == targetPackage && hostClassLoader != null) {
                            startModule(hostClassLoader)
                        }
                    }

                    override fun afterHookedMember(param: dev.ujhhgtg.wekit.loader.abc.IHookBridge.IMemberHookParam) = Unit
                }, priority = 10000)
            } catch (t: Throwable) {
                // All fields are published before the hook can become reachable;
                // roll them back when installation itself fails so init can retry.
                loaderService = null
                hookBridge = null
                hostDataDir = ""
                modulePath = ""
                moduleStarted.set(false)
                Log.e(TAG, "ZygiskEntry.init failed", t)
            }
        }
    }

    private fun startModule(hostClassLoader: ClassLoader) {
        if (!moduleStarted.compareAndSet(false, true)) return

        val started = try {
            val service = loaderService ?: error("Zygisk loader service not initialized")
            val bridge = hookBridge ?: error("Zygisk hook bridge not initialized")
            ModuleLoader.init(
                hostDataDir = hostDataDir,
                initialClassLoader = hostClassLoader,
                loaderService = service,
                hookBridge = bridge,
                modulePath = modulePath,
                allowDynamicLoad = false,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start WeKit module", t)
            false
        }
        if (started) {
            Log.i(TAG, "WeKit module started with host ClassLoader=$hostClassLoader")
        } else {
            // ModuleLoader deliberately leaves its guard unset on failure.
            // Do the same here so a later app lifecycle entry can retry.
            moduleStarted.set(false)
            Log.w(TAG, "WeKit module startup failed; retry remains available")
        }
    }

}
