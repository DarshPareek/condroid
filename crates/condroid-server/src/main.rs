use std::net::{IpAddr, SocketAddr, UdpSocket as StdUdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use clap::Parser;
use condroid_protocol::{
    Packet, PacketType, PongPacket, PROTOCOL_MAGIC, PROTOCOL_VERSION,
    PING_PACKET_SIZE, PONG_PACKET_SIZE, RUMBLE_PACKET_SIZE, STATE_PACKET_SIZE,
};
mod gamepad;
use crate::gamepad::{PlatformGamepad, VirtualGamepad};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, UdpSocket};
use tokio::sync::mpsc;
use tokio::time::interval;
use tracing::{debug, error, info, warn};
use tracing_subscriber::EnvFilter;

/// Condroid Server - High-performance Gamepad Streaming Daemon for Linux
#[derive(Parser, Debug)]
#[command(author, version, about = "Turns your Android device into a low-latency virtual Linux gamepad", long_about = None)]
struct Args {
    /// Port to listen on for UDP and TCP controller packets
    #[arg(short, long, default_value_t = 8448)]
    port: u16,

    /// Network interface address to bind
    #[arg(short, long, default_value = "0.0.0.0")]
    bind: String,

    /// Name of the virtual gamepad registered in the Linux kernel
    #[arg(long, default_value = "Condroid Xbox 360 Controller")]
    device_name: String,

    /// Timeout in milliseconds before centering sticks and releasing buttons when inactive
    #[arg(long, default_value_t = 1000)]
    timeout_ms: u64,

    /// Enable verbose debug logging
    #[arg(short, long)]
    verbose: bool,
}

fn get_local_ip_hint() -> Option<IpAddr> {
    let socket = StdUdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    socket.local_addr().ok().map(|addr| addr.ip())
}

enum ClientChannel {
    Udp(SocketAddr, Arc<UdpSocket>),
    Tcp(mpsc::Sender<Vec<u8>>),
}

struct IncomingEvent {
    packet: Packet,
    client: ClientChannel,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
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
    if let Some(ip) = get_local_ip_hint() {
        info!(">>> Primary LAN / Wi-Fi IP: {}:{}", ip, args.port);
    }
    info!(">>> Listening on all interfaces (UDP + TCP): 0.0.0.0:{}", args.port);
    info!("");
    info!("Connection Modes for Lowest Latency:");
    info!("  1. USB Wired / ADB Reverse (Lowest Latency: < 1 ms):");
    info!("     Run: adb reverse tcp:{} tcp:{}", args.port, args.port);
    info!("     In Android App: Tap 'ADB' (127.0.0.1:{})", args.port);
    info!("");
    info!("  2. Wi-Fi / Hotspot (Wireless ~2 - 8 ms):");
    if cfg!(target_os = "linux") {
        info!("     If using UFW firewall on Linux, allow port {}:", args.port);
        info!("     sudo ufw allow {}/udp && sudo ufw allow {}/tcp", args.port, args.port);
    } else {
        info!("     Ensure port {} is allowed through Windows Firewall if prompted.", args.port);
    }
    info!("===============================================================");

    info!("Initializing virtual gamepad backend...");
    let mut gamepad = match PlatformGamepad::new(&args.device_name) {
        Ok(gp) => gp,
        Err(e) => {
            error!("Failed to initialize virtual gamepad: {}", e);
            eprintln!("\nError: Could not initialize virtual gamepad. Details: {}", e);
            std::process::exit(1);
        }
    };

    let bind_addr = format!("{}:{}", args.bind, args.port);

    // Setup UDP Socket
    let udp_socket = Arc::new(UdpSocket::bind(&bind_addr).await?);
    info!("UDP socket bound on {}", bind_addr);

    // Setup TCP Listener
    let tcp_listener = TcpListener::bind(&bind_addr).await?;
    info!("TCP socket bound on {}", bind_addr);

    let (tx, mut rx) = mpsc::channel::<IncomingEvent>(512);

    let running = Arc::new(AtomicBool::new(true));
    let r = running.clone();

    tokio::spawn(async move {
        tokio::signal::ctrl_c().await.ok();
        info!("Received shutdown signal (Ctrl+C). Cleaning up...");
        r.store(false, Ordering::SeqCst);
    });

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
                                packet: pkt,
                                client: ClientChannel::Udp(src, udp_sock_recv.clone()),
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

                        // Outbound writer subtask
                        let writer_task = tokio::spawn(async move {
                            while let Some(data) = rx_from_tcp.recv().await {
                                if writer.write_all(&data).await.is_err() {
                                    break;
                                }
                            }
                        });

                        // Inbound reader
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
                                    packet: pkt,
                                    client: ClientChannel::Tcp(tx_to_tcp.clone()),
                                }).await.is_err() {
                                    break;
                                }
                            }
                        }

                        info!("TCP client {} disconnected", peer);
                        writer_task.abort();
                    });
                }
                Err(_) => break,
            }
        }
    });

    let mut last_seen_seq: u32 = 0;
    let mut has_client = false;
    let mut last_packet_time = Instant::now();
    let timeout_duration = Duration::from_millis(args.timeout_ms);

    let mut packet_counter: u64 = 0;
    let mut stale_counter: u64 = 0;
    let mut last_stats_print = Instant::now();

    let mut watchdog_interval = interval(Duration::from_millis(50));
    let mut pong_buf = [0u8; 64];

    while running.load(Ordering::SeqCst) {
        tokio::select! {
            _ = watchdog_interval.tick() => {
                if has_client && last_packet_time.elapsed() > timeout_duration {
                    warn!("Client inactivity timeout reached ({:?}). Neutralizing controller.", timeout_duration);
                    let _ = gamepad.reset_neutral();
                    has_client = false;
                    last_seen_seq = 0;
                }

                if last_stats_print.elapsed() >= Duration::from_secs(3) {
                    if has_client && packet_counter > 0 {
                        let elapsed_secs = last_stats_print.elapsed().as_secs_f64();
                        let rate_hz = (packet_counter as f64) / elapsed_secs;
                        info!(
                            rate_hz = format!("{:.1}", rate_hz),
                            packets = packet_counter,
                            stale_dropped = stale_counter,
                            "Streaming active"
                        );
                    }
                    packet_counter = 0;
                    stale_counter = 0;
                    last_stats_print = Instant::now();
                }
            }

            event_opt = rx.recv() => {
                let event = match event_opt {
                    Some(e) => e,
                    None => break,
                };

                match event.packet {
                    Packet::State(state) => {
                        let is_newer = if has_client {
                            if state.seq > last_seen_seq {
                                true
                            } else {
                                (last_seen_seq.wrapping_sub(state.seq)) > (u32::MAX / 2)
                            }
                        } else {
                            info!("Active controller stream established");
                            has_client = true;
                            true
                        };

                        if is_newer {
                            last_seen_seq = state.seq;
                            last_packet_time = Instant::now();
                            packet_counter += 1;

                            if let Err(e) = gamepad.sync_state(&state) {
                                error!("Failed to sync gamepad state: {}", e);
                            }
                        } else {
                            stale_counter += 1;
                        }
                    }
                    Packet::Ping(ping) => {
                        let now_ms = (Instant::now().elapsed().as_millis() & 0xFFFFFFFF) as u32;
                        let pong = PongPacket {
                            seq: ping.seq,
                            client_timestamp_ms: ping.timestamp_ms,
                            server_timestamp_ms: now_ms,
                        };
                        if let Ok(written) = pong.serialize(&mut pong_buf) {
                            match event.client {
                                ClientChannel::Udp(addr, sock) => {
                                    let _ = sock.send_to(&pong_buf[..written], addr).await;
                                }
                                ClientChannel::Tcp(tcp_tx) => {
                                    let _ = tcp_tx.send(pong_buf[..written].to_vec()).await;
                                }
                            }
                        }
                    }
                    Packet::Disconnect => {
                        info!("Client disconnected. Neutralizing controller.");
                        let _ = gamepad.reset_neutral();
                        has_client = false;
                        last_seen_seq = 0;
                    }
                    _ => {}
                }
            }
        }
    }

    info!("Shutting down Condroid server.");
    let _ = gamepad.reset_neutral();
    Ok(())
}
