//! SQLite adapter tests using an in-memory database.
//!
//! These are the closest we get to infrastructure integration tests
//! without external dependencies. `rusqlite` supports `:memory:` databases
//! that live only for the duration of the test — no cleanup needed.

use chrono::{NaiveDate, TimeZone, Utc};
use reporting::application::ports::{ReportStore, TransactionRepository};
use reporting::domain::model::*;
use reporting::infrastructure::persistence::{
    SqliteDb, SqliteReportStore, SqliteTransactionRepository,
};
use rust_decimal_macros::dec;
use std::sync::Arc;

fn setup() -> (Arc<SqliteDb>, Arc<SqliteTransactionRepository>, Arc<SqliteReportStore>) {
    let db = Arc::new(SqliteDb::in_memory().unwrap());
    let repo = Arc::new(SqliteTransactionRepository::new(db.clone()));
    let store = Arc::new(SqliteReportStore::new(db.clone()));
    (db, repo, store)
}

fn make_tx(id: &str, account: &str, amount: f64, date: &str, status: TransactionStatus) -> Transaction {
    Transaction {
        id: id.to_string(),
        source_account_id: account.to_string(),
        target_account_id: None,
        amount: Money::new(
            rust_decimal::Decimal::try_from(amount).unwrap(),
            Currency::Eur,
        ),
        transaction_type: TransactionType::Withdrawal,
        status,
        occurred_at: Utc.from_utc_datetime(
            &NaiveDate::parse_from_str(date, "%Y-%m-%d")
                .unwrap()
                .and_hms_opt(12, 0, 0)
                .unwrap(),
        ),
    }
}

// ── TransactionRepository ─────────────────────────────────────────────────────

#[tokio::test]
async fn sqlite_insert_and_find_by_period() {
    let (_, repo, _) = setup();

    let tx = make_tx("tx-1", "acc-a", 100.0, "2024-01-15", TransactionStatus::Completed);
    repo.insert(&tx).unwrap();

    let results = repo
        .find_by_period(
            NaiveDate::from_ymd_opt(2024, 1, 1).unwrap(),
            NaiveDate::from_ymd_opt(2024, 1, 31).unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(results.len(), 1);
    assert_eq!(results[0].id, "tx-1");
    assert_eq!(results[0].amount.amount, dec!(100.00));
}

#[tokio::test]
async fn sqlite_find_by_period_filters_out_of_range() {
    let (_, repo, _) = setup();

    repo.insert(&make_tx("tx-jan", "acc-a", 100.0, "2024-01-15", TransactionStatus::Completed)).unwrap();
    repo.insert(&make_tx("tx-dec", "acc-a", 200.0, "2023-12-15", TransactionStatus::Completed)).unwrap();

    let results = repo
        .find_by_period(
            NaiveDate::from_ymd_opt(2024, 1, 1).unwrap(),
            NaiveDate::from_ymd_opt(2024, 1, 31).unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(results.len(), 1);
    assert_eq!(results[0].id, "tx-jan");
}

#[tokio::test]
async fn sqlite_insert_is_idempotent_on_duplicate_id() {
    let (_, repo, _) = setup();

    let tx = make_tx("tx-dup", "acc-a", 100.0, "2024-01-15", TransactionStatus::Completed);
    repo.insert(&tx).unwrap();
    repo.insert(&tx).unwrap(); // INSERT OR IGNORE — should not fail

    let count = repo.count().await.unwrap();
    assert_eq!(count, 1); // Only one stored
}

#[tokio::test]
async fn sqlite_count_returns_total() {
    let (_, repo, _) = setup();

    for i in 0..5 {
        repo.insert(&make_tx(&format!("tx-{i}"), "acc-a", 100.0, "2024-01-15", TransactionStatus::Completed))
            .unwrap();
    }

    assert_eq!(repo.count().await.unwrap(), 5);
}

#[tokio::test]
async fn sqlite_find_by_account_filters_correctly() {
    let (_, repo, _) = setup();

    repo.insert(&make_tx("tx-a1", "acc-a", 100.0, "2024-01-10", TransactionStatus::Completed)).unwrap();
    repo.insert(&make_tx("tx-a2", "acc-a", 200.0, "2024-01-11", TransactionStatus::Completed)).unwrap();
    repo.insert(&make_tx("tx-b1", "acc-b", 300.0, "2024-01-12", TransactionStatus::Completed)).unwrap();

    let acc_a_txs = repo.find_by_account("acc-a").await.unwrap();
    assert_eq!(acc_a_txs.len(), 2);
    assert!(acc_a_txs.iter().all(|tx| tx.source_account_id == "acc-a"));
}

// ── ReportStore ───────────────────────────────────────────────────────────────

#[tokio::test]
async fn sqlite_store_save_and_load_period_summary() {
    let (_, _, store) = setup();

    let period = Period::new(
        NaiveDate::from_ymd_opt(2024, 1, 1).unwrap(),
        NaiveDate::from_ymd_opt(2024, 1, 31).unwrap(),
    )
    .unwrap();

    let summary = PeriodSummary {
        period: period.clone(),
        total_completed: 42,
        total_failed: 3,
        total_compensated: 1,
        total_volume: Money::new(dec!(9999.99), Currency::Eur),
        total_withdrawals: Money::new(dec!(5000.00), Currency::Eur),
        total_deposits: Money::new(dec!(4999.99), Currency::Eur),
        total_transfers: Money::zero(Currency::Eur),
        average_transaction: Money::new(dec!(238.09), Currency::Eur),
        failure_rate_pct: 8.69,
        peak_day: Some(NaiveDate::from_ymd_opt(2024, 1, 15).unwrap()),
        peak_day_count: 7,
    };

    store.save_period_summary(&summary).await.unwrap();

    let loaded = store.load_period_summary(&period).await.unwrap();
    assert!(loaded.is_some());
    let loaded = loaded.unwrap();
    assert_eq!(loaded.total_completed, 42);
    assert_eq!(loaded.total_volume.amount, dec!(9999.99));
    assert_eq!(loaded.failure_rate_pct, 8.69);
}

#[tokio::test]
async fn sqlite_load_nonexistent_period_returns_none() {
    let (_, _, store) = setup();

    let period = Period::new(
        NaiveDate::from_ymd_opt(2099, 1, 1).unwrap(),
        NaiveDate::from_ymd_opt(2099, 1, 31).unwrap(),
    )
    .unwrap();

    let result = store.load_period_summary(&period).await.unwrap();
    assert!(result.is_none());
}

#[tokio::test]
async fn sqlite_store_replace_on_duplicate_period() {
    let (_, _, store) = setup();

    let period = Period::new(
        NaiveDate::from_ymd_opt(2024, 1, 1).unwrap(),
        NaiveDate::from_ymd_opt(2024, 1, 31).unwrap(),
    ).unwrap();

    let first = PeriodSummary {
        period: period.clone(),
        total_completed: 10,
        total_failed: 0, total_compensated: 0,
        total_volume: Money::new(dec!(1000.00), Currency::Eur),
        total_withdrawals: Money::zero(Currency::Eur),
        total_deposits: Money::zero(Currency::Eur),
        total_transfers: Money::zero(Currency::Eur),
        average_transaction: Money::zero(Currency::Eur),
        failure_rate_pct: 0.0, peak_day: None, peak_day_count: 0,
    };

    let second = PeriodSummary { total_completed: 99, ..first.clone() };

    store.save_period_summary(&first).await.unwrap();
    store.save_period_summary(&second).await.unwrap(); // INSERT OR REPLACE

    let loaded = store.load_period_summary(&period).await.unwrap().unwrap();
    assert_eq!(loaded.total_completed, 99); // Most recent value
}
