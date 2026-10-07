use std::io;
use std::path::PathBuf;

use condroid_protocol::{Buttons, ControllerState};
use evdev::uinput::{VirtualDevice};
use evdev::{
    AbsInfo, AbsoluteAxisCode, AttributeSet, BusType, EventType, InputEvent, InputId, KeyCode,
    UinputAbsSetup,
};
use thiserror::Error;
use tracing::{debug, info};

#[derive(Debug, Error)]
pub enum UinputError {
    #[error("Failed to open or initialize uinput: {0}")]
    Io(#[from] io::Error),

    #[error("Permission denied accessing /dev/uinput. Ensure user is in 'input' group or uaccess ACLs are configured")]
    PermissionDenied,
}

pub struct UinputGamepad {
    device: VirtualDevice,
    last_state: ControllerState,
}

impl UinputGamepad {
    /// Creates and registers a new virtual Xbox 360 compatible gamepad on Linux.
    pub fn new(device_name: &str) -> Result<Self, UinputError> {
        let mut keys = AttributeSet::<KeyCode>::new();

        // Face buttons
        keys.insert(KeyCode::BTN_SOUTH); // A
        keys.insert(KeyCode::BTN_EAST);  // B
        keys.insert(KeyCode::BTN_NORTH); // X
        keys.insert(KeyCode::BTN_WEST);  // Y

        // Bumpers & Thumbstick clicks
        keys.insert(KeyCode::BTN_TL);     // LB
        keys.insert(KeyCode::BTN_TR);     // RB
        keys.insert(KeyCode::BTN_THUMBL); // LS (Thumb L)
        keys.insert(KeyCode::BTN_THUMBR); // RS (Thumb R)

        // Menu / Navigation
        keys.insert(KeyCode::BTN_SELECT); // Back / Select / View
        keys.insert(KeyCode::BTN_START);  // Start / Menu
        keys.insert(KeyCode::BTN_MODE);   // Guide / Home

        // D-Pad buttons (for compatibility with engines that check keys rather than HAT)
        keys.insert(KeyCode::BTN_DPAD_UP);
        keys.insert(KeyCode::BTN_DPAD_DOWN);
        keys.insert(KeyCode::BTN_DPAD_LEFT);
        keys.insert(KeyCode::BTN_DPAD_RIGHT);

        // Microsoft Xbox 360 Controller USB Vendor and Product ID
        let input_id = InputId::new(BusType::BUS_USB, 0x045e, 0x028e, 0x0114);

        // Thumbstick analog configuration (-32768 to 32767)
        let stick_abs = AbsInfo::new(0, -32768, 32767, 16, 128, 0);

        // Trigger analog configuration (0 to 255)
        let trigger_abs = AbsInfo::new(0, 0, 255, 0, 0, 0);

        // D-pad HAT axis (-1 to 1)
        let dpad_abs = AbsInfo::new(0, -1, 1, 0, 0, 0);

        let mut builder = VirtualDevice::builder().map_err(|e| {
            if e.kind() == io::ErrorKind::PermissionDenied {
                UinputError::PermissionDenied
            } else {
                UinputError::Io(e)
            }
        })?;

        builder = builder
            .name(device_name)
            .input_id(input_id)
            .with_keys(&keys)?
            .with_absolute_axis(&UinputAbsSetup::new(AbsoluteAxisCode::ABS_X, stick_abs))?
            .with_absolute_axis(&UinputAbsSetup::new(AbsoluteAxisCode::ABS_Y, stick_abs))?
            .with_absolute_axis(&UinputAbsSetup::new(AbsoluteAxisCode::ABS_RX, stick_abs))?
            .with_absolute_axis(&UinputAbsSetup::new(AbsoluteAxisCode::ABS_RY, stick_abs))?
            .with_absolute_axis(&UinputAbsSetup::new(AbsoluteAxisCode::ABS_Z, trigger_abs))?
            .with_absolute_axis(&UinputAbsSetup::new(AbsoluteAxisCode::ABS_RZ, trigger_abs))?
            .with_absolute_axis(&UinputAbsSetup::new(AbsoluteAxisCode::ABS_HAT0X, dpad_abs))?
            .with_absolute_axis(&UinputAbsSetup::new(AbsoluteAxisCode::ABS_HAT0Y, dpad_abs))?;

        let mut device = builder.build().map_err(UinputError::Io)?;

        let syspath = device.get_syspath().unwrap_or_else(|_| PathBuf::from("unknown"));
        info!(
            name = %device_name,
            syspath = %syspath.display(),
            "Virtual Xbox 360 gamepad successfully registered in Linux kernel"
        );

        let initial_state = ControllerState::default();
        let mut gamepad = Self {
            device,
            last_state: initial_state,
        };

        // Emit initial centered state
        gamepad.sync_state(&initial_state)?;

        Ok(gamepad)
    }

    /// Returns the sysfs path of the virtual device node.
    pub fn syspath(&mut self) -> Option<PathBuf> {
        self.device.get_syspath().ok()
    }

    /// Synchronizes the current controller state to the Linux kernel via uinput.
    /// Batches all changed axes and button events into a single atomic kernel report.
    pub fn sync_state(&mut self, state: &ControllerState) -> Result<(), UinputError> {
        let mut events = Vec::with_capacity(20);

        // --- Analog Sticks ---
        if state.left_stick_x != self.last_state.left_stick_x {
            events.push(InputEvent::new(
                EventType::ABSOLUTE.0,
                AbsoluteAxisCode::ABS_X.0,
                state.left_stick_x as i32,
            ));
        }
        if state.left_stick_y != self.last_state.left_stick_y {
            events.push(InputEvent::new(
                EventType::ABSOLUTE.0,
                AbsoluteAxisCode::ABS_Y.0,
                state.left_stick_y as i32,
            ));
        }
        if state.right_stick_x != self.last_state.right_stick_x {
            events.push(InputEvent::new(
                EventType::ABSOLUTE.0,
                AbsoluteAxisCode::ABS_RX.0,
                state.right_stick_x as i32,
            ));
        }
        if state.right_stick_y != self.last_state.right_stick_y {
            events.push(InputEvent::new(
                EventType::ABSOLUTE.0,
                AbsoluteAxisCode::ABS_RY.0,
                state.right_stick_y as i32,
            ));
        }

        // --- Triggers ---
        if state.left_trigger != self.last_state.left_trigger {
            events.push(InputEvent::new(
                EventType::ABSOLUTE.0,
                AbsoluteAxisCode::ABS_Z.0,
                state.left_trigger as i32,
            ));
        }
        if state.right_trigger != self.last_state.right_trigger {
            events.push(InputEvent::new(
                EventType::ABSOLUTE.0,
                AbsoluteAxisCode::ABS_RZ.0,
                state.right_trigger as i32,
            ));
        }

        // --- D-Pad Hat & Keys ---
        let dpad_x = if state.buttons.is_set(Buttons::DPAD_RIGHT) {
            1
        } else if state.buttons.is_set(Buttons::DPAD_LEFT) {
            -1
        } else {
            0
        };

        let last_dpad_x = if self.last_state.buttons.is_set(Buttons::DPAD_RIGHT) {
            1
        } else if self.last_state.buttons.is_set(Buttons::DPAD_LEFT) {
            -1
        } else {
            0
        };

        if dpad_x != last_dpad_x {
            events.push(InputEvent::new(
                EventType::ABSOLUTE.0,
                AbsoluteAxisCode::ABS_HAT0X.0,
                dpad_x,
            ));
        }

        let dpad_y = if state.buttons.is_set(Buttons::DPAD_DOWN) {
            1
        } else if state.buttons.is_set(Buttons::DPAD_UP) {
            -1
        } else {
            0
        };

        let last_dpad_y = if self.last_state.buttons.is_set(Buttons::DPAD_DOWN) {
            1
        } else if self.last_state.buttons.is_set(Buttons::DPAD_UP) {
            -1
        } else {
            0
        };

        if dpad_y != last_dpad_y {
            events.push(InputEvent::new(
                EventType::ABSOLUTE.0,
                AbsoluteAxisCode::ABS_HAT0Y.0,
                dpad_y,
            ));
        }

        // --- Buttons Helper Macro ---
        macro_rules! sync_button {
            ($mask:expr, $code:expr) => {
                let is_pressed = state.buttons.is_set($mask);
                let was_pressed = self.last_state.buttons.is_set($mask);
                if is_pressed != was_pressed {
                    events.push(InputEvent::new(
                        EventType::KEY.0,
                        $code.0,
                        if is_pressed { 1 } else { 0 },
                    ));
                }
            };
        }

        sync_button!(Buttons::BTN_A, KeyCode::BTN_SOUTH);
        sync_button!(Buttons::BTN_B, KeyCode::BTN_EAST);
        sync_button!(Buttons::BTN_X, KeyCode::BTN_NORTH);
        sync_button!(Buttons::BTN_Y, KeyCode::BTN_WEST);
        sync_button!(Buttons::BTN_LB, KeyCode::BTN_TL);
        sync_button!(Buttons::BTN_RB, KeyCode::BTN_TR);
        sync_button!(Buttons::BTN_SELECT, KeyCode::BTN_SELECT);
        sync_button!(Buttons::BTN_START, KeyCode::BTN_START);
        sync_button!(Buttons::BTN_GUIDE, KeyCode::BTN_MODE);
        sync_button!(Buttons::BTN_THUMB_L, KeyCode::BTN_THUMBL);
        sync_button!(Buttons::BTN_THUMB_R, KeyCode::BTN_THUMBR);
        sync_button!(Buttons::DPAD_UP, KeyCode::BTN_DPAD_UP);
        sync_button!(Buttons::DPAD_DOWN, KeyCode::BTN_DPAD_DOWN);
        sync_button!(Buttons::DPAD_LEFT, KeyCode::BTN_DPAD_LEFT);
        sync_button!(Buttons::DPAD_RIGHT, KeyCode::BTN_DPAD_RIGHT);

        if !events.is_empty() {
            debug!(count = events.len(), "Emitting uinput batch to kernel");
            self.device.emit(&events).map_err(UinputError::Io)?;
        }

        self.last_state = *state;
        Ok(())
    }

    /// Resets all inputs to neutral/released state (e.g., on client disconnect or timeout)
    pub fn reset_neutral(&mut self) -> Result<(), UinputError> {
        let neutral = ControllerState::default();
        self.sync_state(&neutral)
    }
}
