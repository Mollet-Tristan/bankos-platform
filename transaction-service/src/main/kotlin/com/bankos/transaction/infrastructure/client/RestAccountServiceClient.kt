package com.bankos.transaction.infrastructure.client

import com.bankos.transaction.domain.model.AccountId
import com.bankos.transaction.domain.model.Money
import com.bankos.transaction.domain.port.AccountFrozenRemoteException
import com.bankos.transaction.domain.port.AccountServiceClient
import com.bankos.transaction.domain.port.AccountServiceException
import com.bankos.transaction.domain.port.InsufficientFundsRemoteException
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal

/**
 * RestAccountServiceClient — HTTP Adapter
 *
 * Implements the [AccountServiceClient] port using Spring WebClient.
 *
 * ─── Resilience patterns applied ─────────────────────────────────────────────
 *
 * @CircuitBreaker: If Account Service is consistently failing (>50% failure rate
 * over 10 calls), the circuit opens and calls fail fast for 30s.
 * This prevents cascading failures: a down Account Service should not block
 * all Transaction Service threads indefinitely.
 *
 * @Retry: On transient network errors (timeout, 503), retry up to 3 times
 * with exponential backoff. NOT applied to 4xx errors (those are business errors,
 * retrying won't help).
 *
 * ─── JWT forwarding ───────────────────────────────────────────────────────────
 *
 * The JWT from the incoming request is forwarded to Account Service.
 * This allows Account Service to enforce its own @PreAuthorize rules.
 * The gateway acts as the first line of auth; service-to-service calls
 * carry the original user token (not a service account token in this demo).
 *
 * In a production setup, consider a dedicated service account or
 * mTLS for service-to-service authentication.
 * See: TODO ADR-009 — Service-to-service authentication strategy
 */
@Component
class RestAccountServiceClient(
    private val webClientBuilder: WebClient.Builder,
    @Value("\${bankos.account-service.base-url}") private val baseUrl: String,
) : AccountServiceClient {

    private val log = LoggerFactory.getLogger(RestAccountServiceClient::class.java)

    private val client: WebClient by lazy {
        webClientBuilder.baseUrl(baseUrl).build()
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "debitFallback")
    @Retry(name = "accountService")
    override fun debit(accountId: AccountId, amount: Money, reference: String) {
        log.debug("Debiting account={} amount={} ref={}", accountId, amount, reference)
        try {
            client.post()
                .uri("/api/v1/accounts/{id}/debit", accountId.value)
                .bodyValue(mapOf("amount" to amount.value, "reference" to reference))
                .retrieve()
                .toBodilessEntity()
                .block()
        } catch (ex: WebClientResponseException) {
            handleAccountServiceError(accountId, ex)
        }
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "creditFallback")
    @Retry(name = "accountService")
    override fun credit(accountId: AccountId, amount: Money, reference: String) {
        log.debug("Crediting account={} amount={} ref={}", accountId, amount, reference)
        try {
            client.post()
                .uri("/api/v1/accounts/{id}/credit", accountId.value)
                .bodyValue(mapOf("amount" to amount.value, "reference" to reference))
                .retrieve()
                .toBodilessEntity()
                .block()
        } catch (ex: WebClientResponseException) {
            handleAccountServiceError(accountId, ex)
        }
    }

    // ── Error handling ────────────────────────────────────────────────────

    private fun handleAccountServiceError(accountId: AccountId, ex: WebClientResponseException) {
        when (ex.statusCode) {
            HttpStatus.UNPROCESSABLE_ENTITY -> {
                val body = ex.responseBodyAsString
                if (body.contains("Insufficient", ignoreCase = true)) {
                    throw InsufficientFundsRemoteException(accountId, body)
                }
                throw InsufficientFundsRemoteException(accountId, body)
            }
            HttpStatus.CONFLICT -> throw AccountFrozenRemoteException(accountId)
            HttpStatus.NOT_FOUND -> throw AccountServiceException("Account $accountId not found")
            else -> throw AccountServiceException(
                "Account Service error ${ex.statusCode}: ${ex.responseBodyAsString}", ex
            )
        }
    }

    // ── Circuit breaker fallbacks ─────────────────────────────────────────

    @Suppress("UNUSED_PARAMETER")
    fun debitFallback(accountId: AccountId, amount: Money, reference: String, ex: Exception): Unit {
        log.error("Circuit breaker open for debit — account={}", accountId)
        throw AccountServiceException("Account Service is unavailable (circuit open)", ex)
    }

    @Suppress("UNUSED_PARAMETER")
    fun creditFallback(accountId: AccountId, amount: Money, reference: String, ex: Exception): Unit {
        log.error("Circuit breaker open for credit — account={}", accountId)
        throw AccountServiceException("Account Service is unavailable (circuit open)", ex)
    }
}
