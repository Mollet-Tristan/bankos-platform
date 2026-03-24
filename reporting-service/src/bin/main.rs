//! `bankos-report` — CLI entry point.
//!
//! # Usage
//!
//! ```bash
//! # Period summary
//! bankos-report summary --start 2024-01-01 --end 2024-01-31
//!
//! # Daily breakdown
//! bankos-report daily --start 2024-01-01 --end 2024-01-07
//!
//! # Top accounts by volume
//! bankos-report accounts --start 2024-01-01 --end 2024-01-31 --top 10
//!
//! # Failure rate trend (current vs previous month)
//! bankos-report trend --current-start 2024-02-01 --current-end 2024-02-29 \
//!                     --prev-start 2024-01-01 --prev-end 2024-01-31
//!
//! # List saved reports
//! bankos-report list
//! ```

use anyhow::Result;
use clap::{Parser, Subcommand};
use colored::Colorize;
use comfy_table::{Table, Cell, Color, Attribute};
use reporting::application::report_service::ReportService;
use reporting::infrastructure::http::HttpAccountClient;
use reporting::infrastructure::persistence::{SqliteDb, SqliteReportStore, SqliteTransactionRepository};
use std::sync::Arc;
use tracing_subscriber::EnvFilter;

#[derive(Parser)]
#[command(
    name = "bankos-report",
    about = "BankOS financial reporting CLI",
    version = "0.1.0",
    author = "Tristan <zordym@github>",
    long_about = "
Generates financial analytics reports from the BankOS platform.
Data is sourced from the local SQLite cache populated by bankos-consumer.

Run `bankos-consumer` first to populate the local data store.
"
)]
struct Cli {
    /// Path to the SQLite database
    #[arg(long, env = "BANKOS_DB_PATH", default_value = "./bankos-reports.db")]
    db_path: String,

    /// Account Service URL (for live balance queries)
    #[arg(long, env = "ACCOUNT_SERVICE_URL", default_value = "http://localhost:8081")]
    account_service_url: String,

    /// Bearer token for authenticated Account Service calls
    #[arg(long, env = "BANKOS_TOKEN")]
    token: Option<String>,

    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Show aggregated statistics for a date period
    Summary {
        #[arg(long, help = "Start date (YYYY-MM-DD)")]
        start: String,
        #[arg(long, help = "End date (YYYY-MM-DD)")]
        end: String,
        #[arg(long, short, default_value = "false", help = "Output as JSON")]
        json: bool,
    },
    /// Show daily breakdown within a period
    Daily {
        #[arg(long)]
        start: String,
        #[arg(long)]
        end: String,
    },
    /// Show top accounts by transaction volume
    Accounts {
        #[arg(long)]
        start: String,
        #[arg(long)]
        end: String,
        #[arg(long, default_value = "10")]
        top: usize,
    },
    /// Compare failure rates between two periods
    Trend {
        #[arg(long)]
        current_start: String,
        #[arg(long)]
        current_end: String,
        #[arg(long)]
        prev_start: String,
        #[arg(long)]
        prev_end: String,
    },
    /// List all saved period reports
    List,
    /// Show local database statistics
    Stats,
}

#[tokio::main]
async fn main() -> Result<()> {
    // Initialise structured logging (RUST_LOG controls level)
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env())
        .with_target(false)
        .compact()
        .init();

    let cli = Cli::parse();

    // Wire up infrastructure
    let db = Arc::new(SqliteDb::open(&cli.db_path)?);
    let transaction_repo = Arc::new(SqliteTransactionRepository::new(db.clone()));
    let report_store = Arc::new(SqliteReportStore::new(db.clone()));
    let account_client = Arc::new(HttpAccountClient::new(
        cli.account_service_url.clone(),
        cli.token.clone(),
    ));

    let service = ReportService::new(transaction_repo, account_client, report_store);

    match cli.command {
        Commands::Summary { start, end, json } => {
            let start = parse_date(&start)?;
            let end = parse_date(&end)?;
            let summary = service.generate_period_report(start, end).await?;

            if json {
                println!("{}", serde_json::to_string_pretty(&summary)?);
            } else {
                render_period_summary(&summary);
            }
        }

        Commands::Daily { start, end } => {
            let start = parse_date(&start)?;
            let end = parse_date(&end)?;
            let days = service.generate_daily_breakdown(start, end).await?;
            render_daily_breakdown(&days);
        }

        Commands::Accounts { start, end, top } => {
            let start = parse_date(&start)?;
            let end = parse_date(&end)?;
            let accounts = service.generate_account_report(start, end, top).await?;
            render_account_summaries(&accounts);
        }

        Commands::Trend { current_start, current_end, prev_start, prev_end } => {
            let trend = service.generate_failure_trend(
                parse_date(&current_start)?,
                parse_date(&current_end)?,
                parse_date(&prev_start)?,
                parse_date(&prev_end)?,
            ).await?;

            let direction_str = match trend.direction {
                reporting::domain::aggregation::TrendDirection::Better => "↓ Better".green(),
                reporting::domain::aggregation::TrendDirection::Stable => "→ Stable".yellow(),
                reporting::domain::aggregation::TrendDirection::Worse  => "↑ Worse".red(),
            };

            println!("\n{}", "── Failure Rate Trend ──────────────────".bold());
            println!("Current period:  {:.2}%", trend.current_pct);
            println!("Previous period: {:.2}%", trend.previous_pct);
            println!("Delta:           {:+.2}%", trend.delta_pct);
            println!("Trend:           {}", direction_str);
        }

        Commands::List => {
            // List is accessed via the report store — not yet wired in this demo
            println!("{}", "Saved reports: (feature coming soon)".dimmed());
        }

        Commands::Stats => {
            let count = service.transaction_count().await?;
            println!("\n{}", "── Database Statistics ─────────────────".bold());
            println!("Transactions stored: {}", count.to_string().bold());
            println!("DB path:             {}", cli.db_path);
        }
    }

    Ok(())
}

// ── Rendering helpers ─────────────────────────────────────────────────────────

fn render_period_summary(s: &reporting::domain::model::PeriodSummary) {
    println!("\n{}", format!("── Period Report: {} ──", s.period).bold().cyan());

    let mut table = Table::new();
    table.set_header(vec![
        Cell::new("Metric").add_attribute(Attribute::Bold),
        Cell::new("Value").add_attribute(Attribute::Bold),
    ]);

    let failure_color = if s.failure_rate_pct > 5.0 { Color::Red } else { Color::Green };

    table.add_row(vec!["Completed transactions", &s.total_completed.to_string()]);
    table.add_row(vec!["Failed transactions",    &s.total_failed.to_string()]);
    table.add_row(vec!["Compensated",            &s.total_compensated.to_string()]);
    table.add_row(vec!["Total volume",           &s.total_volume.to_string()]);
    table.add_row(vec!["Withdrawals",            &s.total_withdrawals.to_string()]);
    table.add_row(vec!["Deposits",               &s.total_deposits.to_string()]);
    table.add_row(vec!["Transfers",              &s.total_transfers.to_string()]);
    table.add_row(vec!["Average transaction",    &s.average_transaction.to_string()]);
    table.add_row(vec![
        "Failure rate",
        &format!("{:.2}%", s.failure_rate_pct),
    ]);

    if let Some(peak) = &s.peak_day {
        table.add_row(vec!["Peak day", &format!("{} ({} txs)", peak, s.peak_day_count)]);
    }

    println!("{table}");
}

fn render_daily_breakdown(days: &[reporting::domain::model::DailySummary]) {
    println!("\n{}", "── Daily Breakdown ─────────────────────".bold().cyan());
    let mut table = Table::new();
    table.set_header(vec!["Date", "Completed", "Failed", "Volume"]);

    for day in days {
        table.add_row(vec![
            day.date.to_string(),
            day.completed.to_string(),
            day.failed.to_string(),
            day.volume.to_string(),
        ]);
    }
    println!("{table}");
}

fn render_account_summaries(accounts: &[reporting::domain::model::AccountSummary]) {
    println!("\n{}", "── Top Accounts by Activity ────────────".bold().cyan());
    let mut table = Table::new();
    table.set_header(vec!["Account ID", "Transactions", "Debited", "Credited", "Net Flow"]);

    for acc in accounts {
        let net_str = if acc.net_flow >= rust_decimal::Decimal::ZERO {
            format!("+{:.2}", acc.net_flow).green().to_string()
        } else {
            format!("{:.2}", acc.net_flow).red().to_string()
        };
        table.add_row(vec![
            acc.account_id[..8].to_string() + "...",
            acc.total_transactions.to_string(),
            acc.total_debited.to_string(),
            acc.total_credited.to_string(),
            net_str,
        ]);
    }
    println!("{table}");
}

fn parse_date(s: &str) -> Result<chrono::NaiveDate> {
    chrono::NaiveDate::parse_from_str(s, "%Y-%m-%d")
        .map_err(|e| anyhow::anyhow!("Invalid date '{}': {}", s, e))
}
