//! HTTP client adapter — implements the `AccountClient` port.
//!
//! Uses `reqwest` with `rustls` (no OpenSSL dependency — fully portable).

use crate::application::ports::AccountClient;
use crate::domain::error::{HttpError, ReportingError};
use crate::domain::model::*;
use async_trait::async_trait;
use reqwest::Client;
use rust_decimal::Decimal;
use serde::Deserialize;
use std::time::Duration;
use tracing::{debug, instrument};

pub struct HttpAccountClient {
    client: Client,
    base_url: String,
    token: Option<String>,
}

impl HttpAccountClient {
    pub fn new(base_url: String, token: Option<String>) -> Self {
        let client = Client::builder()
            .timeout(Duration::from_secs(10))
            .connect_timeout(Duration::from_secs(5))
            // Use rustls — no system OpenSSL dependency
            .use_rustls_tls()
            .build()
            .expect("Failed to build HTTP client");

        Self {
            client,
            base_url,
            token,
        }
    }
}

#[derive(Debug, Deserialize)]
struct BalanceResponse {
    balance: String,
    currency: String,
}

#[derive(Debug, Deserialize)]
struct AccountListResponse {
    content: Vec<AccountItem>,
}

#[derive(Debug, Deserialize)]
struct AccountItem {
    id: String,
}

#[async_trait]
impl AccountClient for HttpAccountClient {
    #[instrument(skip(self), fields(account_id = %account_id))]
    async fn get_balance(&self, account_id: &str) -> Result<Money, ReportingError> {
        let url = format!("{}/api/v1/accounts/{}/balance", self.base_url, account_id);
        debug!("Fetching balance from {}", url);

        let mut req = self.client.get(&url);
        if let Some(token) = &self.token {
            req = req.bearer_auth(token);
        }

        let response = req
            .send()
            .await
            .map_err(|e| ReportingError::Http(HttpError::RequestFailed(e.to_string())))?;

        if !response.status().is_success() {
            return Err(ReportingError::Http(HttpError::UnexpectedStatus {
                status: response.status().as_u16(),
                url: url.clone(),
            }));
        }

        let body: BalanceResponse = response
            .json()
            .await
            .map_err(|e| ReportingError::Http(HttpError::RequestFailed(e.to_string())))?;

        let amount = body.balance.parse::<Decimal>().map_err(|e| {
            ReportingError::Http(HttpError::RequestFailed(format!("Invalid decimal: {}", e)))
        })?;

        let currency = match body.currency.as_str() {
            "EUR" => Currency::Eur,
            "USD" => Currency::Usd,
            "GBP" => Currency::Gbp,
            other => {
                return Err(ReportingError::Http(HttpError::RequestFailed(format!(
                    "Unknown currency: {}",
                    other
                ))))
            }
        };

        Ok(Money::new(amount, currency))
    }

    async fn list_account_ids(&self) -> Result<Vec<String>, ReportingError> {
        let url = format!("{}/api/v1/accounts?size=100", self.base_url);

        let mut req = self.client.get(&url);
        if let Some(token) = &self.token {
            req = req.bearer_auth(token);
        }

        let response = req
            .send()
            .await
            .map_err(|e| ReportingError::Http(HttpError::RequestFailed(e.to_string())))?;

        if !response.status().is_success() {
            return Err(ReportingError::Http(HttpError::UnexpectedStatus {
                status: response.status().as_u16(),
                url,
            }));
        }

        let body: AccountListResponse = response
            .json()
            .await
            .map_err(|e| ReportingError::Http(HttpError::RequestFailed(e.to_string())))?;

        Ok(body.content.into_iter().map(|a| a.id).collect())
    }
}
