package com.bankos.account.infrastructure.kafka

import com.bankos.account.domain.event.*
import com.bankos.account.domain.port.EventPublisher
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * KafkaEventPublisher — implements the domain port [EventPublisher]
 *
 * ADR-004: Domain events from the Account aggregate are published
 * asynchronously to Kafka topics. Downstream services (NotificationService,
 * AuditService) consume these events independently.
 *
 * Why Kafka here (and not REST)?
 *  - The notification does not need to block the transaction response
 *  - The notification service can be down without impacting account operations
 *  - Kafka provides natural event replay for audit/debugging
 *  - Independent scalability: notification spikes don't affect account throughput
 *
 * Trade-off acknowledged (documented in ADR-004):
 *  - Eventual consistency: notification may arrive slightly after the transaction
 *  - Operational complexity: Kafka cluster must be managed
 *  - For true at-least-once delivery, the Outbox pattern should be applied
 *    (TODO: ADR-006 — Outbox pattern implementation)
 */
@Component
class KafkaEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) : EventPublisher {

    private val log = LoggerFactory.getLogger(KafkaEventPublisher::class.java)

    companion object {
        const val TOPIC_ACCOUNT_EVENTS = "bankos.account.events"
    }

    override fun publish(events: List<AccountDomainEvent>) {
        events.forEach { event ->
            try {
                val message = objectMapper.writeValueAsString(event.toKafkaMessage())
                val key = event.accountId.toString()

                kafkaTemplate.send(TOPIC_ACCOUNT_EVENTS, key, message)
                    .whenComplete { result, ex ->
                        if (ex != null) {
                            log.error("Failed to publish event ${event.eventId} to Kafka", ex)
                        } else {
                            log.debug(
                                "Published event=${event::class.simpleName} " +
                                "id=${event.eventId} offset=${result.recordMetadata.offset()}"
                            )
                        }
                    }
            } catch (ex: Exception) {
                log.error("Error serializing event ${event.eventId}", ex)
            }
        }
    }

    private fun AccountDomainEvent.toKafkaMessage(): Map<String, Any?> = when (this) {
        is AccountCreated -> mapOf(
            "eventType" to "AccountCreated",
            "eventId" to eventId,
            "accountId" to accountId.value,
            "ownerId" to ownerId,
            "currency" to currency,
            "initialDeposit" to initialDeposit,
            "occurredAt" to occurredAt,
        )
        is AccountDebited -> mapOf(
            "eventType" to "AccountDebited",
            "eventId" to eventId,
            "accountId" to accountId.value,
            "amount" to amount,
            "balanceAfter" to balanceAfter,
            "reference" to reference,
            "occurredAt" to occurredAt,
        )
        is AccountCredited -> mapOf(
            "eventType" to "AccountCredited",
            "eventId" to eventId,
            "accountId" to accountId.value,
            "amount" to amount,
            "balanceAfter" to balanceAfter,
            "reference" to reference,
            "occurredAt" to occurredAt,
        )
        is AccountStatusChanged -> mapOf(
            "eventType" to "AccountStatusChanged",
            "eventId" to eventId,
            "accountId" to accountId.value,
            "previousStatus" to previousStatus,
            "newStatus" to newStatus,
            "occurredAt" to occurredAt,
        )
    }
}
