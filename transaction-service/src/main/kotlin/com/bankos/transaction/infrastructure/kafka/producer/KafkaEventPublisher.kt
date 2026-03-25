package com.bankos.transaction.infrastructure.kafka.producer

import com.bankos.transaction.domain.event.*
import com.bankos.transaction.domain.port.EventPublisher
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * KafkaEventPublisher — implements [EventPublisher] port
 *
 * Topic strategy:
 *  - bankos.transaction.events  → all transaction lifecycle events
 *    Partition key: transactionId (ordering per transaction)
 *
 * Consumers (ADR-004):
 *  - NotificationService  subscribes to TransactionCompleted + TransactionFailed
 *  - AuditService         subscribes to all events
 *  - ReportingCLI (Rust)  subscribes to TransactionCompleted for aggregates
 */
@Component
class KafkaEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) : EventPublisher {

    private val log = LoggerFactory.getLogger(KafkaEventPublisher::class.java)

    companion object {
        const val TOPIC_TRANSACTION_EVENTS = "bankos.transaction.events"
    }

    override fun publish(events: List<TransactionDomainEvent>) {
        events.forEach { event ->
            val message = objectMapper.writeValueAsString(event.toKafkaMessage())
            val key = event.transactionId.toString()
            kafkaTemplate.send(TOPIC_TRANSACTION_EVENTS, key, message)
                .whenComplete { result, ex ->
                    if (ex != null) log.error("Failed to publish ${event::class.simpleName} id=${event.eventId}", ex)
                    else log.debug("Published ${event::class.simpleName} offset=${result.recordMetadata.offset()}")
                }
        }
    }

    private fun TransactionDomainEvent.toKafkaMessage(): Map<String, Any?> = when (this) {
        is TransactionCreated -> mapOf(
            "eventType" to "TransactionCreated",
            "eventId" to eventId, "transactionId" to transactionId.value,
            "sourceAccountId" to sourceAccountId.value,
            "targetAccountId" to targetAccountId?.value,
            "amount" to amount.value, "currency" to amount.currency,
            "type" to type, "idempotencyKey" to idempotencyKey.value,
            "occurredAt" to occurredAt,
        )
        is TransactionCompleted -> mapOf(
            "eventType" to "TransactionCompleted",
            "eventId" to eventId, "transactionId" to transactionId.value,
            "sourceAccountId" to sourceAccountId.value,
            "targetAccountId" to targetAccountId?.value,
            "amount" to amount.value, "currency" to amount.currency,
            "type" to type, "occurredAt" to occurredAt,
        )
        is TransactionFailed -> mapOf(
            "eventType" to "TransactionFailed",
            "eventId" to eventId, "transactionId" to transactionId.value,
            "sourceAccountId" to sourceAccountId.value,
            "amount" to amount.value, "currency" to amount.currency,
            "reason" to reason, "occurredAt" to occurredAt,
        )
        is TransactionCompensated -> mapOf(
            "eventType" to "TransactionCompensated",
            "eventId" to eventId, "transactionId" to transactionId.value,
            "sourceAccountId" to sourceAccountId.value,
            "amount" to amount.value, "currency" to amount.currency,
            "reason" to reason, "occurredAt" to occurredAt,
        )
    }
}
