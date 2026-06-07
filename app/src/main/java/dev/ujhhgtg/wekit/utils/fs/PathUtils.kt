@file:Suppress("NOTHING_TO_INLINE")

package dev.ujhhgtg.wekit.utils.fs

import android.net.Uri
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

@Suppress("NOTHING_TO_INLINE")
inline fun Path.createDirectoriesNoThrow(): Path {
    runCatching { createDirectories() }
    return this
}

inline val String.asPath get() = Path(this)

inline fun Path.toAndroidUri() = Uri.fromFile(toFile())
