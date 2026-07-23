// WeKit Zygisk — SoHider: remap on-disk SO/APK mappings to anonymous memfd
// Android 11+ (memfd_create available on earlier APIs but /proc remap is the value add).
#pragma once

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Hide all /proc/self/maps entries whose path contains `needle`.
 *
 * Each matching file-backed mapping is:
 *   1. Copied into a fresh anonymous memfd.
 *   2. Re-mapped at the original virtual address with the original protection.
 *
 * After this call the mappings no longer show a file path in maps/smaps, so
 * WeChat's native module-detection scan sees no on-disk path for our library.
 *
 * Requires Android 11+ (uses SYS_memfd_create; detects at runtime and no-ops
 * gracefully on older releases).
 *
 * Returns the number of segments successfully remapped, or -1 on fatal error.
 */
int so_hide_path(const char* needle);

#ifdef __cplusplus
}
#endif
