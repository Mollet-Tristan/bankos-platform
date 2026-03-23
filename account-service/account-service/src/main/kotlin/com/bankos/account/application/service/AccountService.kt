package com.bankos.account.application.service

import com.bankos.account.application.dto.*
import com.bankos.account.domain.model.*
import com.bankos.account.domain.port.AccountRepository
import com.bankos.account.domain.port.EventPublisher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * AccountService — Application Service
 *
 * Orchestrates use cases by coordinating the domain model and ports.
 * It has NO business logic itself — that lives in [Account].
 *
 * Responsibilities:
 *  1. Load the aggregate via the repository port
 *  2. Call domain operations
 *  3. Persist the updated aggregate
 *  4. Dispatch collected domain events via the event publisher port
 *
 * The @Transactional boundary ensures that persistence and event
 * dispatch are atomic from the application's point of view.
 * (For true at-least-once delivery, consider the Outbox pattern — see ADR-004)
 */
@Service
@Transactional
class AccountService(
    private val accountRepository: AccountRepository,
    private val eventPublisher: EventPublisher,
) {
    private val log = LoggerFactory.getLogger(AccountService::class.java)

    fun openAccount(command: OpenAccountCommand): AccountResponse {
        log.info("Opening account for owner=${command.ownerId} currency=${command.currency}")

        val account = Account.open(
            ownerId = command.ownerId,
            currency = command.currency,
            initialDeposit = command.initialDeposit,
        )

        val saved = accountRepository.save(account)
        eventPublisher.publish(saved.domainEvents)
        saved.clearDomainEvents()

        log.info("Account opened: id=${saved.id}")
        return saved.toResponse()
    }

    fun debit(command: DebitAccountCommand): AccountResponse {
        val account = loadAccount(AccountId(command.accountId))
        account.debit(command.amount, command.reference)

        val saved = accountRepository.save(account)
        eventPublisher.publish(saved.domainEvents)
        saved.clearDomainEvents()

        return saved.toResponse()
    }

    fun credit(command: CreditAccountCommand): AccountResponse {
        val account = loadAccount(AccountId(command.accountId))
        account.credit(command.amount, command.reference)

        val saved = accountRepository.save(account)
        eventPublisher.publish(saved.domainEvents)
        saved.clearDomainEvents()

        return saved.toResponse()
    }

    @Transactional(readOnly = true)
    fun getAccount(id: UUID): AccountResponse =
        loadAccount(AccountId(id)).toResponse()

    @Transactional(readOnly = true)
    fun getBalance(id: UUID): BalanceResponse {
        val account = loadAccount(AccountId(id))
        return BalanceResponse(
            accountId = id,
            balance = account.balance,
            currency = account.currency,
            status = account.status,
        )
    }

    @Transactional(readOnly = true)
    fun getAccountsByOwner(ownerId: String): List<AccountResponse> =
        accountRepository.findByOwnerId(ownerId).map { it.toResponse() }

    @Transactional(readOnly = true)
    fun listAccounts(page: Int, size: Int): PagedResponse<AccountResponse> {
        val accounts = accountRepository.findAll(page, size).map { it.toResponse() }
        return PagedResponse(content = accounts, page = page, size = size, total = accounts.size)
    }

    fun freezeAccount(id: UUID): AccountResponse {
        val account = loadAccount(AccountId(id))
        account.freeze()
        return accountRepository.save(account).toResponse()
    }

    fun unfreezeAccount(id: UUID): AccountResponse {
        val account = loadAccount(AccountId(id))
        account.unfreeze()
        return accountRepository.save(account).toResponse()
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun loadAccount(id: AccountId): Account =
        accountRepository.findById(id) ?: throw AccountNotFoundException(id)

    private fun Account.toResponse() = AccountResponse(
        id = this.id.value,
        ownerId = this.ownerId,
        currency = this.currency,
        balance = this.balance,
        status = this.status,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
    )
}
