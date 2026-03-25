//! Infrastructure adapters — implement domain ports.
//!
//! Each submodule implements one or more port traits from `application::ports`.

pub mod http;
pub mod kafka;
pub mod persistence;
