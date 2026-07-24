// WeKit Zygisk -- copied-file bootstrap.
#include <sys/types.h>

#include "art_hook.h"
#include "so_hider.h"
#include "zygisk.hpp"

#include <android/log.h>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <jni.h>
#include <limits>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

#define TAG "WekitZygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// The companion is deliberately limited to the root-owned WebUI allow-list.
// Payload files are read through a module-dir fd retained from
// preAppSpecialize, then copied into the app-owned data directory from
// postAppSpecialize.
static constexpr uint8_t COMPANION_REQUEST_ENABLED = 0x01;
static constexpr uint8_t COMPANION_DISABLED = 0;
static constexpr uint8_t COMPANION_ENABLED = 1;
static constexpr uint8_t COMPANION_ERROR = 2;
static constexpr uint16_t MAX_PROCESS_NAME_BYTES = 255;
static constexpr int APP_USER_RANGE = 100000;
static constexpr size_t MAX_PAYLOAD_FILE_BYTES = 256 * 1024 * 1024;
static constexpr size_t MAX_DEX_FILE_BYTES = 64 * 1024 * 1024;
static constexpr size_t MAX_DEX_LIST_BYTES = 4096;
static constexpr const char *TARGETS_PATH =
    "/data/adb/wekit/injection-targets.tsv";
static constexpr const char *WECHAT_PACKAGE = "com.tencent.mm";

static const char *current_abi_dir();

static bool write_all(int fd, const void *buf, size_t len)
{
    const char *bytes = static_cast<const char *>(buf);
    size_t written = 0;
    while (written < len)
    {
        ssize_t result;
        do
        {
            result = write(fd, bytes + written, len - written);
        } while (result < 0 && errno == EINTR);
        if (result <= 0)
            return false;
        written += static_cast<size_t>(result);
    }
    return true;
}

static bool read_all(int fd, void *buf, size_t len)
{
    char *bytes = static_cast<char *>(buf);
    size_t read_count = 0;
    while (read_count < len)
    {
        ssize_t result;
        do
        {
            result = read(fd, bytes + read_count, len - read_count);
        } while (result < 0 && errno == EINTR);
        if (result <= 0)
            return false;
        read_count += static_cast<size_t>(result);
    }
    return true;
}

// Mirrors PackageNames.isWeChat(packageName):
// packageName.startsWith("com.tencent.mm").
static bool is_wechat_package(const std::string &package_name)
{
    const size_t prefix_length = strlen(WECHAT_PACKAGE);
    return package_name.size() >= prefix_length &&
           package_name.compare(0, prefix_length, WECHAT_PACKAGE) == 0;
}

static bool is_process_for_package(const std::string &process_name,
                                   const std::string &package_name)
{
    if (package_name.empty() || process_name.size() < package_name.size())
        return false;
    if (process_name.compare(0, package_name.size(), package_name) != 0)
        return false;
    return process_name.size() == package_name.size() ||
           process_name[package_name.size()] == ':';
}

static bool parse_nonnegative_int(const std::string &value, int &out)
{
    if (value.empty())
        return false;
    char *end = nullptr;
    errno = 0;
    const long parsed = strtol(value.c_str(), &end, 10);
    if (errno != 0 || end == value.c_str() || *end != '\0' || parsed < 0 ||
        parsed > std::numeric_limits<int>::max())
    {
        return false;
    }
    out = static_cast<int>(parsed);
    return true;
}

// The WebUI stores userId, packageName, enabled. Malformed state fails closed.
static bool is_enabled_target(jint uid, const std::string &process_name)
{
    if (uid < 0 || process_name.empty())
        return false;
    const int user_id = uid / APP_USER_RANGE;

    std::ifstream config(TARGETS_PATH);
    if (!config.is_open())
        return false;

    std::string line;
    while (std::getline(config, line))
    {
        if (line.empty() || line[0] == '#')
            continue;

        std::istringstream row(line);
        std::string user_text;
        std::string package_name;
        std::string enabled;
        std::string unexpected_field;
        if (!std::getline(row, user_text, '\t') ||
            !std::getline(row, package_name, '\t') ||
            !std::getline(row, enabled, '\t') ||
            std::getline(row, unexpected_field, '\t'))
        {
            continue;
        }

        int target_user = -1;
        if (!parse_nonnegative_int(user_text, target_user) ||
            target_user != user_id || !is_wechat_package(package_name) ||
            enabled != "1")
        {
            continue;
        }
        if (is_process_for_package(process_name, package_name))
            return true;
    }
    return false;
}

// Runs in the root companion process. It intentionally has no payload-file or
// descriptor transfer behavior.
static void companion_handler(int sock)
{
    uint8_t request = 0;
    jint uid = -1;
    uint16_t process_len = 0;
    uint8_t status = COMPANION_ERROR;
    if (!read_all(sock, &request, sizeof(request)) ||
        request != COMPANION_REQUEST_ENABLED ||
        !read_all(sock, &uid, sizeof(uid)) ||
        !read_all(sock, &process_len, sizeof(process_len)) || process_len == 0 ||
        process_len > MAX_PROCESS_NAME_BYTES)
    {
        LOGE("companion: invalid target-status request");
    }
    else
    {
        std::string process_name(process_len, '\0');
        if (!read_all(sock, &process_name[0], process_name.size()))
        {
            LOGE("companion: failed to read process name");
        }
        else
        {
            status = is_enabled_target(uid, process_name) ? COMPANION_ENABLED
                                                          : COMPANION_DISABLED;
        }
    }
    if (!write_all(sock, &status, sizeof(status)))
        LOGW("companion: failed to return target status");
}

static int request_target_status(Api *api, jint uid,
                                 const std::string &process_name)
{
    const int sock = api->connectCompanion();
    if (sock < 0)
    {
        LOGE("preAppSpecialize: connectCompanion failed");
        return COMPANION_ERROR;
    }

    const uint16_t process_len = static_cast<uint16_t>(process_name.size());
    const uint8_t request = COMPANION_REQUEST_ENABLED;
    uint8_t status = COMPANION_ERROR;
    const bool sent = write_all(sock, &request, sizeof(request)) &&
                      write_all(sock, &uid, sizeof(uid)) &&
                      write_all(sock, &process_len, sizeof(process_len)) &&
                      write_all(sock, process_name.data(), process_name.size());
    const bool received = sent && read_all(sock, &status, sizeof(status));
    close(sock);
    if (!received || status > COMPANION_ERROR)
    {
        LOGE("preAppSpecialize: target-status companion exchange failed");
        return COMPANION_ERROR;
    }
    return status;
}

static const char *current_abi_dir()
{
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__arm__)
    return "armeabi-v7a";
#else
    return nullptr;
#endif
}

static std::string string_from_jstring(JNIEnv *env, jstring value)
{
    if (!value)
        return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars)
    {
        env->ExceptionClear();
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

static bool read_module_text(int module_dir_fd, const std::string &relative,
                             std::string &out)
{
    const int fd = openat(module_dir_fd, relative.c_str(),
                          O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0)
    {
        LOGE("preAppSpecialize: cannot open module payload %s: %s",
             relative.c_str(), strerror(errno));
        return false;
    }
    struct stat st{};
    const bool valid = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) &&
                       st.st_size > 0 &&
                       static_cast<uintmax_t>(st.st_size) <= MAX_DEX_LIST_BYTES;
    if (!valid)
    {
        LOGE("preAppSpecialize: invalid module text payload %s", relative.c_str());
        close(fd);
        return false;
    }
    out.resize(static_cast<size_t>(st.st_size));
    const bool read_ok = read_all(fd, out.data(), out.size());
    close(fd);
    if (!read_ok)
    {
        LOGE("preAppSpecialize: failed reading module payload %s",
             relative.c_str());
        out.clear();
    }
    return read_ok;
}

static bool dex_name_order(const std::string &name, unsigned int &order)
{
    if (name == "classes.dex")
    {
        order = 1;
        return true;
    }
    constexpr const char *prefix = "classes";
    constexpr const char *suffix = ".dex";
    const size_t prefix_size = strlen(prefix);
    const size_t suffix_size = strlen(suffix);
    if (name.size() <= prefix_size + suffix_size ||
        name.compare(0, prefix_size, prefix) != 0 ||
        name.compare(name.size() - suffix_size, suffix_size, suffix) != 0)
    {
        return false;
    }
    unsigned int value = 0;
    for (size_t i = prefix_size; i < name.size() - suffix_size; ++i)
    {
        const char digit = name[i];
        if (digit < '0' || digit > '9' ||
            value > (std::numeric_limits<unsigned int>::max() - 9) / 10)
        {
            return false;
        }
        value = value * 10 + static_cast<unsigned int>(digit - '0');
    }
    if (value < 2)
        return false;
    order = value;
    return true;
}

static bool parse_dex_list(const std::string &text,
                           std::vector<std::string> &dex_names)
{
    std::istringstream input(text);
    std::string line;
    unsigned int expected = 1;
    while (std::getline(input, line))
    {
        unsigned int order = 0;
        if (!dex_name_order(line, order) || order != expected)
            return false;
        dex_names.push_back(line);
        ++expected;
    }
    return !dex_names.empty();
}

static bool ensure_directory(const std::string &path, mode_t mode)
{
    if (mkdir(path.c_str(), mode) == 0)
        return true;
    if (errno == EEXIST)
    {
        struct stat st{};
        return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
    }
    LOGE("postAppSpecialize: cannot create directory %s: %s", path.c_str(),
         strerror(errno));
    return false;
}

static bool copy_module_file(int module_dir_fd, const std::string &relative,
                             const std::string &destination, jint uid, jint gid,
                             size_t maximum_size)
{
    const int source = openat(module_dir_fd, relative.c_str(),
                              O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (source < 0)
    {
        LOGE("postAppSpecialize: cannot open module payload %s: %s",
             relative.c_str(), strerror(errno));
        return false;
    }
    struct stat source_stat{};
    if (fstat(source, &source_stat) != 0 || !S_ISREG(source_stat.st_mode) ||
        source_stat.st_size <= 0 ||
        static_cast<uintmax_t>(source_stat.st_size) > maximum_size)
    {
        LOGE("postAppSpecialize: invalid module payload %s", relative.c_str());
        close(source);
        return false;
    }

    const std::string temporary =
        destination + "." + std::to_string(getpid()) + ".tmp";
    unlink(temporary.c_str());
    const int target =
        open(temporary.c_str(),
             O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (target < 0)
    {
        LOGE("postAppSpecialize: cannot create %s: %s", temporary.c_str(),
             strerror(errno));
        close(source);
        return false;
    }

    bool copied = true;
    char buffer[65536];
    while (copied)
    {
        ssize_t count;
        do
        {
            count = read(source, buffer, sizeof(buffer));
        } while (count < 0 && errno == EINTR);
        if (count == 0)
            break;
        if (count < 0)
        {
            copied = false;
            break;
        }
        size_t written = 0;
        while (written < static_cast<size_t>(count))
        {
            ssize_t result;
            do
            {
                result = write(target, buffer + written,
                               static_cast<size_t>(count) - written);
            } while (result < 0 && errno == EINTR);
            if (result <= 0)
            {
                copied = false;
                break;
            }
            written += static_cast<size_t>(result);
        }
    }
    if (copied && geteuid() == 0 && fchown(target, uid, gid) != 0)
        copied = false;
    if (copied && fsync(target) != 0)
        copied = false;
    close(source);
    close(target);
    if (!copied || rename(temporary.c_str(), destination.c_str()) != 0)
    {
        LOGE("postAppSpecialize: failed to publish %s: %s", destination.c_str(),
             strerror(errno));
        unlink(temporary.c_str());
        return false;
    }
    return true;
}

static bool read_copied_file(const std::string &path,
                             std::vector<uint8_t> &out)
{
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0)
    {
        LOGE("postAppSpecialize: cannot open copied DEX %s: %s", path.c_str(),
             strerror(errno));
        return false;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        static_cast<uintmax_t>(st.st_size) > MAX_DEX_FILE_BYTES)
    {
        LOGE("postAppSpecialize: invalid copied DEX %s", path.c_str());
        close(fd);
        return false;
    }
    out.resize(static_cast<size_t>(st.st_size));
    const bool read_ok = read_all(fd, out.data(), out.size());
    close(fd);
    if (!read_ok)
    {
        LOGE("postAppSpecialize: failed to read copied DEX %s", path.c_str());
        out.clear();
    }
    return read_ok;
}

static bool load_copied_dex(JNIEnv *env, const std::vector<std::string> &paths,
                            jobject &out_classloader)
{
    std::vector<std::vector<uint8_t>> copied;
    copied.reserve(paths.size());
    for (const std::string &path : paths)
    {
        std::vector<uint8_t> bytes;
        if (!read_copied_file(path, bytes))
            return false;
        copied.push_back(std::move(bytes));
    }

    jclass loader_class = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    jclass buffer_class = env->FindClass("java/nio/ByteBuffer");
    jclass class_loader_class = env->FindClass("java/lang/ClassLoader");
    if (!loader_class || !buffer_class || !class_loader_class)
    {
        env->ExceptionClear();
        if (loader_class)
            env->DeleteLocalRef(loader_class);
        if (buffer_class)
            env->DeleteLocalRef(buffer_class);
        if (class_loader_class)
            env->DeleteLocalRef(class_loader_class);
        return false;
    }

    jmethodID constructor =
        env->GetMethodID(loader_class, "<init>",
                         "([Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    jmethodID wrap = env->GetStaticMethodID(
        buffer_class, "wrap", "([B)Ljava/nio/ByteBuffer;");
    jmethodID system_loader = env->GetStaticMethodID(
        class_loader_class, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    if (!constructor || !wrap || !system_loader)
    {
        if (env->ExceptionCheck())
        {
            LOGE("postAppSpecialize: InMemoryDexClassLoader bootstrap method lookup failed");
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(loader_class);
        env->DeleteLocalRef(buffer_class);
        env->DeleteLocalRef(class_loader_class);
        return false;
    }

    jobject parent =
        env->CallStaticObjectMethod(class_loader_class, system_loader);
    jobjectArray buffers = env->NewObjectArray(static_cast<jsize>(copied.size()),
                                               buffer_class, nullptr);
    if (env->ExceptionCheck() || !parent || !buffers)
    {
        env->ExceptionClear();
        if (parent)
            env->DeleteLocalRef(parent);
        if (buffers)
            env->DeleteLocalRef(buffers);
        env->DeleteLocalRef(loader_class);
        env->DeleteLocalRef(buffer_class);
        env->DeleteLocalRef(class_loader_class);
        return false;
    }

    for (jsize index = 0; index < static_cast<jsize>(copied.size()); ++index)
    {
        const std::vector<uint8_t> &bytes = copied[static_cast<size_t>(index)];
        jbyteArray byte_array = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (!byte_array || env->ExceptionCheck())
        {
            if (env->ExceptionCheck())
            {
                LOGE("postAppSpecialize: cannot allocate DEX byte[] for %s",
                     paths[static_cast<size_t>(index)].c_str());
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            if (byte_array)
                env->DeleteLocalRef(byte_array);
            env->DeleteLocalRef(buffers);
            env->DeleteLocalRef(parent);
            env->DeleteLocalRef(loader_class);
            env->DeleteLocalRef(buffer_class);
            env->DeleteLocalRef(class_loader_class);
            return false;
        }
        env->SetByteArrayRegion(byte_array, 0, static_cast<jsize>(bytes.size()),
                                reinterpret_cast<const jbyte *>(bytes.data()));
        if (env->ExceptionCheck())
        {
            LOGE("postAppSpecialize: cannot populate DEX byte[] for %s",
                 paths[static_cast<size_t>(index)].c_str());
            env->ExceptionDescribe();
            env->ExceptionClear();
            env->DeleteLocalRef(byte_array);
            env->DeleteLocalRef(buffers);
            env->DeleteLocalRef(parent);
            env->DeleteLocalRef(loader_class);
            env->DeleteLocalRef(buffer_class);
            env->DeleteLocalRef(class_loader_class);
            return false;
        }
        jobject buffer = env->CallStaticObjectMethod(buffer_class, wrap, byte_array);
        env->DeleteLocalRef(byte_array);
        if (!buffer || env->ExceptionCheck())
        {
            if (env->ExceptionCheck())
            {
                LOGE("postAppSpecialize: ByteBuffer.wrap failed for %s",
                     paths[static_cast<size_t>(index)].c_str());
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
            if (buffer)
                env->DeleteLocalRef(buffer);
            env->DeleteLocalRef(buffers);
            env->DeleteLocalRef(parent);
            env->DeleteLocalRef(loader_class);
            env->DeleteLocalRef(buffer_class);
            env->DeleteLocalRef(class_loader_class);
            return false;
        }
        env->SetObjectArrayElement(buffers, index, buffer);
        env->DeleteLocalRef(buffer);
        if (env->ExceptionCheck())
        {
            LOGE("postAppSpecialize: cannot append ByteBuffer for %s",
                 paths[static_cast<size_t>(index)].c_str());
            env->ExceptionDescribe();
            env->ExceptionClear();
            env->DeleteLocalRef(buffers);
            env->DeleteLocalRef(parent);
            env->DeleteLocalRef(loader_class);
            env->DeleteLocalRef(buffer_class);
            env->DeleteLocalRef(class_loader_class);
            return false;
        }
    }

    jobject loader = env->NewObject(loader_class, constructor, buffers, parent);
    env->DeleteLocalRef(buffers);
    env->DeleteLocalRef(parent);
    env->DeleteLocalRef(loader_class);
    env->DeleteLocalRef(buffer_class);
    env->DeleteLocalRef(class_loader_class);
    if (env->ExceptionCheck() || !loader)
    {
        LOGE("postAppSpecialize: InMemoryDexClassLoader constructor failed");
        env->ExceptionDescribe();
        env->ExceptionClear();
        if (loader)
            env->DeleteLocalRef(loader);
        return false;
    }

    out_classloader = env->NewGlobalRef(loader);
    env->DeleteLocalRef(loader);
    if (!out_classloader)
    {
        if (env->ExceptionCheck())
        {
            LOGE("postAppSpecialize: cannot retain InMemoryDexClassLoader");
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        return false;
    }
    return true;
}

extern "C"
{
    JNIEXPORT jlong JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeGetArtMethod(
        JNIEnv *env, jclass clazz, jobject executable);
    JNIEXPORT jint JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeHookMethod(
        JNIEnv *env, jclass clazz, jlong target_art, jlong backup_art,
        jlong bridge_art, jlong hook_id);
    JNIEXPORT jint JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeUnhookMethod(
        JNIEnv *env, jclass clazz, jlong target_art, jlong backup_art);
    JNIEXPORT jboolean JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeTrustClassLoader(
        JNIEnv *env, jclass clazz, jobject class_loader);
    JNIEXPORT jboolean JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeTrustDexFile(
        JNIEnv *env, jclass clazz, jobject dex_file);
    JNIEXPORT jobject JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeAllocateInstance(
        JNIEnv *env, jclass clazz, jclass target_class);
    JNIEXPORT jboolean JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeHideLoadedModuleLibraries(
        JNIEnv *env, jclass clazz);
    JNIEXPORT jboolean JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskEntry_nativeInitialize(
        JNIEnv *env, jclass clazz);
}

static void log_and_clear_jni_exception(JNIEnv *env, const char *context)
{
    if (!env->ExceptionCheck())
        return;
    LOGE("%s", context);
    env->ExceptionDescribe();
    env->ExceptionClear();
}

static jclass load_class_from(JNIEnv *env, jobject classloader,
                              const char *class_name)
{
    jclass class_loader_class = env->FindClass("java/lang/ClassLoader");
    if (!class_loader_class)
    {
        log_and_clear_jni_exception(env,
                                    "ClassLoader lookup failed before loadClass");
        return nullptr;
    }
    jmethodID load_class =
        env->GetMethodID(class_loader_class, "loadClass",
                         "(Ljava/lang/String;)Ljava/lang/Class;");
    env->DeleteLocalRef(class_loader_class);
    if (!load_class)
    {
        log_and_clear_jni_exception(env,
                                    "ClassLoader.loadClass method lookup failed");
        return nullptr;
    }
    jstring name = env->NewStringUTF(class_name);
    if (!name)
    {
        log_and_clear_jni_exception(env, "cannot allocate loadClass argument");
        return nullptr;
    }
    jclass result =
        static_cast<jclass>(env->CallObjectMethod(classloader, load_class, name));
    env->DeleteLocalRef(name);
    if (env->ExceptionCheck())
    {
        std::string context = "ClassLoader.loadClass failed for ";
        context += class_name;
        log_and_clear_jni_exception(env, context.c_str());
        return nullptr;
    }
    if (!result)
        LOGE("ClassLoader.loadClass returned null for %s", class_name);
    return result;
}

static bool register_hook_bridge_natives(JNIEnv *env, jobject classloader)
{
    jclass bridge = load_class_from(
        env, classloader,
        "dev.ujhhgtg.wekit.loader.entry.zygisk.ArtHookBridge");
    if (!bridge)
        return false;
    JNINativeMethod methods[] = {
        {const_cast<char *>("nativeGetArtMethod"),
         const_cast<char *>("(Ljava/lang/reflect/Executable;)J"),
         reinterpret_cast<void *>(
             Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeGetArtMethod)},
        {const_cast<char *>("nativeHookMethod"), const_cast<char *>("(JJJJ)I"),
         reinterpret_cast<void *>(
             Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeHookMethod)},
        {const_cast<char *>("nativeUnhookMethod"), const_cast<char *>("(JJ)I"),
         reinterpret_cast<void *>(
             Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeUnhookMethod)},
        {const_cast<char *>("nativeTrustDexFile"),
         const_cast<char *>("(Ldalvik/system/DexFile;)Z"),
         reinterpret_cast<void *>(
             Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeTrustDexFile)},
        {const_cast<char *>("nativeAllocateInstance"),
         const_cast<char *>("(Ljava/lang/Class;)Ljava/lang/Object;"),
         reinterpret_cast<void *>(
             Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeAllocateInstance)},
        {const_cast<char *>("nativeHideLoadedModuleLibraries"),
         const_cast<char *>("()Z"),
         reinterpret_cast<void *>(
             Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeHideLoadedModuleLibraries)},
    };
    const jint result = env->RegisterNatives(
        bridge, methods, static_cast<jint>(sizeof(methods) / sizeof(methods[0])));
    const bool success = result == JNI_OK && !env->ExceptionCheck();
    if (!success)
    {
        LOGE("RegisterNatives(ArtHookBridge) failed: rc=%d", result);
        log_and_clear_jni_exception(env,
                                    "RegisterNatives(ArtHookBridge) exception");
    }
    env->DeleteLocalRef(bridge);
    return success;
}

static bool register_entry_natives(JNIEnv *env, jclass entry)
{
    JNINativeMethod methods[] = {
        {const_cast<char *>("nativeInitialize"), const_cast<char *>("()Z"),
         reinterpret_cast<void *>(
             Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskEntry_nativeInitialize)},
    };
    const jint result = env->RegisterNatives(entry, methods, 1);
    const bool success = result == JNI_OK && !env->ExceptionCheck();
    if (!success)
    {
        LOGE("RegisterNatives(ZygiskEntry) failed: rc=%d", result);
        log_and_clear_jni_exception(env, "RegisterNatives(ZygiskEntry) exception");
    }
    return success;
}

static jobject get_class_loader(JNIEnv *env, jclass clazz)
{
    jclass class_class = env->FindClass("java/lang/Class");
    if (!class_class)
    {
        log_and_clear_jni_exception(env,
                                    "Class lookup failed for ZygiskEntry loader");
        return nullptr;
    }
    jmethodID get_class_loader = env->GetMethodID(
        class_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
    env->DeleteLocalRef(class_class);
    if (!get_class_loader)
    {
        log_and_clear_jni_exception(
            env, "Class.getClassLoader method lookup failed for ZygiskEntry");
        return nullptr;
    }
    jobject classloader = env->CallObjectMethod(clazz, get_class_loader);
    if (env->ExceptionCheck())
    {
        log_and_clear_jni_exception(env,
                                    "ZygiskEntry.class.getClassLoader failed");
        return nullptr;
    }
    if (!classloader)
        LOGE("ZygiskEntry.class.getClassLoader returned null");
    return classloader;
}

class WekitZygisk : public zygisk::ModuleBase
{
public:
    void onLoad(Api *_api, JNIEnv *_env) override
    {
        api = _api;
        env = _env;
    }

    void preAppSpecialize(AppSpecializeArgs *args) override
    {
        process_name = string_from_jstring(env, args->nice_name);
        data_dir = string_from_jstring(env, args->app_data_dir);
        if (process_name.empty() || process_name.size() > MAX_PROCESS_NAME_BYTES ||
            data_dir.empty())
        {
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        const int status = request_target_status(api, args->uid, process_name);
        if (status == COMPANION_DISABLED)
        {
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        if (status != COMPANION_ENABLED)
        {
            LOGE("preAppSpecialize: allow-list lookup failed for %s",
                 process_name.c_str());
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        const char *abi = current_abi_dir();
        if (!abi)
        {
            LOGE("preAppSpecialize: unsupported ABI");
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        const int module_dir_fd = api->getModuleDir();
        if (module_dir_fd < 0)
        {
            LOGE("preAppSpecialize: getModuleDir failed");
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        payload_base = std::string("payload/") + abi + "/";
        std::string dex_list_text;
        if (!read_module_text(module_dir_fd, payload_base + "dex.list",
                              dex_list_text) ||
            !parse_dex_list(dex_list_text, dex_names))
        {
            LOGE("preAppSpecialize: failed to prepare payload metadata for %s",
                 process_name.c_str());
            close(module_dir_fd);
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        app_uid = args->uid;
        app_gid = args->gid;
        retained_module_dir_fd = module_dir_fd;
        enabled = true;
        LOGI("preAppSpecialize: enabled %s (uid=%d), deferred APK + %zu DEX copies",
             process_name.c_str(), args->uid, dex_names.size());
    }

    void postAppSpecialize(const AppSpecializeArgs * /*args*/) override
    {
        if (!enabled)
            return;
        if (module_classloader)
        {
            LOGW("postAppSpecialize: module class loader is already initialized");
            return;
        }
        LOGI("postAppSpecialize: copying and loading WeKit payload");

        if (retained_module_dir_fd < 0)
        {
            LOGE("postAppSpecialize: module directory fd is unavailable");
            return;
        }
        const std::string files_dir = data_dir + "/files";
        const std::string mmkv_dir = files_dir + "/mmkv";
        if (!ensure_directory(files_dir, 0700) || !ensure_directory(mmkv_dir, 0700))
            return;
        const std::string copy_base =
            mmkv_dir + "/.wekit-bootstrap-" +
            (current_abi_dir() ? current_abi_dir() : "unknown");
        apk_path = copy_base + ".apk";
        if (!copy_module_file(retained_module_dir_fd, payload_base + "wekit.apk",
                              apk_path, app_uid, app_gid, MAX_PAYLOAD_FILE_BYTES))
        {
            LOGE("postAppSpecialize: failed to copy WeKit APK payload for %s",
                 process_name.c_str());
            return;
        }
        dex_paths.clear();
        dex_paths.reserve(dex_names.size());
        for (const std::string &dex_name : dex_names)
        {
            const std::string dex_destination = copy_base + "-" + dex_name;
            if (!copy_module_file(retained_module_dir_fd, payload_base + dex_name,
                                  dex_destination, app_uid, app_gid,
                                  MAX_DEX_FILE_BYTES))
            {
                LOGE("postAppSpecialize: failed to copy WeKit DEX payload %s",
                     dex_name.c_str());
                dex_paths.clear();
                return;
            }
            dex_paths.push_back(dex_destination);
        }
        close(retained_module_dir_fd);
        retained_module_dir_fd = -1;

        jobject classloader = nullptr;
        if (!load_copied_dex(env, dex_paths, classloader))
        {
            LOGE("postAppSpecialize: InMemoryDexClassLoader creation failed");
            return;
        }

        jclass entry = load_class_from(
            env, classloader, "dev.ujhhgtg.wekit.loader.entry.zygisk.ZygiskEntry");
        if (!entry)
        {
            LOGE("postAppSpecialize: ZygiskEntry class not found");
            env->DeleteGlobalRef(classloader);
            return;
        }
        jmethodID init = env->GetStaticMethodID(
            entry, "init",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (!init)
        {
            log_and_clear_jni_exception(
                env, "ZygiskEntry.init(processName,dataDir,apkPath) lookup failed");
            env->DeleteLocalRef(entry);
            env->DeleteGlobalRef(classloader);
            return;
        }
        if (!register_entry_natives(env, entry))
        {
            LOGE("postAppSpecialize: failed to register ZygiskEntry bootstrap JNI");
            env->DeleteLocalRef(entry);
            env->DeleteGlobalRef(classloader);
            return;
        }

        jstring process = env->NewStringUTF(process_name.c_str());
        jstring data = env->NewStringUTF(data_dir.c_str());
        jstring apk = env->NewStringUTF(apk_path.c_str());
        if (!process || !data || !apk || env->ExceptionCheck())
        {
            log_and_clear_jni_exception(env,
                                        "ZygiskEntry.init argument allocation failed");
            if (process)
                env->DeleteLocalRef(process);
            if (data)
                env->DeleteLocalRef(data);
            if (apk)
                env->DeleteLocalRef(apk);
            env->DeleteLocalRef(entry);
            env->DeleteGlobalRef(classloader);
            return;
        }
        env->CallStaticVoidMethod(entry, init, process, data, apk);
        const bool failed = env->ExceptionCheck();
        if (failed)
        {
            env->ExceptionDescribe();
            env->ExceptionClear();
            LOGE("postAppSpecialize: ZygiskEntry.init failed");
        }
        else
        {
            LOGI("postAppSpecialize: ZygiskEntry.init completed");
        }
        env->DeleteLocalRef(process);
        env->DeleteLocalRef(data);
        env->DeleteLocalRef(apk);
        env->DeleteLocalRef(entry);
        if (failed)
        {
            env->DeleteGlobalRef(classloader);
            return;
        }

        // Keep the loader alive for bootstrap and generated hook bridge classes.
        module_classloader = classloader;
    }

    void preServerSpecialize(ServerSpecializeArgs * /*args*/) override
    {
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    Api *api = nullptr;
    JNIEnv *env = nullptr;
    bool enabled = false;
    jint app_uid = -1;
    jint app_gid = -1;
    int retained_module_dir_fd = -1;
    std::string process_name;
    std::string data_dir;
    std::string payload_base;
    std::string apk_path;
    std::vector<std::string> dex_names;
    std::vector<std::string> dex_paths;
    jobject module_classloader = nullptr;
};

extern "C"
{

    JNIEXPORT jboolean JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskEntry_nativeInitialize(
        JNIEnv *env, jclass entry)
    {
        // The entry class is already loaded, then its own loader is
        // trusted before any hook bridge class is resolved.
        if (!art_hook_init(env))
        {
            LOGE("ZygiskEntry.nativeInitialize: art_hook_init failed");
            return JNI_FALSE;
        }
        jobject classloader = get_class_loader(env, entry);
        if (!classloader)
            return JNI_FALSE;
        const bool trusted = art_trust_class_loader(env, classloader);
        if (!trusted)
        {
            LOGE("ZygiskEntry.nativeInitialize: failed to trust ZygiskEntry loader");
            env->DeleteLocalRef(classloader);
            return JNI_FALSE;
        }
        const bool registered = register_hook_bridge_natives(env, classloader);
        if (!registered)
            LOGE("ZygiskEntry.nativeInitialize: failed to register ArtHookBridge");
        env->DeleteLocalRef(classloader);
        return registered ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT jlong JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeGetArtMethod(
        JNIEnv *env, jclass /*clazz*/, jobject executable)
    {
        return static_cast<jlong>(art_get_art_method(env, executable));
    }

    JNIEXPORT jint JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeHookMethod(
        JNIEnv *env, jclass /*clazz*/, jlong target_art, jlong backup_art,
        jlong bridge_art, jlong /*hook_id*/)
    {
        if (!art_hook_is_initialized() && !art_hook_init(env))
            return -1;
        return art_hook_method(env, static_cast<uintptr_t>(target_art),
                               static_cast<uintptr_t>(backup_art),
                               static_cast<uintptr_t>(bridge_art))
                   ? 0
                   : -1;
    }

    JNIEXPORT jint JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeUnhookMethod(
        JNIEnv *env, jclass /*clazz*/, jlong target_art, jlong backup_art)
    {
        return art_unhook_method(env, static_cast<uintptr_t>(target_art),
                                 static_cast<uintptr_t>(backup_art))
                   ? 0
                   : -1;
    }

    JNIEXPORT jboolean JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeTrustClassLoader(
        JNIEnv *env, jclass /*clazz*/, jobject class_loader)
    {
        if (!art_hook_is_initialized() && !art_hook_init(env))
            return JNI_FALSE;
        return art_trust_class_loader(env, class_loader) ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT jboolean JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeTrustDexFile(
        JNIEnv *env, jclass /*clazz*/, jobject dex_file)
    {
        if (!art_hook_is_initialized() && !art_hook_init(env))
            return JNI_FALSE;
        return art_trust_dex_file(env, dex_file) ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT jobject JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeAllocateInstance(
        JNIEnv *env, jclass /*clazz*/, jclass target_class)
    {
        return target_class ? env->AllocObject(target_class) : nullptr;
    }

    JNIEXPORT jboolean JNICALL
    Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ArtHookBridge_nativeHideLoadedModuleLibraries(
        JNIEnv * /*env*/, jclass /*clazz*/)
    {
        LOGI("hideLoadedModuleLibraries: begin pid=%d library=libdexkit.so",
             getpid());
        const int dexkit = so_hide_path("libdexkit.so");
        LOGI("hideLoadedModuleLibraries: complete pid=%d library=libdexkit.so result=%d",
             getpid(), dexkit);
        LOGI("hideLoadedModuleLibraries: begin pid=%d library=libwekit_native.so",
             getpid());
        const int wekit_native = so_hide_path("libwekit_native.so");
        LOGI(
            "hideLoadedModuleLibraries: complete pid=%d library=libwekit_native.so result=%d",
            getpid(), wekit_native);
        LOGI("hideLoadedModuleLibraries: begin pid=%d library=libmmkv.so", getpid());
        const int mmkv = so_hide_path("libmmkv.so");
        LOGI("hideLoadedModuleLibraries: complete pid=%d library=libmmkv.so result=%d",
             getpid(), mmkv);
        return dexkit >= 0 && wekit_native >= 0 && mmkv >= 0 ? JNI_TRUE : JNI_FALSE;
    }

} // extern "C"

REGISTER_ZYGISK_MODULE(WekitZygisk)
REGISTER_ZYGISK_COMPANION(companion_handler)
