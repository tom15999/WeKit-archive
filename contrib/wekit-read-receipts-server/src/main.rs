use axum::{
    Json, Router,
    extract::{ConnectInfo, Path, Query, State},
    http::{StatusCode, header},
    response::{IntoResponse, Response},
    routing::get,
};
use base64::{Engine as _, engine::general_purpose};
use chrono::Utc;
use libsql::{Builder, Connection};
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, net::SocketAddr, sync::Arc};
use std::sync::{Mutex, OnceLock};
use std::io::Write;
use rustyline::completion::{Completer, Pair};
use rustyline::highlight::Highlighter;
use rustyline::hint::Hinter;
use rustyline::validate::Validator;
use rustyline::{Helper, ExternalPrinter};
use std::borrow::Cow;
use tracing::{error, info, warn};

// 1x1 transparent PNG file bytes to serve as the tracking pixel
const TRACKING_PIXEL: &[u8] = &[
    0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
    0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
    0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
    0x42, 0x60, 0x82,
];

/// Query parameters for the tracking pixel endpoint.
/// Only `uuid` is required for logging; `msg` is optional base64-encoded text.
#[derive(Deserialize)]
struct TrackingPixelParams {
    uuid: Option<String>,
    msg: Option<String>,
}

impl TrackingPixelParams {
    /// Decodes the base64 `msg` field, truncating to 500 chars.
    /// Returns an empty string if absent, invalid base64, or not valid UTF-8.
    fn decoded_msg(&self) -> String {
        self.msg
            .as_deref()
            .and_then(|m| general_purpose::URL_SAFE_NO_PAD.decode(m.as_bytes()).ok())
            .and_then(|bytes| String::from_utf8(bytes).ok())
            .map(|s| s.chars().take(500).collect())
            .unwrap_or_default()
    }
}

struct AppState {
    db: Connection,
}

#[derive(Serialize)]
struct HitRecord {
    uuid: String,
    ip: String,
    msg: String,
    timestamp: String,
}

struct LocalTimer;

impl tracing_subscriber::fmt::time::FormatTime for LocalTimer {
    fn format_time(&self, w: &mut tracing_subscriber::fmt::format::Writer<'_>) -> std::fmt::Result {
        let now = chrono::Local::now();
        write!(w, "{}", now.format("%y/%m/%d %H:%M:%S"))
    }
}

static PRINTER: OnceLock<Mutex<Option<Box<dyn ExternalPrinter + Send + Sync>>>> = OnceLock::new();

struct ReplWriter;

impl std::io::Write for ReplWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let msg = String::from_utf8_lossy(buf);
        write_log(&msg);
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        std::io::stdout().flush()
    }
}

fn write_log(msg: &str) {
    if let Some(mutex) = PRINTER.get() {
        if let Ok(mut opt) = mutex.lock() {
            if let Some(p) = opt.as_mut() {
                let _ = p.print(msg.to_string());
                return;
            }
        }
    }
    
    let mut stdout = std::io::stdout();
    let _ = write!(stdout, "{}", msg);
    let _ = stdout.flush();
}

struct ReplHelper;

impl Helper for ReplHelper {}

impl Completer for ReplHelper {
    type Candidate = Pair;

    fn complete(
        &self,
        line: &str,
        pos: usize,
        _ctx: &rustyline::Context<'_>,
    ) -> rustyline::Result<(usize, Vec<Pair>)> {
        let mut candidates = Vec::new();
        
        let (start, word) = get_word_at_pos(line, pos);
        
        if word.starts_with('/') {
            let commands = &[
                "/sql ", "/exit", "/help", "/status", 
                "/url ", "/tail ", "/query ", "/clear", "/open"
            ];
            for cmd in commands {
                if cmd.starts_with(word) {
                    candidates.push(Pair {
                        display: cmd.trim().to_string(),
                        replacement: cmd.to_string(),
                    });
                }
            }
        } else if line.trim_start().starts_with("/sql") {
            let sql_keywords = &[
                "SELECT", "INSERT", "UPDATE", "DELETE", "FROM", "WHERE", 
                "LIMIT", "ORDER BY", "DESC", "INTO", "VALUES", "CREATE TABLE", 
                "IF NOT EXISTS", "AND", "OR", "hits", "uuid", "ip", "msg", "timestamp"
            ];
            
            let word_lower = word.to_lowercase();
            for &keyword in sql_keywords {
                if keyword.to_lowercase().starts_with(&word_lower) {
                    candidates.push(Pair {
                        display: keyword.to_string(),
                        replacement: keyword.to_string(),
                    });
                }
            }
        }
        
        Ok((start, candidates))
    }
}

fn get_word_at_pos(line: &str, pos: usize) -> (usize, &str) {
    let slice = &line[..pos];
    let start = slice
        .rfind(|c: char| !c.is_alphanumeric() && c != '_' && c != '/' && c != '-')
        .map(|idx| idx + 1)
        .unwrap_or(0);
    (start, &slice[start..])
}

impl Hinter for ReplHelper {
    type Hint = String;
}

impl Highlighter for ReplHelper {
    fn highlight<'l>(&self, line: &'l str, _pos: usize) -> Cow<'l, str> {
        let mut highlighted = line.to_string();
        
        if highlighted.starts_with("/exit") {
            highlighted = highlighted.replace("/exit", "\x1b[1;31m/exit\x1b[0m");
        } else if highlighted.starts_with("/clear") {
            highlighted = highlighted.replace("/clear", "\x1b[1;31m/clear\x1b[0m");
        } else {
            let other_cmds = &["/help", "/status", "/open", "/sql", "/url", "/tail", "/query"];
            for cmd in other_cmds {
                if highlighted.starts_with(cmd) {
                    highlighted = highlighted.replacen(cmd, &format!("\x1b[1;32m{}\x1b[0m", cmd), 1);
                    break;
                }
            }
        }
        
        if line.starts_with("/sql") && highlighted.len() > "\x1b[1;32m/sql\x1b[0m".len() {
            let prefix_len = "\x1b[1;32m/sql\x1b[0m".len();
            let (prefix, sql_part) = highlighted.split_at(prefix_len);
            let colored_sql = highlight_sql(sql_part);
            highlighted = format!("{}{}", prefix, colored_sql);
        }
        
        Cow::Owned(highlighted)
    }
}

impl Validator for ReplHelper {}

fn highlight_sql(sql: &str) -> String {
    let mut result = String::new();
    let mut current_word = String::new();
    let mut in_string = false;
    
    for c in sql.chars() {
        if c == '\'' {
            if !current_word.is_empty() {
                result.push_str(&color_word(&current_word));
                current_word.clear();
            }
            in_string = !in_string;
            if in_string {
                result.push_str("\x1b[33m'");
            } else {
                result.push_str("'\x1b[0m");
            }
            continue;
        }
        
        if in_string {
            result.push(c);
            continue;
        }
        
        if c.is_alphanumeric() || c == '_' || c == '-' {
            current_word.push(c);
        } else {
            if !current_word.is_empty() {
                result.push_str(&color_word(&current_word));
                current_word.clear();
            }
            result.push(c);
        }
    }
    
    if !current_word.is_empty() {
        result.push_str(&color_word(&current_word));
    }
    
    result
}

fn color_word(word: &str) -> String {
    let word_upper = word.to_uppercase();
    match word_upper.as_str() {
        "SELECT" | "INSERT" | "UPDATE" | "DELETE" | "FROM" | "WHERE" | 
        "LIMIT" | "ORDER" | "BY" | "DESC" | "INTO" | "VALUES" | "CREATE" | 
        "TABLE" | "IF" | "NOT" | "EXISTS" | "AND" | "OR" | "JOIN" | "ON" => {
            format!("\x1b[1;36m{}\x1b[0m", word) // Bold Cyan
        }
        "hits" | "HITS" => {
            format!("\x1b[1;35m{}\x1b[0m", word) // Bold Magenta
        }
        "UUID" | "IP" | "MSG" | "TIMESTAMP" | "uuid" | "ip" | "msg" | "timestamp" => {
            format!("\x1b[1;34m{}\x1b[0m", word) // Bold Blue
        }
        _ => word.to_string(),
    }
}

async fn handle_sql_command(conn: &libsql::Connection, sql: &str) -> Result<(), Box<dyn std::error::Error>> {
    let is_query = {
        let sql_lower = sql.trim().to_lowercase();
        sql_lower.starts_with("select") 
            || sql_lower.starts_with("explain") 
            || sql_lower.starts_with("pragma")
            || sql_lower.starts_with("with")
    };

    if is_query {
        let mut rows = conn.query(sql, ()).await?;
        let col_count = rows.column_count();
        if col_count == 0 {
            println!("Query returned 0 columns.");
            return Ok(());
        }

        let mut col_names = Vec::new();
        for i in 0..col_count {
            col_names.push(rows.column_name(i).unwrap_or("").to_string());
        }

        let mut all_rows = Vec::new();
        while let Some(row) = rows.next().await? {
            let mut row_vals = Vec::new();
            for i in 0..col_count {
                let val = row.get_value(i)?;
                let formatted = match val {
                    libsql::Value::Null => "NULL".to_string(),
                    libsql::Value::Integer(n) => n.to_string(),
                    libsql::Value::Real(f) => f.to_string(),
                    libsql::Value::Text(s) => s.clone(),
                    libsql::Value::Blob(b) => format!("BLOB ({} bytes)", b.len()),
                };
                row_vals.push(formatted);
            }
            all_rows.push(row_vals);
        }

        if all_rows.is_empty() {
            println!("0 rows returned.");
            return Ok(());
        }

        let mut col_widths = vec![0; col_count as usize];
        for i in 0..col_count as usize {
            col_widths[i] = col_names[i].len();
        }
        for row in &all_rows {
            for i in 0..col_count as usize {
                if row[i].len() > col_widths[i] {
                    col_widths[i] = row[i].len();
                }
            }
        }

        let print_separator = |col_widths: &[usize]| {
            print!("+");
            for &w in col_widths {
                print!("{}+", "-".repeat(w + 2));
            }
            println!();
        };

        print_separator(&col_widths);

        print!("|");
        for i in 0..col_count as usize {
            print!(" {:<width$} |", col_names[i], width = col_widths[i]);
        }
        println!();

        print_separator(&col_widths);

        for row in &all_rows {
            print!("|");
            for i in 0..col_count as usize {
                print!(" {:<width$} |", row[i], width = col_widths[i]);
            }
            println!();
        }

        print_separator(&col_widths);
        println!("{} rows in set", all_rows.len());
    } else {
        let rows_affected = conn.execute(sql, ()).await?;
        println!("Query OK, {rows_affected} rows affected");
    }

    Ok(())
}

fn handle_help_command() {
    println!("\x1b[1;36mAvailable commands:\x1b[0m");
    println!("  \x1b[1;32m/help\x1b[0m                       Show this help message");
    println!("  \x1b[1;32m/status\x1b[0m                     Show server stats (hits, unique UUIDs, unique IPs, latest hit)");
    println!("  \x1b[1;32m/url <uuid> [msg]\x1b[0m           Generate tracking URL & HTML tag for a UUID and optional message");
    println!("  \x1b[1;32m/tail [count]\x1b[0m               Show the latest [count] (default 10) tracked hits in real-time");
    println!("  \x1b[1;32m/query <uuid>\x1b[0m               Show tracking history for a specific UUID in a table");
    println!("  \x1b[1;32m/clear\x1b[0m                      Clear all tracked read receipts from the database");
    println!("  \x1b[1;32m/open\x1b[0m                       Open the web dashboard in your default browser");
    println!("  \x1b[1;32m/sql <query>\x1b[0m                Execute arbitrary SQL queries on the database");
    println!("  \x1b[1;32m/exit\x1b[0m                       Shutdown the server and exit the REPL");
}

async fn handle_status_command(conn: &libsql::Connection) -> Result<(), Box<dyn std::error::Error>> {
    let mut total_rows = conn.query("SELECT COUNT(*) FROM hits", ()).await?;
    let total_hits = match total_rows.next().await? {
        Some(row) => match row.get_value(0)? {
            libsql::Value::Integer(n) => n,
            _ => 0,
        },
        None => 0,
    };

    let mut uuid_rows = conn.query("SELECT COUNT(DISTINCT uuid) FROM hits", ()).await?;
    let unique_uuids = match uuid_rows.next().await? {
        Some(row) => match row.get_value(0)? {
            libsql::Value::Integer(n) => n,
            _ => 0,
        },
        None => 0,
    };

    let mut ip_rows = conn.query("SELECT COUNT(DISTINCT ip) FROM hits", ()).await?;
    let unique_ips = match ip_rows.next().await? {
        Some(row) => match row.get_value(0)? {
            libsql::Value::Integer(n) => n,
            _ => 0,
        },
        None => 0,
    };

    let mut latest_rows = conn.query("SELECT timestamp FROM hits ORDER BY timestamp DESC LIMIT 1", ()).await?;
    let latest_hit = match latest_rows.next().await? {
        Some(row) => match row.get_value(0)? {
            libsql::Value::Text(s) => s.clone(),
            _ => "N/A".to_string(),
        },
        None => "N/A".to_string(),
    };

    println!("\x1b[1;36m--- Server Status ---\x1b[0m");
    println!("Server address: \x1b[1;32mhttp://localhost:8080\x1b[0m");
    println!("Total tracked hits: \x1b[1;33m{}\x1b[0m", total_hits);
    println!("Unique UUIDs:       \x1b[1;33m{}\x1b[0m", unique_uuids);
    println!("Unique IP visitors: \x1b[1;33m{}\x1b[0m", unique_ips);
    println!("Latest hit time:    \x1b[1;33m{}\x1b[0m", latest_hit);
    
    Ok(())
}

fn handle_url_command(args: &str) {
    let parts: Vec<&str> = args.split_whitespace().collect();
    if parts.is_empty() {
        println!("Usage: /url <uuid> [optional message]");
        return;
    }
    
    let uuid = parts[0];
    let mut url = format!("http://localhost:8080/pixel?uuid={}", uuid);
    
    if parts.len() > 1 {
        let msg = parts[1..].join(" ");
        let encoded_msg = general_purpose::URL_SAFE_NO_PAD.encode(msg.as_bytes());
        url = format!("{}&msg={}", url, encoded_msg);
    }
    
    println!("\x1b[1;36mGenerated Tracking Link Details:\x1b[0m");
    println!("URL:      \x1b[4;32m{}\x1b[0m", url);
    println!("HTML Tag: \x1b[33m<img src=\"{}\" width=\"1\" height=\"1\" style=\"display:none;\" />\x1b[0m", url);
    println!("Markdown: \x1b[35m![pixel]({})\x1b[0m", url);
}

async fn handle_tail_command(conn: &libsql::Connection, args: &str) -> Result<(), Box<dyn std::error::Error>> {
    let count: i64 = args.trim().parse().unwrap_or(10);
    
    let mut rows = conn.query(
        "SELECT timestamp, ip, uuid, msg FROM hits ORDER BY timestamp DESC LIMIT ?1",
        libsql::params![count]
    ).await?;
    
    println!("\x1b[1;36m--- Latest {} Hits ---\x1b[0m", count);
    let mut found = 0;
    while let Some(row) = rows.next().await? {
        let timestamp = match row.get_value(0)? {
            libsql::Value::Text(s) => s.clone(),
            _ => "".to_string(),
        };
        let ip = match row.get_value(1)? {
            libsql::Value::Text(s) => s.clone(),
            _ => "".to_string(),
        };
        let uuid = match row.get_value(2)? {
            libsql::Value::Text(s) => s.clone(),
            _ => "".to_string(),
        };
        let msg = match row.get_value(3)? {
            libsql::Value::Text(s) => s.clone(),
            _ => "".to_string(),
        };
        
        println!(
            "\x1b[34m[{}]\x1b[0m ip: \x1b[32m{:<15}\x1b[0m | uuid: \x1b[35m{}\x1b[0m | msg: \x1b[33m{}\x1b[0m",
            timestamp, ip, uuid, msg
        );
        found += 1;
    }
    
    if found == 0 {
        println!("No hits recorded in the database.");
    }
    
    Ok(())
}

async fn handle_query_command(conn: &libsql::Connection, uuid: &str) -> Result<(), Box<dyn std::error::Error>> {
    if uuid.trim().is_empty() {
        println!("Usage: /query <uuid>");
        return Ok(());
    }
    let sql = format!("SELECT timestamp, ip, msg FROM hits WHERE uuid = '{}' ORDER BY timestamp DESC", uuid.replace('\'', "''"));
    handle_sql_command(conn, &sql).await
}

async fn handle_clear_command(conn: &libsql::Connection) -> Result<(), Box<dyn std::error::Error>> {
    print!("Are you sure you want to clear all records? (y/N): ");
    let _ = std::io::stdout().flush();
    
    let mut response = String::new();
    if std::io::stdin().read_line(&mut response).is_ok() {
        let trimmed = response.trim().to_lowercase();
        if trimmed == "y" || trimmed == "yes" {
            let rows_affected = conn.execute("DELETE FROM hits", ()).await?;
            println!("Database wiped successfully! Wiped \x1b[1;31m{}\x1b[0m records.", rows_affected);
        } else {
            println!("Clear cancelled.");
        }
    }
    Ok(())
}

fn handle_open_command() {
    println!("Opening http://localhost:8080/ in default browser...");
    #[cfg(target_os = "linux")]
    let _ = std::process::Command::new("xdg-open").arg("http://localhost:8080/").spawn();
    #[cfg(target_os = "macos")]
    let _ = std::process::Command::new("open").arg("http://localhost:8080/").spawn();
    #[cfg(target_os = "windows")]
    let _ = std::process::Command::new("cmd").args(["/C", "start", "http://localhost:8080/"]).spawn();
}

async fn route_command(trimmed: &str, repl_conn: &libsql::Connection) -> Result<bool, Box<dyn std::error::Error>> {
    if trimmed == "/exit" {
        return Ok(true);
    } else if trimmed == "/help" {
        handle_help_command();
    } else if trimmed == "/status" {
        if let Err(e) = handle_status_command(repl_conn).await {
            println!("Error showing status: {e}");
        }
    } else if trimmed == "/clear" {
        if let Err(e) = handle_clear_command(repl_conn).await {
            println!("Error clearing database: {e}");
        }
    } else if trimmed == "/open" {
        handle_open_command();
    } else if trimmed.starts_with("/sql ") {
        let sql = trimmed["/sql ".len()..].trim();
        if let Err(e) = handle_sql_command(repl_conn, sql).await {
            println!("Error executing SQL: {e}");
        }
    } else if trimmed.starts_with("/url ") {
        let args = trimmed["/url ".len()..].trim();
        handle_url_command(args);
    } else if trimmed.starts_with("/tail") {
        let args = if trimmed.len() > "/tail".len() {
            trimmed["/tail".len()..].trim()
        } else {
            ""
        };
        if let Err(e) = handle_tail_command(repl_conn, args).await {
            println!("Error tailing hits: {e}");
        }
    } else if trimmed.starts_with("/query ") {
        let uuid = trimmed["/query ".len()..].trim();
        if let Err(e) = handle_query_command(repl_conn, uuid).await {
            println!("Error querying UUID: {e}");
        }
    } else {
        println!("Unknown command. Type /help to list available commands.");
    }
    Ok(false)
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    use std::io::IsTerminal;
    let is_terminal = std::io::stdin().is_terminal();

    let rl = if is_terminal {
        match rustyline::Editor::<ReplHelper, rustyline::history::FileHistory>::new() {
            Ok(mut r) => {
                r.set_helper(Some(ReplHelper));
                if let Ok(printer) = r.create_external_printer() {
                    let _ = PRINTER.set(Mutex::new(Some(Box::new(printer))));
                }
                Some(r)
            }
            Err(_) => None,
        }
    } else {
        None
    };

    tracing_subscriber::fmt()
        .with_writer(|| ReplWriter)
        .with_timer(LocalTimer)
        .with_target(false)
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "debug".into())
                .add_directive("rustyline=warn".parse().unwrap()),
        )
        .init();

    let db_url =
        std::env::var("TURSO_DATABASE_URL").unwrap_or_else(|_| "file:read_receipts.db".to_string());
    let auth_token = std::env::var("TURSO_AUTH_TOKEN").unwrap_or_default();

    let db = if db_url.starts_with("file:") {
        Builder::new_local(db_url.replace("file:", ""))
            .build()
            .await?
    } else {
        Builder::new_remote(db_url, auth_token).build().await?
    };

    let conn = db.connect()?;
    let repl_conn = db.connect()?;

    // Create hits table — logs each pixel request by UUID
    conn.execute(
        "CREATE TABLE IF NOT EXISTS hits (
            uuid TEXT NOT NULL,
            ip TEXT NOT NULL,
            msg TEXT NOT NULL DEFAULT '',
            timestamp TEXT NOT NULL
        );",
        (),
    )
    .await?;

    let app = Router::new()
        .route("/", get(serve_index))
        .route("/pixel", get(serve_tracking_pixel))
        .route("/receipts", get(list_all_receipts).delete(delete_all_receipts))
        .route("/receipts/{uuid}", get(list_receipts_for_uuid).delete(delete_receipts_for_uuid))
        .with_state(Arc::new(AppState { db: conn }));

    let addr = SocketAddr::from(([0, 0, 0, 0], 8080));
    info!("server launching on http://{addr}");

    let listener = tokio::net::TcpListener::bind(addr).await?;
    let (shutdown_tx, shutdown_rx) = tokio::sync::oneshot::channel::<()>();

    let server_handle = tokio::spawn(async move {
        if let Err(e) = axum::serve(
            listener,
            app.into_make_service_with_connect_info::<SocketAddr>(),
        )
        .with_graceful_shutdown(async move {
            let _ = shutdown_rx.await;
            info!("received shutdown signal, shutting down axum gracefully...");
        })
        .await
        {
            error!("server error: {e}");
        }
    });

    let mut run_fallback = !is_terminal;

    if is_terminal {
        if let Some(mut rl) = rl {
            loop {
                let readline = rl.readline(">> ");
                match readline {
                    Ok(line) => {
                        let trimmed = line.trim();
                        if trimmed.is_empty() {
                            continue;
                        }
                        
                        let _ = rl.add_history_entry(line.as_str());

                        if route_command(trimmed, &repl_conn).await? {
                            break;
                        }
                    }
                    Err(rustyline::error::ReadlineError::Interrupted) => {
                        break;
                    }
                    Err(rustyline::error::ReadlineError::Eof) => {
                        break;
                    }
                    Err(rustyline::error::ReadlineError::Io(ref e)) if e.raw_os_error() == Some(25) => {
                        run_fallback = true;
                        break;
                    }
                    Err(err) => {
                        println!("Error: {:?}", err);
                        break;
                    }
                }
            }
        } else {
            run_fallback = true;
        }
    }

    if run_fallback {
        // Fallback simple REPL loop
        let mut input = String::new();
        loop {
            print!(">> ");
            let _ = std::io::stdout().flush();
            input.clear();
            if std::io::stdin().read_line(&mut input)? == 0 {
                break;
            }
            let trimmed = input.trim();
            if trimmed.is_empty() {
                continue;
            }
            if route_command(trimmed, &repl_conn).await? {
                break;
            }
        }
    }

    info!("exiting REPL, stopping server...");
    let _ = shutdown_tx.send(());
    let _ = server_handle.await;

    Ok(())
}

/// Serves the static index HTML page.
async fn serve_index() -> impl IntoResponse {
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/html; charset=utf-8")
        .body(axum::body::Body::from(include_str!("../index.html")))
        .unwrap()
}

/// Serves the 1x1 transparent PNG and logs the requester's IP + timestamp against the UUID.
/// Also dumps all query parameters and request headers at INFO level for debugging.
async fn serve_tracking_pixel(
    State(state): State<Arc<AppState>>,
    Query(params): Query<TrackingPixelParams>,
    ConnectInfo(remote_addr): ConnectInfo<SocketAddr>,
) -> impl IntoResponse {
    let client_ip = remote_addr.ip().to_string();
    let now = Utc::now().format("%Y-%m-%d %H:%M:%S").to_string();

    if let Some(uuid) = &params.uuid {
        let msg = params.decoded_msg();

        info!("/pixel request\nuuid = {uuid}, msg = {msg}, client_ip = {client_ip}");

        if let Err(e) = state
            .db
            .execute(
                "INSERT INTO hits (uuid, ip, msg, timestamp) VALUES (?1, ?2, ?3, ?4)",
                (uuid.as_str(), client_ip, msg.as_str(), now),
            )
            .await
        {
            error!("failed to log hit: {e}");
        }
    } else {
        warn!("/pixel request without 'uuid' query parameter — hit not logged");
    }

    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "image/png")
        .header(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate")
        .header(header::PRAGMA, "no-cache")
        .body(axum::body::Body::from(TRACKING_PIXEL))
        .unwrap()
}

/// Returns every recorded read receipt, newest first.
/// Supports optional `?q=` query parameter to filter by message text content.
async fn list_all_receipts(
    State(state): State<Arc<AppState>>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<Json<Vec<HitRecord>>, (StatusCode, String)> {
    let q = params.get("q").map(|s| s.as_str()).unwrap_or("");

    let mut rows = if q.is_empty() {
        state
            .db
            .query(
                "SELECT uuid, ip, msg, timestamp FROM hits ORDER BY timestamp DESC",
                (),
            )
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    format!("query failed: {e}"),
                )
            })?
    } else {
        state
            .db
            .query(
                "SELECT uuid, ip, msg, timestamp FROM hits WHERE msg LIKE ?1 ORDER BY timestamp DESC",
                libsql::params![format!("%{}%", q)],
            )
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    format!("query failed: {e}"),
                )
            })?
    };

    let mut receipts = Vec::new();
    while let Some(row) = rows.next().await.map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("row read failed: {e}"),
        )
    })? {
        receipts.push(HitRecord {
            uuid: row.get_str(0).unwrap_or_default().to_string(),
            ip: row.get_str(1).unwrap_or_default().to_string(),
            msg: row.get_str(2).unwrap_or_default().to_string(),
            timestamp: row.get_str(3).unwrap_or_default().to_string(),
        });
    }

    Ok(Json(receipts))
}

/// Returns all read receipts for a specific UUID, newest first.
/// Supports optional `?q=` query parameter to filter by message text content.
async fn list_receipts_for_uuid(
    State(state): State<Arc<AppState>>,
    Path(uuid): Path<String>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<Json<Vec<HitRecord>>, (StatusCode, String)> {
    let q = params.get("q").map(|s| s.as_str()).unwrap_or("");

    let mut rows = if q.is_empty() {
        state
            .db
            .query(
                "SELECT uuid, ip, msg, timestamp FROM hits WHERE uuid = ?1 ORDER BY timestamp DESC",
                libsql::params![uuid],
            )
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    format!("query failed: {e}"),
                )
            })?
    } else {
        state
            .db
            .query(
                "SELECT uuid, ip, msg, timestamp FROM hits WHERE uuid = ?1 AND msg LIKE ?2 ORDER BY timestamp DESC",
                libsql::params![uuid, format!("%{}%", q)],
            )
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    format!("query failed: {e}"),
                )
            })?
    };

    let mut receipts = Vec::new();
    while let Some(row) = rows.next().await.map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("row read failed: {e}"),
        )
    })? {
        receipts.push(HitRecord {
            uuid: row.get_str(0).unwrap_or_default().to_string(),
            ip: row.get_str(1).unwrap_or_default().to_string(),
            msg: row.get_str(2).unwrap_or_default().to_string(),
            timestamp: row.get_str(3).unwrap_or_default().to_string(),
        });
    }

    Ok(Json(receipts))
}

/// Deletes ALL read receipts from the database.
async fn delete_all_receipts(
    State(state): State<Arc<AppState>>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    state
        .db
        .execute("DELETE FROM hits", ())
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("delete failed: {e}"),
            )
        })?;

    Ok(Json(serde_json::json!({"status": "ok"})))
}

/// Deletes all read receipts for a specific UUID.
async fn delete_receipts_for_uuid(
    State(state): State<Arc<AppState>>,
    Path(uuid): Path<String>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    state
        .db
        .execute("DELETE FROM hits WHERE uuid = ?1", libsql::params![uuid])
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("delete failed: {e}"),
            )
        })?;

    Ok(Json(serde_json::json!({"status": "ok"})))
}
