//! Integration-level tests for domain aggregation functions.
//!
//! These tests use real domain types with realistic data.
//! No mocking, no I/O — pure computation tests.

use chrono::{NaiveDate, TimeZone, Utc};
use reporting::domain::aggregation::*;
use reporting::domain::model::*;
use rust_decimal_macros::dec;

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

fn january() -> Period {
    Period::new(
        NaiveDate::from_ymd_opt(2024, 1, 1).unwrap(),
        NaiveDate::from_ymd_opt(2024, 1, 31).unwrap(),
    )
    .unwrap()
}

#[test]
fn period_summary_counts_completed_and_failed() {
    let txs = vec![
        make_tx("1", "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-10"),
        make_tx("2", "acc-a", 200.0, TransactionType::Deposit,    TransactionStatus::Completed, "2024-01-15"),
        make_tx("3", "acc-b", 50.0,  TransactionType::Withdrawal, TransactionStatus::Failed,    "2024-01-20"),
        make_tx("4", "acc-b", 75.0,  TransactionType::Transfer,   TransactionStatus::Compensated,"2024-01-22"),
    ];

    let summary = compute_period_summary(&txs, &january());

    assert_eq!(summary.total_completed, 2);
    assert_eq!(summary.total_failed, 1);
    assert_eq!(summary.total_compensated, 1);
}

#[test]
fn period_summary_calculates_correct_volume() {
    let txs = vec![
        make_tx("1", "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-10"),
        make_tx("2", "acc-a", 200.0, TransactionType::Deposit,    TransactionStatus::Completed, "2024-01-15"),
        // Failed — must NOT be counted in volume
        make_tx("3", "acc-b", 9999.0, TransactionType::Withdrawal, TransactionStatus::Failed,   "2024-01-20"),
    ];

    let summary = compute_period_summary(&txs, &january());

    assert_eq!(summary.total_volume.amount, dec!(300.00));
    assert_eq!(summary.total_withdrawals.amount, dec!(100.00));
    assert_eq!(summary.total_deposits.amount, dec!(200.00));
}

#[test]
fn period_summary_computes_average_transaction() {
    let txs = vec![
        make_tx("1", "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-10"),
        make_tx("2", "acc-a", 300.0, TransactionType::Deposit,    TransactionStatus::Completed, "2024-01-15"),
    ];

    let summary = compute_period_summary(&txs, &january());

    assert_eq!(summary.average_transaction.amount, dec!(200.0));
}

#[test]
fn period_summary_failure_rate_is_correct() {
    let txs = vec![
        make_tx("1", "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed,  "2024-01-10"),
        make_tx("2", "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed,  "2024-01-11"),
        make_tx("3", "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed,  "2024-01-12"),
        make_tx("4", "acc-b", 100.0, TransactionType::Withdrawal, TransactionStatus::Failed,     "2024-01-13"),
        // 1 failure / 4 total = 25%
    ];

    let summary = compute_period_summary(&txs, &january());

    let expected = 25.0_f64;
    assert!((summary.failure_rate_pct - expected).abs() < 0.01,
        "Expected failure rate ~{expected}%, got {:.2}%", summary.failure_rate_pct);
}

#[test]
fn period_summary_filters_out_of_range_transactions() {
    let txs = vec![
        make_tx("1", "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-15"),
        // Before period
        make_tx("2", "acc-a", 500.0, TransactionType::Deposit,    TransactionStatus::Completed, "2023-12-31"),
        // After period
        make_tx("3", "acc-a", 500.0, TransactionType::Deposit,    TransactionStatus::Completed, "2024-02-01"),
    ];

    let summary = compute_period_summary(&txs, &january());

    assert_eq!(summary.total_completed, 1);
    assert_eq!(summary.total_volume.amount, dec!(100.00));
}

#[test]
fn daily_breakdown_groups_by_date() {
    let txs = vec![
        make_tx("1", "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-01"),
        make_tx("2", "acc-a", 200.0, TransactionType::Deposit,    TransactionStatus::Completed, "2024-01-01"),
        make_tx("3", "acc-b", 50.0,  TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-02"),
    ];

    let period = Period::new(
        NaiveDate::from_ymd_opt(2024, 1, 1).unwrap(),
        NaiveDate::from_ymd_opt(2024, 1, 7).unwrap(),
    ).unwrap();

    let days = compute_daily_breakdown(&txs, &period);

    assert_eq!(days.len(), 2); // Only days with transactions
    assert_eq!(days[0].date, NaiveDate::from_ymd_opt(2024, 1, 1).unwrap());
    assert_eq!(days[0].completed, 2);
    assert_eq!(days[0].volume.amount, dec!(300.00));
    assert_eq!(days[1].date, NaiveDate::from_ymd_opt(2024, 1, 2).unwrap());
    assert_eq!(days[1].completed, 1);
}

#[test]
fn account_summaries_ranked_by_transaction_count() {
    let txs = vec![
        make_tx("1", "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-01"),
        make_tx("2", "acc-a", 200.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-02"),
        make_tx("3", "acc-a", 300.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-03"),
        make_tx("4", "acc-b", 500.0, TransactionType::Deposit,    TransactionStatus::Completed, "2024-01-01"),
    ];

    let summaries = compute_account_summaries(&txs);

    // acc-a has 3 transactions, acc-b has 1 — acc-a should be first
    assert_eq!(summaries[0].account_id, "acc-a");
    assert_eq!(summaries[0].total_transactions, 3);
    assert_eq!(summaries[1].account_id, "acc-b");
}

#[test]
fn failure_trend_detects_improvement() {
    let worse_period = Period::new(
        NaiveDate::from_ymd_opt(2024, 1, 1).unwrap(),
        NaiveDate::from_ymd_opt(2024, 1, 31).unwrap(),
    ).unwrap();
    let better_period = Period::new(
        NaiveDate::from_ymd_opt(2024, 2, 1).unwrap(),
        NaiveDate::from_ymd_opt(2024, 2, 29).unwrap(),
    ).unwrap();

    // January: 5/10 = 50% failure
    let jan_txs: Vec<Transaction> = (0..5).map(|i| {
        make_tx(&format!("ok-{i}"), "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-10")
    }).chain((0..5).map(|i| {
        make_tx(&format!("fail-{i}"), "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Failed, "2024-01-10")
    })).collect();

    // February: 1/10 = 10% failure
    let feb_txs: Vec<Transaction> = (0..9).map(|i| {
        make_tx(&format!("ok2-{i}"), "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-02-10")
    }).chain(std::iter::once(
        make_tx("fail2-0", "acc-a", 100.0, TransactionType::Withdrawal, TransactionStatus::Failed, "2024-02-10")
    )).collect();

    let previous = compute_period_summary(&jan_txs, &worse_period);
    let current  = compute_period_summary(&feb_txs, &better_period);
    let trend = compute_failure_trend(&current, &previous);

    assert_eq!(trend.direction, TrendDirection::Better);
    assert!(trend.delta_pct < 0.0, "Delta should be negative (improvement)");
}

#[test]
fn money_add_same_currency() {
    let a = Money::new(dec!(100.00), Currency::Eur);
    let b = Money::new(dec!(50.00),  Currency::Eur);
    let result = a.add(&b);
    assert_eq!(result.amount, dec!(150.00));
    assert_eq!(result.currency, Currency::Eur);
}

#[test]
#[should_panic(expected = "Cannot add different currencies")]
fn money_add_different_currencies_panics() {
    let eur = Money::new(dec!(100.00), Currency::Eur);
    let usd = Money::new(dec!(50.00),  Currency::Usd);
    eur.add(&usd); // Should panic
}

#[test]
fn period_contains_boundary_dates() {
    let period = january();
    let start = Utc.from_utc_datetime(
        &NaiveDate::from_ymd_opt(2024, 1, 1).unwrap().and_hms_opt(0, 0, 0).unwrap()
    );
    let end = Utc.from_utc_datetime(
        &NaiveDate::from_ymd_opt(2024, 1, 31).unwrap().and_hms_opt(23, 59, 59).unwrap()
    );
    let before = Utc.from_utc_datetime(
        &NaiveDate::from_ymd_opt(2023, 12, 31).unwrap().and_hms_opt(23, 59, 59).unwrap()
    );

    assert!(period.contains(&start));
    assert!(period.contains(&end));
    assert!(!period.contains(&before));
}
