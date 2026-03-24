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
