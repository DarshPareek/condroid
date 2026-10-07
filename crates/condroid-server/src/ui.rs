use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use condroid_protocol::{Buttons, ControllerState};
use eframe::egui::{self, Color32, RichText};
use tokio::sync::mpsc;


#[derive(Debug, Clone)]
pub enum ServerCommand {
    DisconnectSlot(u8),
    DisconnectAll,
}

#[derive(Debug, Clone)]
pub struct LogEntry {
    pub timestamp: String,
    pub message: String,
    pub is_warn: bool,
}

#[derive(Debug, Clone)]
pub struct SlotUiState {
    pub slot_id: u8,
    pub is_connected: bool,
    pub client_addr: String,
    pub transport: String,
    pub rtt_ms: f32,
    pub rate_hz: f32,
    pub state: ControllerState,
}

impl SlotUiState {
    pub fn new(slot_id: u8) -> Self {
        Self {
            slot_id,
            is_connected: false,
            client_addr: String::new(),
            transport: String::new(),
            rtt_ms: -1.0,
            rate_hz: 0.0,
            state: ControllerState::default(),
        }
    }
}

pub struct SharedServerState {
    pub server_status: String,
    pub lan_ip: String,
    pub port: u16,
    pub max_players: usize,
    pub os_backend: String,
    pub slots: Vec<SlotUiState>,
    pub logs: VecDeque<LogEntry>,
}

impl SharedServerState {
    pub fn new(port: u16, max_players: usize, lan_ip: String, os_backend: String) -> Self {
        let slots = (1..=max_players)
            .map(|i| SlotUiState::new(i as u8))
            .collect();

        let mut logs = VecDeque::new();
        logs.push_back(LogEntry {
            timestamp: Self::current_time_str(),
            message: format!("Condroid {} Server started on port {}", os_backend, port),
            is_warn: false,
        });

        Self {
            server_status: "Listening".to_string(),
            lan_ip,
            port,
            max_players,
            os_backend,
            slots,
            logs,
        }
    }

    pub fn current_time_str() -> String {
        let now = std::time::SystemTime::now();
        let dt: ChronoLite = now.into();
        format!("{:02}:{:02}:{:02}", dt.hours, dt.minutes, dt.seconds)
    }

    pub fn add_log(&mut self, message: String, is_warn: bool) {
        if self.logs.len() >= 100 {
            self.logs.pop_front();
        }
        self.logs.push_back(LogEntry {
            timestamp: Self::current_time_str(),
            message,
            is_warn,
        });
    }
}

// Lightweight timestamp helper without heavy chrono dependency
struct ChronoLite {
    hours: u64,
    minutes: u64,
    seconds: u64,
}

impl From<std::time::SystemTime> for ChronoLite {
    fn from(time: std::time::SystemTime) -> Self {
        let dur = time
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default();
        let secs = dur.as_secs();
        let day_secs = secs % 86400;
        let hours = day_secs / 3600;
        let minutes = (day_secs % 3600) / 60;
        let seconds = day_secs % 60;
        Self {
            hours,
            minutes,
            seconds,
        }
    }
}

pub struct CondroidApp {
    state: Arc<Mutex<SharedServerState>>,
    cmd_tx: mpsc::Sender<ServerCommand>,
    copy_notification_time: Option<Instant>,
}

impl CondroidApp {
    pub fn new(
        state: Arc<Mutex<SharedServerState>>,
        cmd_tx: mpsc::Sender<ServerCommand>,
    ) -> Self {
        Self {
            state,
            cmd_tx,
            copy_notification_time: None,
        }
    }
}

impl eframe::App for CondroidApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Continuous repaint for smooth telemetry
        ctx.request_repaint_after(Duration::from_millis(60));

        let state_guard = self.state.lock().unwrap();

        // 1. Top Panel: Header & Status
        egui::TopBottomPanel::top("top_header")
            .frame(egui::Frame::side_top_panel(&ctx.style()).inner_margin(16.0))
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.heading(RichText::new("🎮 Condroid Host").strong().size(22.0));

                    // Backend badge
                    ui.add_space(8.0);
                    let backend_color = if state_guard.os_backend == "Windows" {
                        Color32::from_rgb(0, 164, 239)
                    } else {
                        Color32::from_rgb(255, 165, 0)
                    };
                    ui.label(
                        RichText::new(format!("[{}]", state_guard.os_backend))
                            .color(backend_color)
                            .strong(),
                    );

                    // Running badge
                    ui.add_space(8.0);
                    ui.label(
                        RichText::new(format!("● {}", state_guard.server_status))
                            .color(Color32::from_rgb(0, 230, 118))
                            .strong(),
                    );

                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        let active_count = state_guard.slots.iter().filter(|s| s.is_connected).count();
                        if active_count > 0 {
                            let btn = egui::Button::new(RichText::new("Disconnect All").color(Color32::WHITE).size(12.0))
                                .fill(Color32::from_rgb(180, 40, 40));
                            if ui.add(btn).clicked() {
                                let _ = self.cmd_tx.try_send(ServerCommand::DisconnectAll);
                            }
                            ui.add_space(8.0);
                        }

                        ui.label(
                            RichText::new(format!("{}/{} Controllers Active", active_count, state_guard.max_players))
                                .color(if active_count > 0 { Color32::from_rgb(0, 230, 255) } else { Color32::GRAY })
                                .size(14.0)
                                .strong(),
                        );
                    });
                });

                ui.add_space(8.0);
                ui.separator();
                ui.add_space(6.0);

                // Network connection pills
                ui.horizontal_wrapped(|ui| {
                    ui.label(RichText::new("📡 Listening on:").color(Color32::LIGHT_GRAY));

                    let lan_display = if !state_guard.lan_ip.is_empty() {
                        format!("{}:{}", state_guard.lan_ip, state_guard.port)
                    } else {
                        format!("0.0.0.0:{}", state_guard.port)
                    };

                    ui.label(RichText::new(lan_display).monospace().strong().color(Color32::WHITE));
                    ui.label(RichText::new("(UDP + TCP)").color(Color32::GRAY));

                    ui.add_space(16.0);

                    // Copy ADB button
                    let adb_cmd = format!("adb reverse tcp:{} tcp:{}", state_guard.port, state_guard.port);
                    if ui.button(RichText::new("📋 Copy USB/ADB Command").size(12.0)).clicked() {
                        ctx.copy_text(adb_cmd);
                        self.copy_notification_time = Some(Instant::now());
                    }

                    if let Some(time) = self.copy_notification_time {
                        if time.elapsed() < Duration::from_secs(2) {
                            ui.label(RichText::new("Copied!").color(Color32::from_rgb(0, 230, 118)));
                        }
                    }
                });
            });

        // 2. Bottom Panel: Event Log
        egui::TopBottomPanel::bottom("bottom_logs")
            .resizable(true)
            .min_height(100.0)
            .default_height(140.0)
            .frame(egui::Frame::side_top_panel(&ctx.style()).inner_margin(12.0))
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.label(RichText::new("📋 Activity Log").strong().size(13.0));
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        ui.label(RichText::new("Protocol: Binary v1 (CRC8)").color(Color32::DARK_GRAY).size(11.0));
                    });
                });
                ui.add_space(4.0);

                egui::ScrollArea::vertical()
                    .stick_to_bottom(true)
                    .show(ui, |ui| {
                        for log in state_guard.logs.iter() {
                            ui.horizontal(|ui| {
                                ui.label(RichText::new(&log.timestamp).color(Color32::DARK_GRAY).monospace().size(11.0));
                                let text_color = if log.is_warn {
                                    Color32::from_rgb(255, 179, 0)
                                } else {
                                    Color32::from_rgb(200, 205, 215)
                                };
                                ui.label(RichText::new(&log.message).color(text_color).size(12.0));
                            });
                        }
                    });
            });

        // 3. Central Panel: Grid of Player Cards
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.add_space(8.0);
            ui.heading(RichText::new("Connected Devices & Player Slots").size(16.0));
            ui.add_space(8.0);

            let slots_data = state_guard.slots.clone();
            drop(state_guard); // Drop lock before layout / button events

            egui::ScrollArea::vertical().show(ui, |ui| {
                for slot in &slots_data {
                    ui.group(|ui| {
                        ui.set_width(ui.available_width());
                        ui.horizontal(|ui| {
                            // Player Badge
                            let (badge_bg, badge_text) = match slot.slot_id {
                                1 => (Color32::from_rgb(16, 124, 65), "PLAYER 1"),
                                2 => (Color32::from_rgb(0, 120, 215), "PLAYER 2"),
                                3 => (Color32::from_rgb(232, 17, 35), "PLAYER 3"),
                                _ => (Color32::from_rgb(255, 140, 0), "PLAYER 4"),
                            };

                            let frame = egui::Frame::NONE
                                .fill(badge_bg)
                                .corner_radius(4.0)
                                .inner_margin(egui::Margin::symmetric(8, 4));
                            frame.show(ui, |ui| {
                                ui.label(RichText::new(badge_text).strong().color(Color32::WHITE).size(12.0));
                            });

                            ui.add_space(8.0);

                            if slot.is_connected {
                                ui.label(RichText::new("● Connected").color(Color32::from_rgb(0, 230, 118)).strong());
                                ui.add_space(4.0);
                                ui.label(RichText::new(&slot.client_addr).monospace().color(Color32::WHITE));
                                ui.label(RichText::new(format!("({})", slot.transport)).color(Color32::GRAY));

                                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                    // Disconnect Button
                                    let btn = egui::Button::new(RichText::new("Disconnect").color(Color32::WHITE))
                                        .fill(Color32::from_rgb(180, 40, 40));
                                    if ui.add(btn).clicked() {
                                        let _ = self.cmd_tx.try_send(ServerCommand::DisconnectSlot(slot.slot_id));
                                    }

                                    // Latency Badge
                                    let (rtt_color, rtt_text) = if slot.rtt_ms >= 0.0 {
                                        let color = if slot.rtt_ms < 5.0 {
                                            Color32::from_rgb(0, 230, 118)
                                        } else if slot.rtt_ms < 15.0 {
                                            Color32::from_rgb(255, 214, 0)
                                        } else {
                                            Color32::from_rgb(255, 82, 82)
                                        };
                                        (color, format!("{:.1} ms", slot.rtt_ms))
                                    } else {
                                        (Color32::GRAY, "-- ms".to_string())
                                    };

                                    ui.label(RichText::new(format!("Ping: {}", rtt_text)).color(rtt_color).strong());
                                    ui.add_space(12.0);
                                    ui.label(RichText::new(format!("{:.0} Hz", slot.rate_hz)).color(Color32::LIGHT_GRAY));
                                    ui.add_space(8.0);
                                });
                            } else {
                                ui.label(RichText::new("○ Waiting for Device...").color(Color32::GRAY));
                                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                    ui.label(RichText::new("Open Condroid on Android to join").color(Color32::DARK_GRAY).italics());
                                });
                            }
                        });

                        // If connected, show live mini controller visualizer
                        if slot.is_connected {
                            ui.add_space(6.0);
                            ui.separator();
                            ui.add_space(4.0);

                            ui.horizontal(|ui| {
                                // Sticks
                                let lx = slot.state.left_stick_x as f32 / 32768.0;
                                let ly = slot.state.left_stick_y as f32 / 32768.0;
                                let rx = slot.state.right_stick_x as f32 / 32768.0;
                                let ry = slot.state.right_stick_y as f32 / 32768.0;
                                ui.label(RichText::new(format!("LS: ({:+.2}, {:+.2})", lx, ly)).monospace().size(11.0).color(Color32::LIGHT_BLUE));
                                ui.add_space(10.0);
                                ui.label(RichText::new(format!("RS: ({:+.2}, {:+.2})", rx, ry)).monospace().size(11.0).color(Color32::LIGHT_BLUE));

                                ui.add_space(16.0);

                                // Triggers
                                ui.label(RichText::new(format!("LT: {} | RT: {}", slot.state.left_trigger, slot.state.right_trigger)).monospace().size(11.0).color(Color32::LIGHT_GREEN));

                                ui.add_space(16.0);

                                // Buttons active
                                ui.label(RichText::new("Buttons:").size(11.0).color(Color32::GRAY));
                                let btns = slot.state.buttons;
                                let mut active_btns = Vec::new();
                                if btns.is_set(Buttons::BTN_A) { active_btns.push("A"); }
                                if btns.is_set(Buttons::BTN_B) { active_btns.push("B"); }
                                if btns.is_set(Buttons::BTN_X) { active_btns.push("X"); }
                                if btns.is_set(Buttons::BTN_Y) { active_btns.push("Y"); }
                                if btns.is_set(Buttons::BTN_LB) { active_btns.push("LB"); }
                                if btns.is_set(Buttons::BTN_RB) { active_btns.push("RB"); }
                                if btns.is_set(Buttons::DPAD_UP) { active_btns.push("UP"); }
                                if btns.is_set(Buttons::DPAD_DOWN) { active_btns.push("DOWN"); }
                                if btns.is_set(Buttons::DPAD_LEFT) { active_btns.push("LEFT"); }
                                if btns.is_set(Buttons::DPAD_RIGHT) { active_btns.push("RIGHT"); }
                                if btns.is_set(Buttons::BTN_START) { active_btns.push("START"); }
                                if btns.is_set(Buttons::BTN_SELECT) { active_btns.push("BACK"); }

                                if active_btns.is_empty() {
                                    ui.label(RichText::new("None").color(Color32::DARK_GRAY).size(11.0));
                                } else {
                                    for b in active_btns {
                                        ui.label(RichText::new(format!("[{}]", b)).color(Color32::YELLOW).strong().size(11.0));
                                    }
                                }
                            });
                        }
                    });
                    ui.add_space(6.0);
                }
            });
        });
    }
}
