package com.bankos.transaction.domain.port

import com.bankos.transaction.domain.event.TransactionDomainEvent
import com.bankos.transaction.domain.model.*

/**
 * Output ports — defined in the domain, implemented in infrastructure.
 *
 * The domain never imports the adapters.
 * Spring wires the implementations at runtime.
 */

/**
 * TransactionRepository — persistence port.
 * Includes idempotency check: find by idempotency key to prevent duplicates.
 */
interface TransactionRepository {
    fun save(transaction: Transaction): Transaction
    fun findById(id: TransactionId): Transaction?
    fun findByIdempotencyKey(key: IdempotencyKey): Transaction?
    fun findBySourceAccountId(accountId: AccountId, page: Int, size: Int): List<Transaction>
    fun findAll(page: Int, size: Int): List<Transaction>
    fun countByStatus(status: TransactionStatus): Long
}

/**
 * EventPublisher — Kafka output port.
 *
 * ADR-004: after a transaction is persisted, domain events are
 * published asynchronously. Decouples this service from all consumers.
 */
interface EventPublisher {
    fun publish(events: List<TransactionDomainEvent>)
}

/**
 * AccountServiceClient — synchronous HTTP client port.
 *
 * ADR-003: The debit/credit on Account Service is synchronous REST
 * because the caller (end user, or Transaction Service) needs an
 * immediate confirmation before the transaction can be marked COMPLETED.
 *
 * A failed call here triggers a FAILED or COMPENSATED status on the
 * transaction — see ADR-007 for the Saga pattern detail.
 */
interface AccountServiceClient {
    /**
     * Debit [amount] from [accountId].
     * @throws AccountServiceException on HTTP error or timeout
     * @throws InsufficientFundsException on 422 from Account Service
     */
    fun debit(accountId: AccountId, amount: Money, reference: String)

    /**
     * Credit [amount] to [accountId].
     * Used for:
     *  - DEPOSIT operation
     *  - TRANSFER target credit
     *  - Saga compensation (reverse a debit that must be rolled back)
     */
    fun credit(accountId: AccountId, amount: Money, reference: String)
}

// ── Port-level exceptions (not domain exceptions) ─────────────────────────────

class AccountServiceException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class InsufficientFundsRemoteException(accountId: AccountId, message: String) :
    RuntimeException("Account $accountId: $message")

class AccountFrozenRemoteException(accountId: AccountId) :
    RuntimeException("Account $accountId is frozen")
