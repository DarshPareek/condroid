use condroid_protocol::ControllerState;
use condroid_uinput::UinputGamepad;
use crate::gamepad::VirtualGamepad;

pub struct PlatformGamepad {
    inner: UinputGamepad,
}

impl PlatformGamepad {
    pub fn new(device_name: &str) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let inner = UinputGamepad::new(device_name)?;
        Ok(Self { inner })
    }
}

impl VirtualGamepad for PlatformGamepad {
    fn sync_state(&mut self, state: &ControllerState) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        self.inner.sync_state(state)?;
        Ok(())
    }

    fn reset_neutral(&mut self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        self.inner.reset_neutral()?;
        Ok(())
    }
}
