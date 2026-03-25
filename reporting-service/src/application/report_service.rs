//! ReportService — Application service orchestrating report generation.
//!
//! Coordinates domain aggregation functions with infrastructure ports.
//! Contains no business logic — only orchestration.

use crate::application::ports::{AccountClient, ReportStore, TransactionRepository};
use crate::domain::aggregation::{
    compute_account_summaries, compute_daily_breakdown, compute_failure_trend,
    compute_period_summary, FailureTrend,
};
use crate::domain::error::ReportingError;
use crate::domain::model::*;
use chrono::NaiveDate;
use std::sync::Arc;
use tracing::{info, warn};

/// Application service for report generation.
///
/// All dependencies are injected as `Arc<dyn Trait>` — the standard
/// Rust pattern for shared, heap-allocated trait objects.
///
/// `Arc` (Atomic Reference Counting) is used instead of `Rc` because
/// the service is shared across async tasks (requires `Send + Sync`).
pub struct ReportService {
    transactions: Arc<dyn TransactionRepository>,
    account_client: Arc<dyn AccountClient>,
    report_store: Arc<dyn ReportStore>,
}

impl ReportService {
    pub fn new(
        transactions: Arc<dyn TransactionRepository>,
        account_client: Arc<dyn AccountClient>,
        report_store: Arc<dyn ReportStore>,
    ) -> Self {
        Self {
            transactions,
            account_client,
            report_store,
        }
    }

    /// Generate a full period report and persist it.
    pub async fn generate_period_report(
        &self,
        start: NaiveDate,
        end: NaiveDate,
    ) -> Result<PeriodSummary, ReportingError> {
        let period = Period::new(start, end).map_err(ReportingError::InvalidPeriod)?;

        info!("Generating period report for {}", period);

        // Check if we have a cached report for this exact period
        if let Some(cached) = self.report_store.load_period_summary(&period).await? {
            info!("Returning cached report for {}", period);
            return Ok(cached);
        }

        let transactions = self.transactions.find_by_period(start, end).await?;

        if transactions.is_empty() {
            warn!("No transactions found for period {}", period);
            return Err(ReportingError::NoData);
        }

        info!("Aggregating {} transactions", transactions.len());
        let summary = compute_period_summary(&transactions, &period);

        // Persist for future CLI invocations (avoid re-fetching Kafka)
        self.report_store.save_period_summary(&summary).await?;

        Ok(summary)
    }

    /// Generate a daily breakdown within a period.
    pub async fn generate_daily_breakdown(
        &self,
        start: NaiveDate,
        end: NaiveDate,
    ) -> Result<Vec<DailySummary>, ReportingError> {
        let period = Period::new(start, end).map_err(ReportingError::InvalidPeriod)?;

        let transactions = self.transactions.find_by_period(start, end).await?;

        if transactions.is_empty() {
            return Err(ReportingError::NoData);
        }

        Ok(compute_daily_breakdown(&transactions, &period))
    }

    /// Generate per-account activity summaries.
    pub async fn generate_account_report(
        &self,
        start: NaiveDate,
        end: NaiveDate,
        top_n: usize,
    ) -> Result<Vec<AccountSummary>, ReportingError> {
        let transactions = self.transactions.find_by_period(start, end).await?;

        if transactions.is_empty() {
            return Err(ReportingError::NoData);
        }

        let mut summaries = compute_account_summaries(&transactions);
        summaries.truncate(top_n);
        Ok(summaries)
    }

    /// Compare failure rates between two consecutive periods.
    pub async fn generate_failure_trend(
        &self,
        current_start: NaiveDate,
        current_end: NaiveDate,
        previous_start: NaiveDate,
        previous_end: NaiveDate,
    ) -> Result<FailureTrend, ReportingError> {
        let current_txs = self
            .transactions
            .find_by_period(current_start, current_end)
            .await?;
        let previous_txs = self
            .transactions
            .find_by_period(previous_start, previous_end)
            .await?;

        let current_period =
            Period::new(current_start, current_end).map_err(ReportingError::InvalidPeriod)?;
        let previous_period =
            Period::new(previous_start, previous_end).map_err(ReportingError::InvalidPeriod)?;

        let current = compute_period_summary(&current_txs, &current_period);
        let previous = compute_period_summary(&previous_txs, &previous_period);

        Ok(compute_failure_trend(&current, &previous))
    }

    /// Show current account balances (live call to Account Service).
    pub async fn get_account_balances(
        &self,
        account_ids: &[String],
    ) -> Result<Vec<(String, Money)>, ReportingError> {
        let mut balances = Vec::with_capacity(account_ids.len());
        for id in account_ids {
            match self.account_client.get_balance(id).await {
                Ok(balance) => balances.push((id.clone(), balance)),
                Err(e) => warn!("Failed to fetch balance for account {}: {}", id, e),
            }
        }
        Ok(balances)
    }

    /// Total number of transactions in the local store.
    pub async fn transaction_count(&self) -> Result<u64, ReportingError> {
        self.transactions.count().await
    }
}
