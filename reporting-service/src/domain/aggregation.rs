//! Aggregation — pure computation functions over transaction slices.
//!
//! All functions here are pure: no I/O, no side effects, no async.
//! They take slices of domain types and return report types.
//!
//! This makes them:
//!  - Trivially unit-testable (no mocking needed)
//!  - Eligible for WASM compilation (no runtime dependencies)
//!  - Parallelisable with rayon if needed
//!
//! # Design note
//!
//! These could be methods on the types, but free functions keep the
//! domain model types as simple data holders and the computation
//! logic separate — easier to reason about independently.

use crate::domain::model::*;
use chrono::NaiveDate;
use rust_decimal::Decimal;
use std::collections::HashMap;

/// Compute a full [`PeriodSummary`] from a slice of transactions.
///
/// Transactions outside the period are ignored (not filtered out —
/// the caller should pre-filter for performance on large datasets).
///

pub fn compute_period_summary(transactions: &[Transaction], period: &Period) -> PeriodSummary {
    let in_period: Vec<&Transaction> = transactions
        .iter()
        .filter(|tx| period.contains(&tx.occurred_at))
        .collect();

    let completed: Vec<&Transaction> = in_period
        .iter()
        .filter(|tx| tx.status == TransactionStatus::Completed)
        .copied()
        .collect();

    let failed_count = in_period
        .iter()
        .filter(|tx| tx.status == TransactionStatus::Failed)
        .count() as u64;

    let compensated_count = in_period
        .iter()
        .filter(|tx| tx.status == TransactionStatus::Compensated)
        .count() as u64;

    // Use the currency of the first completed transaction, defaulting to EUR
    let currency = completed
        .first()
        .map(|tx| tx.amount.currency.clone())
        .unwrap_or(Currency::Eur);

    let total_volume = sum_amounts(&completed, &currency);
    let total_withdrawals = sum_by_type(&completed, &TransactionType::Withdrawal, &currency);
    let total_deposits = sum_by_type(&completed, &TransactionType::Deposit, &currency);
    let total_transfers = sum_by_type(&completed, &TransactionType::Transfer, &currency);

    let average_transaction = if completed.is_empty() {
        Money::zero(currency.clone())
    } else {
        Money::new(
            total_volume.amount / Decimal::from(completed.len() as u64),
            currency.clone(),
        )
    };

    let total_count = in_period.len() as f64;
    let failure_rate_pct = if total_count > 0.0 {
        (failed_count as f64 + compensated_count as f64) / total_count * 100.0
    } else {
        0.0
    };

    let (peak_day, peak_day_count) = find_peak_day(&completed);

    PeriodSummary {
        period: period.clone(),
        total_completed: completed.len() as u64,
        total_failed: failed_count,
        total_compensated: compensated_count,
        total_volume,
        total_withdrawals,
        total_deposits,
        total_transfers,
        average_transaction,
        failure_rate_pct,
        peak_day,
        peak_day_count,
    }
}

/// Compute per-day statistics within a period.
pub fn compute_daily_breakdown(transactions: &[Transaction], period: &Period) -> Vec<DailySummary> {
    let currency = transactions
        .first()
        .map(|tx| tx.amount.currency.clone())
        .unwrap_or(Currency::Eur);

    // Group by date
    let mut by_day: HashMap<NaiveDate, Vec<&Transaction>> = HashMap::new();
    for tx in transactions.iter().filter(|tx| period.contains(&tx.occurred_at)) {
        let date = tx.occurred_at.date_naive();
        by_day.entry(date).or_default().push(tx);
    }

    // Build sorted list of daily summaries
    let mut days: Vec<NaiveDate> = by_day.keys().copied().collect();
    days.sort_unstable();

    days.into_iter()
        .map(|date| {
            let txs = &by_day[&date];
            let completed: Vec<&&Transaction> = txs
                .iter()
                .filter(|tx| tx.status == TransactionStatus::Completed)
                .collect();

            DailySummary {
                date,
                completed: completed.len() as u64,
                failed: txs
                    .iter()
                    .filter(|tx| tx.status == TransactionStatus::Failed)
                    .count() as u64,
                volume: sum_amounts(
                    &completed.iter().map(|tx| **tx).collect::<Vec<_>>(),
                    &currency,
                ),
            }
        })
        .collect()
}

/// Compute per-account activity summaries from a transaction slice.
pub fn compute_account_summaries(transactions: &[Transaction]) -> Vec<AccountSummary> {
    let currency = transactions
        .first()
        .map(|tx| tx.amount.currency.clone())
        .unwrap_or(Currency::Eur);

    let mut by_account: HashMap<&str, Vec<&Transaction>> = HashMap::new();
    for tx in transactions {
        by_account
            .entry(tx.source_account_id.as_str())
            .or_default()
            .push(tx);
    }

    let mut summaries: Vec<AccountSummary> = by_account
        .iter()
        .map(|(&account_id, txs)| {
            let completed: Vec<&&Transaction> = txs
                .iter()
                .filter(|tx| tx.status == TransactionStatus::Completed)
                .collect();

            let total_debited = sum_by_type(
                &completed.iter().map(|tx| **tx).collect::<Vec<_>>(),
                &TransactionType::Withdrawal,
                &currency,
            );
            let total_credited = sum_by_type(
                &completed.iter().map(|tx| **tx).collect::<Vec<_>>(),
                &TransactionType::Deposit,
                &currency,
            );
            let net_flow = total_credited.amount - total_debited.amount;
            let last_activity = txs.iter().map(|tx| tx.occurred_at).max();

            AccountSummary {
                account_id: account_id.to_string(),
                total_transactions: txs.len() as u64,
                total_debited,
                total_credited,
                net_flow,
                last_activity,
            }
        })
        .collect();

    // Sort by total transactions descending
    summaries.sort_unstable_by(|a, b| b.total_transactions.cmp(&a.total_transactions));
    summaries
}

/// Compute the failure rate trend: compare this period vs previous.
pub fn compute_failure_trend(
    current: &PeriodSummary,
    previous: &PeriodSummary,
) -> FailureTrend {
    let delta = current.failure_rate_pct - previous.failure_rate_pct;
    FailureTrend {
        current_pct: current.failure_rate_pct,
        previous_pct: previous.failure_rate_pct,
        delta_pct: delta,
        direction: match delta {
            d if d > 1.0 => TrendDirection::Worse,
            d if d < -1.0 => TrendDirection::Better,
            _ => TrendDirection::Stable,
        },
    }
}

#[derive(Debug, Clone)]
pub struct FailureTrend {
    pub current_pct: f64,
    pub previous_pct: f64,
    pub delta_pct: f64,
    pub direction: TrendDirection,
}

#[derive(Debug, Clone, PartialEq)]
pub enum TrendDirection {
    Better,
    Stable,
    Worse,
}

// ── Private helpers ───────────────────────────────────────────────────────────

fn sum_amounts(transactions: &[&Transaction], currency: &Currency) -> Money {
    let total = transactions
        .iter()
        .filter(|tx| &tx.amount.currency == currency)
        .map(|tx| tx.amount.amount)
        .fold(Decimal::ZERO, |acc, amt| acc + amt);
    Money::new(total, currency.clone())
}

fn sum_by_type(
    transactions: &[&Transaction],
    tx_type: &TransactionType,
    currency: &Currency,
) -> Money {
    let total = transactions
        .iter()
        .filter(|tx| &tx.transaction_type == tx_type && &tx.amount.currency == currency)
        .map(|tx| tx.amount.amount)
        .fold(Decimal::ZERO, |acc, amt| acc + amt);
    Money::new(total, currency.clone())
}

fn find_peak_day(transactions: &[&Transaction]) -> (Option<NaiveDate>, u64) {
    let mut counts: HashMap<NaiveDate, u64> = HashMap::new();
    for tx in transactions {
        *counts.entry(tx.occurred_at.date_naive()).or_insert(0) += 1;
    }
    counts
        .into_iter()
        .max_by_key(|(_, count)| *count)
        .map(|(date, count)| (Some(date), count))
        .unwrap_or((None, 0))
}

// Inline unit tests — idiomatic Rust: tests live in the same file they test.
// Integration tests with realistic data are in tests/aggregation_tests.rs.
#[cfg(test)]
#[path = "aggregation_tests.rs"]
mod tests;
