// WeKit Zygisk — SoHider implementation
// Remaps file-backed mappings that match `needle` to anonymous memfds so that
// /proc/self/maps no longer shows an on-disk path for the injected library.
#include "so_hider.h"

#include <android/api-level.h>
#include <android/log.h>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define TAG "WekitSoHider"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── memfd_create wrapper (not in older NDK headers) ─────────────────────────

static int memfd_create_compat(const char* name, unsigned int flags) {
    return static_cast<int>(syscall(SYS_memfd_create, name, flags));
}

// ─── maps line parser ─────────────────────────────────────────────────────────

struct MapEntry {
    uintptr_t start;
    uintptr_t end;
    int prot;           // PROT_READ | PROT_WRITE | PROT_EXEC
    off_t offset;
    char path[512];
};

static int parse_prot(const char* perms) {
    int p = 0;
    if (perms[0] == 'r') p |= PROT_READ;
    if (perms[1] == 'w') p |= PROT_WRITE;
    if (perms[2] == 'x') p |= PROT_EXEC;
    return p;
}

// Parse one line of /proc/self/maps.
// Returns true if the line represents a file-backed mapping with a non-empty path.
static bool parse_maps_line(const char* line, MapEntry* out) {
    unsigned long long start, end, offset;
    unsigned dev_maj, dev_min;
    unsigned long inode;
    char perms[8] = {};
    char path[512] = {};

    // Format: start-end perms offset dev:min inode [path]
    int n = sscanf(line, "%llx-%llx %7s %llx %x:%x %lu %511s",
                   &start, &end, perms, &offset, &dev_maj, &dev_min, &inode, path);

    if (n < 7) return false;
    if (path[0] == '\0' || path[0] == '[') return false; // anonymous / pseudo

    out->start  = static_cast<uintptr_t>(start);
    out->end    = static_cast<uintptr_t>(end);
    out->prot   = parse_prot(perms);
    out->offset = static_cast<off_t>(offset);
    strncpy(out->path, path, sizeof(out->path) - 1);
    out->path[sizeof(out->path) - 1] = '\0';
    return true;
}

// ─── Remap a single segment to an anonymous memfd ────────────────────────────

// Returns true on success.
static bool remap_segment(const MapEntry* e) {
    size_t len = static_cast<size_t>(e->end - e->start);
    if (len == 0) return true;

    // I-10 fix: skip PROT_NONE guard pages entirely.
    int orig_prot = e->prot;
    if (orig_prot == 0) {
        LOGI("remap: skipping PROT_NONE segment at 0x%zx", (size_t)e->start);
        return true;
    }

    // Make the segment readable if not already (needed to copy contents).
    bool need_remap_read = !(orig_prot & PROT_READ);
    bool read_protection_changed = false;
    auto restore_original_protection = [&]() {
        if (read_protection_changed) {
            if (mprotect(reinterpret_cast<void*>(e->start), len, orig_prot) != 0) {
                LOGW("remap: restore protection failed for %zx: %s",
                     (size_t)e->start, strerror(errno));
            }
            read_protection_changed = false;
        }
    };
    if (need_remap_read) {
        if (mprotect(reinterpret_cast<void*>(e->start), len, orig_prot | PROT_READ) != 0) {
            LOGW("remap: mprotect(+READ) failed for %zx: %s", (size_t)e->start, strerror(errno));
            return false;
        }
        read_protection_changed = true;
    }

    // Create anonymous memfd.
    int mfd = memfd_create_compat("wk", MFD_CLOEXEC);
    if (mfd < 0) {
        LOGE("remap: memfd_create failed: %s", strerror(errno));
        restore_original_protection();
        return false;
    }

    if (ftruncate(mfd, static_cast<off_t>(len)) != 0) {
        LOGE("remap: ftruncate failed: %s", strerror(errno));
        close(mfd);
        restore_original_protection();
        return false;
    }

    // Copy segment contents into memfd.
    size_t written = 0;
    const char* src = reinterpret_cast<const char*>(e->start);
    while (written < len) {
        ssize_t r = write(mfd, src + written, len - written);
        if (r <= 0) {
            LOGE("remap: write to memfd failed: %s", strerror(errno));
            close(mfd);
            restore_original_protection();
            return false;
        }
        written += static_cast<size_t>(r);
    }
    lseek(mfd, 0, SEEK_SET);

    // Restore readable prot before remapping.
    if (need_remap_read) {
        if (mprotect(reinterpret_cast<void*>(e->start), len, orig_prot) != 0) {
            LOGW("remap: restore protection before mmap failed for %zx: %s",
                 (size_t)e->start, strerror(errno));
            close(mfd);
            read_protection_changed = false;
            return false;
        }
        read_protection_changed = false;
    }

    // Executable mappings must be installed with their final protection in one
    // mmap call. Replacing them as RW and then failing mprotect(PROT_EXEC) would
    // irreversibly leave live code non-executable.
    void* addr = MAP_FAILED;

    if (orig_prot & PROT_EXEC) {
        addr = mmap(reinterpret_cast<void*>(e->start), len,
                    orig_prot, MAP_PRIVATE | MAP_FIXED, mfd, 0);
        if (addr == MAP_FAILED) {
            LOGW("remap: mmap(direct exec) failed at 0x%zx: %s",
                 (size_t)e->start, strerror(errno));
            close(mfd);
            return false;
        }
    } else {
        addr = mmap(reinterpret_cast<void*>(e->start), len,
                    PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_FIXED, mfd, 0);
    }

    close(mfd);

    if (addr == MAP_FAILED) {
        LOGE("remap: mmap(MAP_FIXED) at 0x%zx failed: %s",
             (size_t)e->start, strerror(errno));
        return false;
    }

    // Non-executable mappings were installed RW for copying and are restored.
    if (!(orig_prot & PROT_EXEC) && orig_prot != (PROT_READ | PROT_WRITE)) {
        if (mprotect(addr, len, orig_prot) != 0) {
            LOGW("remap: mprotect to 0x%x failed: %s", orig_prot, strerror(errno));
            return false;
        }
    }

    return true;
}

// ─── Public API ──────────────────────────────────────────────────────────────

int so_hide_path(const char* needle) {
    if (!needle || needle[0] == '\0') return -1;

    // Android 11 check: memfd_create on API < 30 still works (syscall is
    // available since kernel 3.17), but remapping shared libs in-process
    // is only reliable on API 30+ due to linker namespace changes.
    if (android_get_device_api_level() < 30) {
        LOGI("so_hide_path: disabled below Android 11");
        return 0;
    }

    // Also perform a runtime probe because vendor kernels may omit memfd_create.
    {
        int probe = memfd_create_compat("probe", MFD_CLOEXEC);
        if (probe < 0) {
            LOGI("so_hide_path: memfd_create unavailable, skipping (device too old)");
            return 0;
        }
        close(probe);
    }

    FILE* maps = fopen("/proc/self/maps", "r");
    if (!maps) {
        LOGE("so_hide_path: cannot open /proc/self/maps: %s", strerror(errno));
        return -1;
    }

    // Collect matching entries first (don't modify maps while reading).
    static constexpr int MAX_ENTRIES = 256;
    MapEntry entries[MAX_ENTRIES];
    int count = 0;

    char line[1024];
    while (fgets(line, sizeof(line), maps) && count < MAX_ENTRIES) {
        MapEntry e{};
        if (!parse_maps_line(line, &e)) continue;
        if (strstr(e.path, needle) == nullptr) continue;
        entries[count++] = e;
    }
    fclose(maps);

    int remapped = 0;
    for (int i = 0; i < count; i++) {
        LOGI("so_hide_path: remapping [0x%zx-0x%zx] %s",
             (size_t)entries[i].start, (size_t)entries[i].end, entries[i].path);
        if (remap_segment(&entries[i])) {
            remapped++;
        }
    }

    LOGI("so_hide_path: %d/%d segments remapped for needle '%s'", remapped, count, needle);
    return remapped;
}
