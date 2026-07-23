# 开发

## 1. 克隆仓库

```bash
git clone https://github.com/Ujhhgtg/WeKit.git --recursive
```

## 2. 安装系统依赖

### A. Arch Linux

```bash
# 确保已在 /etc/pacman.conf 中启用 multilib 软件源
yay -Syu lib32-glibc rustup
rustup toolchain install stable
rustup default stable
rustup target add x86_64-linux-android aarch64-linux-android armv7-linux-androideabi i686-linux-android
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;$(grep '^ndk' ./gradle/libs.versions.toml | sed 's/.*= "\(.*\)"/\1/')"
```

### B. Debian 系

```bash
sudo apt update -y && sudo apt full-upgrade -y
sudo apt install gcc-multilib rustup
rustup toolchain install stable
rustup default stable
rustup target add x86_64-linux-android aarch64-linux-android armv7-linux-androideabi i686-linux-android
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;$(grep '^ndk' ./gradle/libs.versions.toml | sed 's/.*= "\(.*\)"/\1/')"
```

### C. Windows

建议全文背诵 [停止用 Windows 工作!](https://zhuanlan.fxzhihu.com/p/2024527609388627701)

## 3. 构建

构建期间会自动编译 Rust 原生库, 无须手动编译

### 变体 (Flavor)

模块提供两个入口点变体, 通过 `entrypoint` flavor 维度区分:

- **standard**: 包含现代 libxposed api 入口点 (`entry/lxp/*` 与 `META-INF/xposed/*`), 框架会优先使用 libxposed 加载. 大多数用户应使用此变体.
- **legacy**: 移除了 libxposed 入口点与相关元数据, 使框架自动回退到传统 de.robv xposed api (`Xp51HookEntry` + `assets/xposed_init`). 供设备或框架对 libxposed 兼容性差的用户使用.

两个变体共用同一份 `applicationId` 与除入口点外的所有代码资源, 由 Gradle 的 flavor source set 机制自动分离, 无须手动删除文件.

```bash
# 单独构建某个变体
cargo xtask build --release --flavor standard
cargo xtask build --release --flavor legacy

# 一次性构建全部变体 (standard/legacy × debug/release)
cargo xtask build --release
```

产物按 `变体/构建类型` 分目录输出:

```none
app/build/outputs/apk/standard/release/app-standard-arm64-v8a-release.apk
app/build/outputs/apk/legacy/release/app-legacy-arm64-v8a-release.apk
```

### Zygisk 模块

Zygisk 模块由 `xtask` 统一构建，不再使用独立的 Python 构建脚本。

```bash
# 构建 standard APK split、arm64/arm32 Zygisk loader 与 debug 安装包
./x zygisk build

# release 安装包；或仅构建 native loader
./x zygisk build --release
./x zygisk native --abi arm64-v8a

# 刷入设备，或者复用最新 ZIP 而不重新构建
./x zygisk flash --device SERIAL --root ksu --reboot
./x zygisk flash --skip-build
```

`zygisk build` 默认构建两种受支持 ABI，并选择 standard flavor 的 split APK
作为 payload。通过 `--skip-apk-build --apk <path>`（每种 ABI 一次）可复用或显式指定 APK。

## 4. 安装

```bash
# standard 变体
cargo xtask run --release --flavor standard

# legacy 变体
cargo xtask run --release --flavor legacy

# 可选: 应用基准配置 (Baseline Profile)
adb shell cmd package compile -m speed-profile dev.ujhhgtg.wekit
```
