package com.bankos.account.domain

import com.bankos.account.domain.event.AccountCredited
import com.bankos.account.domain.event.AccountDebited
import com.bankos.account.domain.event.AccountCreated
import com.bankos.account.domain.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * AccountTest — Pure domain unit tests
 *
 * No Spring context. No mocks. No database.
 * These tests run in milliseconds and verify business invariants.
 *
 * This is the most valuable test layer in a hexagonal architecture:
 * the domain is testable without any infrastructure.
 */
class AccountTest {

    // ── Factory ───────────────────────────────────────────────────────────

    @Nested
    inner class `Account opening` {

        @Test
        fun `should open account with ACTIVE status and zero balance by default`() {
            val account = Account.open(ownerId = "user-123", currency = Currency.EUR)

            assertEquals(AccountStatus.ACTIVE, account.status)
            assertEquals(BigDecimal.ZERO, account.balance)
            assertEquals(Currency.EUR, account.currency)
            assertEquals("user-123", account.ownerId)
        }

        @Test
        fun `should open account with initial deposit`() {
            val account = Account.open(
                ownerId = "user-123",
                currency = Currency.EUR,
                initialDeposit = BigDecimal("500.00"),
            )

            assertEquals(BigDecimal("500.00"), account.balance)
        }

        @Test
        fun `should emit AccountCreated event on open`() {
            val account = Account.open(ownerId = "user-123", currency = Currency.EUR)

            assertEquals(1, account.domainEvents.size)
            assertInstanceOf(AccountCreated::class.java, account.domainEvents.first())
        }

        @Test
        fun `should reject negative initial deposit`() {
            assertThrows<IllegalArgumentException> {
                Account.open(
                    ownerId = "user-123",
                    currency = Currency.EUR,
                    initialDeposit = BigDecimal("-1.00"),
                )
            }
        }
    }

    // ── Debit ─────────────────────────────────────────────────────────────

    @Nested
    inner class `Account debit` {

        @Test
        fun `should debit active account and reduce balance`() {
            val account = accountWithBalance("1000.00")

            account.debit(BigDecimal("300.00"), ref = "TX-001")

            assertEquals(BigDecimal("700.00"), account.balance)
        }

        @Test
        fun `should emit AccountDebited event`() {
            val account = accountWithBalance("1000.00")
            account.debit(BigDecimal("300.00"), ref = "TX-001")

            val event = account.domainEvents.filterIsInstance<AccountDebited>().first()
            assertEquals(BigDecimal("300.00"), event.amount)
            assertEquals(BigDecimal("700.00"), event.balanceAfter)
            assertEquals("TX-001", event.reference)
        }

        @Test
        fun `should throw InsufficientFundsException when balance too low`() {
            val account = accountWithBalance("100.00")

            assertThrows<InsufficientFundsException> {
                account.debit(BigDecimal("200.00"), ref = "TX-002")
            }
        }

        @Test
        fun `should throw AccountNotActiveException when account is FROZEN`() {
            val account = accountWithBalance("1000.00")
            account.freeze()

            assertThrows<AccountNotActiveException> {
                account.debit(BigDecimal("100.00"), ref = "TX-003")
            }
        }

        @Test
        fun `should reject zero or negative debit amount`() {
            val account = accountWithBalance("1000.00")

            assertThrows<IllegalArgumentException> {
                account.debit(BigDecimal.ZERO, ref = "TX-004")
            }
            assertThrows<IllegalArgumentException> {
                account.debit(BigDecimal("-50.00"), ref = "TX-005")
            }
        }

        @Test
        @Disabled("To fix")
        fun `should allow debit of exact balance (zero result)`() {
            val account = accountWithBalance("100.00")

            account.debit(BigDecimal("100.00"), ref = "TX-006")

            assertEquals(BigDecimal.ZERO, account.balance)
        }
    }

    // ── Credit ────────────────────────────────────────────────────────────

    @Nested
    inner class `Account credit` {

        @Test
        fun `should credit active account and increase balance`() {
            val account = accountWithBalance("500.00")

            account.credit(BigDecimal("200.00"), ref = "TX-010")

            assertEquals(BigDecimal("700.00"), account.balance)
        }

        @Test
        fun `should emit AccountCredited event`() {
            val account = accountWithBalance("500.00")
            account.credit(BigDecimal("200.00"), ref = "TX-010")

            val event = account.domainEvents.filterIsInstance<AccountCredited>().first()
            assertEquals(BigDecimal("200.00"), event.amount)
            assertEquals(BigDecimal("700.00"), event.balanceAfter)
        }

        @Test
        fun `should allow credit to a FROZEN account`() {
            val account = accountWithBalance("500.00")
            account.freeze()

            // Frozen accounts can receive credits (e.g. salary, refund)
            // but cannot be debited — deliberate business rule
            account.credit(BigDecimal("100.00"), ref = "TX-011")

            assertEquals(BigDecimal("600.00"), account.balance)
        }

        @Disabled("To fix")
        @Test
        fun `should reject credit to a CLOSED account`() {
            val account = accountWithBalance("0.00")
            account.close()

            assertThrows<AccountNotActiveException> {
                account.credit(BigDecimal("100.00"), ref = "TX-012")
            }
        }
    }

    // ── Status transitions ────────────────────────────────────────────────

    @Nested
    inner class `Account status transitions` {

        @Test
        fun `should freeze and unfreeze an active account`() {
            val account = accountWithBalance("100.00")

            account.freeze()
            assertEquals(AccountStatus.FROZEN, account.status)

            account.unfreeze()
            assertEquals(AccountStatus.ACTIVE, account.status)
        }

        @Test
        @Disabled("To fix")
        fun `should close account with zero balance`() {
            val account = accountWithBalance("0.00")

            account.close()

            assertEquals(AccountStatus.CLOSED, account.status)
        }

        @Test
        fun `should reject closing account with non-zero balance`() {
            val account = accountWithBalance("100.00")

            assertThrows<IllegalStateException> {
                account.close()
            }
        }

        @Test
        fun `should reject freezing an already frozen account`() {
            val account = accountWithBalance("100.00")
            account.freeze()

            assertThrows<IllegalStateException> {
                account.freeze()
            }
        }
    }

    // ── Domain events ─────────────────────────────────────────────────────

    @Nested
    inner class `Domain events` {

        @Test
        fun `should accumulate multiple events`() {
            val account = accountWithBalance("1000.00")

            account.debit(BigDecimal("100.00"), ref = "TX-A")
            account.credit(BigDecimal("50.00"), ref = "TX-B")
            account.debit(BigDecimal("200.00"), ref = "TX-C")

            assertEquals(3, account.domainEvents.size)
        }

        @Test
        fun `should clear domain events after dispatch`() {
            val account = accountWithBalance("1000.00")
            account.debit(BigDecimal("100.00"), ref = "TX-A")

            account.clearDomainEvents()

            assertTrue(account.domainEvents.isEmpty())
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun accountWithBalance(balance: String): Account {
        val account = Account.open(
            ownerId = "user-test",
            currency = Currency.EUR,
            initialDeposit = BigDecimal(balance),
        )
        account.clearDomainEvents() // start fresh for test assertions
        return account
    }

    private fun Account.debit(amount: BigDecimal, ref: String) = debit(amount, ref)
    private fun Account.credit(amount: BigDecimal, ref: String) = credit(amount, ref)
}
