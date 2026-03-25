//! Kafka consumer adapter.
//!
//! Consumes `bankos.transaction.events` and populates the local SQLite cache.
//!
//! # Consumer group
//!
//! Group ID: `reporting-service`
//! Each instance processes a subset of partitions.
//!
//! # Offset management
//!
//! ADR-005: The reporting service uses `auto.offset.reset = earliest`
//! so it can reconstruct historical reports by replaying the full topic.
//! This is a key advantage of Kafka over REST polling: the event log
//! is the source of truth, and this service can bootstrap from it.

use crate::domain::error::{KafkaError, ReportingError};
use crate::domain::model::*;
use rdkafka::config::ClientConfig;
use rdkafka::consumer::{Consumer, StreamConsumer};
use rdkafka::message::Message;
use serde::Deserialize;
use std::time::Duration;
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};

/// Raw Kafka event payload — matches the JSON published by Transaction Service.
#[derive(Debug, Deserialize)]
struct TransactionEventPayload {
    #[serde(rename = "eventType")]
    event_type: String,
    #[serde(rename = "eventId")]
    event_id: Option<String>,
    #[serde(rename = "transactionId")]
    transaction_id: Option<String>,
    #[serde(rename = "sourceAccountId")]
    source_account_id: Option<String>,
    #[serde(rename = "targetAccountId")]
    target_account_id: Option<String>,
    amount: Option<serde_json::Value>,
    currency: Option<String>,
    #[serde(rename = "type")]
    transaction_type: Option<String>,
    status: Option<String>,
    #[serde(rename = "occurredAt")]
    occurred_at: Option<String>,
}

/// Starts a background Kafka consumer that forwards transactions
/// to the provided `mpsc::Sender`.
///
/// The consumer runs until the `shutdown` token is triggered.
///
/// # Architecture note
///
/// We use `mpsc::channel` to decouple the Kafka consumer (I/O-bound)
/// from the SQLite writer (also I/O-bound). Both run on separate
/// Tokio tasks. The channel acts as a backpressure buffer.
pub async fn start_consumer(
    brokers: &str,
    topic: &str,
    group_id: &str,
    tx: mpsc::Sender<Transaction>,
    shutdown: tokio_util::sync::CancellationToken,
) -> Result<(), ReportingError> {
    let consumer: StreamConsumer = ClientConfig::new()
        .set("bootstrap.servers", brokers)
        .set("group.id", group_id)
        .set("auto.offset.reset", "earliest")
        .set("enable.auto.commit", "true")
        .set("auto.commit.interval.ms", "5000")
        .set("session.timeout.ms", "30000")
        .create()
        .map_err(|e| ReportingError::Kafka(KafkaError::ConnectionFailed(e.to_string())))?;

    consumer
        .subscribe(&[topic])
        .map_err(|e| ReportingError::Kafka(KafkaError::ConnectionFailed(e.to_string())))?;

    info!("Kafka consumer started: topic={} group={}", topic, group_id);

    loop {
        tokio::select! {
            _ = shutdown.cancelled() => {
                info!("Kafka consumer shutting down");
                break;
            }
            message = consumer.recv() => {
                match message {
                    Ok(msg) => {
                        let payload = msg.payload().unwrap_or_default();
                        match process_message(payload) {
                            Ok(Some(transaction)) => {
                                if tx.send(transaction).await.is_err() {
                                    warn!("Transaction receiver dropped — stopping consumer");
                                    break;
                                }
                            }
                            Ok(None) => {
                                // Non-TransactionCompleted event — skip
                                debug!("Skipping non-completed event");
                            }
                            Err(e) => {
                                warn!("Failed to process Kafka message: {}", e);
                                // Continue — one bad message should not stop the consumer
                            }
                        }
                    }
                    Err(e) => {
                        error!("Kafka receive error: {}", e);
                        tokio::time::sleep(Duration::from_secs(1)).await;
                    }
                }
            }
        }
    }

    Ok(())
}

fn process_message(payload: &[u8]) -> Result<Option<Transaction>, ReportingError> {
    let raw: TransactionEventPayload = serde_json::from_slice(payload)
        .map_err(|e| ReportingError::Kafka(KafkaError::DeserializationFailed(e.to_string())))?;

    // We only aggregate completed transactions for volume/revenue reports
    // Failed and compensated are counted separately in the domain aggregation
    let status = match raw.status.as_deref() {
        Some("COMPLETED") => TransactionStatus::Completed,
        Some("FAILED") => TransactionStatus::Failed,
        Some("COMPENSATED") => TransactionStatus::Compensated,
        _ => {
            // TransactionCreated, etc. — not useful for reporting
            return Ok(None);
        }
    };

    let event_id = raw.event_id.ok_or_else(|| {
        ReportingError::Kafka(KafkaError::DeserializationFailed("missing eventId".into()))
    })?;

    let transaction_id = raw.transaction_id.ok_or_else(|| {
        ReportingError::Kafka(KafkaError::DeserializationFailed(
            "missing transactionId".into(),
        ))
    })?;

    let source_account_id = raw.source_account_id.ok_or_else(|| {
        ReportingError::Kafka(KafkaError::DeserializationFailed(
            "missing sourceAccountId".into(),
        ))
    })?;

    let amount_val = raw
        .amount
        .and_then(|v| {
            v.as_str()
                .and_then(|s| s.parse::<rust_decimal::Decimal>().ok())
                .or_else(|| {
                    v.as_f64()
                        .map(|f| rust_decimal::Decimal::try_from(f).unwrap_or_default())
                })
        })
        .unwrap_or_default();

    let currency = match raw.currency.as_deref() {
        Some("EUR") => Currency::Eur,
        Some("USD") => Currency::Usd,
        Some("GBP") => Currency::Gbp,
        _ => Currency::Eur,
    };

    let transaction_type = match raw.transaction_type.as_deref() {
        Some("WITHDRAWAL") => TransactionType::Withdrawal,
        Some("DEPOSIT") => TransactionType::Deposit,
        Some("TRANSFER") => TransactionType::Transfer,
        _ => TransactionType::Withdrawal,
    };

    let occurred_at = raw
        .occurred_at
        .as_deref()
        .and_then(|s| chrono::DateTime::parse_from_rfc3339(s).ok())
        .map(|dt| dt.with_timezone(&chrono::Utc))
        .unwrap_or_else(chrono::Utc::now);

    info!("eventId={}, eventType={}", event_id, raw.event_type);

    Ok(Some(Transaction {
        id: transaction_id,
        source_account_id,
        target_account_id: raw.target_account_id,
        amount: Money::new(amount_val, currency),
        transaction_type,
        status,
        occurred_at,
    }))
}
