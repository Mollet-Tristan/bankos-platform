//! In-memory test doubles for ports.
//!
//! These are NOT production adapters. They exist to make
//! `ReportService` unit-testable without spinning up SQLite.
//!
//! Pattern: test double implementing the port trait.
//! The service only sees `Arc<dyn TransactionRepository>` —
//! it cannot tell whether it's talking to SQLite or this stub.

use crate::application::ports::{AccountClient, ReportStore, TransactionRepository};
use crate::domain::error::ReportingError;
use crate::domain::model::*;
use async_trait::async_trait;
use chrono::NaiveDate;
use std::sync::{Arc, Mutex};

/// In-memory TransactionRepository for unit tests.
///
/// Pre-populated with a fixed set of transactions via `new(transactions)`.
pub struct InMemoryTransactionRepository {
    transactions: Vec<Transaction>,
}

impl InMemoryTransactionRepository {
    pub fn new(transactions: Vec<Transaction>) -> Arc<Self> {
        Arc::new(Self { transactions })
    }

    pub fn empty() -> Arc<Self> {
        Self::new(vec![])
    }
}

#[async_trait]
impl TransactionRepository for InMemoryTransactionRepository {
    async fn find_by_period(
        &self,
        start: NaiveDate,
        end: NaiveDate,
    ) -> Result<Vec<Transaction>, ReportingError> {
        Ok(self
            .transactions
            .iter()
            .filter(|tx| {
                let date = tx.occurred_at.date_naive();
                date >= start && date <= end
            })
            .cloned()
            .collect())
    }

    async fn find_by_account(&self, account_id: &str) -> Result<Vec<Transaction>, ReportingError> {
        Ok(self
            .transactions
            .iter()
            .filter(|tx| tx.source_account_id == account_id)
            .cloned()
            .collect())
    }

    async fn count(&self) -> Result<u64, ReportingError> {
        Ok(self.transactions.len() as u64)
    }
}

/// In-memory AccountClient for unit tests.
///
/// Returns a fixed balance for any account ID.
pub struct StubAccountClient {
    fixed_balance: Money,
}

impl StubAccountClient {
    pub fn with_balance(amount: rust_decimal::Decimal, currency: Currency) -> Arc<Self> {
        Arc::new(Self {
            fixed_balance: Money::new(amount, currency),
        })
    }
}

#[async_trait]
impl AccountClient for StubAccountClient {
    async fn get_balance(&self, _account_id: &str) -> Result<Money, ReportingError> {
        Ok(self.fixed_balance.clone())
    }

    async fn list_account_ids(&self) -> Result<Vec<String>, ReportingError> {
        Ok(vec!["acc-stub-1".to_string(), "acc-stub-2".to_string()])
    }
}

/// In-memory ReportStore for unit tests.
pub struct InMemoryReportStore {
    summaries: Mutex<Vec<PeriodSummary>>,
}

impl InMemoryReportStore {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            summaries: Mutex::new(vec![]),
        })
    }
}

#[async_trait]
impl ReportStore for InMemoryReportStore {
    async fn save_period_summary(&self, summary: &PeriodSummary) -> Result<(), ReportingError> {
        self.summaries.lock().unwrap().push(summary.clone());
        Ok(())
    }

    async fn load_period_summary(
        &self,
        period: &Period,
    ) -> Result<Option<PeriodSummary>, ReportingError> {
        Ok(self
            .summaries
            .lock()
            .unwrap()
            .iter()
            .find(|s| s.period == *period)
            .cloned())
    }

    async fn list_summaries(&self) -> Result<Vec<PeriodSummary>, ReportingError> {
        Ok(self.summaries.lock().unwrap().clone())
    }
}
