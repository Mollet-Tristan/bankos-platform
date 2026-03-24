package com.bankos.notification.application.service

import com.bankos.notification.domain.model.*
import com.bankos.notification.domain.port.*
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * NotificationService — Application Service
 *
 * Orchestrates the full notification delivery lifecycle:
 *  1. Persist the Notification (PENDING)
 *  2. Dispatch to the appropriate ChannelSender
 *  3. Update status (DELIVERED / FAILED / PERMANENTLY_FAILED)
 *  4. Scheduled retry for FAILED notifications
 *
 * ─── Idempotency ──────────────────────────────────────────────────────────────
 *
 * Before creating a notification, we check if one already exists for the
 * same [sourceEventId]. This prevents duplicate notifications if a Kafka
 * message is redelivered (at-least-once semantics).
 *
 * ─── Retry strategy ───────────────────────────────────────────────────────────
 *
 * Failed (retryable) notifications are retried by [retryPendingNotifications]
 * on a fixed schedule. Max 3 attempts total — then PERMANENTLY_FAILED.
 *
 * This is intentionally simple: a production system might use
 * exponential backoff with jitter, or delegate retry to a dead-letter topic.
 * See ADR-011 for the discussion.
 *
 * ─── Channel routing ──────────────────────────────────────────────────────────
 *
 * The service routes to the correct [ChannelSender] based on [Notification.channel].
 * Senders are injected as a list and resolved by their [ChannelSender.channel] property.
 * Adding a new channel = provide a new [ChannelSender] bean. Zero changes here.
 */
@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val channelSenders: List<ChannelSender>,
    private val notificationFactory: NotificationFactory,
) {
    private val log = LoggerFactory.getLogger(NotificationService::class.java)
    private val sendersByChannel: Map<NotificationChannel, ChannelSender> =
        channelSenders.associateBy { it.channel }

    // ── Use cases called by Kafka consumer ────────────────────────────────

    @Transactional
    fun handleTransactionCompleted(event: TransactionCompletedEvent) {
        if (isDuplicate(event.eventId)) return
        val notifications = notificationFactory.forTransactionCompleted(
            recipientId = RecipientId(event.ownerId),
            transactionId = event.transactionId,
            amount = event.amount,
            currency = event.currency,
            type = event.transactionType,
            sourceEventId = event.eventId,
        )
        notifications.forEach { createAndSend(it) }
    }

    @Transactional
    fun handleTransactionFailed(event: TransactionFailedEvent) {
        if (isDuplicate(event.eventId)) return
        val notifications = notificationFactory.forTransactionFailed(
            recipientId = RecipientId(event.ownerId),
            transactionId = event.transactionId,
            amount = event.amount,
            currency = event.currency,
            reason = event.reason,
            sourceEventId = event.eventId,
        )
        notifications.forEach { createAndSend(it) }
    }

    @Transactional
    fun handleTransactionCompensated(event: TransactionCompensatedEvent) {
        if (isDuplicate(event.eventId)) return
        val notifications = notificationFactory.forTransactionCompensated(
            recipientId = RecipientId(event.ownerId),
            transactionId = event.transactionId,
            amount = event.amount,
            currency = event.currency,
            reason = event.reason,
            sourceEventId = event.eventId,
        )
        notifications.forEach { createAndSend(it) }
    }

    @Transactional
    fun handleAccountCreated(event: AccountCreatedEvent) {
        if (isDuplicate(event.eventId)) return
        val notifications = notificationFactory.forAccountCreated(
            recipientId = RecipientId(event.ownerId),
            accountId = event.accountId,
            currency = event.currency,
            sourceEventId = event.eventId,
        )
        notifications.forEach { createAndSend(it) }
    }

    @Transactional
    fun handleAccountFrozen(event: AccountFrozenEvent) {
        if (isDuplicate(event.eventId)) return
        val notifications = notificationFactory.forAccountFrozen(
            recipientId = RecipientId(event.ownerId),
            accountId = event.accountId,
            sourceEventId = event.eventId,
        )
        notifications.forEach { createAndSend(it) }
    }

    // ── Scheduled retry ───────────────────────────────────────────────────

    /**
     * Retries FAILED notifications on a fixed schedule.
     *
     * ADR-011: Simple scheduled retry vs. Kafka Dead-Letter Topic.
     * We chose scheduled retry here to keep the implementation self-contained.
     * The trade-off: retry frequency is fixed (not event-driven), and
     * there is a delay before retry (up to the schedule interval).
     * A Kafka DLT approach would provide immediate retry and better observability.
     */
    @Scheduled(fixedDelayString = "\${bankos.notification.retry-interval-ms:60000}")
    @Transactional
    fun retryPendingNotifications() {
        val retryable = notificationRepository.findPendingRetries(limit = 50)
        if (retryable.isEmpty()) return

        log.info("Retrying ${retryable.size} failed notification(s)")
        retryable.forEach { notification ->
            log.debug("Retrying notification id=${notification.id} attempt=${notification.attempts + 1}")
            send(notification)
        }
    }

    // ── Core: persist + send ──────────────────────────────────────────────

    private fun createAndSend(notification: Notification) {
        val saved = notificationRepository.save(notification)
        notification.clearDomainEvents()
        send(saved)
    }

    private fun send(notification: Notification) {
        val sender = sendersByChannel[notification.channel]
        if (sender == null) {
            log.error("No sender registered for channel=${notification.channel}, notification=${notification.id}")
            return
        }

        notification.startSending()
        notificationRepository.save(notification)

        try {
            sender.send(notification)
            notification.markDelivered()
            log.info("Notification delivered: id=${notification.id} channel=${notification.channel} type=${notification.type}")
        } catch (ex: NonRetryableSendException) {
            log.warn("Permanent delivery failure: id=${notification.id} error=${ex.message}")
            notification.markFailed(ex.message ?: "Permanent failure", retryable = false)
        } catch (ex: RetryableSendException) {
            log.warn("Transient delivery failure: id=${notification.id} attempt=${notification.attempts} error=${ex.message}")
            notification.markFailed(ex.message ?: "Transient failure", retryable = true)
        } catch (ex: Exception) {
            log.error("Unexpected error delivering notification id=${notification.id}", ex)
            notification.markFailed("Unexpected error: ${ex.message}", retryable = true)
        } finally {
            notificationRepository.save(notification)
            notification.clearDomainEvents()
        }
    }

    // ── Idempotency check ─────────────────────────────────────────────────

    private fun isDuplicate(eventId: java.util.UUID): Boolean {
        val existing = notificationRepository.findBySourceEventId(SourceEventId(eventId))
        return if (existing.isNotEmpty()) {
            log.info("Duplicate event detected for eventId=$eventId — skipping")
            true
        } else false
    }
}

// ── Event DTOs (Kafka payload representations) ────────────────────────────────
// These are simple data holders — not domain objects.
// The Kafka consumer deserialises JSON into these and passes them to the service.

data class TransactionCompletedEvent(
    val eventId: java.util.UUID,
    val transactionId: java.util.UUID,
    val ownerId: String,
    val amount: java.math.BigDecimal,
    val currency: String,
    val transactionType: String,
)

data class TransactionFailedEvent(
    val eventId: java.util.UUID,
    val transactionId: java.util.UUID,
    val ownerId: String,
    val amount: java.math.BigDecimal,
    val currency: String,
    val reason: String,
)

data class TransactionCompensatedEvent(
    val eventId: java.util.UUID,
    val transactionId: java.util.UUID,
    val ownerId: String,
    val amount: java.math.BigDecimal,
    val currency: String,
    val reason: String,
)

data class AccountCreatedEvent(
    val eventId: java.util.UUID,
    val accountId: java.util.UUID,
    val ownerId: String,
    val currency: String,
)

data class AccountFrozenEvent(
    val eventId: java.util.UUID,
    val accountId: java.util.UUID,
    val ownerId: String,
)
