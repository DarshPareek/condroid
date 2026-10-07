# Contributing to Condroid

Thanks for your interest in contributing to **Condroid**! Whether you are fixing a bug, improving the low-latency streaming pipeline, adding layout presets, or polishing the UI, your help is appreciated.

---

## Getting Started

1. **Fork the repository** on GitHub.
2. **Clone your fork** locally:
   ```bash
   git clone https://github.com/<your-username>/condroid.git
   cd condroid
   ```
3. **Create a branch** for your feature or bug fix:
   ```bash
   git checkout -b feature/my-new-feature
   ```

---

## Codebase Architecture

Condroid is split into a modular workspace:

```text
condroid/
├── crates/
│   ├── condroid-protocol/   # 31-byte packed binary protocol & CRC8 validation
│   ├── condroid-uinput/     # Linux /dev/uinput Xbox 360 driver
│   ├── condroid-server/     # Low-latency UDP server daemon (Linux & Windows)
│   └── condroid-client/     # Latency benchmark tool, functional tester & keyboard simulator
└── android/                 # Native Android app (Kotlin & Material 3 / Material You)
    └── app/src/main/
        ├── java/com/condroid/app/
        │   ├── protocol/    # Packet serialization & CRC8
        │   ├── network/     # Non-blocking UDP sender/receiver
        │   ├── ui/          # GamepadView, Quick Ball, Layout Editor
        │   └── sensors/     # Gyroscope motion aiming
        └── res/             # Dynamic themes, layouts & vector drawables
```

### Key Principles
- **Zero Allocations in Hot Paths**: The input loop streams at 120Hz–240Hz. Both the Android packet serializer and the Rust server avoid runtime allocations per packet.
- **Fail-Safe Watchdog**: The host automatically resets buttons and centers thumbsticks if connection drops or becomes idle, preventing stuck inputs in games.
- **Platform Separation**: Gamepad hardware abstractions are isolated behind the `VirtualGamepad` trait in `crates/condroid-server/src/gamepad/`.

---

## Code Quality & Standards

### Rust
Before submitting your changes, ensure formatting and clippy checks pass:

```bash
# Check formatting
cargo fmt --all -- --check

# Run linter
cargo clippy --workspace --all-targets -- -D warnings

# Run tests
cargo test --workspace
```

### Android (Kotlin)
- Follow standard [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Verify the project builds cleanly without lint regressions:
  ```bash
  cd android
  ./gradlew lint
  ```

---

## Submitting a Pull Request

1. Commit your changes with clear, descriptive commit messages.
2. Push your branch to GitHub:
   ```bash
   git push origin feature/my-new-feature
   ```
3. Open a **Pull Request** against the `main` branch.
4. Describe what your changes do, why they are needed, and how you tested them.

---

## Reporting Issues

If you run into bugs or unexpected behavior:
- Check existing issues before opening a new one.
- Include your operating system (Linux distribution / Windows version), Android version, and device model.
- Include relevant terminal logs or `adb logcat` output.
