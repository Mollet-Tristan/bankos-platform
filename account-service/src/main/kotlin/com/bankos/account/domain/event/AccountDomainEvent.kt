package com.bankos.account.domain.event

import com.bankos.account.domain.model.AccountId
import com.bankos.account.domain.model.Currency
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Domain Events — emitted by the Account aggregate.
 *
 * These are *domain* events, not infrastructure events.
 * The KafkaEventPublisher (infrastructure) maps them to Kafka messages.
 *
 * ADR-004: events between Account Service and Notification Service
 * are asynchronous via Kafka. The notification of a transaction
 * is not critical to the debit/credit operation itself.
 */
sealed interface AccountDomainEvent {
    val eventId: UUID
    val accountId: AccountId
    val occurredAt: Instant
}

data class AccountCreated(
    override val eventId: UUID = UUID.randomUUID(),
    override val accountId: AccountId,
    override val occurredAt: Instant,
    val ownerId: String,
    val currency: Currency,
    val initialDeposit: BigDecimal,
) : AccountDomainEvent

data class AccountDebited(
    override val eventId: UUID = UUID.randomUUID(),
    override val accountId: AccountId,
    override val occurredAt: Instant,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val reference: String,
) : AccountDomainEvent

data class AccountCredited(
    override val eventId: UUID = UUID.randomUUID(),
    override val accountId: AccountId,
    override val occurredAt: Instant,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val reference: String,
) : AccountDomainEvent

data class AccountStatusChanged(
    override val eventId: UUID = UUID.randomUUID(),
    override val accountId: AccountId,
    override val occurredAt: Instant,
    val previousStatus: String,
    val newStatus: String,
) : AccountDomainEvent
