use std::net::{IpAddr, SocketAddr, UdpSocket as StdUdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use clap::Parser;
use condroid_protocol::{
    Packet, PacketType, PongPacket, SlotInfoPacket, PROTOCOL_MAGIC, PROTOCOL_VERSION,
    PING_PACKET_SIZE, PONG_PACKET_SIZE, RUMBLE_PACKET_SIZE, SLOT_INFO_PACKET_SIZE, STATE_PACKET_SIZE,
};
mod gamepad;
mod ui;

use crate::gamepad::{PlatformGamepad, VirtualGamepad};
use crate::ui::{CondroidApp, ServerCommand, SharedServerState};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, UdpSocket};
use tokio::sync::mpsc;
use tokio::time::interval;
use tracing::{debug, error, info, warn};
use tracing_subscriber::EnvFilter;

/// Condroid Server - High-performance Gamepad Streaming Daemon with GUI
#[derive(Parser, Debug)]
#[command(author, version, about = "Turns your Android device into a low-latency virtual gamepad", long_about = None)]
struct Args {
    /// Port to listen on for UDP and TCP controller packets
    #[arg(short, long, default_value_t = 8448)]
    port: u16,

    /// Network interface address to bind
    #[arg(short, long, default_value = "0.0.0.0")]
    bind: String,

    /// Base name of the virtual gamepad registered in the OS
    #[arg(long, default_value = "Condroid Xbox 360 Controller")]
    device_name: String,

    /// Maximum number of simultaneous player controllers to support
    #[arg(long, default_value_t = 4)]
    max_players: usize,

    /// Timeout in milliseconds before centering sticks and releasing buttons when inactive
    #[arg(long, default_value_t = 1000)]
    timeout_ms: u64,

    /// Run in headless (terminal only) mode without desktop GUI
    #[arg(long)]
    headless: bool,

    /// Enable verbose debug logging
    #[arg(short, long)]
    verbose: bool,
}

fn get_local_ip_hint() -> Option<IpAddr> {
    let socket = StdUdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    socket.local_addr().ok().map(|addr| addr.ip())
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
enum ClientId {
    Udp(SocketAddr),
    Tcp(SocketAddr),
}

impl std::fmt::Display for ClientId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ClientId::Udp(addr) => write!(f, "{}", addr),
            ClientId::Tcp(addr) => write!(f, "{}", addr),
        }
    }
}

#[derive(Clone)]
enum ClientChannel {
    Udp(SocketAddr, Arc<UdpSocket>),
    Tcp(mpsc::Sender<Vec<u8>>),
}

impl ClientChannel {
    async fn send(&self, data: &[u8]) {
        match self {
            ClientChannel::Udp(addr, sock) => {
                let _ = sock.send_to(data, *addr).await;
            }
            ClientChannel::Tcp(tx) => {
                let _ = tx.send(data.to_vec()).await;
            }
        }
    }
}

struct IncomingEvent {
    client_id: ClientId,
    packet: Packet,
    channel: ClientChannel,
}

struct PlayerSlot {
    slot_id: u8,
    gamepad: Option<PlatformGamepad>,
    client_id: Option<ClientId>,
    channel: Option<ClientChannel>,
    last_seen_seq: u32,
    last_packet_time: Instant,
    last_rtt_ms: f32,
    packet_counter: u64,
    stale_counter: u64,
    rate_hz: f32,
    is_neutralized: bool,
    last_state: condroid_protocol::ControllerState,
}

impl PlayerSlot {
    fn new(slot_id: u8) -> Self {
        Self {
            slot_id,
            gamepad: None,
            client_id: None,
            channel: None,
            last_seen_seq: 0,
            last_packet_time: Instant::now(),
            last_rtt_ms: -1.0,
            packet_counter: 0,
            stale_counter: 0,
            rate_hz: 0.0,
            is_neutralized: true,
            last_state: condroid_protocol::ControllerState::default(),
        }
    }

    fn ensure_gamepad(&mut self, base_name: &str) -> Result<&mut PlatformGamepad, Box<dyn std::error::Error + Send + Sync>> {
        if self.gamepad.is_none() {
            let name = format!("{} #{}", base_name, self.slot_id);
            info!("Creating virtual gamepad for Player {}: '{}'...", self.slot_id, name);
            let gp = PlatformGamepad::new(&name)?;
            self.gamepad = Some(gp);
        }
        Ok(self.gamepad.as_mut().unwrap())
    }

    fn neutralize(&mut self) {
        if let Some(gp) = &mut self.gamepad {
            if !self.is_neutralized {
                let _ = gp.reset_neutral();
                self.is_neutralized = true;
                self.last_state = condroid_protocol::ControllerState::default();
            }
        }
    }

    fn release(&mut self) {
        self.neutralize();
        self.client_id = None;
        self.channel = None;
        self.last_seen_seq = 0;
        self.packet_counter = 0;
        self.stale_counter = 0;
        self.rate_hz = 0.0;
        self.last_rtt_ms = -1.0;
    }
}

async fn run_server_engine(
    bind_addr: String,
    device_name: String,
    max_players: usize,
    timeout_ms: u64,
    running: Arc<AtomicBool>,
    shared_state: Arc<Mutex<SharedServerState>>,
    mut cmd_rx: mpsc::Receiver<ServerCommand>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut slots: Vec<PlayerSlot> = (1..=max_players)
        .map(|i| PlayerSlot::new(i as u8))
        .collect();

    // Verify virtual gamepad backend on Slot 1
    info!("Verifying virtual gamepad backend for Slot 1...");
    match slots[0].ensure_gamepad(&device_name) {
        Ok(_) => info!("Virtual gamepad backend verified. Up to {} simultaneous players ready.", max_players),
        Err(e) => {
            error!("Failed to initialize virtual gamepad: {}", e);
            shared_state.lock().unwrap().add_log(format!("ERROR initializing virtual gamepad: {}", e), true);
            eprintln!("\nError: Could not initialize virtual gamepad: {}\n", e);
        }
    }

    // Setup UDP Socket
    let udp_socket = Arc::new(UdpSocket::bind(&bind_addr).await?);
    info!("UDP socket bound on {}", bind_addr);

    // Setup TCP Listener
    let tcp_listener = TcpListener::bind(&bind_addr).await?;
    info!("TCP socket bound on {}", bind_addr);

    let (tx, mut rx) = mpsc::channel::<IncomingEvent>(512);

    // 1. Spawn UDP Receiver Task
    let udp_tx = tx.clone();
    let udp_sock_recv = udp_socket.clone();
    let r_udp = running.clone();
    tokio::spawn(async move {
        let mut buf = [0u8; 1024];
        while r_udp.load(Ordering::SeqCst) {
            match udp_sock_recv.recv_from(&mut buf).await {
                Ok((len, src)) => {
                    if len >= 4 {
                        if let Ok(pkt) = Packet::parse(&buf[..len]) {
                            let _ = udp_tx.send(IncomingEvent {
                                client_id: ClientId::Udp(src),
                                packet: pkt,
                                channel: ClientChannel::Udp(src, udp_sock_recv.clone()),
                            }).await;
                        }
                    }
                }
                Err(e) => {
                    debug!("UDP recv error: {}", e);
                    break;
                }
            }
        }
    });

    // 2. Spawn TCP Acceptor Task
    let tcp_tx = tx.clone();
    let r_tcp = running.clone();
    tokio::spawn(async move {
        while r_tcp.load(Ordering::SeqCst) {
            match tcp_listener.accept().await {
                Ok((stream, peer)) => {
                    info!("New TCP client connected from {}", peer);
                    let _ = stream.set_nodelay(true);
                    let (tx_to_tcp, mut rx_from_tcp) = mpsc::channel::<Vec<u8>>(128);

                    let per_client_tx = tcp_tx.clone();
                    tokio::spawn(async move {
                        let (mut reader, mut writer) = stream.into_split();

                        let writer_task = tokio::spawn(async move {
                            while let Some(data) = rx_from_tcp.recv().await {
                                if writer.write_all(&data).await.is_err() {
                                    break;
                                }
                            }
                        });

                        let mut header = [0u8; 4];
                        let mut body = [0u8; 64];

                        loop {
                            if reader.read_exact(&mut header).await.is_err() {
                                break;
                            }
                            let magic = u16::from_le_bytes([header[0], header[1]]);
                            if magic != PROTOCOL_MAGIC || header[2] != PROTOCOL_VERSION {
                                break;
                            }

                            let expected_len = match PacketType::try_from(header[3]) {
                                Ok(PacketType::State) => STATE_PACKET_SIZE,
                                Ok(PacketType::Ping) => PING_PACKET_SIZE,
                                Ok(PacketType::Pong) => PONG_PACKET_SIZE,
                                Ok(PacketType::Rumble) => RUMBLE_PACKET_SIZE,
                                Ok(PacketType::SlotInfo) => SLOT_INFO_PACKET_SIZE,
                                Ok(PacketType::Disconnect) => 4,
                                Err(_) => break,
                            };

                            let remaining = expected_len - 4;
                            if remaining > 0 {
                                if reader.read_exact(&mut body[..remaining]).await.is_err() {
                                    break;
                                }
                            }

                            let mut full_pkt = Vec::with_capacity(expected_len);
                            full_pkt.extend_from_slice(&header);
                            full_pkt.extend_from_slice(&body[..remaining]);

                            if let Ok(pkt) = Packet::parse(&full_pkt) {
                                if per_client_tx.send(IncomingEvent {
                                    client_id: ClientId::Tcp(peer),
                                    packet: pkt,
                                    channel: ClientChannel::Tcp(tx_to_tcp.clone()),
                                }).await.is_err() {
                                    break;
                                }
                            }
                        }

                        info!("TCP client {} disconnected", peer);
                        let _ = per_client_tx.send(IncomingEvent {
                            client_id: ClientId::Tcp(peer),
                            packet: Packet::Disconnect,
                            channel: ClientChannel::Tcp(tx_to_tcp.clone()),
                        }).await;
                        writer_task.abort();
                    });
                }
                Err(_) => break,
            }
        }
    });

    let timeout_duration = Duration::from_millis(timeout_ms);
    let mut watchdog_interval = interval(Duration::from_millis(50));
    let mut last_stats_print = Instant::now();
    let mut pong_buf = [0u8; 64];

    while running.load(Ordering::SeqCst) {
        tokio::select! {
            _ = watchdog_interval.tick() => {
                let now = Instant::now();

                // Check slot timeouts
                for slot in &mut slots {
                    if let Some(client_id) = slot.client_id.clone() {
                        let elapsed = now.duration_since(slot.last_packet_time);

                        if elapsed > timeout_duration && !slot.is_neutralized {
                            warn!("⚠️ [Player {}] ({}) inactivity timeout. Neutralizing controller.", slot.slot_id, client_id);
                            slot.neutralize();
                            shared_state.lock().unwrap().add_log(format!("Player {} ({}) inactive -> neutral", slot.slot_id, client_id), true);
                        }

                        if elapsed > Duration::from_secs(30) {
                            info!("⌛ [Player {}] ({}) timed out after 30s. Releasing slot.", slot.slot_id, client_id);
                            shared_state.lock().unwrap().add_log(format!("Player {} ({}) timed out. Slot released.", slot.slot_id, client_id), false);
                            slot.release();
                        }
                    }
                }

                // Update UI shared state & throughput stats
                if last_stats_print.elapsed() >= Duration::from_millis(500) {
                    let elapsed_secs = last_stats_print.elapsed().as_secs_f64();
                    let mut state_lock = shared_state.lock().unwrap();

                    for (i, slot) in slots.iter_mut().enumerate() {
                        slot.rate_hz = (slot.packet_counter as f64 / elapsed_secs) as f32;
                        slot.packet_counter = 0;

                        if i < state_lock.slots.len() {
                            state_lock.slots[i].is_connected = slot.client_id.is_some();
                            state_lock.slots[i].client_addr = slot.client_id.as_ref().map(|c| c.to_string()).unwrap_or_default();
                            state_lock.slots[i].transport = if let Some(ClientId::Tcp(_)) = &slot.client_id {
                                "TCP/ADB".to_string()
                            } else if slot.client_id.is_some() {
                                "UDP".to_string()
                            } else {
                                String::new()
                            };
                            state_lock.slots[i].rtt_ms = slot.last_rtt_ms;
                            state_lock.slots[i].rate_hz = slot.rate_hz;
                            state_lock.slots[i].state = slot.last_state;
                        }
                    }

                    last_stats_print = Instant::now();
                }
            }

            // Commands from GUI
            cmd_opt = cmd_rx.recv() => {
                if let Some(cmd) = cmd_opt {
                    match cmd {
                        ServerCommand::DisconnectSlot(slot_id) => {
                            if let Some(slot) = slots.iter_mut().find(|s| s.slot_id == slot_id) {
                                if let Some(client_id) = slot.client_id.take() {
                                    info!("GUI: Disconnecting Player {} ({})", slot_id, client_id);
                                    shared_state.lock().unwrap().add_log(format!("Player {} ({}) kicked by host", slot_id, client_id), true);
                                    slot.release();
                                }
                            }
                        }
                        ServerCommand::DisconnectAll => {
                            info!("GUI: Disconnecting all controllers");
                            for slot in &mut slots {
                                slot.release();
                            }
                            shared_state.lock().unwrap().add_log("All player slots released by host".to_string(), true);
                        }
                    }
                }
            }

            event_opt = rx.recv() => {
                let event = match event_opt {
                    Some(e) => e,
                    None => break,
                };

                let client_id = event.client_id;

                // 1. Find if client is already assigned to a slot
                let mut slot_idx = slots.iter().position(|s| s.client_id.as_ref() == Some(&client_id));

                // 2. If not, assign to lowest free slot
                if slot_idx.is_none() {
                    if let Some(free_idx) = slots.iter().position(|s| s.client_id.is_none()) {
                        let slot_id = (free_idx + 1) as u8;
                        match slots[free_idx].ensure_gamepad(&device_name) {
                            Ok(_) => {
                                info!("🎮 [Player {}] Connected from {} (Assigned Slot {} of {})",
                                    slot_id, client_id, slot_id, max_players);
                                shared_state.lock().unwrap().add_log(
                                    format!("Player {} connected from {}", slot_id, client_id),
                                    false
                                );

                                slots[free_idx].client_id = Some(client_id.clone());
                                slots[free_idx].channel = Some(event.channel.clone());
                                slots[free_idx].last_packet_time = Instant::now();
                                slots[free_idx].last_seen_seq = 0;
                                slots[free_idx].is_neutralized = false;
                                slot_idx = Some(free_idx);

                                // Send SlotInfo packet to client
                                let slot_info = SlotInfoPacket {
                                    player_slot: slot_id,
                                    total_slots: max_players as u8,
                                };
                                let mut slot_buf = [0u8; 16];
                                if let Ok(written) = slot_info.serialize(&mut slot_buf) {
                                    event.channel.send(&slot_buf[..written]).await;
                                }
                            }
                            Err(e) => {
                                error!("Failed to provision gamepad for Player {}: {}", slot_id, e);
                                shared_state.lock().unwrap().add_log(
                                    format!("Failed to provision gamepad for Player {}: {}", slot_id, e),
                                    true
                                );
                            }
                        }
                    } else {
                        warn!("⚠️ Client {} attempted connection, but all {} slots are full", client_id, max_players);
                    }
                }

                // 3. Dispatch to assigned slot
                if let Some(idx) = slot_idx {
                    let slot = &mut slots[idx];
                    slot.channel = Some(event.channel.clone());

                    match event.packet {
                        Packet::State(state) => {
                            let is_newer = if slot.last_seen_seq == 0 {
                                true
                            } else if state.seq > slot.last_seen_seq {
                                true
                            } else {
                                (slot.last_seen_seq.wrapping_sub(state.seq)) > (u32::MAX / 2)
                            };

                            if is_newer {
                                slot.last_seen_seq = state.seq;
                                slot.last_packet_time = Instant::now();
                                slot.packet_counter += 1;
                                slot.is_neutralized = false;
                                slot.last_state = state;

                                if let Some(gp) = &mut slot.gamepad {
                                    let _ = gp.sync_state(&state);
                                }
                            } else {
                                slot.stale_counter += 1;
                            }
                        }
                        Packet::Ping(ping) => {
                            slot.last_packet_time = Instant::now();
                            let now_ms = (Instant::now().elapsed().as_millis() & 0xFFFFFFFF) as u32;

                            // Calculate instantaneous latency if client sent a timestamp
                            if now_ms >= ping.timestamp_ms {
                                slot.last_rtt_ms = (now_ms - ping.timestamp_ms) as f32;
                            }

                            let pong = PongPacket {
                                seq: ping.seq,
                                client_timestamp_ms: ping.timestamp_ms,
                                server_timestamp_ms: now_ms,
                            };
                            if let Ok(written) = pong.serialize(&mut pong_buf) {
                                event.channel.send(&pong_buf[..written]).await;
                            }
                        }
                        Packet::Disconnect => {
                            info!("🎮 [Player {}] ({}) disconnected cleanly.", slot.slot_id, client_id);
                            shared_state.lock().unwrap().add_log(format!("Player {} ({}) disconnected", slot.slot_id, client_id), false);
                            slot.release();
                        }
                        _ => {}
                    }
                }
            }
        }
    }

    info!("Shutting down Condroid server engine. Neutralizing all controllers...");
    for slot in &mut slots {
        slot.neutralize();
    }
    Ok(())
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();

    let default_filter = if args.verbose { "debug" } else { "info" };
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new(default_filter)),
        )
        .init();

    let os_title = if cfg!(target_os = "windows") { "Windows" } else { "Linux" };
    info!("===============================================================");
    info!("             Condroid {} Host Daemon Started                ", os_title);
    info!("===============================================================");
    let local_ip = get_local_ip_hint();
    let lan_ip_str = local_ip.map(|ip| ip.to_string()).unwrap_or_default();
    if !lan_ip_str.is_empty() {
        info!(">>> Primary LAN / Wi-Fi IP: {}:{}", lan_ip_str, args.port);
    }
    info!(">>> Listening on all interfaces (UDP + TCP): 0.0.0.0:{}", args.port);
    info!(">>> Maximum simultaneous player slots: {}", args.max_players);
    info!("===============================================================");

    let shared_state = Arc::new(Mutex::new(SharedServerState::new(
        args.port,
        args.max_players,
        lan_ip_str,
        os_title.to_string(),
    )));

    let (cmd_tx, cmd_rx) = mpsc::channel::<ServerCommand>(32);
    let running = Arc::new(AtomicBool::new(true));

    let r_clone = running.clone();
    let shared_state_clone = shared_state.clone();
    let max_players = args.max_players;
    let bind_addr = format!("{}:{}", args.bind, args.port);
    let device_name = args.device_name.clone();
    let timeout_ms = args.timeout_ms;

    let server_thread = std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("Failed to build Tokio runtime");

        rt.block_on(async move {
            if let Err(e) = run_server_engine(
                bind_addr,
                device_name,
                max_players,
                timeout_ms,
                r_clone,
                shared_state_clone,
                cmd_rx,
            ).await {
                error!("Server engine error: {}", e);
            }
        });
    });

    if args.headless {
        info!("Running in headless mode (no GUI). Press Ctrl+C to terminate.");
        let r_ctrlc = running.clone();
        let _ = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()?
            .block_on(async move {
                tokio::signal::ctrl_c().await.ok();
                info!("Shutdown signal received (Ctrl+C). Exiting...");
                r_ctrlc.store(false, Ordering::SeqCst);
            });
        let _ = server_thread.join();
    } else {
        info!("Launching Condroid Host Desktop GUI...");
        let mut native_options = eframe::NativeOptions::default();
        native_options.viewport = eframe::egui::ViewportBuilder::default()
            .with_inner_size([780.0, 620.0])
            .with_min_inner_size([650.0, 500.0])
            .with_title(format!("Condroid {} Host", os_title));

        let app = CondroidApp::new(shared_state.clone(), cmd_tx);
        let _ = eframe::run_native(
            &format!("Condroid {} Host", os_title),
            native_options,
            Box::new(|_cc| Ok(Box::new(app))),
        );

        info!("GUI window closed. Terminating server engine...");
        running.store(false, Ordering::SeqCst);
        let _ = server_thread.join();
    }

    info!("Condroid server shutdown completed.");
    Ok(())
}
