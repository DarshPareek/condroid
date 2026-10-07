use condroid_protocol::{Buttons, ControllerState};
use crate::gamepad::VirtualGamepad;
use tracing::{error, info};
use vigem_client::{Client, TargetId, XButtons, XGamepad, Xbox360Wired};

pub struct PlatformGamepad {
    target: Xbox360Wired<Client>,
}

impl PlatformGamepad {
    pub fn new(_device_name: &str) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        info!("Connecting to ViGEmBus driver...");
        let client = match Client::connect() {
            Ok(c) => c,
            Err(e) => {
                error!("===============================================================");
                error!("ERROR: Failed to connect to ViGEmBus driver!");
                error!("Please ensure the ViGEmBus virtual gamepad driver is installed.");
                error!("Download installer from: https://github.com/nefarius/ViGEmBus/releases");
                error!("===============================================================");
                return Err(Box::new(e));
            }
        };

        let mut target = Xbox360Wired::new(client, TargetId::XBOX360_WIRED);
        target.plugin()?;
        target.wait_ready()?;
        info!("Virtual Xbox 360 controller successfully plugged in via ViGEmBus.");

        Ok(Self { target })
    }
}

impl VirtualGamepad for PlatformGamepad {
    fn sync_state(&mut self, state: &ControllerState) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let mut raw_buttons = 0u16;

        let raw = state.buttons.0;
        if (raw & Buttons::BTN_A) != 0 { raw_buttons |= XButtons::A; }
        if (raw & Buttons::BTN_B) != 0 { raw_buttons |= XButtons::B; }
        if (raw & Buttons::BTN_X) != 0 { raw_buttons |= XButtons::X; }
        if (raw & Buttons::BTN_Y) != 0 { raw_buttons |= XButtons::Y; }
        if (raw & Buttons::BTN_LB) != 0 { raw_buttons |= XButtons::LB; }
        if (raw & Buttons::BTN_RB) != 0 { raw_buttons |= XButtons::RB; }
        if (raw & Buttons::BTN_SELECT) != 0 { raw_buttons |= XButtons::BACK; }
        if (raw & Buttons::BTN_START) != 0 { raw_buttons |= XButtons::START; }
        if (raw & Buttons::BTN_GUIDE) != 0 { raw_buttons |= XButtons::GUIDE; }
        if (raw & Buttons::BTN_THUMB_L) != 0 { raw_buttons |= XButtons::LTHUMB; }
        if (raw & Buttons::BTN_THUMB_R) != 0 { raw_buttons |= XButtons::RTHUMB; }
        if (raw & Buttons::DPAD_UP) != 0 { raw_buttons |= XButtons::UP; }
        if (raw & Buttons::DPAD_DOWN) != 0 { raw_buttons |= XButtons::DOWN; }
        if (raw & Buttons::DPAD_LEFT) != 0 { raw_buttons |= XButtons::LEFT; }
        if (raw & Buttons::DPAD_RIGHT) != 0 { raw_buttons |= XButtons::RIGHT; }

        let gamepad = XGamepad {
            buttons: XButtons(raw_buttons),
            left_trigger: state.left_trigger,
            right_trigger: state.right_trigger,
            thumb_lx: state.left_stick_x,
            thumb_ly: state.left_stick_y,
            thumb_rx: state.right_stick_x,
            thumb_ry: state.right_stick_y,
        };

        self.target.update(&gamepad)?;
        Ok(())
    }

    fn reset_neutral(&mut self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let neutral = XGamepad::default();
        self.target.update(&neutral)?;
        Ok(())
    }
}

impl Drop for PlatformGamepad {
    fn drop(&mut self) {
        let _ = self.target.unplug();
    }
}

