//! ReportService integration tests using in-memory test doubles.
//!
//! No Kafka, no SQLite, no HTTP. Pure application logic testing.
//! The service is wired with test doubles implementing the port traits.

use chrono::{NaiveDate, TimeZone, Utc};
use reporting::application::report_service::ReportService;
use reporting::domain::model::*;
use reporting::infrastructure::persistence::in_memory::{
    InMemoryReportStore, InMemoryTransactionRepository, StubAccountClient,
};
use rust_decimal_macros::dec;
use reporting::application::ports::ReportStore;

fn make_tx(
    id: &str,
    account: &str,
    amount: f64,
    tx_type: TransactionType,
    status: TransactionStatus,
    date: &str,
) -> Transaction {
    Transaction {
        id: id.to_string(),
        source_account_id: account.to_string(),
        target_account_id: None,
        amount: Money::new(
            rust_decimal::Decimal::try_from(amount).unwrap(),
            Currency::Eur,
        ),
        transaction_type: tx_type,
        status,
        occurred_at: Utc.from_utc_datetime(
            &NaiveDate::parse_from_str(date, "%Y-%m-%d")
                .unwrap()
                .and_hms_opt(12, 0, 0)
                .unwrap(),
        ),
    }
}

fn build_service(transactions: Vec<Transaction>) -> ReportService {
    ReportService::new(
        InMemoryTransactionRepository::new(transactions),
        StubAccountClient::with_balance(dec!(1000.00), Currency::Eur),
        InMemoryReportStore::new(),
    )
}

// ── generate_period_report ────────────────────────────────────────────────────

#[tokio::test]
async fn period_report_returns_correct_volume() {
    let txs = vec![
        make_tx("1", "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-10"),
        make_tx("2", "acc-a", 200.0, TransactionType::Deposit,    TransactionStatus::Completed, "2024-01-15"),
    ];
    let service = build_service(txs);

    let summary = service
        .generate_period_report(
            NaiveDate::from_ymd_opt(2024, 1, 1).unwrap(),
            NaiveDate::from_ymd_opt(2024, 1, 31).unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(summary.total_completed, 2);
    assert_eq!(summary.total_volume.amount, dec!(300.00));
}

#[tokio::test]
async fn period_report_returns_error_when_no_data() {
    let service = build_service(vec![]);

    let result = service
        .generate_period_report(
            NaiveDate::from_ymd_opt(2024, 1, 1).unwrap(),
            NaiveDate::from_ymd_opt(2024, 1, 31).unwrap(),
        )
        .await;

    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), reporting::domain::error::ReportingError::NoData));
}

#[tokio::test]
async fn period_report_returns_error_for_invalid_period() {
    let service = build_service(vec![]);

    let result = service
        .generate_period_report(
            NaiveDate::from_ymd_opt(2024, 1, 31).unwrap(),
            NaiveDate::from_ymd_opt(2024, 1, 1).unwrap(), // end before start
        )
        .await;

    assert!(result.is_err());
    assert!(matches!(
        result.unwrap_err(),
        reporting::domain::error::ReportingError::InvalidPeriod(_)
    ));
}

#[tokio::test]
async fn period_report_uses_cache_on_second_call() {
    let store = InMemoryReportStore::new();
    let txs = vec![
        make_tx("1", "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-10"),
    ];
    let repo = InMemoryTransactionRepository::new(txs);
    let client = StubAccountClient::with_balance(dec!(1000.00), Currency::Eur);
    let service = ReportService::new(repo, client, store.clone());

    let start = NaiveDate::from_ymd_opt(2024, 1, 1).unwrap();
    let end   = NaiveDate::from_ymd_opt(2024, 1, 31).unwrap();

    // First call — computes and caches
    let first = service.generate_period_report(start, end).await.unwrap();
    // Second call — should hit cache (same result)
    let second = service.generate_period_report(start, end).await.unwrap();

    assert_eq!(first.total_completed, second.total_completed);
    assert_eq!(first.total_volume.amount, second.total_volume.amount);

    // Verify it was saved to the store
    let saved = store.list_summaries().await.unwrap();
    assert_eq!(saved.len(), 1);
}

// ── generate_daily_breakdown ──────────────────────────────────────────────────

#[tokio::test]
async fn daily_breakdown_groups_transactions_by_day() {
    let txs = vec![
        make_tx("1", "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-01"),
        make_tx("2", "acc-a", 200.0, TransactionType::Deposit,    TransactionStatus::Completed, "2024-01-01"),
        make_tx("3", "acc-b", 50.0,  TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-03"),
    ];
    let service = build_service(txs);

    let days = service
        .generate_daily_breakdown(
            NaiveDate::from_ymd_opt(2024, 1, 1).unwrap(),
            NaiveDate::from_ymd_opt(2024, 1, 7).unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(days.len(), 2);
    assert_eq!(days[0].date, NaiveDate::from_ymd_opt(2024, 1, 1).unwrap());
    assert_eq!(days[0].completed, 2);
    assert_eq!(days[0].volume.amount, dec!(300.00));
}

// ── generate_account_report ───────────────────────────────────────────────────

#[tokio::test]
async fn account_report_returns_top_n_accounts() {
    let txs: Vec<Transaction> = (0..5)
        .map(|i| make_tx(&format!("tx-a-{i}"), "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-10"))
        .chain((0..2).map(|i| make_tx(&format!("tx-b-{i}"), "acc-b", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-10")))
        .collect();

    let service = build_service(txs);

    let accounts = service
        .generate_account_report(
            NaiveDate::from_ymd_opt(2024, 1, 1).unwrap(),
            NaiveDate::from_ymd_opt(2024, 1, 31).unwrap(),
            1, // top 1 only
        )
        .await
        .unwrap();

    assert_eq!(accounts.len(), 1);
    assert_eq!(accounts[0].account_id, "acc-a"); // acc-a has 5 txs, acc-b has 2
    assert_eq!(accounts[0].total_transactions, 5);
}

// ── transaction_count ─────────────────────────────────────────────────────────

#[tokio::test]
async fn transaction_count_returns_total_in_store() {
    let txs = vec![
        make_tx("1", "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-01"),
        make_tx("2", "acc-a", 200.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-02"),
        make_tx("3", "acc-a", 300.0, TransactionType::Withdrawal, TransactionStatus::Failed,    "2024-01-03"),
    ];
    let service = build_service(txs);

    let count = service.transaction_count().await.unwrap();
    assert_eq!(count, 3);
}

#[tokio::test]
async fn transaction_count_is_zero_for_empty_store() {
    let service = build_service(vec![]);
    assert_eq!(service.transaction_count().await.unwrap(), 0);
}
