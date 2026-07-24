// WeKit Zygisk - ART method hook implementation.
#include "art_hook.h"

#include <android/api-level.h>
#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <jni.h>
#include <link.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <array>
#include <unordered_map>
#include <vector>

#define TAG "WeKit"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace
{

    constexpr uint32_t kAccPublic = 0x0001;
    constexpr uint32_t kAccPrivate = 0x0002;
    constexpr uint32_t kAccProtected = 0x0004;
    constexpr uint32_t kAccStatic = 0x0008;
    constexpr uint32_t kAccCompileDontBother = 0x02000000;
    constexpr uint32_t kAccIntrinsic = 0x80000000;
    constexpr uint32_t kAccFastInterpreterToInterpreterInvoke = 0x40000000;

    constexpr size_t kMaxArtMethodSize = 256;
    constexpr size_t kTrampolinePoolSize = 1024 * 1024;
    constexpr size_t kTrampolineStride = 32;

    static pthread_mutex_t g_hook_mutex = PTHREAD_MUTEX_INITIALIZER;
    static std::atomic<bool> g_initialized{false};

    static size_t g_art_method_size = 0;
    static size_t g_entry_point_offset = 0;
    static size_t g_access_flags_offset = 0;
    static uint32_t g_acc_precompiled = 0;
    static uint32_t g_acc_fast_interpreter = 0;
    static jfieldID g_art_method_field = nullptr;
    static jfieldID g_access_flags_field = nullptr;

    using SuspendAllCtor = void (*)(void *, const char *, bool);
    using SuspendAllDtor = void (*)(void *);
    using SetNotIntrinsic = void (*)(void *);
    using SetDexFileTrusted = void (*)(JNIEnv *, jclass, jobject);
    using SetRuntimeDebugState = void (*)(void *, int);
    using SetJavaDebuggable = void (*)(void *, bool);

    static SuspendAllCtor g_suspend_all_ctor = nullptr;
    static SuspendAllDtor g_suspend_all_dtor = nullptr;
    static SetNotIntrinsic g_set_not_intrinsic = nullptr;
    static SetDexFileTrusted g_set_dex_file_trusted = nullptr;
    static jmethodID g_set_dex_file_trusted_method = nullptr;
    static SetRuntimeDebugState g_set_runtime_debug_state = nullptr;
    static SetJavaDebuggable g_set_java_debuggable = nullptr;
    static void **g_runtime_instance = nullptr;

    struct ArtLibraryLocation
    {
        uintptr_t base = 0;
        char path[512] = {};
    };

    static ArtLibraryLocation g_art_library;

    struct ArtLibraryPathQuery
    {
        const char *path = nullptr;
        uintptr_t base = 0;
    };

    struct TrampolinePool
    {
        uint8_t *executable = nullptr;
        uint8_t *writable = nullptr;
        size_t used = 0;
    };

    static TrampolinePool g_trampoline_pool;

    // backup_art deliberately receives hook-time runtime flags so ART can execute
    // it as the original implementation while a hook is active. Keep the target's
    // pre-hook flags separately: they must be restored exactly on unhook.
    struct HookRecord
    {
        uintptr_t backup_art = 0;
        uint32_t original_access_flags = 0;
    };

    // All access is serialized by g_hook_mutex.
    static std::unordered_map<uintptr_t, HookRecord> g_hook_records;

#if defined(__LP64__)
    using NativeElfEhdr = Elf64_Ehdr;
    using NativeElfShdr = Elf64_Shdr;
    using NativeElfSym = Elf64_Sym;
#define WEKIT_ELF_ST_TYPE ELF64_ST_TYPE
#else
    using NativeElfEhdr = Elf32_Ehdr;
    using NativeElfShdr = Elf32_Shdr;
    using NativeElfSym = Elf32_Sym;
#define WEKIT_ELF_ST_TYPE ELF32_ST_TYPE
#endif

    static bool is_art_library_name(const char *path)
    {
        if (!path || path[0] == '\0')
            return false;
        const char *basename = strrchr(path, '/');
        basename = basename ? basename + 1 : path;
        return strcmp(basename, "libart.so") == 0 ||
               strcmp(basename, "libartd.so") == 0;
    }

    static int locate_art_library(dl_phdr_info *info, size_t, void *data)
    {
        auto *location = static_cast<ArtLibraryLocation *>(data);
        if (!is_art_library_name(info->dlpi_name))
            return 0;

        location->base = static_cast<uintptr_t>(info->dlpi_addr);
        strncpy(location->path, info->dlpi_name, sizeof(location->path) - 1);
        location->path[sizeof(location->path) - 1] = '\0';
        return 1;
    }

    static int locate_library_by_exact_path(dl_phdr_info *info, size_t, void *data)
    {
        auto *query = static_cast<ArtLibraryPathQuery *>(data);
        if (!query->path || !info->dlpi_name || info->dlpi_name[0] == '\0')
            return 0;
        if (strcmp(info->dlpi_name, query->path) != 0)
            return 0;
        query->base = static_cast<uintptr_t>(info->dlpi_addr);
        return 1;
    }

    static uintptr_t find_loaded_library_base(const char *path)
    {
        ArtLibraryPathQuery query{path, 0};
        dl_iterate_phdr(locate_library_by_exact_path, &query);
        return query.base;
    }

    static bool resolve_loaded_art_library()
    {
        if (g_art_library.base != 0 && g_art_library.path[0] != '\0')
            return true;
        g_art_library = {};
        dl_iterate_phdr(locate_art_library, &g_art_library);
        if (g_art_library.base == 0)
        {
            LOGE("ArtHooker: could not locate loaded libart.so");
            return false;
        }
        LOGI("ArtHooker: libart: base=%p path=%s", reinterpret_cast<void *>(g_art_library.base),
             g_art_library.path[0] ? g_art_library.path : "<unknown>");
        return true;
    }

    static bool range_is_valid(size_t offset, size_t length, size_t total)
    {
        return offset <= total && length <= total - offset;
    }

    static bool symbol_name_matches(const char *candidate, const char *name,
                                    bool prefix)
    {
        if (!candidate || !name)
            return false;
        return prefix ? strncmp(candidate, name, strlen(name)) == 0
                      : strcmp(candidate, name) == 0;
    }

    static const NativeElfShdr *get_elf_sections(const uint8_t *base,
                                                 size_t image_size,
                                                 const NativeElfEhdr **out_ehdr)
    {
        if (!base || image_size < sizeof(NativeElfEhdr))
            return nullptr;
        const auto *ehdr = reinterpret_cast<const NativeElfEhdr *>(base);
        if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0 ||
            ehdr->e_ident[EI_CLASS] !=
#if defined(__LP64__)
                ELFCLASS64 ||
#else
                ELFCLASS32 ||
#endif
            ehdr->e_shentsize != sizeof(NativeElfShdr) ||
            !range_is_valid(ehdr->e_shoff,
                            static_cast<size_t>(ehdr->e_shnum) *
                                sizeof(NativeElfShdr),
                            image_size))
        {
            return nullptr;
        }
        *out_ehdr = ehdr;
        return reinterpret_cast<const NativeElfShdr *>(base + ehdr->e_shoff);
    }

    static void *resolve_symbol_from_elf_image(const uint8_t *base,
                                               size_t image_size,
                                               uintptr_t loaded_base,
                                               const char *name, bool prefix)
    {
        const NativeElfEhdr *ehdr = nullptr;
        const NativeElfShdr *sections =
            get_elf_sections(base, image_size, &ehdr);
        if (!sections)
            return nullptr;

        void *result = nullptr;
        // Search dynsym first so a public ART export does not depend on a retained
        // symtab.
        for (int pass = 0; pass < 2 && !result; ++pass)
        {
            const uint32_t expected = pass == 0 ? SHT_DYNSYM : SHT_SYMTAB;
            for (uint16_t i = 0; i < ehdr->e_shnum && !result; ++i)
            {
                const NativeElfShdr &symbols = sections[i];
                if (symbols.sh_type != expected ||
                    symbols.sh_entsize != sizeof(NativeElfSym) ||
                    symbols.sh_link >= ehdr->e_shnum ||
                    !range_is_valid(symbols.sh_offset, symbols.sh_size, image_size))
                {
                    continue;
                }
                const NativeElfShdr &strings = sections[symbols.sh_link];
                if (!range_is_valid(strings.sh_offset, strings.sh_size, image_size))
                    continue;

                const auto *entries =
                    reinterpret_cast<const NativeElfSym *>(base + symbols.sh_offset);
                const auto *string_table =
                    reinterpret_cast<const char *>(base + strings.sh_offset);
                const size_t count = symbols.sh_size / sizeof(NativeElfSym);
                for (size_t j = 0; j < count; ++j)
                {
                    const NativeElfSym &symbol = entries[j];
                    if (symbol.st_shndx == SHN_UNDEF || symbol.st_value == 0 ||
                        symbol.st_name >= strings.sh_size)
                    {
                        continue;
                    }
                    const char *symbol_name = string_table + symbol.st_name;
                    const size_t remaining = strings.sh_size - symbol.st_name;
                    if (strnlen(symbol_name, remaining) == remaining ||
                        !symbol_name_matches(symbol_name, name, prefix))
                    {
                        continue;
                    }
                    const unsigned type = WEKIT_ELF_ST_TYPE(symbol.st_info);
                    if (type != STT_FUNC && type != STT_NOTYPE && type != STT_OBJECT)
                        continue;
                    result = reinterpret_cast<void *>(loaded_base + symbol.st_value);
                    break;
                }
            }
        }
        return result;
    }

    // libart's mini debug data uses XZ. libart itself links liblzma, so resolve
    // the decoder only when an OEM has stripped a needed local ART symbol.
    using LzmaStreamBufferDecode = int (*)(uint64_t *, uint32_t, const void *,
                                           const uint8_t *, size_t *, size_t,
                                           uint8_t *, size_t *, size_t);

    static LzmaStreamBufferDecode get_lzma_stream_buffer_decode()
    {
        static LzmaStreamBufferDecode decode = []
        {
            void *symbol = dlsym(RTLD_DEFAULT, "lzma_stream_buffer_decode");
            if (!symbol)
            {
                // Keep this handle open: the returned function pointer is used for
                // the process lifetime after ART has loaded liblzma.
                void *handle = dlopen("liblzma.so", RTLD_NOW | RTLD_LOCAL);
                if (handle)
                    symbol = dlsym(handle, "lzma_stream_buffer_decode");
            }
            return reinterpret_cast<LzmaStreamBufferDecode>(symbol);
        }();
        return decode;
    }

    static bool decompress_xz(const uint8_t *input, size_t input_size,
                              std::vector<uint8_t> *output)
    {
        constexpr int kLzmaOk = 0;
        constexpr int kLzmaBufError = 10;
        constexpr size_t kInitialOutputSize = 1024 * 1024;
        constexpr size_t kMaxOutputSize = 64 * 1024 * 1024;

        const auto decode = get_lzma_stream_buffer_decode();
        if (!decode || !input || input_size == 0 || !output)
            return false;

        size_t output_size = input_size;
        if (output_size < kInitialOutputSize)
            output_size = kInitialOutputSize;
        while (output_size <= kMaxOutputSize)
        {
            output->resize(output_size);
            uint64_t memory_limit = UINT64_MAX;
            size_t input_position = 0;
            size_t output_position = 0;
            const int result = decode(&memory_limit, 0, nullptr, input,
                                      &input_position, input_size, output->data(),
                                      &output_position, output->size());
            if (result == kLzmaOk && input_position == input_size)
            {
                output->resize(output_position);
                return true;
            }
            if (result != kLzmaBufError || output_size > kMaxOutputSize / 2)
                break;
            output_size *= 2;
        }
        output->clear();
        return false;
    }

    static void *resolve_art_symbol_from_gnu_debugdata(const char *path,
                                                       uintptr_t loaded_base,
                                                       const char *name,
                                                       bool prefix)
    {
        if (!path || path[0] == '\0' || loaded_base == 0)
            return nullptr;

        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0)
            return nullptr;

        struct stat st{};
        if (fstat(fd, &st) != 0 || st.st_size <= 0)
        {
            close(fd);
            return nullptr;
        }
        const size_t image_size = static_cast<size_t>(st.st_size);
        void *image = mmap(nullptr, image_size, PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (image == MAP_FAILED)
            return nullptr;

        void *result = nullptr;
        const auto *base = static_cast<const uint8_t *>(image);
        const NativeElfEhdr *ehdr = nullptr;
        const NativeElfShdr *sections =
            get_elf_sections(base, image_size, &ehdr);
        if (sections && ehdr->e_shstrndx < ehdr->e_shnum)
        {
            const NativeElfShdr &section_names = sections[ehdr->e_shstrndx];
            if (range_is_valid(section_names.sh_offset, section_names.sh_size,
                               image_size))
            {
                const auto *section_name_table = reinterpret_cast<const char *>(
                    base + section_names.sh_offset);
                for (uint16_t i = 0; i < ehdr->e_shnum; ++i)
                {
                    const NativeElfShdr &section = sections[i];
                    if (section.sh_name >= section_names.sh_size ||
                        !range_is_valid(section.sh_offset, section.sh_size,
                                        image_size))
                    {
                        continue;
                    }
                    const char *section_name = section_name_table + section.sh_name;
                    const size_t remaining = section_names.sh_size - section.sh_name;
                    if (strnlen(section_name, remaining) == remaining ||
                        strcmp(section_name, ".gnu_debugdata") != 0)
                    {
                        continue;
                    }

                    std::vector<uint8_t> debug_image;
                    if (decompress_xz(base + section.sh_offset, section.sh_size,
                                      &debug_image))
                    {
                        result = resolve_symbol_from_elf_image(
                            debug_image.data(), debug_image.size(), loaded_base,
                            name, prefix);
                    }
                    break;
                }
            }
        }

        munmap(image, image_size);
        return result;
    }

    static void *resolve_art_symbol_from_file(const char *path, uintptr_t loaded_base,
                                              const char *name, bool prefix)
    {
        if (!path || path[0] == '\0' || loaded_base == 0)
            return nullptr;

        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0)
            return nullptr;

        struct stat st{};
        if (fstat(fd, &st) != 0 || st.st_size <= 0)
        {
            close(fd);
            return nullptr;
        }
        const size_t image_size = static_cast<size_t>(st.st_size);
        void *image = mmap(nullptr, image_size, PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (image == MAP_FAILED)
            return nullptr;

        void *result = resolve_symbol_from_elf_image(
            static_cast<const uint8_t *>(image), image_size, loaded_base, name,
            prefix);
        munmap(image, image_size);
        return result;
    }

    static void *resolve_art_symbol(const char *name, bool prefix)
    {
        if (!name || !resolve_loaded_art_library())
            return nullptr;
        if (!prefix)
        {
            if (void *direct = dlsym(RTLD_DEFAULT, name))
                return direct;
        }

        if (void *from_loaded_file =
                resolve_art_symbol_from_file(g_art_library.path, g_art_library.base,
                                             name, prefix))
        {
            return from_loaded_file;
        }

#if defined(__LP64__)
        static const char *const kFallbackPaths[] = {
            "/apex/com.android.art/lib64/libart.so",
            "/system/lib64/libart.so",
        };
#else
        static const char *const kFallbackPaths[] = {
            "/apex/com.android.art/lib/libart.so",
            "/system/lib/libart.so",
        };
#endif
        for (const char *path : kFallbackPaths)
        {
            if (strcmp(path, g_art_library.path) == 0)
                continue;
            const uintptr_t loaded_base = find_loaded_library_base(path);
            if (loaded_base == 0)
                continue;
            if (void *from_fallback =
                    resolve_art_symbol_from_file(path, loaded_base, name, prefix))
            {
                return from_fallback;
            }
        }
        return nullptr;
    }

    static bool init_reflection_fields(JNIEnv *env)
    {
        jclass executable = env->FindClass("java/lang/reflect/Executable");
        if (!executable)
        {
            env->ExceptionClear();
            LOGE("could not find java.lang.reflect.Executable");
            return false;
        }
        jfieldID art_method = env->GetFieldID(executable, "artMethod", "J");
        if (!art_method)
        {
            env->ExceptionClear();
            env->DeleteLocalRef(executable);
            LOGE("could not find Executable.artMethod");
            return false;
        }
        jfieldID access_flags = env->GetFieldID(executable, "accessFlags", "I");
        if (!access_flags)
        {
            env->ExceptionClear();
            env->DeleteLocalRef(executable);
            LOGE("could not find Executable.accessFlags");
            return false;
        }
        env->DeleteLocalRef(executable);
        g_art_method_field = art_method;
        g_access_flags_field = access_flags;
        return true;
    }

    static uintptr_t get_art_method_ptr(JNIEnv *env, jobject executable)
    {
        if (!executable)
            return 0;
        if (g_art_method_field)
        {
            jlong value = env->GetLongField(executable, g_art_method_field);
            if (!env->ExceptionCheck() && value != 0)
                return static_cast<uintptr_t>(value);
            env->ExceptionClear();
        }
        return reinterpret_cast<uintptr_t>(env->FromReflectedMethod(executable));
    }

    static bool probe_art_method_layout(JNIEnv *env, uintptr_t *first_art_method,
                                        size_t *art_method_size,
                                        uint32_t *access_flags)
    {
        jclass throwable = env->FindClass("java/lang/Throwable");
        jclass clazz = env->FindClass("java/lang/Class");
        if (!throwable || !clazz)
        {
            env->ExceptionClear();
            if (throwable)
                env->DeleteLocalRef(throwable);
            if (clazz)
                env->DeleteLocalRef(clazz);
            return false;
        }
        jmethodID get_ctors = env->GetMethodID(clazz, "getDeclaredConstructors",
                                               "()[Ljava/lang/reflect/Constructor;");
        env->DeleteLocalRef(clazz);
        if (!get_ctors)
        {
            env->ExceptionClear();
            env->DeleteLocalRef(throwable);
            return false;
        }
        auto ctors =
            static_cast<jobjectArray>(env->CallObjectMethod(throwable, get_ctors));
        env->DeleteLocalRef(throwable);
        if (!ctors || env->ExceptionCheck())
        {
            env->ExceptionClear();
            if (ctors)
                env->DeleteLocalRef(ctors);
            return false;
        }
        if (env->GetArrayLength(ctors) < 2)
        {
            env->DeleteLocalRef(ctors);
            return false;
        }

        jobject first_ctor = env->GetObjectArrayElement(ctors, 0);
        jobject second_ctor = env->GetObjectArrayElement(ctors, 1);
        const uintptr_t first = get_art_method_ptr(env, first_ctor);
        const uintptr_t second = get_art_method_ptr(env, second_ctor);
        const uint32_t flags = first_ctor && g_access_flags_field
                                   ? static_cast<uint32_t>(env->GetIntField(
                                         first_ctor, g_access_flags_field))
                                   : 0;
        const bool has_exception = env->ExceptionCheck();
        env->ExceptionClear();
        if (first_ctor)
            env->DeleteLocalRef(first_ctor);
        if (second_ctor)
            env->DeleteLocalRef(second_ctor);
        env->DeleteLocalRef(ctors);
        if (has_exception || first == 0 || second == 0)
            return false;

        const size_t size = first > second ? first - second : second - first;
        if (size < sizeof(uintptr_t) * 3 || size > kMaxArtMethodSize ||
            size % sizeof(uintptr_t) != 0)
        {
            LOGE("invalid ArtMethod size: %zu", size);
            return false;
        }
        *first_art_method = first;
        *art_method_size = size;
        *access_flags = flags;
        return true;
    }

    static bool find_access_flags_offset(uintptr_t art_method,
                                         size_t art_method_size,
                                         uint32_t reflected_flags, size_t *offset)
    {
        for (size_t candidate = 0; candidate + sizeof(uint32_t) <= art_method_size;
             candidate += sizeof(uint32_t))
        {
            uint32_t value = 0;
            memcpy(&value, reinterpret_cast<const void *>(art_method + candidate),
                   sizeof(value));
            if (value != reflected_flags)
                continue;
            if (candidate == sizeof(uint32_t))
            {
                *offset = candidate;
                return true;
            }
            if (*offset == 0)
                *offset = candidate;
        }
        return *offset != 0;
    }

    static int read_page_protection(uintptr_t address)
    {
        FILE *maps = fopen("/proc/self/maps", "r");
        if (!maps)
            return -1;
        char line[256];
        int result = 0;
        bool found = false;
        while (fgets(line, sizeof(line), maps))
        {
            unsigned long long start = 0;
            unsigned long long end = 0;
            char permissions[8] = {};
            if (sscanf(line, "%llx-%llx %7s", &start, &end, permissions) != 3 ||
                address < static_cast<uintptr_t>(start) ||
                address >= static_cast<uintptr_t>(end))
            {
                continue;
            }
            if (permissions[0] == 'r')
                result |= PROT_READ;
            if (permissions[1] == 'w')
                result |= PROT_WRITE;
            if (permissions[2] == 'x')
                result |= PROT_EXEC;
            found = true;
            break;
        }
        fclose(maps);
        return found ? result : -1;
    }

    class WritableArtMethod
    {
    public:
        bool acquire(uintptr_t address, size_t length)
        {
            if (!restore())
                return false;

            const long system_page_size = sysconf(_SC_PAGESIZE);
            if (system_page_size <= 0 || address == 0 || length == 0 ||
                length > kMaxArtMethodSize || length - 1 > UINTPTR_MAX - address)
                return false;

            const size_t page_size = static_cast<size_t>(system_page_size);
            const uintptr_t first_page = address - address % page_size;
            const uintptr_t last_address = address + length - 1;
            const uintptr_t last_page = last_address - last_address % page_size;
            const size_t page_count = (last_page - first_page) / page_size + 1;
            if (page_count > pages_.size())
            {
                LOGE("ArtMethod page span is unexpectedly large: %zu", page_count);
                return false;
            }
            page_count_ = 0;

            for (uintptr_t page = first_page;; page += page_size)
            {
                const int original_protection = read_page_protection(page);
                if (original_protection < 0)
                {
                    LOGE("could not read mapping protection for %p",
                         reinterpret_cast<void *>(page));
                    restore();
                    return false;
                }

                Page &state = pages_[page_count_++];
                state = {page, page_size, original_protection, false};
                if (!(state.original_protection & PROT_WRITE) &&
                    mprotect(reinterpret_cast<void *>(state.address), page_size,
                             state.original_protection | PROT_WRITE) != 0)
                {
                    LOGE("mprotect writable failed at %p: %s",
                         reinterpret_cast<void *>(state.address), strerror(errno));
                    restore();
                    return false;
                }
                state.changed = !(state.original_protection & PROT_WRITE);

                if (page == last_page)
                    return true;
            }
        }

        bool restore()
        {
            bool success = true;
            for (size_t i = page_count_; i > 0; --i)
            {
                Page &state = pages_[i - 1];
                if (!state.changed)
                    continue;
                if (mprotect(reinterpret_cast<void *>(state.address), state.length,
                             state.original_protection) != 0)
                {
                    LOGE("mprotect restore failed at %p: %s",
                         reinterpret_cast<void *>(state.address), strerror(errno));
                    success = false;
                    continue;
                }
                state.changed = false;
            }
            return success;
        }

    private:
        struct Page
        {
            uintptr_t address;
            size_t length;
            int original_protection;
            bool changed;
        };

        std::array<Page, kMaxArtMethodSize + 1> pages_{};
        size_t page_count_ = 0;
    };

    class ScopedArtSuspendAll
    {
    public:
        ScopedArtSuspendAll()
        {
            if (g_suspend_all_ctor && g_suspend_all_dtor)
            {
                // ART's ScopedSuspendAll is a field-less RAII wrapper in AOSP.  Match
                // FunBox/LSPlant by passing this wrapper object directly instead of
                // constructing into a guessed fixed-size byte buffer.
                g_suspend_all_ctor(this, "ArtHooker Hooking", false);
            }
        }

        ~ScopedArtSuspendAll()
        {
            if (active())
                g_suspend_all_dtor(this);
        }

        bool active() const { return g_suspend_all_ctor && g_suspend_all_dtor; }
    };

    class ScopedArtMutation
    {
    public:
        bool active() const { return suspend_all_.active(); }

    private:
        ScopedArtSuspendAll suspend_all_;
    };

    static int memfd_create_compat(const char *name, unsigned int flags)
    {
        return static_cast<int>(syscall(SYS_memfd_create, name, flags));
    }

    static bool initialize_trampoline_pool()
    {
        if (g_trampoline_pool.executable && g_trampoline_pool.writable)
            return true;
        int fd = memfd_create_compat("jit-cache", MFD_CLOEXEC);
        if (fd < 0)
        {
            LOGE("memfd_create(jit-cache) failed: %s", strerror(errno));
            return false;
        }
        if (ftruncate(fd, static_cast<off_t>(kTrampolinePoolSize)) != 0)
        {
            LOGE("ftruncate trampoline pool failed: %s", strerror(errno));
            close(fd);
            return false;
        }
        auto *writable = static_cast<uint8_t *>(mmap(
            nullptr, kTrampolinePoolSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0));
        if (writable == MAP_FAILED)
        {
            LOGE("mmap writable trampoline pool failed: %s", strerror(errno));
            close(fd);
            return false;
        }
        auto *executable = static_cast<uint8_t *>(mmap(
            nullptr, kTrampolinePoolSize, PROT_READ | PROT_EXEC, MAP_SHARED, fd, 0));
        close(fd);
        if (executable == MAP_FAILED)
        {
            LOGE("mmap executable trampoline pool failed: %s", strerror(errno));
            munmap(writable, kTrampolinePoolSize);
            return false;
        }
        g_trampoline_pool.executable = executable;
        g_trampoline_pool.writable = writable;
        g_trampoline_pool.used = 0;
        LOGI("ArtHooker: trampoline pool: rw=%p rx=%p", writable, executable);
        return true;
    }

    static bool generate_trampoline(uintptr_t bridge_art_method,
                                    void **out_entrypoint)
    {
        if (!out_entrypoint || !g_trampoline_pool.writable ||
            !g_trampoline_pool.executable)
            return false;
        if (g_trampoline_pool.used + kTrampolineStride > kTrampolinePoolSize)
        {
            LOGE("trampoline pool exhausted");
            return false;
        }

#if defined(__aarch64__)
        if (g_entry_point_offset > 0x1ff)
        {
            LOGE("arm64 quick-entry offset is too large: %zu", g_entry_point_offset);
            return false;
        }
        uint8_t code[] = {
            0x60,
            0x00,
            0x00,
            0x58, // ldr x0, #12
            0x10,
            0x00,
            0x40,
            0xf8, // ldur x16, [x0, #entry_point_offset]
            0x00,
            0x02,
            0x1f,
            0xd6, // br x16
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
        };
        uint32_t load_entry = 0;
        memcpy(&load_entry, code + 4, sizeof(load_entry));
        load_entry |= static_cast<uint32_t>(g_entry_point_offset) << 12;
        memcpy(code + 4, &load_entry, sizeof(load_entry));
        memcpy(code + 12, &bridge_art_method, sizeof(bridge_art_method));
#elif defined(__arm__)
        if (g_entry_point_offset > 0x0fff)
        {
            LOGE("arm quick-entry offset is too large: %zu", g_entry_point_offset);
            return false;
        }
        uint8_t code[] = {
            0x00,
            0x00,
            0x9f,
            0xe5, // ldr r0, [pc]
            0x00,
            0xf0,
            0x90,
            0xe5, // ldr pc, [r0, #entry_point_offset]
            0x00,
            0x00,
            0x00,
            0x00,
        };
        uint32_t load_entry = 0;
        memcpy(&load_entry, code + 4, sizeof(load_entry));
        load_entry |= static_cast<uint32_t>(g_entry_point_offset);
        memcpy(code + 4, &load_entry, sizeof(load_entry));
        const uint32_t bridge = static_cast<uint32_t>(bridge_art_method);
        memcpy(code + 8, &bridge, sizeof(bridge));
#else
#error "WeKit Zygisk supports arm64-v8a and armeabi-v7a only"
#endif

        const size_t offset = g_trampoline_pool.used;
        memcpy(g_trampoline_pool.writable + offset, code, sizeof(code));
        auto *executable = g_trampoline_pool.executable + offset;
        __builtin___clear_cache(reinterpret_cast<char *>(executable),
                                reinterpret_cast<char *>(executable + sizeof(code)));
        g_trampoline_pool.used += kTrampolineStride;
        *out_entrypoint = executable;
        return true;
    }

    static uint32_t read_u32(uintptr_t address)
    {
        return reinterpret_cast<const std::atomic<uint32_t> *>(address)->load(
            std::memory_order_relaxed);
    }

    static void write_u32(uintptr_t address, uint32_t value)
    {
        reinterpret_cast<std::atomic<uint32_t> *>(address)->store(
            value, std::memory_order_relaxed);
    }

    static void set_non_intrinsic(uintptr_t art_method)
    {
        if (g_set_not_intrinsic)
        {
            g_set_not_intrinsic(reinterpret_cast<void *>(art_method));
            return;
        }
        uint32_t flags = read_u32(art_method + g_access_flags_offset);
        flags &= ~kAccIntrinsic;
        write_u32(art_method + g_access_flags_offset, flags);
    }

    static bool has_dex_file_trust_backend()
    {
        return g_set_dex_file_trusted || g_set_dex_file_trusted_method;
    }

    static jmethodID resolve_dex_file_set_trusted_method(JNIEnv *env)
    {
        if (!env)
            return nullptr;

        jclass dex_file_class = env->FindClass("dalvik/system/DexFile");
        if (!dex_file_class)
        {
            env->ExceptionClear();
            return nullptr;
        }
        jmethodID method = env->GetStaticMethodID(
            dex_file_class, "setTrusted", "(Ljava/lang/Object;)V");
        if (env->ExceptionCheck())
        {
            env->ExceptionClear();
            method = nullptr;
        }
        env->DeleteLocalRef(dex_file_class);
        return method;
    }

    // Discovers Runtime::debug_state_'s offset by calling SetRuntimeDebugState on a zeroed
    // scratch Runtime-shaped buffer, toggles the real runtime's state directly,
    // then invokes Runtime::SetJavaDebuggable.
    static void set_funbox_trust_debug_state(bool enabled)
    {
        if (g_runtime_instance && *g_runtime_instance &&
            g_set_runtime_debug_state)
        {
            alignas(uintptr_t) uint8_t scratch[4096] = {};
            g_set_runtime_debug_state(scratch, 1);

            size_t debug_state_offset = 0;
            for (size_t offset = 1; offset + sizeof(uint32_t) <= sizeof(scratch);
                 ++offset)
            {
                uint32_t value = 0;
                memcpy(&value, scratch + offset, sizeof(value));
                if (value == 1)
                {
                    debug_state_offset = offset;
                    break;
                }
            }
            if (debug_state_offset != 0)
            {
                const uint32_t state = enabled ? 2u : 0u;
                memcpy(reinterpret_cast<uint8_t *>(*g_runtime_instance) +
                           debug_state_offset,
                       &state, sizeof(state));
            }
            else
            {
                LOGW("ArtHooker: Runtime::debug_state_ offset not found");
            }
        }
        else
        {
            LOGW("ArtHooker: Runtime::instance_ or SetRuntimeDebugState unavailable");
        }

        if (g_runtime_instance && *g_runtime_instance && g_set_java_debuggable)
        {
            g_set_java_debuggable(*g_runtime_instance, enabled);
        }
    }

    static bool trust_dex_file(JNIEnv *env, jobject dex_file)
    {
        if (!dex_file || !has_dex_file_trust_backend())
        {
            LOGE("DexFile.setTrusted backend is unavailable");
            return false;
        }
        jclass dex_file_class = env->FindClass("dalvik/system/DexFile");
        if (!dex_file_class)
        {
            env->ExceptionClear();
            LOGE("DexFile class not found");
            return false;
        }
        jfieldID cookie_field =
            env->GetFieldID(dex_file_class, "mCookie", "Ljava/lang/Object;");
        if (!cookie_field)
        {
            env->ExceptionClear();
            env->DeleteLocalRef(dex_file_class);
            LOGE("DexFile.mCookie not found");
            return false;
        }
        jobject cookie = env->GetObjectField(dex_file, cookie_field);
        if (env->ExceptionCheck() || !cookie)
        {
            env->ExceptionClear();
            if (cookie)
                env->DeleteLocalRef(cookie);
            env->DeleteLocalRef(dex_file_class);
            LOGE("DexFile.mCookie is null");
            return false;
        }

        set_funbox_trust_debug_state(true);
        if (g_set_dex_file_trusted)
        {
            g_set_dex_file_trusted(env, dex_file_class, cookie);
        }
        else
        {
            // Some OEM ART builds strip the local native function from the
            // runtime symbol tables. Call the same registered JNI method.
            env->CallStaticVoidMethod(dex_file_class,
                                      g_set_dex_file_trusted_method, cookie);
        }
        const bool ok = !env->ExceptionCheck();
        if (!ok)
        {
            LOGE("DexFile.setTrusted exception");
            jthrowable exception = env->ExceptionOccurred();
            env->ExceptionClear();
            if (exception)
                env->DeleteLocalRef(exception);
        }
        set_funbox_trust_debug_state(false);
        env->DeleteLocalRef(cookie);
        env->DeleteLocalRef(dex_file_class);
        return ok;
    }

} // namespace

bool art_trust_dex_file(JNIEnv *env, jobject dex_file)
{
    if (!g_initialized.load(std::memory_order_acquire) || !env || !dex_file ||
        !has_dex_file_trust_backend())
    {
        return false;
    }
    return trust_dex_file(env, dex_file);
}

bool art_hook_init(JNIEnv *env)
{
    pthread_mutex_lock(&g_hook_mutex);
    if (g_initialized.load(std::memory_order_acquire))
    {
        pthread_mutex_unlock(&g_hook_mutex);
        return true;
    }

    uintptr_t sample_art_method = 0;
    size_t art_method_size = 0;
    uint32_t sample_access_flags = 0;
    size_t access_flags_offset = 0;
    if (!env || !init_reflection_fields(env) ||
        !probe_art_method_layout(env, &sample_art_method, &art_method_size,
                                 &sample_access_flags) ||
        !find_access_flags_offset(sample_art_method, art_method_size,
                                  sample_access_flags, &access_flags_offset))
    {
        LOGE("failed to probe ART reflection layout");
        pthread_mutex_unlock(&g_hook_mutex);
        return false;
    }

    const size_t entry_point_offset = art_method_size - sizeof(uintptr_t);
    if (access_flags_offset + sizeof(uint32_t) > entry_point_offset)
    {
        LOGE("invalid ArtMethod layout: flags=%zu entry=%zu size=%zu",
             access_flags_offset, entry_point_offset, art_method_size);
        pthread_mutex_unlock(&g_hook_mutex);
        return false;
    }

    void *suspend_ctor =
        resolve_art_symbol("_ZN3art16ScopedSuspendAllC2EPKcb", false);
    if (!suspend_ctor)
    {
        suspend_ctor =
            resolve_art_symbol("_ZN3art16ScopedSuspendAllC1EPKcb", false);
    }
    void *suspend_dtor =
        resolve_art_symbol("_ZN3art16ScopedSuspendAllD2Ev", false);
    if (!suspend_dtor)
    {
        suspend_dtor = resolve_art_symbol("_ZN3art16ScopedSuspendAllD1Ev", false);
    }
    auto *set_not_intrinsic =
        resolve_art_symbol("_ZN3art9ArtMethod15SetNotIntrinsicEv", false);
    auto *set_trusted = resolve_art_symbol(
        "_ZN3artL18DexFile_setTrustedEP7_JNIEnvP7_jclassP8_jobject", true);
    if (!set_trusted)
    {
        set_trusted = resolve_art_symbol_from_gnu_debugdata(
            g_art_library.path, g_art_library.base,
            "_ZN3artL18DexFile_setTrustedEP7_JNIEnvP7_jclassP8_jobject", true);
        if (set_trusted)
        {
            LOGI("ArtHooker: DexFile_setTrusted resolved from libart .gnu_debugdata");
        }
    }
    jmethodID set_trusted_method =
        set_trusted ? nullptr : resolve_dex_file_set_trusted_method(env);
    auto *runtime_instance =
        resolve_art_symbol("_ZN3art7Runtime9instance_E", false);
    auto *set_runtime_debug_state = resolve_art_symbol(
        "_ZN3art7Runtime20SetRuntimeDebugStateENS0_17RuntimeDebugStateE",
        false);
    auto *set_java_debuggable =
        resolve_art_symbol("_ZN3art7Runtime17SetJavaDebuggableEb", false);
    if (!suspend_ctor || !suspend_dtor ||
        (!set_trusted && !set_trusted_method))
    {
        LOGE("required ART entry missing: suspend_ctor=%p suspend_dtor=%p "
             "trusted_symbol=%p trusted_method=%p",
             suspend_ctor, suspend_dtor, set_trusted, set_trusted_method);
        pthread_mutex_unlock(&g_hook_mutex);
        return false;
    }

    g_art_method_size = art_method_size;
    g_entry_point_offset = entry_point_offset;
    g_access_flags_offset = access_flags_offset;
    g_suspend_all_ctor = reinterpret_cast<SuspendAllCtor>(suspend_ctor);
    g_suspend_all_dtor = reinterpret_cast<SuspendAllDtor>(suspend_dtor);
    g_set_not_intrinsic = reinterpret_cast<SetNotIntrinsic>(set_not_intrinsic);
    g_set_dex_file_trusted = reinterpret_cast<SetDexFileTrusted>(set_trusted);
    g_set_dex_file_trusted_method = set_trusted_method;
    g_runtime_instance = reinterpret_cast<void **>(runtime_instance);
    g_set_runtime_debug_state =
        reinterpret_cast<SetRuntimeDebugState>(set_runtime_debug_state);
    g_set_java_debuggable =
        reinterpret_cast<SetJavaDebuggable>(set_java_debuggable);

    const int api = android_get_device_api_level();
    g_acc_precompiled = api < 30 ? 0 : (api >= 31 ? 0x00800000 : 0x00200000);
    g_acc_fast_interpreter =
        api < 29 ? 0 : kAccFastInterpreterToInterpreterInvoke;
    if (!g_set_not_intrinsic)
    {
        LOGW("ArtHooker: ArtMethod::SetNotIntrinsic unavailable; using access-flag fallback");
    }
    if (!g_set_dex_file_trusted)
    {
        LOGW("ArtHooker: DexFile_setTrusted symbol unavailable; using registered JNI method");
    }
    if (!initialize_trampoline_pool())
    {
        pthread_mutex_unlock(&g_hook_mutex);
        return false;
    }

    LOGI("ArtHooker: ART layout: method_size=%zu entry_offset=%zu access_flags_offset=%zu "
         "api=%d",
         g_art_method_size, g_entry_point_offset, g_access_flags_offset, api);
    g_initialized.store(true, std::memory_order_release);
    pthread_mutex_unlock(&g_hook_mutex);
    return true;
}

uintptr_t art_get_art_method(JNIEnv *env, jobject executable)
{
    return get_art_method_ptr(env, executable);
}

bool art_hook_method(JNIEnv *, uintptr_t target_art, uintptr_t backup_art,
                     uintptr_t bridge_art)
{
    if (!g_initialized.load(std::memory_order_acquire) || !target_art ||
        !backup_art || !bridge_art)
    {
        return false;
    }

    pthread_mutex_lock(&g_hook_mutex);
    ScopedArtMutation mutation;
    if (!mutation.active())
    {
        LOGE("ART mutation suspend guard is unavailable");
        pthread_mutex_unlock(&g_hook_mutex);
        return false;
    }

    WritableArtMethod target_writable;
    WritableArtMethod backup_writable;
    WritableArtMethod bridge_writable;
    if (!target_writable.acquire(target_art, g_art_method_size) ||
        !backup_writable.acquire(backup_art, g_art_method_size) ||
        !bridge_writable.acquire(bridge_art, g_art_method_size))
    {
        bridge_writable.restore();
        backup_writable.restore();
        target_writable.restore();
        pthread_mutex_unlock(&g_hook_mutex);
        return false;
    }

    if (g_hook_records.find(target_art) != g_hook_records.end())
    {
        LOGE("target=%p already has an active hook",
             reinterpret_cast<void *>(target_art));
        bridge_writable.restore();
        backup_writable.restore();
        target_writable.restore();
        pthread_mutex_unlock(&g_hook_mutex);
        return false;
    }

    void *trampoline = nullptr;
    if (!generate_trampoline(bridge_art, &trampoline))
    {
        bridge_writable.restore();
        backup_writable.restore();
        target_writable.restore();
        pthread_mutex_unlock(&g_hook_mutex);
        return false;
    }

    // This is the only pristine copy of the target flags. Every mutation below
    // clears or sets ART runtime bits, so neither target nor backup can provide
    // the original value during unhook.
    const uint32_t original_access_flags =
        read_u32(target_art + g_access_flags_offset);

    // The trampoline puts bridge_art into x0/r0, which is the interpreter/nterp
    // ArtMethod register. A compiled bridge expects receiver/arg0 there, so keep
    // this generated bridge on the interpreter path before it becomes reachable.
    // As hook-owned generated code, its runtime flags need not be restored on
    // unhook.
    uint32_t bridge_flags = read_u32(bridge_art + g_access_flags_offset);
    bridge_flags |= kAccCompileDontBother;
    bridge_flags &= ~g_acc_precompiled;
    write_u32(bridge_art + g_access_flags_offset, bridge_flags);

    // Match FunBox/LSPlant ordering: mark the target non-compilable, snapshot it
    // into backup, then direct target quick calls through a bridge-ArtMethod
    // trampoline.
    set_non_intrinsic(target_art);
    uint32_t target_flags = read_u32(target_art + g_access_flags_offset);
    target_flags |= kAccCompileDontBother;
    target_flags &= ~g_acc_precompiled;
    write_u32(target_art + g_access_flags_offset, target_flags);

    memcpy(reinterpret_cast<void *>(backup_art),
           reinterpret_cast<const void *>(target_art), g_art_method_size);

    target_flags &= ~g_acc_fast_interpreter;
    write_u32(target_art + g_access_flags_offset, target_flags);

    uint32_t backup_flags = read_u32(backup_art + g_access_flags_offset);
    if ((backup_flags & kAccStatic) == 0)
    {
        backup_flags |= kAccPrivate;
        backup_flags &= ~(kAccPublic | kAccProtected);
        write_u32(backup_art + g_access_flags_offset, backup_flags);
    }

    memcpy(reinterpret_cast<void *>(target_art + g_entry_point_offset),
           &trampoline, sizeof(trampoline));

    // Insert after all fallible setup has completed. The mutex keeps this record
    // coherent with the target and backup ArtMethod mutations above.
    g_hook_records.emplace(target_art,
                           HookRecord{backup_art, original_access_flags});

    bridge_writable.restore();
    backup_writable.restore();
    target_writable.restore();
    LOGI("ArtHooker: hooked target=%p bridge=%p trampoline=%p",
         reinterpret_cast<void *>(target_art),
         reinterpret_cast<void *>(bridge_art), trampoline);
    pthread_mutex_unlock(&g_hook_mutex);
    return true;
}

bool art_unhook_method(JNIEnv *, uintptr_t target_art, uintptr_t backup_art)
{
    if (!g_initialized.load(std::memory_order_acquire) || !target_art ||
        !backup_art)
        return false;

    pthread_mutex_lock(&g_hook_mutex);
    ScopedArtMutation mutation;
    if (!mutation.active())
    {
        LOGE("ArtHooker: ART mutation suspend guard is unavailable");
        pthread_mutex_unlock(&g_hook_mutex);
        return false;
    }

    const auto record = g_hook_records.find(target_art);
    if (record == g_hook_records.end() ||
        record->second.backup_art != backup_art)
    {
        LOGE("ArtHooker: unhook target=%p has no matching active hook",
             reinterpret_cast<void *>(target_art));
        pthread_mutex_unlock(&g_hook_mutex);
        return false;
    }

    WritableArtMethod target_writable;
    if (!target_writable.acquire(target_art, g_art_method_size))
    {
        pthread_mutex_unlock(&g_hook_mutex);
        return false;
    }
    memcpy(reinterpret_cast<void *>(target_art),
           reinterpret_cast<const void *>(backup_art), g_art_method_size);
    write_u32(target_art + g_access_flags_offset,
              record->second.original_access_flags);
    target_writable.restore();
    g_hook_records.erase(record);
    pthread_mutex_unlock(&g_hook_mutex);
    return true;
}

bool art_trust_class_loader(JNIEnv *env, jobject class_loader)
{
    if (!g_initialized.load(std::memory_order_acquire) || !env || !class_loader ||
        !has_dex_file_trust_backend())
    {
        return false;
    }

    jclass base_loader_class = env->FindClass("dalvik/system/BaseDexClassLoader");
    jclass path_list_class = env->FindClass("dalvik/system/DexPathList");
    jclass element_class = env->FindClass("dalvik/system/DexPathList$Element");
    if (!base_loader_class || !path_list_class || !element_class)
    {
        env->ExceptionClear();
        if (base_loader_class)
            env->DeleteLocalRef(base_loader_class);
        if (path_list_class)
            env->DeleteLocalRef(path_list_class);
        if (element_class)
            env->DeleteLocalRef(element_class);
        return false;
    }

    jfieldID path_list_field = env->GetFieldID(base_loader_class, "pathList",
                                               "Ldalvik/system/DexPathList;");
    jfieldID elements_field = env->GetFieldID(
        path_list_class, "dexElements", "[Ldalvik/system/DexPathList$Element;");
    jfieldID dex_file_field =
        env->GetFieldID(element_class, "dexFile", "Ldalvik/system/DexFile;");
    if (!path_list_field || !elements_field || !dex_file_field)
    {
        env->ExceptionClear();
        env->DeleteLocalRef(base_loader_class);
        env->DeleteLocalRef(path_list_class);
        env->DeleteLocalRef(element_class);
        LOGE("ArtHooker: could not resolve BaseDexClassLoader DexFile fields");
        return false;
    }

    jobject path_list = env->GetObjectField(class_loader, path_list_field);
    auto elements = path_list ? static_cast<jobjectArray>(env->GetObjectField(
                                    path_list, elements_field))
                              : nullptr;
    if (env->ExceptionCheck() || !elements)
    {
        env->ExceptionClear();
        if (path_list)
            env->DeleteLocalRef(path_list);
        if (elements)
            env->DeleteLocalRef(elements);
        env->DeleteLocalRef(base_loader_class);
        env->DeleteLocalRef(path_list_class);
        env->DeleteLocalRef(element_class);
        return false;
    }

    bool success = true;
    jint trusted_count = 0;
    const jsize count = env->GetArrayLength(elements);
    for (jsize i = 0; i < count; ++i)
    {
        jobject element = env->GetObjectArrayElement(elements, i);
        jobject dex_file =
            element ? env->GetObjectField(element, dex_file_field) : nullptr;
        if (env->ExceptionCheck())
        {
            env->ExceptionClear();
            success = false;
        }
        else if (dex_file)
        {
            if (trust_dex_file(env, dex_file))
            {
                ++trusted_count;
            }
            else
            {
                success = false;
            }
        }
        if (dex_file)
            env->DeleteLocalRef(dex_file);
        if (element)
            env->DeleteLocalRef(element);
    }

    env->DeleteLocalRef(elements);
    env->DeleteLocalRef(path_list);
    env->DeleteLocalRef(base_loader_class);
    env->DeleteLocalRef(path_list_class);
    env->DeleteLocalRef(element_class);
    if (!success || trusted_count == 0)
    {
        LOGE("ArtHooker: failed to trust every DexFile in class loader (trusted=%d)",
             trusted_count);
        return false;
    }
    LOGI("ArtHooker: trusted %d DexFile(s) for class loader %p", trusted_count,
         class_loader);
    return true;
}

bool art_hook_is_initialized(void)
{
    return g_initialized.load(std::memory_order_acquire);
}
