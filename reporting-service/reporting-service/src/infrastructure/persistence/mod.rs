//! SQLite persistence adapters and in-memory test doubles.

pub mod in_memory;
//!
//! Two adapters:
//!  - `SqliteTransactionRepository` — local cache of Kafka events
//!  - `SqliteReportStore` — persists generated report summaries
//!
//! # Why SQLite?
//!
//! ADR-005: The reporting service is a CLI + background consumer.
//! SQLite provides:
//!  - Zero-infrastructure persistence (single file, no server)
//!  - Full SQL for complex period queries
//!  - Portable (the binary + the .db file = full state)
//!
//! A production deployment would use PostgreSQL (read replica of
//! transaction-service DB) or a dedicated time-series store (TimescaleDB).
//! SQLite is appropriate for a self-contained CLI portfolio demo.

use crate::application::ports::{ReportStore, TransactionRepository};
use crate::domain::error::{PersistenceError, ReportingError};
use crate::domain::model::*;
use async_trait::async_trait;
use chrono::NaiveDate;
use rusqlite::{Connection, params};
use serde_json;
use std::sync::Mutex;

/// Thread-safe SQLite connection wrapper.
///
/// `rusqlite::Connection` is not `Send`, so we wrap it in a `Mutex`.
/// For a high-throughput scenario, use `r2d2_sqlite` for connection pooling.
pub struct SqliteDb {
    conn: Mutex<Connection>,
}

impl SqliteDb {
    pub fn open(path: &str) -> Result<Self, ReportingError> {
        let conn = Connection::open(path)
            .map_err(|e| ReportingError::Persistence(PersistenceError::SqliteError(e.to_string())))?;

        let db = Self { conn: Mutex::new(conn) };
        db.run_migrations()?;
        Ok(db)
    }

    pub fn in_memory() -> Result<Self, ReportingError> {
        Self::open(":memory:")
    }

    fn run_migrations(&self) -> Result<(), ReportingError> {
        let conn = self.conn.lock().unwrap();
        conn.execute_batch(MIGRATIONS)
            .map_err(|e| ReportingError::Persistence(
                PersistenceError::MigrationFailed(e.to_string())
            ))
    }
}

const MIGRATIONS: &str = "
    CREATE TABLE IF NOT EXISTS transactions (
        id                TEXT PRIMARY KEY,
        source_account_id TEXT NOT NULL,
        target_account_id TEXT,
        amount            TEXT NOT NULL,
        currency          TEXT NOT NULL,
        transaction_type  TEXT NOT NULL,
        status            TEXT NOT NULL,
        occurred_at       TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_transactions_occurred_at
        ON transactions (occurred_at);
    CREATE INDEX IF NOT EXISTS idx_transactions_account
        ON transactions (source_account_id);

    CREATE TABLE IF NOT EXISTS period_summaries (
        period_start TEXT NOT NULL,
        period_end   TEXT NOT NULL,
        data         TEXT NOT NULL,
        created_at   TEXT NOT NULL,
        PRIMARY KEY (period_start, period_end)
    );
";

// ── TransactionRepository adapter ─────────────────────────────────────────────

pub struct SqliteTransactionRepository {
    db: std::sync::Arc<SqliteDb>,
}

impl SqliteTransactionRepository {
    pub fn new(db: std::sync::Arc<SqliteDb>) -> Self {
        Self { db }
    }

    pub fn insert(&self, tx: &Transaction) -> Result<(), ReportingError> {
        let conn = self.db.conn.lock().unwrap();
        conn.execute(
            "INSERT OR IGNORE INTO transactions
             (id, source_account_id, target_account_id, amount, currency,
              transaction_type, status, occurred_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![
                tx.id,
                tx.source_account_id,
                tx.target_account_id,
                tx.amount.amount.to_string(),
                format!("{}", tx.amount.currency),
                format!("{:?}", tx.transaction_type).to_uppercase(),
                format!("{:?}", tx.status).to_uppercase(),
                tx.occurred_at.to_rfc3339(),
            ],
        )
        .map_err(|e| ReportingError::Persistence(PersistenceError::SqliteError(e.to_string())))?;
        Ok(())
    }
}

#[async_trait]
impl TransactionRepository for SqliteTransactionRepository {
    async fn find_by_period(
        &self,
        start: NaiveDate,
        end: NaiveDate,
    ) -> Result<Vec<Transaction>, ReportingError> {
        let conn = self.db.conn.lock().unwrap();
        let mut stmt = conn
            .prepare(
                "SELECT id, source_account_id, target_account_id, amount, currency,
                        transaction_type, status, occurred_at
                 FROM transactions
                 WHERE date(occurred_at) >= ?1 AND date(occurred_at) <= ?2
                 ORDER BY occurred_at ASC",
            )
            .map_err(|e| ReportingError::Persistence(PersistenceError::SqliteError(e.to_string())))?;

        let rows = stmt
            .query_map(params![start.to_string(), end.to_string()], |row| {
                Ok(RawRow {
                    id: row.get(0)?,
                    source_account_id: row.get(1)?,
                    target_account_id: row.get(2)?,
                    amount: row.get(3)?,
                    currency: row.get(4)?,
                    transaction_type: row.get(5)?,
                    status: row.get(6)?,
                    occurred_at: row.get(7)?,
                })
            })
            .map_err(|e| ReportingError::Persistence(PersistenceError::SqliteError(e.to_string())))?;

        rows.filter_map(|r| r.ok().and_then(|row| row.into_transaction().ok()))
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| ReportingError::Persistence(PersistenceError::SqliteError(e.to_string())))
    }

    async fn find_by_account(
        &self,
        account_id: &str,
    ) -> Result<Vec<Transaction>, ReportingError> {
        let conn = self.db.conn.lock().unwrap();
        let mut stmt = conn
            .prepare(
                "SELECT id, source_account_id, target_account_id, amount, currency,
                        transaction_type, status, occurred_at
                 FROM transactions
                 WHERE source_account_id = ?1
                 ORDER BY occurred_at DESC",
            )
            .map_err(|e| ReportingError::Persistence(PersistenceError::SqliteError(e.to_string())))?;

        let rows = stmt
            .query_map(params![account_id], |row| {
                Ok(RawRow {
                    id: row.get(0)?,
                    source_account_id: row.get(1)?,
                    target_account_id: row.get(2)?,
                    amount: row.get(3)?,
                    currency: row.get(4)?,
                    transaction_type: row.get(5)?,
                    status: row.get(6)?,
                    occurred_at: row.get(7)?,
                })
            })
            .map_err(|e| ReportingError::Persistence(PersistenceError::SqliteError(e.to_string())))?;

        rows.filter_map(|r| r.ok().and_then(|row| row.into_transaction().ok()))
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| ReportingError::Persistence(PersistenceError::SqliteError(e.to_string())))
    }

    async fn count(&self) -> Result<u64, ReportingError> {
        let conn = self.db.conn.lock().unwrap();
        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM transactions", [], |row| row.get(0))
            .map_err(|e| ReportingError::Persistence(PersistenceError::SqliteError(e.to_string())))?;
        Ok(count as u64)
    }
}

// ── ReportStore adapter ───────────────────────────────────────────────────────

pub struct SqliteReportStore {
    db: std::sync::Arc<SqliteDb>,
}

impl SqliteReportStore {
    pub fn new(db: std::sync::Arc<SqliteDb>) -> Self {
        Self { db }
    }
}

#[async_trait]
impl ReportStore for SqliteReportStore {
    async fn save_period_summary(
        &self,
        summary: &PeriodSummary,
    ) -> Result<(), ReportingError> {
        let conn = self.db.conn.lock().unwrap();
        let data = serde_json::to_string(summary).map_err(|e| {
            ReportingError::Persistence(PersistenceError::SqliteError(e.to_string()))
        })?;
        conn.execute(
            "INSERT OR REPLACE INTO period_summaries (period_start, period_end, data, created_at)
             VALUES (?1, ?2, ?3, ?4)",
            params![
                summary.period.start.to_string(),
                summary.period.end.to_string(),
                data,
                chrono::Utc::now().to_rfc3339(),
            ],
        )
        .map_err(|e| ReportingError::Persistence(PersistenceError::SqliteError(e.to_string())))?;
        Ok(())
    }

    async fn load_period_summary(
        &self,
        period: &Period,
    ) -> Result<Option<PeriodSummary>, ReportingError> {
        let conn = self.db.conn.lock().unwrap();
        let result = conn.query_row(
            "SELECT data FROM period_summaries WHERE period_start = ?1 AND period_end = ?2",
            params![period.start.to_string(), period.end.to_string()],
            |row| row.get::<_, String>(0),
        );

        match result {
            Ok(data) => {
                let summary: PeriodSummary = serde_json::from_str(&data).map_err(|e| {
                    ReportingError::Persistence(PersistenceError::SqliteError(e.to_string()))
                })?;
                Ok(Some(summary))
            }
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(ReportingError::Persistence(PersistenceError::SqliteError(e.to_string()))),
        }
    }

    async fn list_summaries(&self) -> Result<Vec<PeriodSummary>, ReportingError> {
        let conn = self.db.conn.lock().unwrap();
        let mut stmt = conn
            .prepare("SELECT data FROM period_summaries ORDER BY period_start DESC")
            .map_err(|e| ReportingError::Persistence(PersistenceError::SqliteError(e.to_string())))?;

        let rows = stmt
            .query_map([], |row| row.get::<_, String>(0))
            .map_err(|e| ReportingError::Persistence(PersistenceError::SqliteError(e.to_string())))?;

        rows.filter_map(|r| r.ok())
            .map(|data| {
                serde_json::from_str::<PeriodSummary>(&data)
                    .map_err(|e| ReportingError::Persistence(PersistenceError::SqliteError(e.to_string())))
            })
            .collect()
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

struct RawRow {
    id: String,
    source_account_id: String,
    target_account_id: Option<String>,
    amount: String,
    currency: String,
    transaction_type: String,
    status: String,
    occurred_at: String,
}

impl RawRow {
    fn into_transaction(self) -> Result<Transaction, String> {
        let amount = self.amount.parse::<rust_decimal::Decimal>()
            .map_err(|e| e.to_string())?;
        let currency = match self.currency.as_str() {
            "EUR" => Currency::Eur,
            "USD" => Currency::Usd,
            "GBP" => Currency::Gbp,
            other => return Err(format!("Unknown currency: {}", other)),
        };
        let transaction_type = match self.transaction_type.as_str() {
            "WITHDRAWAL" => TransactionType::Withdrawal,
            "DEPOSIT"    => TransactionType::Deposit,
            "TRANSFER"   => TransactionType::Transfer,
            other => return Err(format!("Unknown type: {}", other)),
        };
        let status = match self.status.as_str() {
            "COMPLETED"   => TransactionStatus::Completed,
            "FAILED"      => TransactionStatus::Failed,
            "COMPENSATED" => TransactionStatus::Compensated,
            other => return Err(format!("Unknown status: {}", other)),
        };
        let occurred_at = chrono::DateTime::parse_from_rfc3339(&self.occurred_at)
            .map(|dt| dt.with_timezone(&chrono::Utc))
            .map_err(|e| e.to_string())?;

        Ok(Transaction {
            id: self.id,
            source_account_id: self.source_account_id,
            target_account_id: self.target_account_id,
            amount: Money::new(amount, currency),
            transaction_type,
            status,
            occurred_at,
        })
    }
}
