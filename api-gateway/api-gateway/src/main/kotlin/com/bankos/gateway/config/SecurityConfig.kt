package com.bankos.gateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity.OAuth2ResourceServerSpec
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.server.SecurityWebFilterChain

/**
 * SecurityConfig — Gateway security configuration.
 *
 * ─── Responsibility split (ADR-001) ───────────────────────────────────────────
 *
 * The gateway:
 *  ✅ Validates JWT signature and expiry
 *  ✅ Extracts roles from the token
 *  ✅ Rejects requests with invalid/expired tokens (401)
 *  ✅ Rejects requests where the role does not match the route (403)
 *  ✅ Forwards the original Authorization header to downstream services
 *
 * Downstream services:
 *  ✅ Trust the forwarded JWT (they re-validate locally with JWKS)
 *  ✅ Apply fine-grained @PreAuthorize rules on their own endpoints
 *  ❌ Do NOT call Keycloak for each request (that would create N×M load)
 *
 * The two-layer validation (gateway coarse + service fine-grained) provides
 * defence-in-depth: even if the gateway is misconfigured, a service with
 * @PreAuthorize("hasRole('ADMIN')") will still reject unauthorised callers.
 *
 * ─── Why not put all auth in the gateway? ────────────────────────────────────
 *
 * Fine-grained rules (e.g. "a USER can only see their own accounts, not others'")
 * require business context (ownership checks) that the gateway does not have.
 * Those rules live in the service that owns the data.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .authorizeExchange { exchanges ->
                // Public paths — no JWT required
                exchanges.pathMatchers(
                    "/actuator/health/**",
                    "/actuator/info",
                    "/fallback/**",
                ).permitAll()

                // All API routes require authentication
                exchanges.pathMatchers("/api/**").authenticated()

                // Everything else — authenticated
                exchanges.anyExchange().authenticated()
            }
            .oauth2ResourceServer { oauth2: OAuth2ResourceServerSpec ->
                oauth2.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(grantedAuthoritiesExtractor())
                }
            }
            .build()
    }

    /**
     * Maps Keycloak realm roles to Spring Security authorities.
     *
     * Keycloak puts roles in the JWT claim: realm_access.roles = ["USER", "BACKOFFICE"]
     * Spring Security expects: ROLE_USER, ROLE_BACKOFFICE
     *
     * This mapping is shared with downstream services (same Keycloak realm).
     */
    @Bean
    fun grantedAuthoritiesExtractor(): ReactiveJwtAuthenticationConverterAdapter {
        val grantedAuthoritiesConverter = JwtGrantedAuthoritiesConverter().apply {
            setAuthoritiesClaimName("realm_access.roles")
            setAuthorityPrefix("ROLE_")
        }
        val jwtAuthenticationConverter = JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter)
        }
        return ReactiveJwtAuthenticationConverterAdapter(jwtAuthenticationConverter)
    }
}
