package com.bankos.gateway.actuator

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.ReactiveHealthIndicator
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * DownstreamHealthIndicator — Custom health indicator for the gateway.
 *
 * Exposes /actuator/health with the health of each downstream service.
 * This allows Kubernetes liveness/readiness probes to detect degradation
 * in the platform without checking each service individually.
 *
 * ─── Why aggregate health at the gateway? ────────────────────────────────────
 *
 * A load balancer or orchestrator watching only /actuator/health/liveness
 * can understand the full platform health from a single endpoint.
 * Operations teams get a single dashboard view.
 *
 * The gateway is NOT considered unhealthy if a downstream service is down
 * (it would fall back via circuit breaker). But the aggregate health
 * surface the degradation for alerting.
 *
 * ─── Why not use Spring Cloud Gateway's built-in discovery? ──────────────────
 *
 * In this demo we use static service URLs (no service registry like Consul/Eureka).
 * A production setup with Kubernetes would use:
 *  - Kubernetes Service DNS (bankos-account-service.bankos.svc.cluster.local)
 *  - Or a service mesh (Istio) for mTLS + discovery
 *
 * See ADR-001 for the service discovery strategy discussion.
 */
@Component("downstreamServices")
class DownstreamHealthIndicator(
    private val webClientBuilder: WebClient.Builder,
) : ReactiveHealthIndicator {

    private val serviceUrls = mapOf(
        "account-service"      to System.getenv().getOrDefault("ACCOUNT_SERVICE_URL", "http://localhost:8081"),
        "transaction-service"  to System.getenv().getOrDefault("TRANSACTION_SERVICE_URL", "http://localhost:8082"),
        "notification-service" to System.getenv().getOrDefault("NOTIFICATION_SERVICE_URL", "http://localhost:8083"),
    )

    override fun health(): Mono<Health> {
        val checks = serviceUrls.map { (name, url) -> checkService(name, url) }

        return Mono.zip(checks) { results ->
            val statuses = results.map { it as ServiceStatus }
            val allUp = statuses.all { it.up }

            val builder = if (allUp) Health.up() else Health.down()
            statuses.forEach { status ->
                builder.withDetail(
                    status.name,
                    mapOf("status" to if (status.up) "UP" else "DOWN", "error" to status.error),
                )
            }
            builder.build()
        }
    }

    private fun checkService(name: String, baseUrl: String): Mono<ServiceStatus> {
        return webClientBuilder.build()
            .get()
            .uri("$baseUrl/actuator/health/liveness")
            .retrieve()
            .toBodilessEntity()
            .timeout(Duration.ofSeconds(3))
            .map { ServiceStatus(name, up = true, error = null) }
            .onErrorResume { ex ->
                Mono.just(ServiceStatus(name, up = false, error = ex.message?.take(100)))
            }
    }

    private data class ServiceStatus(val name: String, val up: Boolean, val error: String?)
}
