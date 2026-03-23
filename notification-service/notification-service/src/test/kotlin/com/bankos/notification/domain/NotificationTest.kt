package com.bankos.notification.domain

import com.bankos.notification.domain.event.*
import com.bankos.notification.domain.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * NotificationTest — Pure domain unit tests.
 *
 * No Spring. No Kafka. No SMTP.
 * Verifies state machine transitions, retry logic, and event emission.
 */
class NotificationTest {

    @Nested
    inner class `Notification creation` {

        @Test
        fun `should create notification in PENDING status`() {
            val n = emailNotification()
            assertEquals(NotificationStatus.PENDING, n.status)
            assertEquals(0, n.attempts)
            assertNull(n.lastError)
        }

        @Test
        fun `should emit NotificationScheduled event on creation`() {
            val n = emailNotification()
            assertEquals(1, n.domainEvents.size)
            assertInstanceOf(NotificationScheduled::class.java, n.domainEvents.first())
        }

        @Test
        fun `should reject blank subject`() {
            assertThrows<IllegalArgumentException> {
                Notification.create(
                    recipientId = RecipientId("user-1"),
                    channel = NotificationChannel.EMAIL,
                    type = NotificationType.TRANSACTION_COMPLETED,
                    subject = "   ",
                    body = "some body",
                    sourceEventId = SourceEventId(UUID.randomUUID()),
                    sourceEventType = "TransactionCompleted",
                )
            }
        }

        @Test
        fun `should reject blank body`() {
            assertThrows<IllegalArgumentException> {
                Notification.create(
                    recipientId = RecipientId("user-1"),
                    channel = NotificationChannel.EMAIL,
                    type = NotificationType.TRANSACTION_COMPLETED,
                    subject = "Subject",
                    body = "",
                    sourceEventId = SourceEventId(UUID.randomUUID()),
                    sourceEventType = "TransactionCompleted",
                )
            }
        }
    }

    @Nested
    inner class `Happy path — PENDING to DELIVERED` {

        @Test
        fun `should transition PENDING → SENDING → DELIVERED`() {
            val n = fresh()
            n.startSending()
            assertEquals(NotificationStatus.SENDING, n.status)
            assertEquals(1, n.attempts)

            n.markDelivered()
            assertEquals(NotificationStatus.DELIVERED, n.status)
        }

        @Test
        fun `should emit NotificationDelivered event`() {
            val n = fresh()
            n.startSending()
            n.markDelivered()

            val event = n.domainEvents.filterIsInstance<NotificationDelivered>().first()
            assertEquals(n.id, event.notificationId)
            assertEquals(NotificationChannel.EMAIL, event.channel)
        }

        @Test
        fun `should increment attempts counter on each startSending`() {
            val n = fresh()
            n.startSending()
            assertEquals(1, n.attempts)
        }
    }

    @Nested
    inner class `Failure and retry` {

        @Test
        fun `should transition SENDING → FAILED on retryable error`() {
            val n = fresh()
            n.startSending()
            n.markFailed("SMTP timeout", retryable = true)

            assertEquals(NotificationStatus.FAILED, n.status)
            assertEquals("SMTP timeout", n.lastError)
            assertTrue(n.isRetryable())
        }

        @Test
        fun `should transition SENDING → PERMANENTLY_FAILED on non-retryable error`() {
            val n = fresh()
            n.startSending()
            n.markFailed("Invalid email address", retryable = false)

            assertEquals(NotificationStatus.PERMANENTLY_FAILED, n.status)
            assertFalse(n.isRetryable())
        }

        @Test
        fun `should become PERMANENTLY_FAILED after max attempts exceeded`() {
            val n = fresh()

            // Attempt 1
            n.startSending(); n.markFailed("timeout", retryable = true)
            assertEquals(NotificationStatus.FAILED, n.status)

            // Attempt 2
            n.startSending(); n.markFailed("timeout", retryable = true)
            assertEquals(NotificationStatus.FAILED, n.status)

            // Attempt 3 — final
            n.startSending(); n.markFailed("timeout", retryable = true)
            assertEquals(NotificationStatus.PERMANENTLY_FAILED, n.status)
            assertFalse(n.isRetryable())
        }

        @Test
        fun `should emit NotificationFailed with permanent=true when max exceeded`() {
            val n = fresh()
            repeat(Notification.MAX_ATTEMPTS) {
                n.startSending()
                n.markFailed("timeout", retryable = true)
                if (it < Notification.MAX_ATTEMPTS - 1) n.clearDomainEvents()
            }
            val event = n.domainEvents.filterIsInstance<NotificationFailed>().first()
            assertTrue(event.permanent)
            assertEquals(Notification.MAX_ATTEMPTS, event.attempts)
        }

        @Test
        fun `should allow retry after FAILED (not after PERMANENTLY_FAILED)`() {
            val n = fresh()
            n.startSending()
            n.markFailed("timeout", retryable = true)
            assertEquals(NotificationStatus.FAILED, n.status)

            // Can start sending again from FAILED
            n.startSending()
            assertEquals(NotificationStatus.SENDING, n.status)
        }

        @Test
        fun `should reject startSending from DELIVERED`() {
            val n = fresh()
            n.startSending()
            n.markDelivered()

            assertThrows<InvalidNotificationStateException> {
                n.startSending()
            }
        }

        @Test
        fun `should reject markDelivered from FAILED`() {
            val n = fresh()
            n.startSending()
            n.markFailed("error", retryable = true)

            assertThrows<InvalidNotificationStateException> {
                n.markDelivered()
            }
        }
    }

    @Nested
    inner class `Terminal state checks` {

        @Test
        fun `DELIVERED is terminal`() {
            val n = fresh()
            n.startSending()
            n.markDelivered()
            assertTrue(n.isTerminal())
        }

        @Test
        fun `PERMANENTLY_FAILED is terminal`() {
            val n = fresh()
            n.startSending()
            n.markFailed("bad address", retryable = false)
            assertTrue(n.isTerminal())
        }

        @Test
        fun `FAILED is not terminal`() {
            val n = fresh()
            n.startSending()
            n.markFailed("timeout", retryable = true)
            assertFalse(n.isTerminal())
        }

        @Test
        fun `PENDING is not terminal`() {
            assertFalse(fresh().isTerminal())
        }
    }

    @Nested
    inner class `Domain events` {

        @Test
        fun `should clear events after dispatch`() {
            val n = fresh()
            n.startSending()
            n.markDelivered()
            n.clearDomainEvents()
            assertTrue(n.domainEvents.isEmpty())
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun emailNotification() = Notification.create(
        recipientId = RecipientId("user-test-123"),
        channel = NotificationChannel.EMAIL,
        type = NotificationType.TRANSACTION_COMPLETED,
        subject = "Transaction confirmed",
        body = "Your payment of 200 EUR was successful.",
        sourceEventId = SourceEventId(UUID.randomUUID()),
        sourceEventType = "TransactionCompleted",
    )

    private fun fresh(): Notification {
        val n = emailNotification()
        n.clearDomainEvents()
        return n
    }
}
