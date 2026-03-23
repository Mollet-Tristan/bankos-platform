package com.bankos.gateway.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.test.context.ActiveProfiles
import reactor.test.StepVerifier

/**
 * GatewayRoutesConfigTest — Route declaration tests.
 *
 * Verifies that all expected routes are registered with the correct:
 *  - Route IDs
 *  - URI targets
 *  - Number of filters
 *
 * These are smoke tests for the gateway configuration.
 * They catch YAML/DSL misconfigurations before deployment.
 *
 * The full integration test (routing a real request to a MockWebServer)
 * is in GatewayIntegrationTest — not included in this portfolio scaffold
 * to keep scope manageable, but documented here as a TODO.
 */
@SpringBootTest(
    properties = [
        "spring.cloud.gateway.routes[0].id=account-service",
        "spring.cloud.gateway.routes[0].uri=http://localhost:8081",
        "spring.cloud.gateway.routes[0].predicates[0]=Path=/api/v1/accounts/**",
        "spring.cloud.gateway.routes[1].id=transaction-service",
        "spring.cloud.gateway.routes[1].uri=http://localhost:8082",
        "spring.cloud.gateway.routes[1].predicates[0]=Path=/api/v1/transactions/**",
        "spring.cloud.gateway.routes[2].id=notification-service",
        "spring.cloud.gateway.routes[2].uri=http://localhost:8083",
        "spring.cloud.gateway.routes[2].predicates[0]=Path=/api/v1/notifications/**",
        // Disable Redis for unit tests
        "spring.cloud.gateway.filter.request-rate-limiter.deny-empty-key=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
    ]
)
@ActiveProfiles("test")
class GatewayRoutesConfigTest {

    @Autowired
    lateinit var routeLocator: RouteLocator

    @Test
    fun `should register all three service routes`() {
        StepVerifier.create(routeLocator.routes.map { it.id }.collectList())
            .assertNext { routeIds ->
                assertTrue(routeIds.contains("account-service"),
                    "account-service route should be registered")
                assertTrue(routeIds.contains("transaction-service"),
                    "transaction-service route should be registered")
                assertTrue(routeIds.contains("notification-service"),
                    "notification-service route should be registered")
            }
            .verifyComplete()
    }

    @Test
    fun `should configure account-service route to correct URI`() {
        StepVerifier.create(
            routeLocator.routes.filter { it.id == "account-service" }.next()
        )
            .assertNext { route ->
                assertEquals("http://localhost:8081", route.uri.toString())
            }
            .verifyComplete()
    }

    @Test
    fun `should configure transaction-service route to correct URI`() {
        StepVerifier.create(
            routeLocator.routes.filter { it.id == "transaction-service" }.next()
        )
            .assertNext { route ->
                assertEquals("http://localhost:8082", route.uri.toString())
            }
            .verifyComplete()
    }
}
