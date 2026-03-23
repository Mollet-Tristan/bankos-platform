package com.bankos.gateway.config

import com.bankos.gateway.filter.JwtAuthenticationFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * GatewayRoutesConfig — Routes defined as code (Java DSL).
 *
 * This is an ALTERNATIVE to the YAML route configuration in application.yml.
 * Both approaches are valid. This class demonstrates the code-first approach,
 * which provides:
 *  - Type safety (no YAML typos)
 *  - IDE navigation and refactoring support
 *  - Conditional routes via Spring @Profile or @ConditionalOn*
 *  - Easier unit testing (routes can be instantiated without full context)
 *
 * ─── YAML vs Code — ADR note ──────────────────────────────────────────────────
 *
 * For simple, static routes: YAML is more readable and ops-friendly
 * (can be updated without recompilation via ConfigMap in Kubernetes).
 *
 * For complex routes with conditional logic: the Java DSL is preferable.
 *
 * This class is active only under the "code-routes" Spring profile.
 * The default configuration uses YAML routes (application.yml).
 * The two approaches are mutually exclusive — use one or the other.
 *
 * Keeping this class in the codebase demonstrates both approaches
 * and documents the trade-off — intentional for portfolio purposes.
 *
 * @Profile("code-routes") — activate with: --spring.profiles.active=code-routes
 */
@Configuration
@Profile("code-routes")
class GatewayRoutesConfig(
    private val jwtAuthFilter: JwtAuthenticationFilter,
    @Value("\${ACCOUNT_SERVICE_URL:http://localhost:8081}") private val accountServiceUrl: String,
    @Value("\${TRANSACTION_SERVICE_URL:http://localhost:8082}") private val transactionServiceUrl: String,
    @Value("\${NOTIFICATION_SERVICE_URL:http://localhost:8083}") private val notificationServiceUrl: String,
) {

    @Bean
    fun gatewayRoutes(builder: RouteLocatorBuilder): RouteLocator =
        builder.routes()

            // ── Account Service ──────────────────────────────────────────
            .route("account-service") { route ->
                route
                    .path("/api/v1/accounts/**")
                    .filters { filter ->
                        filter
                            .filter(jwtAuthFilter.apply(JwtAuthenticationFilter.Config()))
                            .requestRateLimiter { limiter ->
                                limiter.rateLimiter = org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter(20, 40, 1)
                            }
                            .circuitBreaker { cb ->
                                cb.name = "accountServiceCB"
                                cb.fallbackUri = "forward:/fallback/account"
                            }
                            .removeRequestHeader("X-Internal-Timing")
                    }
                    .uri(accountServiceUrl)
            }

            // ── Transaction Service ──────────────────────────────────────
            .route("transaction-service") { route ->
                route
                    .path("/api/v1/transactions/**")
                    .filters { filter ->
                        filter
                            .filter(jwtAuthFilter.apply(JwtAuthenticationFilter.Config()))
                            .requestRateLimiter { limiter ->
                                // Stricter limit for financial operations
                                limiter.rateLimiter = org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter(5, 10, 1)
                            }
                            .circuitBreaker { cb ->
                                cb.name = "transactionServiceCB"
                                cb.fallbackUri = "forward:/fallback/transaction"
                            }
                    }
                    .uri(transactionServiceUrl)
            }

            // ── Notification Service ─────────────────────────────────────
            .route("notification-service") { route ->
                route
                    .path("/api/v1/notifications/**")
                    .filters { filter ->
                        filter
                            .filter(jwtAuthFilter.apply(JwtAuthenticationFilter.Config()))
                            .requestRateLimiter { limiter ->
                                limiter.rateLimiter = org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter(10, 20, 1)
                            }
                            .circuitBreaker { cb ->
                                cb.name = "notificationServiceCB"
                                cb.fallbackUri = "forward:/fallback/notification"
                            }
                    }
                    .uri(notificationServiceUrl)
            }

            .build()
}
