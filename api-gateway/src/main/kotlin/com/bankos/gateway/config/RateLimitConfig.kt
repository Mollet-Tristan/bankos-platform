package com.bankos.gateway.config

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import reactor.core.publisher.Mono

/**
 * RateLimitConfig — Redis-backed rate limiting configuration.
 *
 * ─── Why rate limit at the gateway? ──────────────────────────────────────────
 *
 * ADR-001: Rate limiting is a cross-cutting concern. If each service
 * implemented its own rate limiting:
 *  - The limits would be per-instance, not per-user across the cluster
 *  - A user could bypass a service's limit by hitting a different instance
 *  - Logic duplication across N services
 *
 * The gateway enforces limits centrally using Redis as shared state.
 * All gateway replicas share the same counters.
 *
 * ─── Token bucket algorithm ───────────────────────────────────────────────────
 *
 * replenishRate  = tokens added per second      (sustained rate)
 * burstCapacity  = maximum tokens in bucket     (peak rate, short bursts allowed)
 * requestedTokens = tokens consumed per request (usually 1)
 *
 * Example (transaction route): replenishRate=5, burstCapacity=10
 *  → Steady state: 5 requests/second
 *  → Burst: up to 10 requests at once if bucket was full
 *  → After burst: refills at 5/second
 *
 * ─── Key resolution strategies ───────────────────────────────────────────────
 *
 * Three key resolvers are provided:
 *  1. userKeyResolver    (primary) — per authenticated user (JWT sub claim)
 *  2. ipKeyResolver      — per IP address (fallback for unauthenticated)
 *  3. routeKeyResolver   — per route (global route-level cap)
 *
 * The route configuration in application.yml selects which resolver to use.
 */
@Configuration
class RateLimitConfig {

    /**
     * Primary key resolver: rate limit per authenticated user (JWT subject).
     *
     * This ensures:
     *  - Each user has their own quota
     *  - A heavy user cannot consume another user's quota
     *  - Horizontal scaling works: all gateway instances share Redis counters
     *
     * Falls back to "anonymous" for unauthenticated requests
     * (those should be blocked by security config, but defence-in-depth).
     */
    @Bean
    @Primary
    fun userKeyResolver(): KeyResolver = KeyResolver { exchange ->
        val principal = exchange.getPrincipal<org.springframework.security.core.Authentication>()
        principal
            .map { it.name }
            .defaultIfEmpty("anonymous")
    }

    /**
     * IP-based key resolver — used for public endpoints where no JWT is available.
     * Less precise than user-based: NAT/proxies can make multiple users share one IP.
     */
    @Bean
    fun ipKeyResolver(): KeyResolver = KeyResolver { exchange ->
        val remoteAddress = exchange.request.remoteAddress?.address?.hostAddress
        Mono.just(remoteAddress ?: "unknown")
    }

    /**
     * Route-level key resolver — caps total traffic regardless of user.
     * Useful to protect a downstream service from thundering herd even
     * if individual user limits are not exceeded.
     */
    @Bean
    fun routeKeyResolver(): KeyResolver = KeyResolver { exchange ->
        val routeId = exchange.getAttribute<String>("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteAttr")
        Mono.just(routeId ?: "unknown-route")
    }

    /**
     * Default RedisRateLimiter bean.
     * Individual routes override replenish/burst in application.yml.
     * This bean is used when no per-route override is specified.
     */
    @Bean
    fun defaultRedisRateLimiter(): RedisRateLimiter =
        RedisRateLimiter(10, 20, 1)
}
