package com.bankos.gateway.filter

import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * JwtAuthenticationFilter — Gateway filter applied per route.
 *
 * Applied on routes that require authentication (all /api/** routes).
 *
 * Responsibilities:
 *  1. Verify the security context contains a valid authenticated JWT
 *     (Spring Security has already validated the token — this filter
 *     just rejects if no valid auth was established)
 *  2. Enrich the forwarded request with derived headers:
 *     - X-User-Id     : JWT subject (Keycloak user UUID)
 *     - X-User-Roles  : comma-separated roles from the token
 *     - X-User-Email  : email claim if present
 *  3. Remove the raw Authorization header replacement is NOT done —
 *     the original JWT is forwarded as-is for downstream service validation
 *
 * ─── Why add derived headers? ────────────────────────────────────────────────
 *
 * Downstream services could extract the user ID from the JWT themselves,
 * but that requires JWT parsing on every request. Adding X-User-Id at the
 * gateway means downstream services can trust the header (it was set by
 * the trusted gateway after token validation) and avoid re-parsing.
 *
 * Security note: downstream services must NEVER trust X-User-* headers
 * from external clients. The gateway strips any incoming X-User-* headers
 * before adding its own (see stripIncomingUserHeaders).
 *
 * ─── Trust model ─────────────────────────────────────────────────────────────
 *
 * Gateway → validates JWT → adds X-User-Id → forwards to service
 * Service → trusts X-User-Id because it comes from the gateway's network
 *
 * In a Kubernetes deployment, network policies ensure only the gateway
 * can reach the services. External traffic cannot inject X-User-* headers.
 */
@Component
class JwtAuthenticationFilter : AbstractGatewayFilterFactory<JwtAuthenticationFilter.Config>(Config::class.java) {

    private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    data class Config(val dummy: String = "")

    override fun apply(config: Config): GatewayFilter = GatewayFilter { exchange, chain ->
        ReactiveSecurityContextHolder.getContext()
            .flatMap { securityContext ->
                val authentication = securityContext.authentication
                if (authentication == null || !authentication.isAuthenticated) {
                    log.warn("Unauthenticated request to protected route: ${exchange.request.path}")
                    exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                    return@flatMap exchange.response.setComplete()
                }

                val enrichedExchange = when (authentication) {
                    is JwtAuthenticationToken -> enrichWithJwtClaims(exchange, authentication.token)
                    else -> exchange
                }

                chain.filter(enrichedExchange)
            }
            .switchIfEmpty(
                // No security context at all — should not happen with Spring Security configured
                Mono.defer {
                    log.warn("No security context for request: ${exchange.request.path}")
                    exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                    exchange.response.setComplete()
                }
            )
    }

    private fun enrichWithJwtClaims(exchange: ServerWebExchange, jwt: Jwt): ServerWebExchange {
        val userId = jwt.subject
        val email = jwt.getClaimAsString("email")
        val roles = jwt.getClaimAsStringList("realm_access.roles")
            ?.joinToString(",") ?: ""

        return exchange.mutate()
            .request { request ->
                request
                    // Strip any incoming X-User-* headers (security: clients must not inject these)
                    .headers { headers ->
                        headers.remove("X-User-Id")
                        headers.remove("X-User-Roles")
                        headers.remove("X-User-Email")
                    }
                    // Add gateway-validated user context
                    .header("X-User-Id", userId)
                    .header("X-User-Roles", roles)
                    .apply { if (email != null) header("X-User-Email", email) }
            }
            .build()
    }
}
