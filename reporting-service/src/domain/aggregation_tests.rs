// Unit tests embedded in the module — idiomatic Rust style.
// Add this block at the bottom of src/domain/aggregation.rs

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{NaiveDate, TimeZone, Utc};
    use rust_decimal_macros::dec;
    use crate::domain::aggregation::{compute_failure_trend, compute_period_summary, find_peak_day, TrendDirection};
    use crate::domain::model::{Currency, Money, Period, PeriodSummary, Transaction, TransactionStatus, TransactionType};

    fn tx(
        id: &str,
        amount: f64,
        tx_type: TransactionType,
        status: TransactionStatus,
        date: &str,
    ) -> Transaction {
        Transaction {
            id: id.to_string(),
            source_account_id: "acc-test".to_string(),
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

    fn jan() -> Period {
        Period::new(
            NaiveDate::from_ymd_opt(2024, 1, 1).unwrap(),
            NaiveDate::from_ymd_opt(2024, 1, 31).unwrap(),
        )
        .unwrap()
    }

    #[test]
    fn empty_slice_returns_zero_summary() {
        let summary = compute_period_summary(&[], &jan());
        assert_eq!(summary.total_completed, 0);
        assert_eq!(summary.total_failed, 0);
        assert_eq!(summary.total_volume.amount, dec!(0));
        assert_eq!(summary.failure_rate_pct, 0.0);
    }

    #[test]
    fn only_failed_txs_gives_100_pct_failure_rate() {
        let txs = vec![
            tx("1", 100.0, TransactionType::Withdrawal, TransactionStatus::Failed, "2024-01-10"),
            tx("2", 200.0, TransactionType::Withdrawal, TransactionStatus::Failed, "2024-01-11"),
        ];
        let summary = compute_period_summary(&txs, &jan());
        assert_eq!(summary.total_completed, 0);
        assert_eq!(summary.total_failed, 2);
        assert!((summary.failure_rate_pct - 100.0).abs() < 0.01);
        // Failed transactions do not count toward volume
        assert_eq!(summary.total_volume.amount, dec!(0));
    }

    #[test]
    fn peak_day_is_day_with_most_completed_txs() {
        let txs = vec![
            tx("1", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-05"),
            tx("2", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-10"),
            tx("3", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-10"),
            tx("4", 100.0, TransactionType::Withdrawal, TransactionStatus::Completed, "2024-01-10"),
        ];
        let summary = compute_period_summary(&txs, &jan());
        assert_eq!(
            summary.peak_day,
            Some(NaiveDate::from_ymd_opt(2024, 1, 10).unwrap())
        );
        assert_eq!(summary.peak_day_count, 3);
    }

    #[test]
    fn find_peak_day_returns_none_for_empty() {
        let (day, count) = find_peak_day(&[]);
        assert!(day.is_none());
        assert_eq!(count, 0);
    }

    #[test]
    fn failure_trend_stable_within_one_pct() {
        let current = PeriodSummary {
            period: jan(),
            total_completed: 95,
            total_failed: 5,
            total_compensated: 0,
            total_volume: Money::zero(Currency::Eur),
            total_withdrawals: Money::zero(Currency::Eur),
            total_deposits: Money::zero(Currency::Eur),
            total_transfers: Money::zero(Currency::Eur),
            average_transaction: Money::zero(Currency::Eur),
            failure_rate_pct: 5.0,
            peak_day: None,
            peak_day_count: 0,
        };
        let previous = PeriodSummary { failure_rate_pct: 4.5, ..current.clone() };

        let trend = compute_failure_trend(&current, &previous);
        assert_eq!(trend.direction, TrendDirection::Stable);
        assert!((trend.delta_pct - 0.5).abs() < 0.01);
    }
}
