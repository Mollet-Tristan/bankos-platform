package com.bankos.notification.infrastructure.kafka

import com.bankos.notification.application.service.*
import com.bankos.notification.infrastructure.kafka.consumer.TransactionEventConsumer
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.Acknowledgment
import java.util.UUID

/**
 * TransactionEventConsumerTest — Kafka consumer layer tests.
 *
 * Verifies:
 *  - Correct routing of event types to service methods
 *  - Message is always ACKed (even on processing error — avoid partition stall)
 *  - Malformed messages (poison pills) are ACKed and skipped
 *  - Unknown event types are ignored gracefully
 *
 * We do NOT test Kafka connectivity here — that belongs to integration tests.
 * We test the consumer logic in isolation: JSON parsing + delegation + ACK.
 */
class TransactionEventConsumerTest {

    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val objectMapper = ObjectMapper().apply {
        findAndRegisterModules()
    }
    private val acknowledgment = mockk<Acknowledgment>(relaxed = true)

    private lateinit var consumer: TransactionEventConsumer

    @BeforeEach
    fun setUp() {
        consumer = TransactionEventConsumer(notificationService, objectMapper)
    }

    // ── Transaction event routing ─────────────────────────────────────────

    @Nested
    inner class `Transaction event routing` {

        @Test
        fun `should call handleTransactionCompleted for TransactionCompleted event`() {
            val message = transactionCompletedJson()

            consumer.onTransactionEvent(message, partition = 0, offset = 1L, acknowledgment)

            verify(exactly = 1) { notificationService.handleTransactionCompleted(any()) }
            verify(exactly = 1) { acknowledgment.acknowledge() }
        }

        @Test
        fun `should call handleTransactionFailed for TransactionFailed event`() {
            val message = transactionFailedJson()

            consumer.onTransactionEvent(message, partition = 0, offset = 2L, acknowledgment)

            verify(exactly = 1) { notificationService.handleTransactionFailed(any()) }
            verify(exactly = 1) { acknowledgment.acknowledge() }
        }

        @Test
        fun `should call handleTransactionCompensated for TransactionCompensated event`() {
            val message = transactionCompensatedJson()

            consumer.onTransactionEvent(message, partition = 0, offset = 3L, acknowledgment)

            verify(exactly = 1) { notificationService.handleTransactionCompensated(any()) }
            verify(exactly = 1) { acknowledgment.acknowledge() }
        }

        @Test
        fun `should ignore unknown transaction event type without error`() {
            val message = unknownEventJson("TransactionArchived")

            consumer.onTransactionEvent(message, partition = 0, offset = 4L, acknowledgment)

            verify(exactly = 0) { notificationService.handleTransactionCompleted(any()) }
            verify(exactly = 0) { notificationService.handleTransactionFailed(any()) }
            // Still acknowledged — unknown types must not stall the partition
            verify(exactly = 1) { acknowledgment.acknowledge() }
        }
    }

    // ── Account event routing ─────────────────────────────────────────────

    @Nested
    inner class `Account event routing` {

        @Test
        fun `should call handleAccountCreated for AccountCreated event`() {
            val message = accountCreatedJson()

            consumer.onAccountEvent(message, partition = 0, offset = 5L, acknowledgment)

            verify(exactly = 1) { notificationService.handleAccountCreated(any()) }
            verify(exactly = 1) { acknowledgment.acknowledge() }
        }

        @Test
        fun `should call handleAccountFrozen for AccountStatusChanged with FROZEN status`() {
            val message = accountStatusChangedJson(newStatus = "FROZEN")

            consumer.onAccountEvent(message, partition = 0, offset = 6L, acknowledgment)

            verify(exactly = 1) { notificationService.handleAccountFrozen(any()) }
        }

        @Test
        fun `should NOT call handleAccountFrozen for AccountStatusChanged with ACTIVE status`() {
            val message = accountStatusChangedJson(newStatus = "ACTIVE")

            consumer.onAccountEvent(message, partition = 0, offset = 7L, acknowledgment)

            verify(exactly = 0) { notificationService.handleAccountFrozen(any()) }
            // Still acknowledged
            verify(exactly = 1) { acknowledgment.acknowledge() }
        }
    }

    // ── Resilience: poison pills and processing errors ────────────────────

    @Nested
    inner class `Resilience — never stall a partition` {

        @Test
        fun `should ACK and skip malformed JSON (poison pill)`() {
            val poisonPill = "{ this is not valid json !!!"

            consumer.onTransactionEvent(poisonPill, partition = 0, offset = 99L, acknowledgment)

            // Must ACK — a non-ACK would stall the partition for all subsequent messages
            verify(exactly = 1) { acknowledgment.acknowledge() }
            verify(exactly = 0) { notificationService.handleTransactionCompleted(any()) }
        }

        @Test
        fun `should ACK even when service throws an exception`() {
            every { notificationService.handleTransactionCompleted(any()) } throws
                RuntimeException("Unexpected DB error")

            consumer.onTransactionEvent(
                transactionCompletedJson(), partition = 0, offset = 100L, acknowledgment,
            )

            // The notification is persisted as FAILED by the service before throwing,
            // but even if it throws here, we still ACK to avoid stalling the partition.
            verify(exactly = 1) { acknowledgment.acknowledge() }
        }

        @Test
        fun `should ACK message with missing eventType field`() {
            val noType = """{"transactionId":"${UUID.randomUUID()}","amount":100}"""

            consumer.onTransactionEvent(noType, partition = 0, offset = 101L, acknowledgment)

            verify(exactly = 1) { acknowledgment.acknowledge() }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun transactionCompletedJson() = """
        {
          "eventType": "TransactionCompleted",
          "eventId": "${UUID.randomUUID()}",
          "transactionId": "${UUID.randomUUID()}",
          "sourceAccountId": "user-test-123",
          "amount": "200.00",
          "currency": "EUR",
          "type": "WITHDRAWAL",
          "occurredAt": "2024-01-15T10:00:00Z"
        }
    """.trimIndent()

    private fun transactionFailedJson() = """
        {
          "eventType": "TransactionFailed",
          "eventId": "${UUID.randomUUID()}",
          "transactionId": "${UUID.randomUUID()}",
          "sourceAccountId": "user-test-123",
          "amount": "200.00",
          "currency": "EUR",
          "reason": "Insufficient funds",
          "occurredAt": "2024-01-15T10:00:00Z"
        }
    """.trimIndent()

    private fun transactionCompensatedJson() = """
        {
          "eventType": "TransactionCompensated",
          "eventId": "${UUID.randomUUID()}",
          "transactionId": "${UUID.randomUUID()}",
          "sourceAccountId": "user-test-123",
          "amount": "200.00",
          "currency": "EUR",
          "reason": "Credit step failed",
          "occurredAt": "2024-01-15T10:00:00Z"
        }
    """.trimIndent()

    private fun accountCreatedJson() = """
        {
          "eventType": "AccountCreated",
          "eventId": "${UUID.randomUUID()}",
          "accountId": "${UUID.randomUUID()}",
          "ownerId": "user-test-123",
          "currency": "EUR",
          "occurredAt": "2024-01-15T10:00:00Z"
        }
    """.trimIndent()

    private fun accountStatusChangedJson(newStatus: String) = """
        {
          "eventType": "AccountStatusChanged",
          "eventId": "${UUID.randomUUID()}",
          "accountId": "${UUID.randomUUID()}",
          "ownerId": "user-test-123",
          "previousStatus": "ACTIVE",
          "newStatus": "$newStatus",
          "occurredAt": "2024-01-15T10:00:00Z"
        }
    """.trimIndent()

    private fun unknownEventJson(type: String) = """
        {
          "eventType": "$type",
          "eventId": "${UUID.randomUUID()}",
          "occurredAt": "2024-01-15T10:00:00Z"
        }
    """.trimIndent()
}
