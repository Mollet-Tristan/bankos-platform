package com.bankos.notification.application.service

import com.bankos.notification.domain.model.*
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/**
 * NotificationFactory — maps upstream Kafka event payloads to Notification aggregates.
 *
 * This class centralises all template selection logic.
 * Adding a new notification type = adding a `create*` method here + a Thymeleaf template.
 * No other class changes.
 *
 * ─── Why a dedicated Factory? ────────────────────────────────────────────────
 *
 * Keeping the mapping logic here (rather than in the Kafka consumer) means:
 *  - The consumer stays thin: deserialise → delegate → ack
 *  - The factory is independently testable (pure function, no Kafka context)
 *  - Subject/body generation can be unit-tested without spinning up Kafka
 */
@Component
class NotificationFactory {

    fun forTransactionCompleted(
        recipientId: RecipientId,
        transactionId: UUID,
        amount: BigDecimal,
        currency: String,
        type: String,
        sourceEventId: UUID,
    ): List<Notification> = buildNotifications(
        recipientId = recipientId,
        type = NotificationType.TRANSACTION_COMPLETED,
        subject = "Transaction confirmed — $amount $currency",
        body = buildTransactionCompletedBody(transactionId, amount, currency, type),
        sourceEventId = SourceEventId(sourceEventId),
        sourceEventType = "TransactionCompleted",
    )

    fun forTransactionFailed(
        recipientId: RecipientId,
        transactionId: UUID,
        amount: BigDecimal,
        currency: String,
        reason: String,
        sourceEventId: UUID,
    ): List<Notification> = buildNotifications(
        recipientId = recipientId,
        type = NotificationType.TRANSACTION_FAILED,
        subject = "Transaction failed — $amount $currency",
        body = buildTransactionFailedBody(transactionId, amount, currency, reason),
        sourceEventId = SourceEventId(sourceEventId),
        sourceEventType = "TransactionFailed",
    )

    fun forTransactionCompensated(
        recipientId: RecipientId,
        transactionId: UUID,
        amount: BigDecimal,
        currency: String,
        reason: String,
        sourceEventId: UUID,
    ): List<Notification> = buildNotifications(
        recipientId = recipientId,
        type = NotificationType.TRANSACTION_COMPENSATED,
        subject = "Transaction reversed — $amount $currency refunded",
        body = buildCompensatedBody(transactionId, amount, currency, reason),
        sourceEventId = SourceEventId(sourceEventId),
        sourceEventType = "TransactionCompensated",
    )

    fun forAccountCreated(
        recipientId: RecipientId,
        accountId: UUID,
        currency: String,
        sourceEventId: UUID,
    ): List<Notification> = buildNotifications(
        recipientId = recipientId,
        type = NotificationType.ACCOUNT_CREATED,
        subject = "Welcome to BankOS — your $currency account is ready",
        body = buildAccountCreatedBody(accountId, currency),
        sourceEventId = SourceEventId(sourceEventId),
        sourceEventType = "AccountCreated",
    )

    fun forAccountFrozen(
        recipientId: RecipientId,
        accountId: UUID,
        sourceEventId: UUID,
    ): List<Notification> = buildNotifications(
        recipientId = recipientId,
        type = NotificationType.ACCOUNT_FROZEN,
        subject = "Important: your account has been frozen",
        body = buildAccountFrozenBody(accountId),
        sourceEventId = SourceEventId(sourceEventId),
        sourceEventType = "AccountStatusChanged",
    )

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Creates one Notification per channel.
     * In this demo: EMAIL only. SMS would be added here when the SmsSender is ready.
     *
     * ADR-010: channels are driven by recipient preferences.
     * For simplicity, all recipients get EMAIL in this demo.
     * A production implementation would call RecipientResolver to check preferences.
     */
    private fun buildNotifications(
        recipientId: RecipientId,
        type: NotificationType,
        subject: String,
        body: String,
        sourceEventId: SourceEventId,
        sourceEventType: String,
    ): List<Notification> = listOf(
        Notification.create(
            recipientId = recipientId,
            channel = NotificationChannel.EMAIL,
            type = type,
            subject = subject,
            body = body,
            sourceEventId = sourceEventId,
            sourceEventType = sourceEventType,
        )
        // SMS would be added here: Notification.create(..., channel = NotificationChannel.SMS)
    )

    // ── Body builders (plain text — Thymeleaf templates used in EmailSender) ─

    private fun buildTransactionCompletedBody(
        txId: UUID, amount: BigDecimal, currency: String, type: String,
    ) = """
        Your $type of $amount $currency has been processed successfully.
        Transaction reference: $txId
        
        If you did not initiate this transaction, please contact support immediately.
    """.trimIndent()

    private fun buildTransactionFailedBody(
        txId: UUID, amount: BigDecimal, currency: String, reason: String,
    ) = """
        Your transaction of $amount $currency could not be processed.
        Reason: $reason
        Transaction reference: $txId
        
        Please check your account balance or contact support for assistance.
    """.trimIndent()

    private fun buildCompensatedBody(
        txId: UUID, amount: BigDecimal, currency: String, reason: String,
    ) = """
        A transaction of $amount $currency has been reversed to your account.
        Reason: $reason
        Transaction reference: $txId
        
        The amount will appear in your balance shortly.
    """.trimIndent()

    private fun buildAccountCreatedBody(accountId: UUID, currency: String) = """
        Your new $currency account has been successfully opened.
        Account reference: $accountId
        
        You can now make deposits, withdrawals, and transfers through the BankOS platform.
    """.trimIndent()

    private fun buildAccountFrozenBody(accountId: UUID) = """
        Your account ($accountId) has been temporarily frozen.
        
        No debits can be made while the account is frozen.
        Please contact your account manager or support to resolve this.
    """.trimIndent()
}
