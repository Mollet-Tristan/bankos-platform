//! Output ports — traits defining what the application needs from infrastructure.
//!
//! In Rust, ports are `trait`s. Infrastructure adapters implement these traits.
//! The application service depends only on the traits — never on concrete types.
//!
//! This enables:
//!  - Unit testing with mock implementations (no real Kafka/HTTP/SQLite)
//!  - Swapping infrastructure without touching domain or application code
//!
//! Compare with the Kotlin services where ports are `interface`s.
//! The pattern is identical — only the syntax differs.

use crate::domain::error::ReportingError;
use crate::domain::model::*;
use async_trait::async_trait;
use chrono::NaiveDate;

// `async_trait` is needed because Rust traits do not yet support
// async functions natively (stabilization in progress as of Rust 1.75).
// This is a known limitation — async_trait adds a small Box<Future> overhead.

/// Port for fetching transactions from any source.
///
/// Implementations:
///  - `KafkaTransactionRepository` — consumes from bankos.transaction.events
///  - `SqliteTransactionRepository` — reads from local SQLite cache
///  - `InMemoryTransactionRepository` — test double
#[async_trait]
pub trait TransactionRepository: Send + Sync {
    /// Fetch all completed transactions within a date range.
    async fn find_by_period(
        &self,
        start: NaiveDate,
        end: NaiveDate,
    ) -> Result<Vec<Transaction>, ReportingError>;

    /// Fetch transactions for a specific account.
    async fn find_by_account(
        &self,
        account_id: &str,
    ) -> Result<Vec<Transaction>, ReportingError>;

    /// Total number of stored transactions.
    async fn count(&self) -> Result<u64, ReportingError>;
}

/// Port for fetching account balance snapshots from Account Service.
///
/// Implementation: `HttpAccountClient` — calls /api/v1/accounts/{id}/balance
#[async_trait]
pub trait AccountClient: Send + Sync {
    async fn get_balance(&self, account_id: &str) -> Result<Money, ReportingError>;
    async fn list_account_ids(&self) -> Result<Vec<String>, ReportingError>;
}

/// Port for persisting generated reports.
///
/// Implementation: `SqliteReportStore`
#[async_trait]
pub trait ReportStore: Send + Sync {
    async fn save_period_summary(
        &self,
        summary: &PeriodSummary,
    ) -> Result<(), ReportingError>;

    async fn load_period_summary(
        &self,
        period: &Period,
    ) -> Result<Option<PeriodSummary>, ReportingError>;

    async fn list_summaries(&self) -> Result<Vec<PeriodSummary>, ReportingError>;
}
