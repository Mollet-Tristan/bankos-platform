package com.bankos.notification.domain.model

import com.bankos.notification.domain.event.NotificationDomainEvent
import com.bankos.notification.domain.event.NotificationDelivered
import com.bankos.notification.domain.event.NotificationFailed
import com.bankos.notification.domain.event.NotificationScheduled
import java.time.Instant
import java.util.UUID

/**
 * Notification — Aggregate Root
 *
 * Represents a single notification to be delivered to an account owner
 * via one or more channels (EMAIL, SMS).
 *
 * Lifecycle:
 *
 *   PENDING → SENDING → DELIVERED
 *                    ↘ FAILED (retryable)
 *                    ↘ PERMANENTLY_FAILED (max retries exceeded)
 *
 * ─── Why a domain model for notifications? ───────────────────────────────────
 *
 * This service is the "simplest" in the platform, but it still benefits from
 * a proper domain model because:
 *
 *  1. Retry logic has business rules: max 3 attempts, exponential backoff,
 *     some failure types are non-retryable (e.g. invalid email address)
 *  2. Idempotency: the same Kafka event must not trigger duplicate notifications
 *  3. Audit: every delivery attempt (success or failure) must be traceable
 *  4. Channel preference: owner may have EMAIL, SMS, or both configured
 *
 * This class has ZERO dependencies on Spring, Kafka, or SMTP.
 */
class Notification private constructor(
    val id: NotificationId,
    val recipientId: RecipientId,
    val channel: NotificationChannel,
    val type: NotificationType,
    val subject: String,
    val body: String,
    val sourceEventId: SourceEventId,
    val sourceEventType: String,
    private var _status: NotificationStatus,
    val createdAt: Instant,
    private var _updatedAt: Instant,
    private var _attempts: Int = 0,
    private var _lastError: String? = null,
) {
    val status: NotificationStatus get() = _status
    val updatedAt: Instant get() = _updatedAt
    val attempts: Int get() = _attempts
    val lastError: String? get() = _lastError

    private val _domainEvents: MutableList<NotificationDomainEvent> = mutableListOf()
    val domainEvents: List<NotificationDomainEvent> get() = _domainEvents.toList()
    fun clearDomainEvents() = _domainEvents.clear()

    companion object {
        const val MAX_ATTEMPTS = 3
    }

    // ── Business operations ───────────────────────────────────────────────

    /**
     * Mark as SENDING — called just before dispatching to the channel adapter.
     * Increments attempt counter.
     */
    fun startSending(): Notification {
        check(_status == NotificationStatus.PENDING || _status == NotificationStatus.FAILED) {
            throw InvalidNotificationStateException(id, _status, NotificationStatus.SENDING)
        }
        _status = NotificationStatus.SENDING
        _attempts++
        _updatedAt = Instant.now()
        return this
    }

    /**
     * Mark as DELIVERED.
     * Terminal state — no further operations allowed.
     */
    fun markDelivered(): Notification {
        check(_status == NotificationStatus.SENDING) {
            throw InvalidNotificationStateException(id, _status, NotificationStatus.DELIVERED)
        }
        _status = NotificationStatus.DELIVERED
        _updatedAt = Instant.now()
        _domainEvents += NotificationDelivered(
            notificationId = id,
            recipientId = recipientId,
            channel = channel,
            type = type,
            occurredAt = _updatedAt,
        )
        return this
    }

    /**
     * Mark as FAILED after a delivery attempt.
     *
     * If max attempts exceeded → PERMANENTLY_FAILED (non-retryable).
     * Otherwise → FAILED (eligible for retry by the scheduler).
     *
     * [retryable] = false for hard failures: invalid address, unsubscribed recipient.
     */
    fun markFailed(error: String, retryable: Boolean = true): Notification {
        check(_status == NotificationStatus.SENDING) {
            throw InvalidNotificationStateException(id, _status, NotificationStatus.FAILED)
        }
        _lastError = error
        _status = when {
            !retryable || _attempts >= MAX_ATTEMPTS -> NotificationStatus.PERMANENTLY_FAILED
            else -> NotificationStatus.FAILED
        }
        _updatedAt = Instant.now()
        _domainEvents += NotificationFailed(
            notificationId = id,
            recipientId = recipientId,
            channel = channel,
            type = type,
            error = error,
            attempts = _attempts,
            permanent = _status == NotificationStatus.PERMANENTLY_FAILED,
            occurredAt = _updatedAt,
        )
        return this
    }

    fun isRetryable(): Boolean =
        _status == NotificationStatus.FAILED && _attempts < MAX_ATTEMPTS

    fun isTerminal(): Boolean =
        _status == NotificationStatus.DELIVERED || _status == NotificationStatus.PERMANENTLY_FAILED

    // ── Factory ───────────────────────────────────────────────────────────

    companion object {
        fun create(
            recipientId: RecipientId,
            channel: NotificationChannel,
            type: NotificationType,
            subject: String,
            body: String,
            sourceEventId: SourceEventId,
            sourceEventType: String,
        ): Notification {
            require(subject.isNotBlank()) { "Subject must not be blank" }
            require(body.isNotBlank()) { "Body must not be blank" }

            val now = Instant.now()
            val notification = Notification(
                id = NotificationId(UUID.randomUUID()),
                recipientId = recipientId,
                channel = channel,
                type = type,
                subject = subject,
                body = body,
                sourceEventId = sourceEventId,
                sourceEventType = sourceEventType,
                _status = NotificationStatus.PENDING,
                createdAt = now,
                _updatedAt = now,
            )
            notification._domainEvents += NotificationScheduled(
                notificationId = notification.id,
                recipientId = recipientId,
                channel = channel,
                type = type,
                sourceEventId = sourceEventId,
                occurredAt = now,
            )
            return notification
        }

        fun reconstitute(
            id: NotificationId,
            recipientId: RecipientId,
            channel: NotificationChannel,
            type: NotificationType,
            subject: String,
            body: String,
            sourceEventId: SourceEventId,
            sourceEventType: String,
            status: NotificationStatus,
            createdAt: Instant,
            updatedAt: Instant,
            attempts: Int,
            lastError: String?,
        ) = Notification(
            id, recipientId, channel, type, subject, body,
            sourceEventId, sourceEventType, status, createdAt, updatedAt, attempts, lastError,
        )
    }
}

// ── Value Objects ─────────────────────────────────────────────────────────────

@JvmInline value class NotificationId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class RecipientId(val value: String)  { override fun toString() = value }
@JvmInline value class SourceEventId(val value: UUID)  { override fun toString() = value.toString() }

enum class NotificationChannel { EMAIL, SMS }
enum class NotificationStatus  { PENDING, SENDING, DELIVERED, FAILED, PERMANENTLY_FAILED }

/**
 * NotificationType — maps directly to template names and consumer event types.
 *
 * Adding a new type requires:
 *  1. Add enum value here
 *  2. Add Thymeleaf template in resources/templates/email/
 *  3. Add mapping in NotificationFactory
 *  4. No changes to infrastructure needed — open/closed principle
 */
enum class NotificationType {
    TRANSACTION_COMPLETED,
    TRANSACTION_FAILED,
    TRANSACTION_COMPENSATED,
    ACCOUNT_CREATED,
    ACCOUNT_FROZEN,
}

// ── Domain Exceptions ─────────────────────────────────────────────────────────

open class NotificationDomainException(message: String) : RuntimeException(message)

class InvalidNotificationStateException(
    id: NotificationId, current: NotificationStatus, attempted: NotificationStatus,
) : NotificationDomainException(
    "Notification $id cannot transition from $current to $attempted"
)

class NotificationNotFoundException(id: NotificationId) :
    NotificationDomainException("Notification $id not found")
