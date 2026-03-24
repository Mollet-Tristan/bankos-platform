//! # BankOS Reporting Service
//!
//! A Rust CLI for financial analytics over the BankOS platform.
//!
//! ## Architecture
//!
//! This crate follows the same Hexagonal Architecture as the Kotlin services,
//! adapted to Rust idioms:
//!
//! ```text
//! ┌─────────────────────────────────────────────┐
//! │              domain/                        │
//! │  Transaction, Account, Money, Period        │
//! │  Pure Rust structs — no I/O, no async       │
//! │  Computation functions (aggregations)       │
//! └─────────────────┬───────────────────────────┘
//!                   │
//! ┌─────────────────▼───────────────────────────┐
//! │            application/                     │
//! │  ReportService — orchestrates domain        │
//! │  + infrastructure ports (traits)            │
//! └─────────────────┬───────────────────────────┘
//!                   │
//! ┌─────────────────▼───────────────────────────┐
//! │          infrastructure/                    │
//! │  kafka/    — Kafka consumer                 │
//! │  http/     — Account Service REST client    │
//! │  persistence/ — SQLite report store         │
//! └─────────────────────────────────────────────┘
//! ```
//!
//! ## Why Rust? (ADR-005)
//!
//! This service handles CPU-bound aggregations over potentially millions of
//! transaction records. Rust's advantages here:
//!
//! - **No GC pauses**: aggregation loops run uninterrupted
//! - **Memory efficiency**: no JVM overhead, < 10MB RSS for the binary
//! - **Embeddable**: the lib can be compiled to WASM for browser-side analytics
//! - **Portfolio signal**: demonstrates polyglot capability

pub mod domain;
pub mod application;
pub mod infrastructure;
