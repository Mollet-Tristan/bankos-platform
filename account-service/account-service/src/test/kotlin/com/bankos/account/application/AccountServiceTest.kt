package com.bankos.account.application

import com.bankos.account.application.dto.CreditAccountCommand
import com.bankos.account.application.dto.DebitAccountCommand
import com.bankos.account.application.dto.OpenAccountCommand
import com.bankos.account.domain.model.*
import com.bankos.account.domain.port.AccountRepository
import com.bankos.account.domain.port.EventPublisher
import com.bankos.account.application.service.AccountService
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID

/**
 * AccountServiceTest — Application layer unit tests
 *
 * Uses MockK to isolate the service from infrastructure.
 * Verifies:
 *  - Correct delegation to the domain aggregate
 *  - Repository interactions (save called after operations)
 *  - Event publication after each state-changing operation
 *  - Error propagation from domain to caller
 */
class AccountServiceTest {

    private val accountRepository = mockk<AccountRepository>()
    private val eventPublisher = mockk<EventPublisher>(relaxed = true)

    private lateinit var accountService: AccountService

    @BeforeEach
    fun setUp() {
        accountService = AccountService(accountRepository, eventPublisher)
    }

    @Nested
    inner class `Open account` {

        @Test
        fun `should open account and publish AccountCreated event`() {
            val command = OpenAccountCommand(
                ownerId = "user-abc",
                currency = Currency.EUR,
                initialDeposit = BigDecimal("100.00"),
            )
            val savedSlot = slot<Account>()
            every { accountRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            val response = accountService.openAccount(command)

            assertEquals("user-abc", response.ownerId)
            assertEquals(Currency.EUR, response.currency)
            assertEquals(BigDecimal("100.00"), response.balance)
            assertEquals(AccountStatus.ACTIVE, response.status)

            verify(exactly = 1) { accountRepository.save(any()) }
            verify(exactly = 1) { eventPublisher.publish(any()) }
        }
    }

    @Nested
    inner class `Debit account` {

        @Test
        fun `should debit account and publish event`() {
            val account = activeAccountWithBalance("500.00")
            val accountId = account.id.value
            val command = DebitAccountCommand(accountId, BigDecimal("200.00"), "REF-001")

            every { accountRepository.findById(AccountId(accountId)) } returns account
            every { accountRepository.save(any()) } answers { firstArg() }

            val response = accountService.debit(command)

            assertEquals(BigDecimal("300.00"), response.balance)
            verify(exactly = 1) { eventPublisher.publish(any()) }
        }

        @Test
        fun `should propagate InsufficientFundsException`() {
            val account = activeAccountWithBalance("50.00")
            val accountId = account.id.value
            val command = DebitAccountCommand(accountId, BigDecimal("200.00"), "REF-002")

            every { accountRepository.findById(AccountId(accountId)) } returns account

            assertThrows<InsufficientFundsException> {
                accountService.debit(command)
            }
            verify(exactly = 0) { accountRepository.save(any()) }
            verify(exactly = 0) { eventPublisher.publish(any()) }
        }

        @Test
        fun `should throw AccountNotFoundException when account does not exist`() {
            val randomId = UUID.randomUUID()
            every { accountRepository.findById(AccountId(randomId)) } returns null

            assertThrows<AccountNotFoundException> {
                accountService.debit(DebitAccountCommand(randomId, BigDecimal("10.00"), "REF-003"))
            }
        }
    }

    @Nested
    inner class `Credit account` {

        @Test
        fun `should credit account and publish event`() {
            val account = activeAccountWithBalance("200.00")
            val accountId = account.id.value
            val command = CreditAccountCommand(accountId, BigDecimal("150.00"), "REF-010")

            every { accountRepository.findById(AccountId(accountId)) } returns account
            every { accountRepository.save(any()) } answers { firstArg() }

            val response = accountService.credit(command)

            assertEquals(BigDecimal("350.00"), response.balance)
            verify(exactly = 1) { eventPublisher.publish(any()) }
        }
    }

    @Nested
    inner class `Freeze and unfreeze` {

        @Test
        fun `should freeze an active account`() {
            val account = activeAccountWithBalance("100.00")
            every { accountRepository.findById(account.id) } returns account
            every { accountRepository.save(any()) } answers { firstArg() }

            val response = accountService.freezeAccount(account.id.value)

            assertEquals(AccountStatus.FROZEN, response.status)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun activeAccountWithBalance(balance: String): Account {
        val account = Account.open(
            ownerId = "user-test",
            currency = Currency.EUR,
            initialDeposit = BigDecimal(balance),
        )
        account.clearDomainEvents()
        return account
    }
}
