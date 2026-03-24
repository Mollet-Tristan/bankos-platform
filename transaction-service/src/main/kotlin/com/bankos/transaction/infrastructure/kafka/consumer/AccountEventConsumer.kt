package com.bankos.transaction.infrastructure.kafka.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component

/**
 * AccountEventConsumer — Kafka Consumer (infrastructure, driven side)
 *
 * This service optionally listens to Account Service events for internal
 * enrichment — e.g. detecting that an account was FROZEN while a transaction
 * is PENDING, allowing proactive FAILED transition without waiting for the
 * REST call to fail.
 *
 * ─── Why consume account events? ─────────────────────────────────────────────
 *
 * This is an example of the "choreography" side of a hybrid saga:
 * rather than polling or being notified by REST callback, this service
 * reacts to state changes in Account Service autonomously.
 *
 * In the current implementation this is informational / logging only.
 * A full implementation would update a local read model of account statuses
 * to short-circuit transactions against frozen/closed accounts before
 * even calling the Account Service REST API.
 *
 * Pattern: Local read model / anti-corruption cache
 * Trade-off documented in ADR-007.
 *
 * ─── Consumer group ───────────────────────────────────────────────────────────
 *
 * Group ID: transaction-service-account-events
 * Each instance in the group processes a distinct partition subset.
 * Horizontal scaling: adding pods automatically rebalances partitions.
 */
@Component
class AccountEventConsumer(
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AccountEventConsumer::class.java)

    @KafkaListener(
        topics = ["bankos.account.events"],
        groupId = "transaction-service-account-events",
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun onAccountEvent(
        @Payload message: String,
        @Header(KafkaHeaders.RECEIVED_PARTITION) partition: Int,
        @Header(KafkaHeaders.OFFSET) offset: Long,
        acknowledgment: Acknowledgment,
    ) {
        try {
            val event = objectMapper.readValue(message, Map::class.java)
            val eventType = event["eventType"] as? String ?: "UNKNOWN"
            val accountId = event["accountId"]

            log.debug("Received account event: type={} accountId={} partition={} offset={}",
                eventType, accountId, partition, offset)

            when (eventType) {
                "AccountStatusChanged" -> handleAccountStatusChanged(event)
                "AccountCreated"       -> log.info("Account created: {}", accountId)
                else                   -> log.debug("Ignoring event type: {}", eventType)
            }

            acknowledgment.acknowledge()
        } catch (ex: Exception) {
            log.error("Failed to process account event at offset={}", offset, ex)
            // Do NOT acknowledge — will be retried or sent to DLT
            // In production: configure a DeadLetterPublishingRecoverer
        }
    }

    private fun handleAccountStatusChanged(event: Map<*, *>) {
        val accountId = event["accountId"]
        val newStatus = event["newStatus"]
        log.info("Account status changed: accountId={} newStatus={}", accountId, newStatus)

        // TODO: update local account status cache
        // This would allow `TransactionService` to short-circuit calls
        // to frozen/closed accounts without an extra REST round-trip.
        // See ADR-007 for the full discussion.
    }
}
