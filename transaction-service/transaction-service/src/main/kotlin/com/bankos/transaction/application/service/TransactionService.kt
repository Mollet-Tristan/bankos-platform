package com.bankos.transaction.application.service

import com.bankos.transaction.application.dto.*
import com.bankos.transaction.domain.model.*
import com.bankos.transaction.domain.port.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * TransactionService — Application Service
 *
 * This service orchestrates the most complex flow in the BankOS platform:
 * a financial transaction that spans TWO services (Account Service + this one).
 *
 * ─── Choreography Saga (simplified) ──────────────────────────────────────────
 *
 * The full flow for a WITHDRAWAL or TRANSFER:
 *
 *   1. Validate command + idempotency check
 *   2. Create Transaction aggregate (status = PENDING)
 *   3. Persist transaction (status = PENDING) — safe to retry from here
 *   4. Mark PROCESSING — idempotency guard: concurrent retries are rejected
 *   5. Call Account Service via REST (debit source account)   ← can fail
 *   6a. On success → mark COMPLETED, persist, publish events
 *   6b. On account rejection (insufficient funds, frozen)
 *         → mark FAILED, persist, publish TransactionFailed
 *   6c. On infrastructure error (timeout, 5xx)
 *         → mark COMPENSATED, persist, publish TransactionCompensated
 *         → issue compensating credit call to Account Service
 *
 * ─── Why synchronous REST for Account Service call? (ADR-003) ────────────────
 *
 * The caller (end user via API Gateway) expects an immediate response:
 * "did my payment go through or not?". This requires synchronous confirmation
 * from Account Service. The latency trade-off is acceptable for a payment.
 *
 * ─── Why Kafka for post-completion events? (ADR-004) ─────────────────────────
 *
 * After the transaction is committed, notifications (email, SMS) and audit
 * entries are not critical to the response. They can lag by seconds.
 * Kafka decouples these consumers and allows independent scaling.
 *
 * ─── Idempotency ─────────────────────────────────────────────────────────────
 *
 * Each request carries an `idempotencyKey`. If a transaction with the same key
 * already exists, we return the existing one without re-processing.
 * This allows safe HTTP retries from the client.
 */
@Service
class TransactionService(
    private val transactionRepository: TransactionRepository,
    private val eventPublisher: EventPublisher,
    private val accountServiceClient: AccountServiceClient,
) {
    private val log = LoggerFactory.getLogger(TransactionService::class.java)

    /**
     * Execute a transaction.
     *
     * The @Transactional here wraps only the DB operations.
     * The Account Service HTTP call is intentionally OUTSIDE the DB transaction
     * to avoid holding a DB connection open during a network call.
     *
     * See ADR-007 for the full discussion of transaction boundary choices.
     */
    fun executeTransaction(command: CreateTransactionCommand): TransactionResponse {
        // ── Step 1: Idempotency check ─────────────────────────────────────
        val idempotencyKey = IdempotencyKey(command.idempotencyKey)
        transactionRepository.findByIdempotencyKey(idempotencyKey)?.let { existing ->
            log.info("Idempotent request for key=$idempotencyKey, returning existing tx=${existing.id}")
            return existing.toResponse()
        }

        // ── Step 2: Create and persist PENDING transaction ────────────────
        val transaction = Transaction.create(
            sourceAccountId = AccountId(command.sourceAccountId),
            targetAccountId = command.targetAccountId?.let { AccountId(it) },
            amount = Money(command.amount, command.currency),
            type = command.type,
            description = command.description,
            idempotencyKey = idempotencyKey,
        )
        val pending = persistWithEvents(transaction)
        log.info("Transaction created: id=${pending.id} type=${pending.type} amount=${pending.amount}")

        // ── Step 3: Mark PROCESSING (idempotency guard) ───────────────────
        pending.startProcessing()
        persistOnly(pending)

        // ── Step 4: Execute debit via Account Service ─────────────────────
        return when (pending.type) {
            TransactionType.WITHDRAWAL -> executeWithdrawal(pending)
            TransactionType.DEPOSIT    -> executeDeposit(pending)
            TransactionType.TRANSFER   -> executeTransfer(pending)
        }
    }

    // ── Use case implementations ──────────────────────────────────────────

    private fun executeWithdrawal(tx: Transaction): TransactionResponse {
        return try {
            accountServiceClient.debit(tx.sourceAccountId, tx.amount, tx.id.toString())
            tx.complete()
            persistWithEvents(tx).toResponse()
                .also { log.info("Withdrawal completed: id=${tx.id}") }
        } catch (ex: InsufficientFundsRemoteException) {
            log.warn("Withdrawal failed (insufficient funds): id=${tx.id} - ${ex.message}")
            tx.fail(ex.message ?: "Insufficient funds")
            persistWithEvents(tx).toResponse()
        } catch (ex: AccountFrozenRemoteException) {
            log.warn("Withdrawal failed (account frozen): id=${tx.id}")
            tx.fail("Account is frozen")
            persistWithEvents(tx).toResponse()
        } catch (ex: AccountServiceException) {
            log.error("Infrastructure error during withdrawal id=${tx.id}, triggering compensation", ex)
            triggerCompensation(tx, ex.message ?: "Infrastructure error")
            tx.toResponse()
        }
    }

    private fun executeDeposit(tx: Transaction): TransactionResponse {
        return try {
            accountServiceClient.credit(tx.sourceAccountId, tx.amount, tx.id.toString())
            tx.complete()
            persistWithEvents(tx).toResponse()
                .also { log.info("Deposit completed: id=${tx.id}") }
        } catch (ex: AccountServiceException) {
            log.error("Infrastructure error during deposit id=${tx.id}", ex)
            tx.fail(ex.message ?: "Infrastructure error")
            persistWithEvents(tx).toResponse()
        }
    }

    private fun executeTransfer(tx: Transaction): TransactionResponse {
        val target = requireNotNull(tx.targetAccountId) { "Transfer missing target account" }

        // Step A: debit source
        try {
            accountServiceClient.debit(tx.sourceAccountId, tx.amount, "${tx.id}-debit")
        } catch (ex: InsufficientFundsRemoteException) {
            tx.fail(ex.message ?: "Insufficient funds")
            return persistWithEvents(tx).toResponse()
        } catch (ex: AccountServiceException) {
            tx.fail(ex.message ?: "Infrastructure error on debit")
            return persistWithEvents(tx).toResponse()
        }

        // Step B: credit target (source already debited — compensation needed on failure)
        return try {
            accountServiceClient.credit(target, tx.amount, "${tx.id}-credit")
            tx.complete()
            persistWithEvents(tx).toResponse()
                .also { log.info("Transfer completed: id=${tx.id}") }
        } catch (ex: AccountServiceException) {
            log.error("Credit step failed for transfer id=${tx.id}, triggering compensation", ex)
            triggerCompensation(tx, "Credit to target account failed: ${ex.message}")
            tx.toResponse()
        }
    }

    /**
     * Saga compensation: reverse the debit already applied to the source account.
     * Marks the transaction as COMPENSATED and publishes TransactionCompensated event.
     */
    private fun triggerCompensation(tx: Transaction, reason: String) {
        tx.compensate(reason)
        persistWithEvents(tx)

        try {
            accountServiceClient.credit(tx.sourceAccountId, tx.amount, "${tx.id}-compensation")
            log.info("Compensation credit issued for tx=${tx.id}")
        } catch (ex: Exception) {
            // Compensation failed — requires manual intervention / dead-letter queue
            log.error(
                "CRITICAL: Compensation credit FAILED for tx=${tx.id}. " +
                "Manual intervention required. sourceAccount=${tx.sourceAccountId} amount=${tx.amount}",
                ex,
            )
            // TODO: publish to a dead-letter topic for ops alerting (ADR-008)
        }
    }

    // ── Read operations ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun getTransaction(id: java.util.UUID): TransactionResponse =
        (transactionRepository.findById(TransactionId(id))
            ?: throw TransactionNotFoundException(TransactionId(id))).toResponse()

    @Transactional(readOnly = true)
    fun getTransactionsByAccount(
        accountId: java.util.UUID,
        page: Int,
        size: Int,
    ): PagedResponse<TransactionResponse> {
        val txs = transactionRepository
            .findBySourceAccountId(AccountId(accountId), page, size)
            .map { it.toResponse() }
        return PagedResponse(txs, page, size, txs.size)
    }

    @Transactional(readOnly = true)
    fun listTransactions(page: Int, size: Int): PagedResponse<TransactionResponse> {
        val txs = transactionRepository.findAll(page, size).map { it.toResponse() }
        return PagedResponse(txs, page, size, txs.size)
    }

    // ── Persistence helpers ───────────────────────────────────────────────

    @Transactional
    fun persistWithEvents(tx: Transaction): Transaction {
        val saved = transactionRepository.save(tx)
        eventPublisher.publish(tx.domainEvents)
        tx.clearDomainEvents()
        return saved
    }

    @Transactional
    fun persistOnly(tx: Transaction) {
        transactionRepository.save(tx)
    }

    // ── Mapping ───────────────────────────────────────────────────────────

    private fun Transaction.toResponse() = TransactionResponse(
        id = id.value,
        sourceAccountId = sourceAccountId.value,
        targetAccountId = targetAccountId?.value,
        amount = amount.value,
        currency = amount.currency,
        type = type,
        status = status,
        description = description,
        failureReason = failureReason,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
