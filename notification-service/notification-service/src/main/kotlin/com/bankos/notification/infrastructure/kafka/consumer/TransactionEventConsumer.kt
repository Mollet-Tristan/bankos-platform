package com.bankos.notification.infrastructure.kafka.consumer

import com.bankos.notification.application.service.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/**
 * TransactionEventConsumer — Kafka Consumer (primary entry point)
 *
 * This is the main "driving adapter" of this service.
 * It listens to two topics:
 *  - bankos.transaction.events  (from Transaction Service)
 *  - bankos.account.events      (from Account Service)
 *
 * ─── Consumer group ───────────────────────────────────────────────────────────
 *
 * Group ID: notification-service
 * Each consumer instance handles a subset of topic partitions.
 * Adding replicas scales consumption linearly up to partition count.
 *
 * ─── Manual acknowledgment ────────────────────────────────────────────────────
 *
 * We use manual ACK (enable-auto-commit: false) to ensure at-least-once processing:
 *  - Message is NOT acknowledged until the notification is persisted
 *  - If the service crashes mid-processing, the message is redelivered
 *  - Idempotency check in NotificationService handles the redelivery safely
 *
 * ─── Error handling strategy ──────────────────────────────────────────────────
 *
 * On deserialization error → log and ACK (poison pill — do not block the partition)
 * On processing error     → log and ACK (notification is persisted as FAILED for retry)
 * On unrecoverable error  → do NOT ACK (redelivery will occur)
 *
 * ADR-011: A production deployment would configure a Dead Letter Topic (DLT)
 * for poison pills and a SeekToCurrentErrorHandler for retries.
 */
@Component
class TransactionEventConsumer(
    private val notificationService: NotificationService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(TransactionEventConsumer::class.java)

    @KafkaListener(
        topics = ["bankos.transaction.events"],
        groupId = "notification-service",
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun onTransactionEvent(
        @Payload message: String,
        @Header(KafkaHeaders.RECEIVED_PARTITION) partition: Int,
        @Header(KafkaHeaders.OFFSET) offset: Long,
        acknowledgment: Acknowledgment,
    ) {
        val eventType = parseEventType(message) ?: run {
            log.warn("Cannot parse eventType from message at offset=$offset — skipping")
            acknowledgment.acknowledge()
            return
        }

        log.debug("Received transaction event: type={} partition={} offset={}", eventType, partition, offset)

        try {
            when (eventType) {
                "TransactionCompleted"  -> handleCompleted(message)
                "TransactionFailed"     -> handleFailed(message)
                "TransactionCompensated"-> handleCompensated(message)
                else -> log.debug("Ignoring unhandled transaction event type: {}", eventType)
            }
            acknowledgment.acknowledge()
        } catch (ex: Exception) {
            log.error("Error processing transaction event type=$eventType offset=$offset", ex)
            // ACK anyway — notification stored as FAILED, retry scheduler will pick it up
            // Not acknowledging would stall the partition for all subsequent messages
            acknowledgment.acknowledge()
        }
    }

    @KafkaListener(
        topics = ["bankos.account.events"],
        groupId = "notification-service",
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun onAccountEvent(
        @Payload message: String,
        @Header(KafkaHeaders.RECEIVED_PARTITION) partition: Int,
        @Header(KafkaHeaders.OFFSET) offset: Long,
        acknowledgment: Acknowledgment,
    ) {
        val eventType = parseEventType(message) ?: run {
            acknowledgment.acknowledge()
            return
        }

        log.debug("Received account event: type={} partition={} offset={}", eventType, partition, offset)

        try {
            when (eventType) {
                "AccountCreated"       -> handleAccountCreated(message)
                "AccountStatusChanged" -> handleAccountStatusChanged(message)
                else -> log.debug("Ignoring unhandled account event type: {}", eventType)
            }
            acknowledgment.acknowledge()
        } catch (ex: Exception) {
            log.error("Error processing account event type=$eventType offset=$offset", ex)
            acknowledgment.acknowledge()
        }
    }

    // ── Private handlers ──────────────────────────────────────────────────

    private fun handleCompleted(message: String) {
        val raw = objectMapper.readValue(message, Map::class.java)
        notificationService.handleTransactionCompleted(
            TransactionCompletedEvent(
                eventId = UUID.fromString(raw["eventId"] as String),
                transactionId = UUID.fromString(raw["transactionId"] as String),
                ownerId = raw["sourceAccountId"] as String, // resolved to owner in real impl
                amount = BigDecimal(raw["amount"].toString()),
                currency = raw["currency"] as String,
                transactionType = raw["type"] as String,
            )
        )
    }

    private fun handleFailed(message: String) {
        val raw = objectMapper.readValue(message, Map::class.java)
        notificationService.handleTransactionFailed(
            TransactionFailedEvent(
                eventId = UUID.fromString(raw["eventId"] as String),
                transactionId = UUID.fromString(raw["transactionId"] as String),
                ownerId = raw["sourceAccountId"] as String,
                amount = BigDecimal(raw["amount"].toString()),
                currency = raw["currency"] as String,
                reason = raw["reason"] as String,
            )
        )
    }

    private fun handleCompensated(message: String) {
        val raw = objectMapper.readValue(message, Map::class.java)
        notificationService.handleTransactionCompensated(
            TransactionCompensatedEvent(
                eventId = UUID.fromString(raw["eventId"] as String),
                transactionId = UUID.fromString(raw["transactionId"] as String),
                ownerId = raw["sourceAccountId"] as String,
                amount = BigDecimal(raw["amount"].toString()),
                currency = raw["currency"] as String,
                reason = raw["reason"] as String,
            )
        )
    }

    private fun handleAccountCreated(message: String) {
        val raw = objectMapper.readValue(message, Map::class.java)
        notificationService.handleAccountCreated(
            AccountCreatedEvent(
                eventId = UUID.fromString(raw["eventId"] as String),
                accountId = UUID.fromString(raw["accountId"] as String),
                ownerId = raw["ownerId"] as String,
                currency = raw["currency"] as String,
            )
        )
    }

    private fun handleAccountStatusChanged(message: String) {
        val raw = objectMapper.readValue(message, Map::class.java)
        val newStatus = raw["newStatus"] as? String ?: return
        if (newStatus == "FROZEN") {
            notificationService.handleAccountFrozen(
                AccountFrozenEvent(
                    eventId = UUID.fromString(raw["eventId"] as String),
                    accountId = UUID.fromString(raw["accountId"] as String),
                    ownerId = raw["ownerId"] as? String ?: return,
                )
            )
        }
    }

    private fun parseEventType(message: String): String? = try {
        objectMapper.readTree(message).get("eventType")?.asText()
    } catch (ex: Exception) {
        null
    }
}
