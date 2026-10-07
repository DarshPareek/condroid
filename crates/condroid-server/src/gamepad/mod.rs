use condroid_protocol::ControllerState;

pub trait VirtualGamepad: Send {
    fn sync_state(&mut self, state: &ControllerState) -> Result<(), Box<dyn std::error::Error + Send + Sync>>;
    fn reset_neutral(&mut self) -> Result<(), Box<dyn std::error::Error + Send + Sync>>;
}

#[cfg(target_os = "linux")]
pub mod linux;
#[cfg(target_os = "linux")]
pub use linux::PlatformGamepad;

#[cfg(target_os = "windows")]
pub mod windows;
#[cfg(target_os = "windows")]
pub use windows::PlatformGamepad;
