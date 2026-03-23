//! Domain errors — typed error hierarchy using `thiserror`.
//!
//! `thiserror` generates `std::error::Error` implementations from
//! annotated enums. This is idiomatic Rust error handling:
//!  - Errors are types, not strings
//!  - Pattern matching on error variants is exhaustive
//!  - No string parsing to determine error kind

use thiserror::Error;

/// Top-level error type for the reporting service.
///
/// Each variant wraps a more specific error from the corresponding layer.
/// `anyhow::Error` is used at the application boundary (main, CLI handlers)
/// for ergonomic error propagation with `?`.
#[derive(Debug, Error)]
pub enum ReportingError {
    #[error("Invalid period: {0}")]
    InvalidPeriod(String),

    #[error("No transactions found for the given period")]
    NoData,

    #[error("Kafka error: {0}")]
    Kafka(#[from] KafkaError),

    #[error("HTTP client error: {0}")]
    Http(#[from] HttpError),

    #[error("Persistence error: {0}")]
    Persistence(#[from] PersistenceError),

    #[error("Configuration error: {0}")]
    Config(String),

    #[error("Currency mismatch: cannot aggregate {0} and {1}")]
    CurrencyMismatch(String, String),
}

#[derive(Debug, Error)]
pub enum KafkaError {
    #[error("Failed to connect to broker: {0}")]
    ConnectionFailed(String),

    #[error("Failed to consume message: {0}")]
    ConsumeFailed(String),

    #[error("Failed to deserialize event: {0}")]
    DeserializationFailed(String),
}

#[derive(Debug, Error)]
pub enum HttpError {
    #[error("Request failed: {0}")]
    RequestFailed(String),

    #[error("Service unavailable: {service}")]
    ServiceUnavailable { service: String },

    #[error("Unexpected response status {status} from {url}")]
    UnexpectedStatus { status: u16, url: String },
}

#[derive(Debug, Error)]
pub enum PersistenceError {
    #[error("SQLite error: {0}")]
    SqliteError(String),

    #[error("Migration failed: {0}")]
    MigrationFailed(String),

    #[error("Record not found: {0}")]
    NotFound(String),
}
