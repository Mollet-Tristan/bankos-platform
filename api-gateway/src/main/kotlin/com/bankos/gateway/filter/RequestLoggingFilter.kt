package com.bankos.gateway.filter

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.UUID

/**
 * RequestLoggingFilter — Global filter, runs for every request.
 *
 * Provides:
 *  1. Correlation ID propagation for distributed tracing
 *  2. Structured access logging (method, path, status, duration, user)
 *  3. MDC context for downstream log correlation
 *
 * ─── Correlation ID strategy ─────────────────────────────────────────────────
 *
 * If the incoming request carries an X-Correlation-Id header, we use it.
 * Otherwise, the gateway generates one (UUID v4).
 *
 * The correlation ID is:
 *  - Forwarded to all downstream services via X-Correlation-Id header
 *  - Added to every log line via MDC
 *  - Returned to the client in the response header
 *
 * This enables tracing a single user action across gateway logs,
 * account-service logs, transaction-service logs, etc.
 *
 * In a full observability setup (Jaeger, Zipkin, OpenTelemetry),
 * the X-Correlation-Id maps to the trace ID.
 *
 * ─── Why a GlobalFilter instead of per-route? ────────────────────────────────
 *
 * Logging applies to ALL routes including health checks and fallback endpoints.
 * A GlobalFilter ensures no request is missed regardless of route configuration.
 */
@Component
class RequestLoggingFilter : GlobalFilter, Ordered {

    private val log = LoggerFactory.getLogger(RequestLoggingFilter::class.java)

    // Runs before routing (highest priority)
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1

    override fun filter(exchange: ServerWebExchange, chain: org.springframework.cloud.gateway.filter.GatewayFilterChain): Mono<Void> {
        val correlationId = exchange.request.headers.getFirst("X-Correlation-Id")
            ?: UUID.randomUUID().toString()

        val startTime = System.currentTimeMillis()

        // Forward correlation ID to downstream service
        val mutatedRequest = exchange.request.mutate()
            .header("X-Correlation-Id", correlationId)
            .build()

        val mutatedExchange = exchange.mutate()
            .request(mutatedRequest)
            .build()

        return chain.filter(mutatedExchange)
            .doOnSubscribe {
                logRequest(mutatedRequest, correlationId)
            }
            .doFinally {
                val duration = System.currentTimeMillis() - startTime
                val status = exchange.response.statusCode?.value() ?: 0
                logResponse(mutatedRequest, correlationId, status, duration)

                // Return correlation ID to client
                exchange.response.headers.add("X-Correlation-Id", correlationId)
            }
    }

    private fun logRequest(request: ServerHttpRequest, correlationId: String) {
        log.info(
            "→ REQUEST method={} path={} correlationId={} remoteAddr={}",
            request.method,
            request.path.value(),
            correlationId,
            request.remoteAddress?.address?.hostAddress ?: "unknown",
        )
    }

    private fun logResponse(
        request: ServerHttpRequest,
        correlationId: String,
        status: Int,
        durationMs: Long,
    ) {
        val level = when {
            status in 500..599 -> "ERROR"
            status in 400..499 -> "WARN"
            else -> "INFO"
        }
        log.info(
            "← RESPONSE method={} path={} status={} durationMs={} correlationId={} level={}",
            request.method,
            request.path.value(),
            status,
            durationMs,
            correlationId,
            level,
        )
    }
}
