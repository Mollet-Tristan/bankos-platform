package com.bankos.gateway.filter

import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Instant
import java.util.UUID

/**
 * JwtAuthenticationFilterTest
 *
 * Verifies:
 *  - Authenticated requests are enriched with X-User-Id, X-User-Roles, X-User-Email
 *  - Incoming X-User-* headers are stripped (security: clients must not inject these)
 *  - Unauthenticated requests receive 401
 *  - Missing email claim does not cause failure
 */
@Disabled
class JwtAuthenticationFilterTest {

    private val filter = JwtAuthenticationFilter()
    private val chain  = mockk<GatewayFilterChain> {
        every { filter(any()) } returns Mono.empty()
    }

    @Nested
    inner class `Authenticated requests` {

        @Test
        fun `should add X-User-Id from JWT subject`() {
            val userId = UUID.randomUUID().toString()
            val jwt = buildJwt(subject = userId)
            val exchange = buildExchange(jwt)

            StepVerifier.create(
                filter.apply(JwtAuthenticationFilter.Config())
                    .filter(exchange, chain)
                    .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(SecurityContextImpl(JwtAuthenticationToken(jwt)))
                    ))
            ).verifyComplete()

            val forwardedRequest = captureForwardedRequest()
            assertEquals(userId, forwardedRequest?.headers?.getFirst("X-User-Id"))
        }

        @Test
        fun `should add X-User-Roles from JWT realm_access roles`() {
            val jwt = buildJwt(roles = listOf("USER", "BACKOFFICE"))
            val exchange = buildExchange(jwt)

            filter.apply(JwtAuthenticationFilter.Config())
                .filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                    Mono.just(SecurityContextImpl(JwtAuthenticationToken(jwt)))
                ))
                .block()

            val forwardedRequest = captureForwardedRequest()
            val rolesHeader = forwardedRequest?.headers?.getFirst("X-User-Roles")
            assertNotNull(rolesHeader)
            assertTrue(rolesHeader!!.contains("USER"))
            assertTrue(rolesHeader.contains("BACKOFFICE"))
        }

        @Test
        fun `should strip incoming X-User-Id header from client`() {
            val jwt = buildJwt(subject = "real-user-id")
            // Malicious client tries to inject a fake user ID
            val request = MockServerHttpRequest.get("/api/v1/accounts")
                .header("X-User-Id", "injected-fake-id")
                .header("Authorization", "Bearer fake")
                .build()
            val exchange = MockServerWebExchange.from(request)

            filter.apply(JwtAuthenticationFilter.Config())
                .filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                    Mono.just(SecurityContextImpl(JwtAuthenticationToken(jwt)))
                ))
                .block()

            val forwardedRequest = captureForwardedRequest()
            val userId = forwardedRequest?.headers?.getFirst("X-User-Id")
            // Must be the real ID from JWT, not the injected one
            assertEquals("real-user-id", userId)
            assertNotEquals("injected-fake-id", userId)
        }

        @Test
        fun `should add X-User-Email when email claim present`() {
            val jwt = buildJwt(email = "user@bankos.demo")
            val exchange = buildExchange(jwt)

            filter.apply(JwtAuthenticationFilter.Config())
                .filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                    Mono.just(SecurityContextImpl(JwtAuthenticationToken(jwt)))
                ))
                .block()

            val forwardedRequest = captureForwardedRequest()
            assertEquals("user@bankos.demo", forwardedRequest?.headers?.getFirst("X-User-Email"))
        }

        @Test
        fun `should not fail when email claim is absent`() {
            val jwt = buildJwt(email = null)
            val exchange = buildExchange(jwt)

            StepVerifier.create(
                filter.apply(JwtAuthenticationFilter.Config())
                    .filter(exchange, chain)
                    .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(SecurityContextImpl(JwtAuthenticationToken(jwt)))
                    ))
            ).verifyComplete()
        }
    }

    @Nested
    inner class `Unauthenticated requests` {

        @Test
        fun `should return 401 when no security context`() {
            val exchange = buildExchange(null)

            filter.apply(JwtAuthenticationFilter.Config())
                .filter(exchange, chain)
                // No security context in the reactive context
                .block()

            assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun buildJwt(
        subject: String = UUID.randomUUID().toString(),
        roles: List<String> = listOf("USER"),
        email: String? = "test@bankos.demo",
    ): Jwt {
        val claimsBuilder = mapOf(
            "sub" to subject,
            "iss" to "http://localhost:8080/realms/bankos",
            "realm_access" to mapOf("roles" to roles),
        ).toMutableMap()
        if (email != null) claimsBuilder["email"] = email

        return Jwt(
            "token-value",
            Instant.now(),
            Instant.now().plusSeconds(3600),
            mapOf("alg" to "RS256"),
            claimsBuilder,
        )
    }

    private fun buildExchange(jwt: Jwt?): MockServerWebExchange {
        val request = MockServerHttpRequest.get("/api/v1/accounts").build()
        return MockServerWebExchange.from(request)
    }

    private fun captureForwardedRequest(): org.springframework.http.server.reactive.ServerHttpRequest? {
        val slot = slot<org.springframework.web.server.ServerWebExchange>()
        verify { chain.filter(capture(slot)) }
        return slot.captured.request
    }
}
