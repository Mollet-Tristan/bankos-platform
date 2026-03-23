package com.bankos.account.domain.model

import com.bankos.account.domain.event.AccountDomainEvent
import com.bankos.account.domain.event.AccountDebited
import com.bankos.account.domain.event.AccountCredited
import com.bankos.account.domain.event.AccountCreated
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Account — Aggregate Root
 *
 * Encapsulates all business rules for a bank account.
 * This class has NO dependency on Spring, JPA, or any infrastructure concern.
 * It is the heart of the Hexagonal Architecture.
 *
 * Invariants enforced here:
 *  - Balance must never go below zero (no overdraft in this demo)
 *  - A FROZEN account cannot be debited
 *  - A CLOSED account cannot receive any operation
 *
 * Domain events are collected in [domainEvents] and dispatched
 * by the Application Service after persistence.
 */
class Account private constructor(
    val id: AccountId,
    val ownerId: String,
    val currency: Currency,
    private var _balance: BigDecimal,
    private var _status: AccountStatus,
    val createdAt: Instant,
    private var _updatedAt: Instant,
) {
    // ── Read-only projections ─────────────────────────────────────────────
    val balance: BigDecimal get() = _balance
    val status: AccountStatus get() = _status
    val updatedAt: Instant get() = _updatedAt

    // ── Domain events (collected, not published here) ─────────────────────
    private val _domainEvents: MutableList<AccountDomainEvent> = mutableListOf()
    val domainEvents: List<AccountDomainEvent> get() = _domainEvents.toList()

    fun clearDomainEvents() = _domainEvents.clear()

    // ── Business operations ───────────────────────────────────────────────

    /**
     * Debits the account by [amount].
     *
     * ADR-003 note: this operation is called synchronously via REST from
     * the Transaction Service. The caller needs an immediate confirmation.
     *
     * @throws InsufficientFundsException if balance would go negative
     * @throws AccountNotActiveException if account is not ACTIVE
     */
    fun debit(amount: BigDecimal, reference: String): Account {
        require(amount > BigDecimal.ZERO) { "Debit amount must be positive" }
        check(_status == AccountStatus.ACTIVE) {
            throw AccountNotActiveException(id, _status)
        }
        if (_balance < amount) {
            throw InsufficientFundsException(id, _balance, amount)
        }
        _balance = _balance.subtract(amount)
        _updatedAt = Instant.now()
        _domainEvents += AccountDebited(
            accountId = id,
            amount = amount,
            balanceAfter = _balance,
            reference = reference,
            occurredAt = _updatedAt,
        )
        return this
    }

    /**
     * Credits the account by [amount].
     *
     * @throws AccountNotActiveException if account is CLOSED
     */
    fun credit(amount: BigDecimal, reference: String): Account {
        require(amount > BigDecimal.ZERO) { "Credit amount must be positive" }
        check(_status != AccountStatus.CLOSED) {
            throw AccountNotActiveException(id, _status)
        }
        _balance = _balance.add(amount)
        _updatedAt = Instant.now()
        _domainEvents += AccountCredited(
            accountId = id,
            amount = amount,
            balanceAfter = _balance,
            reference = reference,
            occurredAt = _updatedAt,
        )
        return this
    }

    fun freeze(): Account {
        check(_status == AccountStatus.ACTIVE) { "Only ACTIVE accounts can be frozen" }
        _status = AccountStatus.FROZEN
        _updatedAt = Instant.now()
        return this
    }

    fun unfreeze(): Account {
        check(_status == AccountStatus.FROZEN) { "Only FROZEN accounts can be unfrozen" }
        _status = AccountStatus.ACTIVE
        _updatedAt = Instant.now()
        return this
    }

    fun close(): Account {
        check(_balance == BigDecimal.ZERO) { "Cannot close account with non-zero balance" }
        _status = AccountStatus.CLOSED
        _updatedAt = Instant.now()
        return this
    }

    // ── Factory ───────────────────────────────────────────────────────────

    companion object {
        fun open(
            ownerId: String,
            currency: Currency,
            initialDeposit: BigDecimal = BigDecimal.ZERO,
        ): Account {
            require(initialDeposit >= BigDecimal.ZERO) { "Initial deposit cannot be negative" }
            val now = Instant.now()
            val account = Account(
                id = AccountId(UUID.randomUUID()),
                ownerId = ownerId,
                currency = currency,
                _balance = initialDeposit,
                _status = AccountStatus.ACTIVE,
                createdAt = now,
                _updatedAt = now,
            )
            account._domainEvents += AccountCreated(
                accountId = account.id,
                ownerId = ownerId,
                currency = currency,
                initialDeposit = initialDeposit,
                occurredAt = now,
            )
            return account
        }

        /** Reconstitute from persistence — no events emitted */
        fun reconstitute(
            id: AccountId,
            ownerId: String,
            currency: Currency,
            balance: BigDecimal,
            status: AccountStatus,
            createdAt: Instant,
            updatedAt: Instant,
        ) = Account(id, ownerId, currency, balance, status, createdAt, updatedAt)
    }
}

// ── Value Objects ─────────────────────────────────────────────────────────────

@JvmInline
value class AccountId(val value: UUID) {
    override fun toString() = value.toString()
}

enum class AccountStatus { ACTIVE, FROZEN, CLOSED }

enum class Currency { EUR, USD, GBP }

// ── Domain Exceptions ─────────────────────────────────────────────────────────

class InsufficientFundsException(
    accountId: AccountId,
    available: BigDecimal,
    requested: BigDecimal,
) : DomainException("Account $accountId has insufficient funds: available=$available, requested=$requested")

class AccountNotActiveException(
    accountId: AccountId,
    status: AccountStatus,
) : DomainException("Account $accountId cannot be operated: status=$status")

class AccountNotFoundException(accountId: AccountId) :
    DomainException("Account $accountId not found")

open class DomainException(message: String) : RuntimeException(message)
