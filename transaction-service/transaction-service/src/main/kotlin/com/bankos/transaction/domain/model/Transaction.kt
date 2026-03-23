package com.bankos.transaction.domain.model

import com.bankos.transaction.domain.event.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Transaction — Aggregate Root
 *
 * Represents a financial movement between accounts (or a deposit/withdrawal).
 * This aggregate captures the full lifecycle of a transaction:
 *
 *   PENDING → PROCESSING → COMPLETED
 *                       ↘ FAILED
 *                       ↘ COMPENSATED  (after a failed saga step)
 *
 * Why a lifecycle?
 * A transaction touches TWO services (Account Service for debit, this service
 * for persistence). Tracking status allows:
 *  - Idempotency (reject duplicate PENDING → PROCESSING transitions)
 *  - Compensation (if Account Service debit succeeds but downstream fails)
 *  - Audit trail (exact timestamp of each state change)
 *
 * ADR-003: The debit call to Account Service is synchronous REST.
 * ADR-004: The TransactionCreated event is published to Kafka after commit.
 *
 * This class has ZERO dependencies on Spring, JPA, or Kafka.
 */
class Transaction private constructor(
    val id: TransactionId,
    val sourceAccountId: AccountId,
    val targetAccountId: AccountId?,
    val amount: Money,
    val type: TransactionType,
    val description: String,
    val idempotencyKey: IdempotencyKey,
    private var _status: TransactionStatus,
    val createdAt: Instant,
    private var _updatedAt: Instant,
    private var _failureReason: String? = null,
) {
    val status: TransactionStatus get() = _status
    val updatedAt: Instant get() = _updatedAt
    val failureReason: String? get() = _failureReason

    private val _domainEvents: MutableList<TransactionDomainEvent> = mutableListOf()
    val domainEvents: List<TransactionDomainEvent> get() = _domainEvents.toList()
    fun clearDomainEvents() = _domainEvents.clear()

    // ── State machine ─────────────────────────────────────────────────────

    /**
     * Marks the transaction as PROCESSING.
     * Called just before the Account Service debit call is made.
     *
     * Invariant: only PENDING transactions can start processing.
     * This prevents duplicate processing if the same request is retried
     * before the first one completes (idempotency guard).
     */
    fun startProcessing(): Transaction {
        check(_status == TransactionStatus.PENDING) {
            throw InvalidTransactionStateException(id, _status, TransactionStatus.PROCESSING)
        }
        _status = TransactionStatus.PROCESSING
        _updatedAt = Instant.now()
        return this
    }

    /**
     * Marks the transaction as COMPLETED.
     * Called after the Account Service debit is confirmed AND
     * the transaction is persisted.
     */
    fun complete(): Transaction {
        check(_status == TransactionStatus.PROCESSING) {
            throw InvalidTransactionStateException(id, _status, TransactionStatus.COMPLETED)
        }
        _status = TransactionStatus.COMPLETED
        _updatedAt = Instant.now()
        _domainEvents += TransactionCompleted(
            transactionId = id,
            sourceAccountId = sourceAccountId,
            targetAccountId = targetAccountId,
            amount = amount,
            type = type,
            occurredAt = _updatedAt,
        )
        return this
    }

    /**
     * Marks the transaction as FAILED.
     * Called when the Account Service rejects the debit
     * (e.g. InsufficientFunds, AccountFrozen).
     *
     * No compensation needed here — the debit never happened.
     */
    fun fail(reason: String): Transaction {
        check(_status == TransactionStatus.PROCESSING) {
            throw InvalidTransactionStateException(id, _status, TransactionStatus.FAILED)
        }
        _status = TransactionStatus.FAILED
        _failureReason = reason
        _updatedAt = Instant.now()
        _domainEvents += TransactionFailed(
            transactionId = id,
            sourceAccountId = sourceAccountId,
            amount = amount,
            reason = reason,
            occurredAt = _updatedAt,
        )
        return this
    }

    /**
     * Marks the transaction as COMPENSATED.
     * Called when the debit succeeded but a downstream step failed
     * (e.g. persistence error). A compensating credit must be issued
     * to the Account Service.
     *
     * This is the core of the Saga compensating transaction pattern.
     * See ADR-007: Saga pattern for distributed transaction management.
     */
    fun compensate(reason: String): Transaction {
        check(_status == TransactionStatus.PROCESSING) {
            throw InvalidTransactionStateException(id, _status, TransactionStatus.COMPENSATED)
        }
        _status = TransactionStatus.COMPENSATED
        _failureReason = reason
        _updatedAt = Instant.now()
        _domainEvents += TransactionCompensated(
            transactionId = id,
            sourceAccountId = sourceAccountId,
            amount = amount,
            reason = reason,
            occurredAt = _updatedAt,
        )
        return this
    }

    // ── Factory ───────────────────────────────────────────────────────────

    companion object {
        fun create(
            sourceAccountId: AccountId,
            targetAccountId: AccountId?,
            amount: Money,
            type: TransactionType,
            description: String,
            idempotencyKey: IdempotencyKey,
        ): Transaction {
            require(amount.value > BigDecimal.ZERO) { "Amount must be positive" }
            if (type == TransactionType.TRANSFER) {
                requireNotNull(targetAccountId) { "Transfer requires a target account" }
                require(sourceAccountId != targetAccountId) { "Source and target accounts must differ" }
            }

            val now = Instant.now()
            val tx = Transaction(
                id = TransactionId(UUID.randomUUID()),
                sourceAccountId = sourceAccountId,
                targetAccountId = targetAccountId,
                amount = amount,
                type = type,
                description = description,
                idempotencyKey = idempotencyKey,
                _status = TransactionStatus.PENDING,
                createdAt = now,
                _updatedAt = now,
            )
            tx._domainEvents += TransactionCreated(
                transactionId = tx.id,
                sourceAccountId = sourceAccountId,
                targetAccountId = targetAccountId,
                amount = amount,
                type = type,
                idempotencyKey = idempotencyKey,
                occurredAt = now,
            )
            return tx
        }

        fun reconstitute(
            id: TransactionId,
            sourceAccountId: AccountId,
            targetAccountId: AccountId?,
            amount: Money,
            type: TransactionType,
            description: String,
            idempotencyKey: IdempotencyKey,
            status: TransactionStatus,
            createdAt: Instant,
            updatedAt: Instant,
            failureReason: String?,
        ) = Transaction(
            id, sourceAccountId, targetAccountId, amount, type,
            description, idempotencyKey, status, createdAt, updatedAt, failureReason,
        )
    }
}

// ── Value Objects ─────────────────────────────────────────────────────────────

@JvmInline value class TransactionId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class AccountId(val value: UUID) { override fun toString() = value.toString() }
@JvmInline value class IdempotencyKey(val value: String) { override fun toString() = value }

data class Money(val value: BigDecimal, val currency: Currency) {
    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Cannot add different currencies: $currency vs ${other.currency}" }
        return Money(value + other.value, currency)
    }
    override fun toString() = "$value $currency"
}

enum class Currency { EUR, USD, GBP }

enum class TransactionType {
    /** Withdrawal: debit source account, no target */
    WITHDRAWAL,
    /** Deposit: credit source account, no debit */
    DEPOSIT,
    /** Transfer: debit source, credit target */
    TRANSFER,
}

enum class TransactionStatus { PENDING, PROCESSING, COMPLETED, FAILED, COMPENSATED }

// ── Domain Exceptions ─────────────────────────────────────────────────────────

open class TransactionDomainException(message: String) : RuntimeException(message)

class InvalidTransactionStateException(
    id: TransactionId,
    current: TransactionStatus,
    attempted: TransactionStatus,
) : TransactionDomainException(
    "Transaction $id cannot transition from $current to $attempted"
)

class TransactionNotFoundException(id: TransactionId) :
    TransactionDomainException("Transaction $id not found")

class DuplicateTransactionException(key: IdempotencyKey) :
    TransactionDomainException("Transaction with idempotency key '$key' already exists")
