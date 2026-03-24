//! Domain layer — pure Rust, no I/O, no async.
//!
//! All business logic for report computation lives here.
//! This module is independently testable without any runtime or network.

pub mod aggregation;
pub mod error;
pub mod model;
