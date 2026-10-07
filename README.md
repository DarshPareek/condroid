# Condroid 🎮📱

> Turn your Android phone into an ultra-low-latency game controller for Linux and Windows.

Condroid is built for gamers who want responsive touch controls without annoying input lag. It streams controller inputs using a lightweight binary UDP protocol directly to a host daemon that emulates an official Microsoft Xbox 360 controller at the kernel level—giving you plug-and-play compatibility with Steam, emulators, and PC games.

---

## ✨ Features

- **⚡ Ultra-Low Latency**: Sub-1ms over USB cable, ~2ms over a 5GHz Wi-Fi hotspot.
- **🎮 Kernel Xbox 360 Emulation**: Native `/dev/uinput` driver on Linux and ViGEmBus on Windows. Works instantly with Steam, Wine/Proton, and all PC games.
- **🎨 Material You Theming**: Dynamically adapts colors from your phone's wallpaper (Android 12+).
- **🕹️ Fully Customizable Layouts**: Move, resize, and hide buttons. Choose button shapes (**Circle**, **Square**, **Triangle**, **Rounded Rect**), and save custom presets.
- **⚡ Floating Quick Ball**: Access settings, calibration, and layout presets through a collapsible floating ball without blocking in-game buttons.
- **👆 Sliding Multi-Touch**: Slide seamlessly between face buttons and D-pad with up to 10 simultaneous touch points.
- **🎯 Gyro Aiming & Haptics**: Motion aiming using your phone's gyroscope and tactile vibration feedback.

---

## 📦 Installation Instructions

Pre-compiled release packages are available in the [`release/`](release/) directory:

| Platform | Package | Download | Requirements |
| :--- | :--- | :--- | :--- |
| **Android** | Android App | [`condroid-v1.0.0-android.apk`](release/condroid-v1.0.0-android.apk) | Android 8.0 or newer |
| **Linux** | Server AppImage | [`condroid-server-v1.0.0-linux-x86_64.AppImage`](release/condroid-server-v1.0.0-linux-x86_64.AppImage) | 64-bit Linux |
| **Windows** | Windows Server | [`condroid-server-v1.0.0-windows-x64.exe`](release/condroid-server-v1.0.0-windows-x64.exe) | Windows 10/11 (64-bit) |

Integrity checksums are available in [`release/SHA256SUMS.txt`](release/SHA256SUMS.txt).

### Platform Setup

#### Linux
Make the AppImage executable and run:
```bash
chmod +x release/condroid-server-v1.0.0-linux-x86_64.AppImage
./release/condroid-server-v1.0.0-linux-x86_64.AppImage
```

> **Note**: Condroid uses `/dev/uinput` to create virtual controllers. Most modern distributions grant access automatically. If you get a permission error, add your user to the `input` group:
> ```bash
> sudo usermod -aG input $USER
> ```

#### Windows
Condroid requires the **ViGEmBus** virtual gamepad driver on Windows:
1. Download and run the [ViGEmBus Installer](https://github.com/nefarius/ViGEmBus/releases) (`ViGEmBus_Setup_...exe`).
2. Run `condroid-server-v1.0.0-windows-x64.exe` from PowerShell or Command Prompt.

#### Android
Download and install [`condroid-v1.0.0-android.apk`](release/condroid-v1.0.0-android.apk) on your phone.

---

## 🚀 Usage Guide

### 1. Start the Server
Start `condroid-server` on your PC. It will print its listening address (default UDP port `8448`) and local IP address.

### 2. Connect Your Phone
Open Condroid on your Android device and choose one of the connection modes:

- **USB Cable / ADB (Fastest — < 1 ms)**:
  Connect your phone with USB debugging enabled, then forward port 8448:
  ```bash
  adb forward tcp:8448 tcp:8448
  ```
  In the app, tap the **⚡ Quick Ball > Config**, select **ADB (127.0.0.1)**, and tap **Connect**.

- **5GHz PC Hotspot (~2 ms)**:
  Turn on a 5GHz Wi-Fi Hotspot on your PC, connect your phone to it, choose **Hotspot (10.42.0.1)**, and tap **Connect**.

- **Local Wi-Fi Network (~4–8 ms)**:
  Connect both your PC and phone to the same Wi-Fi router. Enter your PC's local IP address (shown in the server window) and tap **Connect**.

### 3. Customizing Your Gamepad
- **Quick Ball (⚡)**: Tap the floating icon at the bottom of the screen to expand the quick menu (Config, Gyro toggle, Layout selection).
- **Layout Editor**: From the Home screen, tap **Edit** on any layout preset to reposition buttons, resize them, or switch shapes between circles, squares, rounded rectangles, and triangles.

---

## 🛠️ Building from Source

To compile the server, package the AppImage, or build the Android APK yourself, check out the [Building Guide](BUILDING.md).

---

## 🤝 Contributing

Contributions, bug reports, and suggestions are welcome! Please check out [CONTRIBUTING.md](CONTRIBUTING.md) for architecture details, coding guidelines, and pull request steps.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
