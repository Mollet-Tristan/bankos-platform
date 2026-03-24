package com.bankos.notification.domain.port

import com.bankos.notification.domain.model.*

/**
 * Output ports — defined in domain, implemented in infrastructure.
 */

interface NotificationRepository {
    fun save(notification: Notification): Notification
    fun findById(id: NotificationId): Notification?
    fun findBySourceEventId(sourceEventId: SourceEventId): List<Notification>
    fun findPendingRetries(limit: Int): List<Notification>
    fun findByRecipientId(recipientId: RecipientId, page: Int, size: Int): List<Notification>
}

/**
 * ChannelSender — Output port for delivery adapters.
 *
 * Two implementations:
 *  - EmailSender  (infrastructure/sender/email/)  → Spring JavaMailSender + Thymeleaf
 *  - SmsSender    (infrastructure/sender/sms/)    → stub / external gateway
 *
 * ADR-010: channel adapters are replaceable without touching the domain or
 * application service. Swapping from SendGrid to AWS SES = new EmailSender impl only.
 */
interface ChannelSender {
    val channel: NotificationChannel
    /**
     * @throws NonRetryableSendException for permanent failures (invalid address, unsubscribed)
     * @throws RetryableSendException for transient failures (SMTP timeout, rate limit)
     */
    fun send(notification: Notification)
}

/**
 * RecipientResolver — fetches contact info (email, phone) for a recipientId.
 *
 * In this demo, recipients are Keycloak user IDs (sub claim).
 * A real implementation would call an Account/Profile service or a local cache.
 */
interface RecipientResolver {
    fun resolveEmail(recipientId: RecipientId): String?
    fun resolvePhone(recipientId: RecipientId): String?
}

// ── Port-level exceptions ─────────────────────────────────────────────────────

class NonRetryableSendException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class RetryableSendException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
