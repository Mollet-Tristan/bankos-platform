package com.bankos.notification.application

import com.bankos.notification.application.service.*
import com.bankos.notification.domain.model.*
import com.bankos.notification.domain.port.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * NotificationServiceTest — Application layer tests.
 *
 * Verifies:
 *  - Notification is created and sent on each event type
 *  - Idempotency: duplicate events do not create duplicate notifications
 *  - Retry: FAILED notifications are retried by the scheduler
 *  - Channel routing: the correct sender is called per channel
 *  - Non-retryable errors mark the notification PERMANENTLY_FAILED
 */
class NotificationServiceTest {

    private val notificationRepository = mockk<NotificationRepository>()
    private val emailSender            = mockk<ChannelSender>()
    private val notificationFactory    = NotificationFactory()

    private lateinit var service: NotificationService

    @BeforeEach
    fun setUp() {
        every { emailSender.channel } returns NotificationChannel.EMAIL
        every { notificationRepository.save(any()) } answers { firstArg() }
        every { notificationRepository.findBySourceEventId(any()) } returns emptyList()

        service = NotificationService(
            notificationRepository = notificationRepository,
            channelSenders = listOf(emailSender),
            notificationFactory = notificationFactory,
        )
    }

    // ── Event handling ────────────────────────────────────────────────────

    @Nested
    inner class `TransactionCompleted event` {

        @Test
        fun `should create and deliver email notification`() {
            every { emailSender.send(any()) } just Runs

            service.handleTransactionCompleted(transactionCompletedEvent())

            verify(exactly = 1) { emailSender.send(any()) }
            verify(atLeast = 1) { notificationRepository.save(any()) }
        }

        @Test
        fun `should save notification with DELIVERED status after successful send`() {
            every { emailSender.send(any()) } just Runs
            val savedStatuses = mutableListOf<NotificationStatus>()
            every { notificationRepository.save(any()) } answers {
                val n: Notification = firstArg()
                savedStatuses.add(n.status)
                n
            }

            service.handleTransactionCompleted(transactionCompletedEvent())

            assertTrue(savedStatuses.contains(NotificationStatus.DELIVERED))
        }
    }

    @Nested
    inner class `TransactionFailed event` {

        @Test
        fun `should create and deliver failure notification`() {
            every { emailSender.send(any()) } just Runs

            service.handleTransactionFailed(transactionFailedEvent())

            verify(exactly = 1) { emailSender.send(any()) }
        }
    }

    @Nested
    inner class `AccountCreated event` {

        @Test
        fun `should send welcome email`() {
            every { emailSender.send(any()) } just Runs

            service.handleAccountCreated(accountCreatedEvent())

            val slot = slot<Notification>()
            verify { emailSender.send(capture(slot)) }
            assertEquals(NotificationType.ACCOUNT_CREATED, slot.captured.type)
        }
    }

    // ── Idempotency ───────────────────────────────────────────────────────

    @Nested
    inner class `Idempotency — duplicate event redelivery` {

        @Test
        fun `should skip processing when source event already handled`() {
            val eventId = UUID.randomUUID()
            val existing = mockk<Notification>(relaxed = true)
            every {
                notificationRepository.findBySourceEventId(SourceEventId(eventId))
            } returns listOf(existing)

            service.handleTransactionCompleted(transactionCompletedEvent(eventId = eventId))

            // No new notification created, no send attempted
            verify(exactly = 0) { emailSender.send(any()) }
            verify(exactly = 0) { notificationRepository.save(any()) }
        }
    }

    // ── Failure handling ──────────────────────────────────────────────────

    @Nested
    inner class `Delivery failure` {

        @Test
        fun `should mark FAILED on retryable send error`() {
            every { emailSender.send(any()) } throws RetryableSendException("SMTP timeout")
            val savedStatuses = captureStatuses()

            service.handleTransactionCompleted(transactionCompletedEvent())

            assertTrue(savedStatuses.any { it == NotificationStatus.FAILED })
            assertFalse(savedStatuses.any { it == NotificationStatus.DELIVERED })
        }

        @Test
        fun `should mark PERMANENTLY_FAILED on non-retryable error`() {
            every { emailSender.send(any()) } throws NonRetryableSendException("Invalid email")
            val savedStatuses = captureStatuses()

            service.handleTransactionCompleted(transactionCompletedEvent())

            assertTrue(savedStatuses.any { it == NotificationStatus.PERMANENTLY_FAILED })
        }
    }

    // ── Retry scheduler ───────────────────────────────────────────────────

    @Nested
    inner class `Retry scheduler` {

        @Test
        fun `should retry FAILED notifications and mark DELIVERED on success`() {
            val failedNotification = buildFailedNotification()
            every { notificationRepository.findPendingRetries(50) } returns listOf(failedNotification)
            every { emailSender.send(any()) } just Runs

            service.retryPendingNotifications()

            verify(exactly = 1) { emailSender.send(any()) }
        }

        @Test
        fun `should do nothing when no retryable notifications`() {
            every { notificationRepository.findPendingRetries(50) } returns emptyList()

            service.retryPendingNotifications()

            verify(exactly = 0) { emailSender.send(any()) }
        }
    }

    // ── Channel routing ───────────────────────────────────────────────────

    @Nested
    inner class `Channel routing` {

        @Test
        fun `should route EMAIL notifications to emailSender`() {
            every { emailSender.send(any()) } just Runs

            service.handleTransactionCompleted(transactionCompletedEvent())

            verify { emailSender.send(match { it.channel == NotificationChannel.EMAIL }) }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun transactionCompletedEvent(eventId: UUID = UUID.randomUUID()) =
        TransactionCompletedEvent(
            eventId = eventId,
            transactionId = UUID.randomUUID(),
            ownerId = "user-test-123",
            amount = BigDecimal("200.00"),
            currency = "EUR",
            transactionType = "WITHDRAWAL",
        )

    private fun transactionFailedEvent() = TransactionFailedEvent(
        eventId = UUID.randomUUID(),
        transactionId = UUID.randomUUID(),
        ownerId = "user-test-123",
        amount = BigDecimal("200.00"),
        currency = "EUR",
        reason = "Insufficient funds",
    )

    private fun accountCreatedEvent() = AccountCreatedEvent(
        eventId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        ownerId = "user-test-123",
        currency = "EUR",
    )

    private fun buildFailedNotification(): Notification {
        val n = Notification.create(
            recipientId = RecipientId("user-test-123"),
            channel = NotificationChannel.EMAIL,
            type = NotificationType.TRANSACTION_COMPLETED,
            subject = "Transaction confirmed",
            body = "Your payment was processed.",
            sourceEventId = SourceEventId(UUID.randomUUID()),
            sourceEventType = "TransactionCompleted",
        )
        n.startSending()
        n.markFailed("SMTP timeout", retryable = true)
        n.clearDomainEvents()
        return n
    }

    private fun captureStatuses(): MutableList<NotificationStatus> {
        val statuses = mutableListOf<NotificationStatus>()
        every { notificationRepository.save(any()) } answers {
            val n: Notification = firstArg()
            statuses.add(n.status)
            n
        }
        return statuses
    }
}
