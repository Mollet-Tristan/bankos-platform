package com.bankos.transaction.domain

import com.bankos.transaction.domain.event.*
import com.bankos.transaction.domain.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID

/**
 * TransactionTest — Pure domain unit tests.
 *
 * No Spring. No mocks. No database.
 * Verifies the Transaction state machine and all business invariants.
 *
 * Test strategy per layer:
 *  - Domain tests (here)       : state machine, invariants, event emission
 *  - Application tests         : saga orchestration, port interactions
 *  - Infrastructure/web tests  : HTTP contracts, idempotency header
 */
class TransactionTest {

    // ── Factory ───────────────────────────────────────────────────────────

    @Nested
    inner class `Transaction creation` {

        @Test
        fun `should create transaction in PENDING status`() {
            val tx = withdrawal()

            assertEquals(TransactionStatus.PENDING, tx.status)
            assertNotNull(tx.id)
            assertNull(tx.failureReason)
        }

        @Test
        fun `should emit TransactionCreated event on creation`() {
            val tx = withdrawal()

            assertEquals(1, tx.domainEvents.size)
            assertInstanceOf(TransactionCreated::class.java, tx.domainEvents.first())
        }

        @Test
        fun `should reject zero or negative amount`() {
            assertThrows<IllegalArgumentException> {
                Transaction.create(
                    sourceAccountId = AccountId(UUID.randomUUID()),
                    targetAccountId = null,
                    amount = Money(BigDecimal.ZERO, Currency.EUR),
                    type = TransactionType.WITHDRAWAL,
                    description = "test",
                    idempotencyKey = IdempotencyKey("key-1"),
                )
            }
        }

        @Test
        fun `should reject TRANSFER without target account`() {
            assertThrows<IllegalArgumentException> {
                Transaction.create(
                    sourceAccountId = AccountId(UUID.randomUUID()),
                    targetAccountId = null,
                    amount = Money(BigDecimal("100.00"), Currency.EUR),
                    type = TransactionType.TRANSFER,
                    description = "transfer without target",
                    idempotencyKey = IdempotencyKey("key-2"),
                )
            }
        }

        @Test
        fun `should reject TRANSFER to same account`() {
            val accountId = AccountId(UUID.randomUUID())
            assertThrows<IllegalArgumentException> {
                Transaction.create(
                    sourceAccountId = accountId,
                    targetAccountId = accountId,
                    amount = Money(BigDecimal("100.00"), Currency.EUR),
                    type = TransactionType.TRANSFER,
                    description = "self transfer",
                    idempotencyKey = IdempotencyKey("key-3"),
                )
            }
        }
    }

    // ── State machine: happy path ─────────────────────────────────────────

    @Nested
    inner class `Happy path — PENDING to COMPLETED` {

        @Test
        fun `should transition PENDING → PROCESSING → COMPLETED`() {
            val tx = freshWithdrawal()

            tx.startProcessing()
            assertEquals(TransactionStatus.PROCESSING, tx.status)

            tx.complete()
            assertEquals(TransactionStatus.COMPLETED, tx.status)
        }

        @Test
        fun `should emit TransactionCompleted event on complete()`() {
            val tx = freshWithdrawal()
            tx.startProcessing()
            tx.complete()

            val event = tx.domainEvents.filterIsInstance<TransactionCompleted>().first()
            assertEquals(tx.id, event.transactionId)
            assertEquals(tx.amount, event.amount)
        }

        @Test
        fun `should record updatedAt timestamp on each transition`() {
            val tx = freshWithdrawal()
            val t0 = tx.updatedAt

            Thread.sleep(2)
            tx.startProcessing()
            val t1 = tx.updatedAt
            assertTrue(t1 >= t0)

            Thread.sleep(2)
            tx.complete()
            val t2 = tx.updatedAt
            assertTrue(t2 >= t1)
        }
    }

    // ── State machine: failure paths ──────────────────────────────────────

    @Nested
    inner class `Failure path — PENDING to FAILED` {

        @Test
        fun `should transition PROCESSING → FAILED with reason`() {
            val tx = freshWithdrawal()
            tx.startProcessing()
            tx.fail("Insufficient funds")

            assertEquals(TransactionStatus.FAILED, tx.status)
            assertEquals("Insufficient funds", tx.failureReason)
        }

        @Test
        fun `should emit TransactionFailed event`() {
            val tx = freshWithdrawal()
            tx.startProcessing()
            tx.fail("Account frozen")

            val event = tx.domainEvents.filterIsInstance<TransactionFailed>().first()
            assertEquals("Account frozen", event.reason)
        }

        @Test
        fun `should reject fail() on PENDING transaction`() {
            val tx = freshWithdrawal()
            // Must be PROCESSING first
            assertThrows<InvalidTransactionStateException> {
                tx.fail("too early")
            }
        }

        @Test
        fun `should reject fail() on already COMPLETED transaction`() {
            val tx = freshWithdrawal()
            tx.startProcessing()
            tx.complete()

            assertThrows<InvalidTransactionStateException> {
                tx.fail("too late")
            }
        }
    }

    // ── State machine: compensation ───────────────────────────────────────

    @Nested
    inner class `Compensation path — Saga rollback` {

        @Test
        fun `should transition PROCESSING → COMPENSATED with reason`() {
            val tx = freshWithdrawal()
            tx.startProcessing()
            tx.compensate("Credit to target failed after debit")

            assertEquals(TransactionStatus.COMPENSATED, tx.status)
            assertEquals("Credit to target failed after debit", tx.failureReason)
        }

        @Test
        fun `should emit TransactionCompensated event`() {
            val tx = freshWithdrawal()
            tx.startProcessing()
            tx.compensate("Infrastructure timeout")

            val event = tx.domainEvents.filterIsInstance<TransactionCompensated>().first()
            assertEquals(tx.id, event.transactionId)
            assertEquals("Infrastructure timeout", event.reason)
        }

        @Test
        fun `should reject compensate() if not PROCESSING`() {
            val tx = freshWithdrawal()
            // PENDING → COMPENSATED is illegal (debit never happened)
            assertThrows<InvalidTransactionStateException> {
                tx.compensate("should not work")
            }
        }
    }

    // ── Guard: duplicate transitions ──────────────────────────────────────

    @Nested
    inner class `Idempotency guards — duplicate transition attempts` {

        @Test
        fun `should reject startProcessing() if already PROCESSING`() {
            val tx = freshWithdrawal()
            tx.startProcessing()

            assertThrows<InvalidTransactionStateException> {
                tx.startProcessing() // concurrent retry attempt
            }
        }

        @Test
        fun `should reject complete() if already COMPLETED`() {
            val tx = freshWithdrawal()
            tx.startProcessing()
            tx.complete()

            assertThrows<InvalidTransactionStateException> {
                tx.complete()
            }
        }
    }

    // ── Domain events management ──────────────────────────────────────────

    @Nested
    inner class `Domain events` {

        @Test
        fun `should accumulate events across lifecycle`() {
            val tx = freshWithdrawal()
            tx.startProcessing()
            tx.complete()

            // TransactionCompleted
            assertEquals(1, tx.domainEvents.size)
        }

        @Test
        fun `should clear events after dispatch`() {
            val tx = freshWithdrawal()
            tx.startProcessing()
            tx.complete()
            tx.clearDomainEvents()

            assertTrue(tx.domainEvents.isEmpty())
        }
    }

    // ── Money value object ────────────────────────────────────────────────

    @Nested
    inner class `Money value object` {

        @Test
        fun `should add two Money values of same currency`() {
            val a = Money(BigDecimal("100.00"), Currency.EUR)
            val b = Money(BigDecimal("50.00"), Currency.EUR)
            assertEquals(BigDecimal("150.00"), (a + b).value)
        }

        @Test
        fun `should reject adding different currencies`() {
            val eur = Money(BigDecimal("100.00"), Currency.EUR)
            val usd = Money(BigDecimal("50.00"), Currency.USD)
            assertThrows<IllegalArgumentException> { eur + usd }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun withdrawal() = Transaction.create(
        sourceAccountId = AccountId(UUID.randomUUID()),
        targetAccountId = null,
        amount = Money(BigDecimal("200.00"), Currency.EUR),
        type = TransactionType.WITHDRAWAL,
        description = "ATM withdrawal",
        idempotencyKey = IdempotencyKey(UUID.randomUUID().toString()),
    )

    private fun freshWithdrawal(): Transaction {
        val tx = withdrawal()
        tx.clearDomainEvents()
        return tx
    }
}
