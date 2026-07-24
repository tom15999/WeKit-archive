package dev.ujhhgtg.wekit.loader.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Process
import com.tencent.mmkv.MMKV
import dev.ujhhgtg.wekit.loader.utils.NativeLoader.init
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.div
import kotlin.io.path.exists

object NativeLoader {

    private const val TAG = "NativeLoader"

    private data class ZygiskPayload(
        val apk: File,
        val dataDir: File,
    )

    private val nativeLoadLock = Any()
    private var zygiskPayload: ZygiskPayload? = null
    private var zygiskNativeLibraries: Map<String, File> = emptyMap()
    private var nativeLibrariesLoaded = false

    /**
     * Configures native loading for the copied APK that the FunBox-style
     * bootstrap placed in the target app's data directory. This must run before
     * module startup reaches [init].
     */
    @JvmStatic
    fun configureZygiskPayload(apkPath: String, dataDir: String) = synchronized(nativeLoadLock) {
        check(!nativeLibrariesLoaded) { "native libraries were already loaded" }
        val apk = File(apkPath)
        require(apk.isFile && apk.canRead()) { "Zygisk payload APK is unreadable: $apkPath" }
        val appDataDir = File(dataDir)
        require(appDataDir.isDirectory) { "Zygisk app data directory is unavailable: $dataDir" }
        zygiskPayload = ZygiskPayload(apk, appDataDir)
    }

    fun init(hostCtx: Context) {
        val identity = processIdentity()
        WeLogger.i(TAG, "stage=init-enter $identity zygisk=${zygiskPayload != null}")
        ensureNativeLibrariesLoaded()
        WeLogger.i(TAG, "stage=native-libraries-ready $identity")
        val mmkvDir = hostCtx.filesDir.toPath() / "mmkv"
        if (!mmkvDir.exists()) {
            WeLogger.i(TAG, "stage=mmkv-directory-create-begin $identity path=$mmkvDir")
            mmkvDir.createDirsSafe()
            WeLogger.i(TAG, "stage=mmkv-directory-create-complete $identity path=$mmkvDir")
        }

        val libLoader = zygiskPayload?.let { zygiskMmkvLibLoader() }
        WeLogger.i(
            TAG,
            "stage=mmkv-initialize-begin $identity path=$mmkvDir customLoader=${libLoader != null}"
        )
        if (libLoader == null) {
            MMKV.initialize(hostCtx, mmkvDir.toString())
        } else {
            MMKV.initialize(hostCtx, mmkvDir.toString(), libLoader)
        }
        WeLogger.i(TAG, "stage=mmkv-initialize-complete $identity")

        WeLogger.i(TAG, "stage=mmkv-open-preferences-begin $identity id=${WePrefs.PREFS_NAME}")
        MMKV.mmkvWithID(WePrefs.PREFS_NAME, MMKV.MULTI_PROCESS_MODE)
        WeLogger.i(TAG, "stage=mmkv-open-preferences-complete $identity")
    }

    private fun ensureNativeLibrariesLoaded() {
        synchronized(nativeLoadLock) {
            val identity = processIdentity()
            if (nativeLibrariesLoaded) {
                WeLogger.i(TAG, "stage=native-libraries-already-loaded $identity")
                return@synchronized
            }
            val payload = zygiskPayload
            if (payload == null) {
                // Xposed/Frida paths use the normal installed-APK library lookup.
                WeLogger.i(TAG, "stage=system-load-library-begin $identity library=dexkit")
                System.loadLibrary("dexkit")
                WeLogger.i(TAG, "stage=system-load-library-complete $identity library=dexkit")
                WeLogger.i(TAG, "stage=system-load-library-begin $identity library=wekit_native")
                System.loadLibrary("wekit_native")
                WeLogger.i(TAG, "stage=system-load-library-complete $identity library=wekit_native")
            } else {
                WeLogger.i(TAG, "stage=zygisk-native-libraries-begin $identity apk=${payload.apk}")
                loadZygiskLibraries(payload)
                WeLogger.i(TAG, "stage=zygisk-native-libraries-complete $identity")
            }
            nativeLibrariesLoaded = true
        }
    }

    /**
     * InMemoryDexClassLoader has no native-library directory on API 28. Match
     * FunBox's workaround: extract packaged libraries into app data, then use
     * absolute System.load paths from this module ClassLoader.
     */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun loadZygiskLibraries(payload: ZygiskPayload) {
        val identity = processIdentity()
        WeLogger.i(TAG, "stage=abi-detection-begin $identity")
        val abi = currentProcessAbi(payload.apk)
        WeLogger.i(TAG, "stage=abi-detection-complete $identity abi=$abi")
        val libraryDir = File(payload.dataDir, ".wekit-native-$abi")
        if (!libraryDir.exists() && !libraryDir.mkdirs()) {
            error("cannot create Zygisk native-library directory: $libraryDir")
        }
        require(libraryDir.isDirectory) { "Zygisk native-library path is not a directory: $libraryDir" }

        val libraries = mutableMapOf<String, File>()
        ZipFile(payload.apk).use { archive ->
            val names = listOf(
                "androidx.graphics.path" to "libandroidx.graphics.path.so",
                "dexkit" to "libdexkit.so",
                "mmkv" to "libmmkv.so",
                "wekit_native" to "libwekit_native.so",
            )
            for (name in names) {
                val (libraryName, fileName) = name
                val entry = archive.getEntry("lib/$abi/$fileName") ?: continue
                WeLogger.i(
                    TAG,
                    "stage=native-library-extract-begin $identity library=$libraryName file=$fileName"
                )
                val extracted = extractLibrary(archive, entry.name, libraryDir, fileName)
                WeLogger.i(
                    TAG,
                    "stage=native-library-extract-complete $identity " +
                        "library=$libraryName path=$extracted size=${extracted.length()}"
                )
                libraries[libraryName] = extracted
                if (libraryName != "mmkv") {
                    WeLogger.i(
                        TAG,
                        "stage=system-load-begin $identity library=$libraryName path=$extracted"
                    )
                    System.load(extracted.absolutePath)
                    WeLogger.i(
                        TAG,
                        "stage=system-load-complete $identity library=$libraryName path=$extracted"
                    )
                }
            }
            require(archive.getEntry("lib/$abi/libdexkit.so") != null) {
                "Zygisk payload is missing libdexkit.so for $abi"
            }
            require(archive.getEntry("lib/$abi/libwekit_native.so") != null) {
                "Zygisk payload is missing libwekit_native.so for $abi"
            }
        }
        zygiskNativeLibraries = libraries
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun zygiskMmkvLibLoader(): MMKV.LibLoader = MMKV.LibLoader { libraryName ->
        val identity = processIdentity()
        val library = zygiskNativeLibraries[libraryName]
        if (library != null) {
            WeLogger.i(
                TAG,
                "stage=mmkv-system-load-begin $identity library=$libraryName path=$library"
            )
            System.load(library.absolutePath)
            WeLogger.i(
                TAG,
                "stage=mmkv-system-load-complete $identity library=$libraryName path=$library"
            )
        } else {
            WeLogger.i(
                TAG,
                "stage=mmkv-system-load-library-begin $identity library=$libraryName"
            )
            System.loadLibrary(libraryName)
            WeLogger.i(
                TAG,
                "stage=mmkv-system-load-library-complete $identity library=$libraryName"
            )
        }
    }

    private fun processIdentity(): String =
        "process=${runCatching { android.app.Application.getProcessName() }.getOrDefault("unknown")} " +
            "pid=${Process.myPid()} tid=${Process.myTid()}"

    private fun currentProcessAbi(apk: File): String {
        val candidates = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.asList()
        } else {
            Build.SUPPORTED_32_BIT_ABIS.asList()
        }
        ZipFile(apk).use { archive ->
            return candidates.firstOrNull { abi ->
                archive.getEntry("lib/$abi/libwekit_native.so") != null
            } ?: error("Zygisk payload has no native library for this process ABI")
        }
    }

    private fun extractLibrary(
        archive: ZipFile,
        entryName: String,
        destinationDir: File,
        libraryName: String,
    ): File {
        val destination = File(destinationDir, libraryName)
        val temporary = File(destinationDir, "$libraryName.${Process.myPid()}.tmp")
        temporary.delete()
        archive.getInputStream(archive.getEntry(entryName)).use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        temporary.setReadable(true, true)
        temporary.setExecutable(true, true)
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("cannot publish Zygisk native library: $destination")
        }
        return destination
    }
}
