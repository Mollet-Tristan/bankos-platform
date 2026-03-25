package com.bankos.transaction.domain.event

import com.bankos.transaction.domain.model.AccountId
import com.bankos.transaction.domain.model.IdempotencyKey
import com.bankos.transaction.domain.model.Money
import com.bankos.transaction.domain.model.TransactionId
import com.bankos.transaction.domain.model.TransactionType
import java.time.Instant
import java.util.UUID

/**
 * Transaction Domain Events — sealed hierarchy
 *
 * ADR-004: TransactionCompleted is the primary event consumed by:
 *  - NotificationService  → sends confirmation email/SMS to account owner
 *  - AuditService         → appends to immutable audit log
 *  - ReportingService     → updates daily aggregates
 *
 * TransactionFailed and TransactionCompensated are consumed by:
 *  - NotificationService  → failure notification to owner
 *  - AuditService         → failure audit trail
 *
 * All events carry enough context for consumers to act without
 * needing to call back into this service (self-contained events).
 */
sealed interface TransactionDomainEvent {
    val eventId: UUID
    val transactionId: TransactionId
    val occurredAt: Instant
}

data class TransactionCreated(
    override val eventId: UUID = UUID.randomUUID(),
    override val transactionId: TransactionId,
    override val occurredAt: Instant,
    val sourceAccountId: AccountId,
    val targetAccountId: AccountId?,
    val amount: Money,
    val type: TransactionType,
    val idempotencyKey: IdempotencyKey,
) : TransactionDomainEvent

data class TransactionCompleted(
    override val eventId: UUID = UUID.randomUUID(),
    override val transactionId: TransactionId,
    override val occurredAt: Instant,
    val sourceAccountId: AccountId,
    val targetAccountId: AccountId?,
    val amount: Money,
    val type: TransactionType,
) : TransactionDomainEvent

data class TransactionFailed(
    override val eventId: UUID = UUID.randomUUID(),
    override val transactionId: TransactionId,
    override val occurredAt: Instant,
    val sourceAccountId: AccountId,
    val amount: Money,
    val reason: String,
) : TransactionDomainEvent

/**
 * TransactionCompensated — emitted when a Saga compensation is triggered.
 *
 * This event tells downstream consumers that a previously debited amount
 * has been (or will be) credited back. Consumers must handle this event
 * to avoid double-notifying or mis-reporting.
 *
 * See ADR-007: Saga pattern.
 */
data class TransactionCompensated(
    override val eventId: UUID = UUID.randomUUID(),
    override val transactionId: TransactionId,
    override val occurredAt: Instant,
    val sourceAccountId: AccountId,
    val amount: Money,
    val reason: String,
) : TransactionDomainEvent
