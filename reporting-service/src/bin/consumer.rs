//! `bankos-consumer` — Background Kafka consumer daemon.
//!
//! Subscribes to `bankos.transaction.events` and `bankos.account.events`,
//! persisting all transactions to the local SQLite database.
//!
//! # Usage
//!
//! ```bash
//! bankos-consumer \
//!   --brokers localhost:9092 \
//!   --db-path ./bankos-reports.db
//!
//! # Graceful shutdown: Ctrl+C or SIGTERM
//! ```
//!
//! Run this as a long-running background process (systemd service, Docker container).
//! Then use `bankos-report` CLI to query the accumulated data.

use anyhow::Result;
use clap::Parser;
use reporting::infrastructure::kafka::start_consumer;
use reporting::infrastructure::persistence::{SqliteDb, SqliteTransactionRepository};
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::{info, warn};
use tracing_subscriber::EnvFilter;

#[derive(Parser)]
#[command(
    name = "bankos-consumer",
    about = "BankOS Kafka consumer — populates local report database",
    long_about = "
Subscribes to Kafka topics and persists events to a local SQLite database.
Run this continuously alongside the BankOS platform to maintain a local
transaction history for reporting.

Use bankos-report to query the accumulated data.
"
)]
struct Cli {
    #[arg(long, env = "KAFKA_BROKERS", default_value = "localhost:9092")]
    brokers: String,

    #[arg(long, env = "BANKOS_DB_PATH", default_value = "./bankos-reports.db")]
    db_path: String,

    #[arg(long, env = "KAFKA_GROUP_ID", default_value = "reporting-service")]
    group_id: String,

    #[arg(long, env = "KAFKA_TOPIC", default_value = "bankos.transaction.events")]
    topic: String,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive("info".parse()?))
        .with_target(false)
        .compact()
        .init();

    let cli = Cli::parse();

    info!(
        "Starting BankOS consumer: brokers={} topic={} db={}",
        cli.brokers, cli.topic, cli.db_path
    );

    let db = Arc::new(SqliteDb::open(&cli.db_path)?);
    let repo = Arc::new(SqliteTransactionRepository::new(db.clone()));

    let (tx, mut rx) = mpsc::channel(1000);
    let shutdown = tokio_util::sync::CancellationToken::new();
    let shutdown_clone = shutdown.clone();

    // Graceful shutdown on Ctrl+C / SIGTERM
    tokio::spawn(async move {
        tokio::signal::ctrl_c().await.ok();
        info!("Shutdown signal received");
        shutdown_clone.cancel();
    });

    // Background task: write received transactions to SQLite
    let repo_clone = repo.clone();
    let writer = tokio::spawn(async move {
        let mut count = 0u64;
        while let Some(transaction) = rx.recv().await {
            if let Err(e) = repo_clone.insert(&transaction) {
                warn!("Failed to persist transaction {}: {}", transaction.id, e);
            } else {
                count += 1;
                if count.is_multiple_of(100) {
                    info!("Persisted {} transactions", count);
                }
            }
        }
        info!("Writer task complete — total persisted: {}", count);
    });

    // Main Kafka consumer (blocks until shutdown)
    start_consumer(&cli.brokers, &cli.topic, &cli.group_id, tx, shutdown).await?;

    writer.await?;
    info!("Consumer shutdown complete");
    Ok(())
}
