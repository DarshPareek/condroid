# Building Condroid from Source

This guide covers building all components of **Condroid**:
1. **Server Daemon** for Linux and Windows
2. **Linux AppImage** package
3. **Android Application** (Debug and Release)
4. **Test & Benchmark Utilities**

---

## 1. Prerequisites

### Host Environment
- **Rust Toolchain**: `rustup` with Rust 1.75+
- **JDK 17+** and **Android SDK** (for Android client)
- **Git**

```bash
# Install Rust (if not already installed)
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

---

## 2. Building the Server (Linux)

### Build with Cargo
To build the optimized Linux binary:

```bash
cargo build --release -p condroid-server
```

The compiled binary will be placed at:
```text
target/release/condroid-server
```

### Packaging the Standalone AppImage
To bundle the binary into a portable `.AppImage`:

```bash
# 1. Download appimagetool if you do not have it
curl -L -o /tmp/appimagetool https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
chmod +x /tmp/appimagetool

# 2. Stage files into AppDir
mkdir -p packaging/linux/AppDir/usr/bin
cp target/release/condroid-server packaging/linux/AppDir/usr/bin/condroid-server
chmod +x packaging/linux/AppDir/usr/bin/condroid-server
cp packaging/linux/AppRun packaging/linux/AppDir/AppRun
chmod +x packaging/linux/AppDir/AppRun
cp packaging/linux/condroid.desktop packaging/linux/AppDir/condroid.desktop

# 3. Generate the AppImage
mkdir -p release
ARCH=x86_64 /tmp/appimagetool --appimage-extract-and-run -n packaging/linux/AppDir release/condroid-server-v1.0.0-linux-x86_64.AppImage
```

---

## 3. Building the Server (Windows)

The Windows server can be cross-compiled directly from Linux using `cargo-xwin` or built natively on Windows.

### Cross-compiling from Linux (Recommended)
`cargo-xwin` automatically retrieves Windows SDK headers and CRT libraries to produce official MSVC binaries:

```bash
# Add the Windows MSVC target
rustup target add x86_64-pc-windows-msvc

# Install cargo-xwin
cargo install cargo-xwin

# Build release executable
cargo xwin build --release --target x86_64-pc-windows-msvc -p condroid-server
```

The output `.exe` will be located at:
```text
target/x86_64-pc-windows-msvc/release/condroid-server.exe
```

### Building on Windows
On a Windows machine with Visual Studio Build Tools (C++ workload) installed:

```cmd
cargo build --release -p condroid-server
```

---

## 4. Building the Android App

### Command Line (Gradle)
Navigate to the `android/` directory:

```bash
cd android

# Build Debug APK
./gradlew assembleDebug

# Build Signed Release APK
./gradlew assembleRelease
```

Generated packages:
- Debug: `android/app/build/outputs/apk/debug/app-debug.apk`
- Release: `android/app/build/outputs/apk/release/app-release.apk`

### Android Studio
1. Open Android Studio.
2. Select **Open** and choose the `condroid/android` directory.
3. Allow Gradle to sync dependencies.
4. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)** or click **Run**.

---

## 5. Benchmarking & Testing Tools

Condroid includes a dedicated test suite and latency measurement tool (`condroid-client`):

### Latency Benchmark (Round-Trip Time & Jitter)
Measures packet round-trip time, loss rate, and jitter at up to 1000 Hz:

```bash
cargo run --bin condroid-client -- benchmark --rate-hz 1000 --duration-secs 5
```

### Automated Functional Test
Simulates all controller inputs (D-pad, triggers, thumbsticks, buttons) to verify kernel driver responsiveness:

```bash
cargo run --bin condroid-client -- test
```

### Interactive Terminal Controller
Drive the virtual Xbox controller using your PC keyboard:

```bash
cargo run --bin condroid-client -- interactive
```
