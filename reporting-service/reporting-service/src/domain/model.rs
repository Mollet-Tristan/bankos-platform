//! Domain model — value types for the reporting domain.
//!
//! These types are designed for computation, not persistence.
//! Serde derives enable JSON deserialization from Kafka events.

use chrono::{DateTime, Utc, NaiveDate};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use std::fmt;
use uuid::Uuid;

// ── Re-export uuid (used throughout) ─────────────────────────────────────────
// (add to Cargo.toml: uuid = { version = "1", features = ["v4", "serde"] })

// ── Money — no floats for financial values ────────────────────────────────────

/// A monetary amount with its currency.
///
/// Uses `rust_decimal::Decimal` — never `f64` — to avoid floating-point
/// rounding errors in financial calculations.
///
/// # Example
/// ```
/// use rust_decimal_macros::dec;
/// let total = Money::new(dec!(100.00), Currency::Eur)
///     + Money::new(dec!(50.00), Currency::Eur);
/// assert_eq!(total.amount, dec!(150.00));
/// ```
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Money {
    #[serde(with = "rust_decimal::serde::str")]
    pub amount: Decimal,
    pub currency: Currency,
}

impl Money {
    pub fn new(amount: Decimal, currency: Currency) -> Self {
        Self { amount, currency }
    }

    pub fn zero(currency: Currency) -> Self {
        Self { amount: Decimal::ZERO, currency }
    }

    /// Add two Money values. Panics if currencies differ.
    /// Use `checked_add` for safe addition across currencies.
    pub fn add(&self, other: &Money) -> Self {
        assert_eq!(self.currency, other.currency, "Cannot add different currencies");
        Money::new(self.amount + other.amount, self.currency.clone())
    }
}

impl fmt::Display for Money {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:.2} {}", self.amount, self.currency)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum Currency {
    Eur,
    Usd,
    Gbp,
}

impl fmt::Display for Currency {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Currency::Eur => write!(f, "EUR"),
            Currency::Usd => write!(f, "USD"),
            Currency::Gbp => write!(f, "GBP"),
        }
    }
}

// ── Transaction ───────────────────────────────────────────────────────────────

/// A completed transaction as received from the Kafka topic.
///
/// Only `COMPLETED` transactions are processed by this service.
/// `FAILED` and `COMPENSATED` are tracked separately for reporting.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Transaction {
    pub id: String,
    pub source_account_id: String,
    pub target_account_id: Option<String>,
    pub amount: Money,
    pub transaction_type: TransactionType,
    pub status: TransactionStatus,
    pub occurred_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum TransactionType {
    Withdrawal,
    Deposit,
    Transfer,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum TransactionStatus {
    Completed,
    Failed,
    Compensated,
}

// ── Reporting period ──────────────────────────────────────────────────────────

/// A time window for report aggregation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Period {
    pub start: NaiveDate,
    pub end: NaiveDate,
}

impl Period {
    pub fn new(start: NaiveDate, end: NaiveDate) -> Result<Self, String> {
        if end < start {
            return Err(format!("Period end ({end}) must be >= start ({start})"));
        }
        Ok(Self { start, end })
    }

    pub fn contains(&self, dt: &DateTime<Utc>) -> bool {
        let date = dt.date_naive();
        date >= self.start && date <= self.end
    }

    pub fn days(&self) -> i64 {
        (self.end - self.start).num_days() + 1
    }
}

impl fmt::Display for Period {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} → {}", self.start, self.end)
    }
}

// ── Report output types ───────────────────────────────────────────────────────

/// Aggregated statistics for a given period.
///
/// This is the primary output of the domain aggregation functions.
/// The CLI renders this to a table; the Kafka consumer stores it in SQLite.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeriodSummary {
    pub period: Period,
    pub total_completed: u64,
    pub total_failed: u64,
    pub total_compensated: u64,
    pub total_volume: Money,
    pub total_withdrawals: Money,
    pub total_deposits: Money,
    pub total_transfers: Money,
    pub average_transaction: Money,
    pub failure_rate_pct: f64,
    pub peak_day: Option<NaiveDate>,
    pub peak_day_count: u64,
}

/// Per-day statistics within a period.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DailySummary {
    pub date: NaiveDate,
    pub completed: u64,
    pub failed: u64,
    pub volume: Money,
}

/// Account-level activity summary.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AccountSummary {
    pub account_id: String,
    pub total_transactions: u64,
    pub total_debited: Money,
    pub total_credited: Money,
    pub net_flow: Decimal,
    pub last_activity: Option<DateTime<Utc>>,
}

/// Progress snapshot for the portfolio CLI.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PortfolioProgress {
    pub generated_at: DateTime<Utc>,
    pub rust_features_demonstrated: Vec<String>,
    pub lines_of_rust: u64,
    pub test_count: u64,
}
