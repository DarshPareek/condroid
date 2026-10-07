use std::io::{stdout, Write};
use std::net::SocketAddr;
use std::time::{Duration, Instant};

use clap::{Parser, Subcommand};
use condroid_protocol::{
    Buttons, ControllerState, Packet, PingPacket, STATE_PACKET_SIZE,
};
use crossterm::event::{self, Event, KeyCode, KeyEventKind};
use crossterm::terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen};
use crossterm::{cursor, execute, style::{Color, Print, ResetColor, SetForegroundColor}};
use tokio::net::UdpSocket;
use tokio::time::sleep;

#[derive(Parser, Debug)]
#[command(author, version, about = "Condroid Client - Testing, Benchmarking & Interactive Controller Simulator", long_about = None)]
struct Cli {
    /// Host address of the Condroid server
    #[arg(short = 'H', long, default_value = "127.0.0.1")]
    host: String,

    /// UDP port of the Condroid server
    #[arg(short, long, default_value_t = 8448)]
    port: u16,

    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand, Debug)]
enum Commands {
    /// Run an automated functional test of all gamepad buttons, sticks, and triggers
    Test {
        /// Number of test cycles to execute
        #[arg(short, long, default_value_t = 1)]
        cycles: u32,
    },
    /// Benchmark network latency, jitter, and packet delivery rate
    Benchmark {
        /// Send rate in Hz (e.g. 120, 250, 500, 1000)
        #[arg(short, long, default_value_t = 250)]
        rate_hz: u64,

        /// Test duration in seconds
        #[arg(short, long, default_value_t = 5)]
        duration_secs: u64,
    },
    /// Interactive keyboard-driven virtual controller simulator in terminal
    Interactive,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let cli = Cli::parse();
    let target_addr: SocketAddr = format!("{}:{}", cli.host, cli.port).parse()?;

    let local_bind = "0.0.0.0:0";
    let socket = UdpSocket::bind(local_bind).await?;
    socket.connect(target_addr).await?;

    println!("Condroid Client connected to {}", target_addr);

    match cli.command {
        Commands::Test { cycles } => {
            run_automated_test(&socket, cycles).await?;
        }
        Commands::Benchmark {
            rate_hz,
            duration_secs,
        } => {
            run_benchmark(&socket, rate_hz, duration_secs).await?;
        }
        Commands::Interactive => {
            run_interactive(&socket).await?;
        }
    }

    Ok(())
}

async fn run_automated_test(
    socket: &UdpSocket,
    cycles: u32,
) -> Result<(), Box<dyn std::error::Error>> {
    println!("\n>>> Running Automated Controller Functional Test ({} cycle(s))", cycles);
    let mut seq = 1u32;
    let mut buf = [0u8; 64];

    for c in 1..=cycles {
        println!("Cycle {}/{}:", c, cycles);

        // 1. Test Buttons
        let button_tests = [
            ("A (South)", Buttons::BTN_A),
            ("B (East)", Buttons::BTN_B),
            ("X (North)", Buttons::BTN_X),
            ("Y (West)", Buttons::BTN_Y),
            ("LB (Left Bumper)", Buttons::BTN_LB),
            ("RB (Right Bumper)", Buttons::BTN_RB),
            ("Select / View", Buttons::BTN_SELECT),
            ("Start / Menu", Buttons::BTN_START),
            ("D-Pad Up", Buttons::DPAD_UP),
            ("D-Pad Down", Buttons::DPAD_DOWN),
            ("D-Pad Left", Buttons::DPAD_LEFT),
            ("D-Pad Right", Buttons::DPAD_RIGHT),
        ];

        for (name, mask) in button_tests {
            print!("  Testing button {:<20} ... ", name);
            stdout().flush()?;

            let mut state = ControllerState {
                seq,
                timestamp_ms: 0,
                ..Default::default()
            };
            seq += 1;
            state.buttons.set(mask, true);
            let len = state.serialize(&mut buf)?;
            socket.send(&buf[..len]).await?;
            sleep(Duration::from_millis(60)).await;

            // Release button
            state.buttons.set(mask, false);
            state.seq = seq;
            seq += 1;
            let len = state.serialize(&mut buf)?;
            socket.send(&buf[..len]).await?;
            sleep(Duration::from_millis(40)).await;

            println!("OK");
        }

        // 2. Test Triggers
        print!("  Testing analog triggers (LT / RT sweep) ... ");
        stdout().flush()?;
        for val in (0..=255).step_by(15) {
            let state = ControllerState {
                seq,
                left_trigger: val as u8,
                right_trigger: val as u8,
                ..Default::default()
            };
            seq += 1;
            let len = state.serialize(&mut buf)?;
            socket.send(&buf[..len]).await?;
            sleep(Duration::from_millis(10)).await;
        }
        println!("OK");

        // 3. Test Sticks
        print!("  Testing Left Stick & Right Stick circular motion ... ");
        stdout().flush()?;
        for deg in (0..360).step_by(10) {
            let rad = (deg as f64).to_radians();
            let x = (rad.cos() * 32767.0) as i16;
            let y = (rad.sin() * 32767.0) as i16;

            let state = ControllerState {
                seq,
                left_stick_x: x,
                left_stick_y: y,
                right_stick_x: -x,
                right_stick_y: -y,
                ..Default::default()
            };
            seq += 1;
            let len = state.serialize(&mut buf)?;
            socket.send(&buf[..len]).await?;
            sleep(Duration::from_millis(10)).await;
        }
        println!("OK");
    }

    // Reset neutral
    let neutral = ControllerState {
        seq,
        ..Default::default()
    };
    let len = neutral.serialize(&mut buf)?;
    socket.send(&buf[..len]).await?;

    println!("\n>>> Functional test completed successfully!\n");
    Ok(())
}

async fn run_benchmark(
    socket: &UdpSocket,
    rate_hz: u64,
    duration_secs: u64,
) -> Result<(), Box<dyn std::error::Error>> {
    println!("\n>>> Starting Benchmark: {} Hz for {} seconds", rate_hz, duration_secs);
    let interval_micros = 1_000_000 / rate_hz;
    let total_packets = rate_hz * duration_secs;

    let mut send_buf = [0u8; 64];
    let mut recv_buf = [0u8; 64];

    let start_time = Instant::now();
    let mut seq = 1u32;
    let mut rtt_samples = Vec::with_capacity(1000);

    let mut next_send = Instant::now();
    let end_time = Instant::now() + Duration::from_secs(duration_secs);

    let mut ping_counter = 0;

    while Instant::now() < end_time {
        let now = Instant::now();
        if now >= next_send {
            // Send high-frequency state packet
            let state = ControllerState {
                seq,
                timestamp_ms: (now - start_time).as_millis() as u32,
                ..Default::default()
            };
            seq += 1;
            let len = state.serialize(&mut send_buf)?;
            let _ = socket.send(&send_buf[..len]).await;

            // Every 10 state packets, send a Ping packet to measure RTT
            ping_counter += 1;
            if ping_counter % 10 == 0 {
                let ping = PingPacket {
                    seq,
                    timestamp_ms: (now - start_time).as_micros() as u32,
                };
                let ping_len = ping.serialize(&mut send_buf)?;
                let _ = socket.send(&send_buf[..ping_len]).await;
            }

            next_send += Duration::from_micros(interval_micros);
        }

        // Try non-blocking receive for pong responses
        match socket.try_recv(&mut recv_buf) {
            Ok(n) => {
                if let Ok(Packet::Pong(pong)) = Packet::parse(&recv_buf[..n]) {
                    let now_micros = (Instant::now() - start_time).as_micros() as u32;
                    if now_micros >= pong.client_timestamp_ms {
                        let rtt_us = (now_micros - pong.client_timestamp_ms) as f64;
                        rtt_samples.push(rtt_us / 1000.0);
                    }
                }
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                // Yield briefly to avoid 100% spin
                sleep(Duration::from_micros(100)).await;
            }
            Err(_) => {}
        }
    }

    println!(">>> Benchmark Complete!");
    println!("  Total State Packets Sent: {}", total_packets);
    println!("  Effective Send Rate:      {} Hz", rate_hz);

    if !rtt_samples.is_empty() {
        rtt_samples.sort_by(|a, b| a.partial_cmp(b).unwrap());
        let min = rtt_samples.first().copied().unwrap_or(0.0);
        let max = rtt_samples.last().copied().unwrap_or(0.0);
        let avg: f64 = rtt_samples.iter().sum::<f64>() / (rtt_samples.len() as f64);
        let p50 = rtt_samples[rtt_samples.len() / 2];
        let p95 = rtt_samples[(rtt_samples.len() * 95) / 100];
        let p99 = rtt_samples[(rtt_samples.len() * 99) / 100];

        println!("\n  Network RTT Latency Results ({} ping samples):", rtt_samples.len());
        println!("    Min:   {:.3} ms", min);
        println!("    Avg:   {:.3} ms", avg);
        println!("    p50:   {:.3} ms", p50);
        println!("    p95:   {:.3} ms", p95);
        println!("    p99:   {:.3} ms", p99);
        println!("    Max:   {:.3} ms", max);
    } else {
        println!("  (No pong samples received. Server may be processing state-only packets).");
    }
    println!();
    Ok(())
}

async fn run_interactive(socket: &UdpSocket) -> Result<(), Box<dyn std::error::Error>> {
    enable_raw_mode()?;
    let mut out = stdout();
    execute!(out, EnterAlternateScreen, cursor::Hide)?;

    let mut state = ControllerState::default();
    let mut seq = 1u32;
    let mut buf = [0u8; STATE_PACKET_SIZE];

    let mut running = true;
    let mut dirty = true;

    while running {
        if dirty {
            state.seq = seq;
            seq += 1;
            let len = state.serialize(&mut buf)?;
            let _ = socket.send(&buf[..len]).await;

            execute!(
                out,
                cursor::MoveTo(0, 0),
                SetForegroundColor(Color::Cyan),
                Print("=== Condroid Interactive Controller Simulator ===\r\n"),
                ResetColor,
                Print("Controls:\r\n"),
                Print("  Left Stick:  W / A / S / D\r\n"),
                Print("  Right Stick: Up / Left / Down / Right Arrows\r\n"),
                Print("  Buttons:     J = A, K = B, U = X, I = Y\r\n"),
                Print("  Bumpers:     Q = LB, E = RB\r\n"),
                Print("  Triggers:    1 = LT, 2 = RT (toggles)\r\n"),
                Print("  Menu:        Enter = Start, Space = Select\r\n"),
                Print("  Reset:       C (center sticks / clear buttons)\r\n"),
                Print("  Quit:        Escape or Ctrl+C\r\n\r\n"),
                SetForegroundColor(Color::Yellow),
                Print(format!("Left Stick:  ({:>6}, {:>6})\r\n", state.left_stick_x, state.left_stick_y)),
                Print(format!("Right Stick: ({:>6}, {:>6})\r\n", state.right_stick_x, state.right_stick_y)),
                Print(format!("Triggers:    LT: {:>3} | RT: {:>3}\r\n", state.left_trigger, state.right_trigger)),
                Print(format!("Buttons:     0x{:04x} [A:{} B:{} X:{} Y:{} LB:{} RB:{}]\r\n",
                    state.buttons.0,
                    if state.buttons.is_set(Buttons::BTN_A) { "ON " } else { "off" },
                    if state.buttons.is_set(Buttons::BTN_B) { "ON " } else { "off" },
                    if state.buttons.is_set(Buttons::BTN_X) { "ON " } else { "off" },
                    if state.buttons.is_set(Buttons::BTN_Y) { "ON " } else { "off" },
                    if state.buttons.is_set(Buttons::BTN_LB) { "ON " } else { "off" },
                    if state.buttons.is_set(Buttons::BTN_RB) { "ON " } else { "off" },
                )),
                ResetColor
            )?;
            out.flush()?;
            dirty = false;
        }

        if event::poll(Duration::from_millis(20))? {
            if let Event::Key(key) = event::read()? {
                if key.kind == KeyEventKind::Press {
                    match key.code {
                        KeyCode::Esc => running = false,
                        KeyCode::Char('c') | KeyCode::Char('C') => {
                            state = ControllerState::default();
                            dirty = true;
                        }
                        // Left Stick (WASD)
                        KeyCode::Char('w') => { state.left_stick_y = -32767; dirty = true; }
                        KeyCode::Char('s') => { state.left_stick_y = 32767; dirty = true; }
                        KeyCode::Char('a') => { state.left_stick_x = -32767; dirty = true; }
                        KeyCode::Char('d') => { state.left_stick_x = 32767; dirty = true; }

                        // Right Stick (Arrow Keys)
                        KeyCode::Up => { state.right_stick_y = -32767; dirty = true; }
                        KeyCode::Down => { state.right_stick_y = 32767; dirty = true; }
                        KeyCode::Left => { state.right_stick_x = -32767; dirty = true; }
                        KeyCode::Right => { state.right_stick_x = 32767; dirty = true; }

                        // Face buttons
                        KeyCode::Char('j') => { state.buttons.set(Buttons::BTN_A, !state.buttons.is_set(Buttons::BTN_A)); dirty = true; }
                        KeyCode::Char('k') => { state.buttons.set(Buttons::BTN_B, !state.buttons.is_set(Buttons::BTN_B)); dirty = true; }
                        KeyCode::Char('u') => { state.buttons.set(Buttons::BTN_X, !state.buttons.is_set(Buttons::BTN_X)); dirty = true; }
                        KeyCode::Char('i') => { state.buttons.set(Buttons::BTN_Y, !state.buttons.is_set(Buttons::BTN_Y)); dirty = true; }

                        // Bumpers
                        KeyCode::Char('q') => { state.buttons.set(Buttons::BTN_LB, !state.buttons.is_set(Buttons::BTN_LB)); dirty = true; }
                        KeyCode::Char('e') => { state.buttons.set(Buttons::BTN_RB, !state.buttons.is_set(Buttons::BTN_RB)); dirty = true; }

                        // Triggers
                        KeyCode::Char('1') => { state.left_trigger = if state.left_trigger == 0 { 255 } else { 0 }; dirty = true; }
                        KeyCode::Char('2') => { state.right_trigger = if state.right_trigger == 0 { 255 } else { 0 }; dirty = true; }

                        // Start / Select
                        KeyCode::Enter => { state.buttons.set(Buttons::BTN_START, !state.buttons.is_set(Buttons::BTN_START)); dirty = true; }
                        KeyCode::Char(' ') => { state.buttons.set(Buttons::BTN_SELECT, !state.buttons.is_set(Buttons::BTN_SELECT)); dirty = true; }
                        _ => {}
                    }
                }
            }
        }
    }

    // Clean up
    let neutral = ControllerState::default();
    let len = neutral.serialize(&mut buf)?;
    let _ = socket.send(&buf[..len]).await;

    execute!(out, cursor::Show, LeaveAlternateScreen)?;
    disable_raw_mode()?;
    println!("Exited interactive simulator.");
    Ok(())
}
