package com.bankos.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * ApiGatewayApplication
 *
 * Entry point of the BankOS API Gateway.
 *
 * This application is built on Spring WebFlux (reactive, non-blocking).
 * It uses NO traditional Spring MVC, NO JPA, NO blocking I/O.
 *
 * Why reactive for the gateway?
 *  - The gateway's job is I/O forwarding — it spends most of its time
 *    waiting for upstream responses.
 *  - A reactive (non-blocking) stack handles thousands of concurrent
 *    connections with a small thread pool.
 *  - A blocking stack (MVC) would require one thread per connection,
 *    limiting concurrency under load.
 *
 * See ADR-001 for the full gateway design rationale.
 */
@SpringBootApplication
class ApiGatewayApplication

fun main(args: Array<String>) {
    runApplication<ApiGatewayApplication>(*args)
}
