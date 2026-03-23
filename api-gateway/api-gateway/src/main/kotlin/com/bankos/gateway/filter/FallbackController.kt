package com.bankos.gateway.filter

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Instant

/**
 * FallbackController — Circuit breaker fallback endpoints.
 *
 * When a downstream service's circuit is OPEN, Spring Cloud Gateway
 * forwards the request to these fallback URIs instead of timing out.
 *
 * ─── Why structured fallback responses? ──────────────────────────────────────
 *
 * A circuit breaker that just returns 503 with no body forces clients
 * to guess what failed. RFC 7807 Problem Details provides:
 *  - A machine-readable `type` URI for programmatic handling
 *  - A human-readable `title` and `detail`
 *  - The `service` field so clients know which upstream failed
 *
 * ─── Fallback vs retry ────────────────────────────────────────────────────────
 *
 * The circuit breaker OPENS after repeated failures to protect both:
 *  - The gateway (avoids thread exhaustion waiting for timeouts)
 *  - The failing service (gives it time to recover without load)
 *
 * During OPEN state, requests fail immediately (~1ms) instead of waiting
 * for the downstream timeout (~30s). This is the core value of the pattern.
 *
 * Clients should implement their own retry with exponential backoff
 * and respect the Retry-After header when present.
 *
 * See ADR-001 for circuit breaker configuration rationale.
 */
@RestController
@RequestMapping("/fallback")
class FallbackController {

    private val log = LoggerFactory.getLogger(FallbackController::class.java)

    @GetMapping("/account")
    @PostMapping("/account")
    fun accountFallback(exchange: ServerWebExchange): Mono<ProblemDetail> {
        logFallback("account-service", exchange)
        return Mono.just(serviceFallbackProblem(
            service = "account-service",
            detail = "The Account Service is temporarily unavailable. Please retry in a few seconds.",
        ))
    }

    @GetMapping("/transaction")
    @PostMapping("/transaction")
    fun transactionFallback(exchange: ServerWebExchange): Mono<ProblemDetail> {
        logFallback("transaction-service", exchange)
        return Mono.just(serviceFallbackProblem(
            service = "transaction-service",
            detail = "The Transaction Service is temporarily unavailable. Your transaction was NOT processed. Please retry with the same Idempotency-Key.",
        ))
    }

    @GetMapping("/notification")
    @PostMapping("/notification")
    fun notificationFallback(exchange: ServerWebExchange): Mono<ProblemDetail> {
        logFallback("notification-service", exchange)
        return Mono.just(serviceFallbackProblem(
            service = "notification-service",
            detail = "The Notification Service is temporarily unavailable.",
        ))
    }

    private fun serviceFallbackProblem(service: String, detail: String) =
        ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE).apply {
            type = URI.create("https://api.bankos.demo/problems/service-unavailable")
            title = "Service Unavailable"
            this.detail = detail
            setProperty("service", service)
            setProperty("timestamp", Instant.now())
            setProperty("retryAfter", "30s")
        }

    private fun logFallback(service: String, exchange: ServerWebExchange) {
        log.warn(
            "Circuit breaker fallback triggered: service={} method={} path={}",
            service,
            exchange.request.method,
            exchange.request.path.value(),
        )
    }
}
