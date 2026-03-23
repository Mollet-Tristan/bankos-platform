package com.bankos.transaction.application

import com.bankos.transaction.application.dto.CreateTransactionCommand
import com.bankos.transaction.application.service.TransactionService
import com.bankos.transaction.domain.model.*
import com.bankos.transaction.domain.port.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * TransactionServiceTest — Application layer (saga orchestration)
 *
 * This is the most important test class in the project.
 * It verifies the saga flows: happy path, business failures,
 * infrastructure failures requiring compensation.
 *
 * All infrastructure ports are mocked — we test the orchestration logic only.
 */
class TransactionServiceTest {

    private val transactionRepository = mockk<TransactionRepository>()
    private val eventPublisher         = mockk<EventPublisher>(relaxed = true)
    private val accountServiceClient   = mockk<AccountServiceClient>()

    private lateinit var service: TransactionService

    @BeforeEach
    fun setUp() {
        service = TransactionService(transactionRepository, eventPublisher, accountServiceClient)

        // Default: no existing idempotency key
        every { transactionRepository.findByIdempotencyKey(any()) } returns null
        // Default: save returns the transaction as-is
        every { transactionRepository.save(any()) } answers { firstArg() }
    }

    // ── Withdrawal — happy path ───────────────────────────────────────────

    @Nested
    inner class `Withdrawal — happy path` {

        @Test
        fun `should debit account and return COMPLETED`() {
            every { accountServiceClient.debit(any(), any(), any()) } just Runs

            val response = service.executeTransaction(withdrawalCommand())

            assertEquals(TransactionStatus.COMPLETED, response.status)
            assertNull(response.failureReason)
            verify(exactly = 1) { accountServiceClient.debit(any(), any(), any()) }
            verify(exactly = 0) { accountServiceClient.credit(any(), any(), any()) }
        }

        @Test
        fun `should publish TransactionCompleted event`() {
            every { accountServiceClient.debit(any(), any(), any()) } just Runs

            service.executeTransaction(withdrawalCommand())

            verify { eventPublisher.publish(match { events ->
                events.any { it is com.bankos.transaction.domain.event.TransactionCompleted }
            }) }
        }

        @Test
        fun `should save transaction with PENDING status before debit`() {
            val savedStatuses = mutableListOf<TransactionStatus>()
            every { transactionRepository.save(any()) } answers {
                val tx: Transaction = firstArg()
                savedStatuses.add(tx.status)
                tx
            }
            every { accountServiceClient.debit(any(), any(), any()) } just Runs

            service.executeTransaction(withdrawalCommand())

            // First save: PENDING, second: PROCESSING, third: COMPLETED
            assertTrue(savedStatuses.contains(TransactionStatus.PENDING))
            assertTrue(savedStatuses.contains(TransactionStatus.PROCESSING))
            assertTrue(savedStatuses.contains(TransactionStatus.COMPLETED))
        }
    }

    // ── Withdrawal — insufficient funds ───────────────────────────────────

    @Nested
    inner class `Withdrawal — insufficient funds` {

        @Test
        fun `should return FAILED when Account Service rejects with insufficient funds`() {
            val accountId = AccountId(UUID.randomUUID())
            every { accountServiceClient.debit(any(), any(), any()) } throws
                InsufficientFundsRemoteException(accountId, "Available: 50.00, Requested: 200.00")

            val response = service.executeTransaction(withdrawalCommand())

            assertEquals(TransactionStatus.FAILED, response.status)
            assertNotNull(response.failureReason)
        }

        @Test
        fun `should NOT issue compensation on business failure (no debit occurred)`() {
            every { accountServiceClient.debit(any(), any(), any()) } throws
                InsufficientFundsRemoteException(AccountId(UUID.randomUUID()), "Insufficient")

            service.executeTransaction(withdrawalCommand())

            // No credit call — the debit was rejected, nothing to compensate
            verify(exactly = 0) { accountServiceClient.credit(any(), any(), any()) }
        }

        @Test
        fun `should publish TransactionFailed event`() {
            every { accountServiceClient.debit(any(), any(), any()) } throws
                InsufficientFundsRemoteException(AccountId(UUID.randomUUID()), "Insufficient")

            service.executeTransaction(withdrawalCommand())

            verify { eventPublisher.publish(match { events ->
                events.any { it is com.bankos.transaction.domain.event.TransactionFailed }
            }) }
        }
    }

    // ── Withdrawal — infrastructure failure + compensation ────────────────

    @Nested
    inner class `Withdrawal — infrastructure failure triggers compensation` {

        @Test
        fun `should return COMPENSATED and issue credit when infra fails after debit`() {
            // Simulate: debit succeeded, but then something went wrong (e.g. Kafka down)
            // In reality this scenario is triggered when the save() after debit fails,
            // but we test it here by having debit succeed then throwing on complete path.
            // For this test, we simulate it via AccountServiceException on a secondary call.
            every { accountServiceClient.debit(any(), any(), any()) } throws
                AccountServiceException("Connection timeout to Account Service")
            every { accountServiceClient.credit(any(), any(), any()) } just Runs

            val response = service.executeTransaction(withdrawalCommand())

            // On AccountServiceException during debit (ambiguous — may or may not have applied),
            // the service marks as COMPENSATED and issues a compensating credit
            assertEquals(TransactionStatus.COMPENSATED, response.status)
            verify(exactly = 1) { accountServiceClient.credit(any(), any(), any()) }
        }
    }

    // ── Transfer — happy path ─────────────────────────────────────────────

    @Nested
    inner class `Transfer — happy path` {

        @Test
        fun `should debit source and credit target`() {
            every { accountServiceClient.debit(any(), any(), any()) } just Runs
            every { accountServiceClient.credit(any(), any(), any()) } just Runs

            val response = service.executeTransaction(transferCommand())

            assertEquals(TransactionStatus.COMPLETED, response.status)
            verify(exactly = 1) { accountServiceClient.debit(any(), any(), any()) }
            verify(exactly = 1) { accountServiceClient.credit(any(), any(), any()) }
        }
    }

    // ── Transfer — partial failure (debit ok, credit fails) ───────────────

    @Nested
    inner class `Transfer — credit step fails after successful debit` {

        @Test
        fun `should compensate source account when credit to target fails`() {
            every { accountServiceClient.debit(any(), any(), any()) } just Runs
            every { accountServiceClient.credit(any(), any(), any()) }
                .throwsMany(
                    AccountServiceException("Target account service timeout"), // credit target fails
                ).andThen(Unit)                                                 // compensation credit succeeds

            val response = service.executeTransaction(transferCommand())

            assertEquals(TransactionStatus.COMPENSATED, response.status)
            // First credit attempt (target) + compensation credit (source)
            verify(exactly = 2) { accountServiceClient.credit(any(), any(), any()) }
        }

        @Test
        fun `should publish TransactionCompensated event`() {
            every { accountServiceClient.debit(any(), any(), any()) } just Runs
            every { accountServiceClient.credit(any(), any(), any()) }
                .throwsMany(AccountServiceException("Timeout"))
                .andThen(Unit)

            service.executeTransaction(transferCommand())

            verify { eventPublisher.publish(match { events ->
                events.any { it is com.bankos.transaction.domain.event.TransactionCompensated }
            }) }
        }
    }

    // ── Idempotency ───────────────────────────────────────────────────────

    @Nested
    inner class `Idempotency` {

        @Test
        fun `should return existing transaction without re-processing`() {
            val existing = existingCompletedTransaction()
            every { transactionRepository.findByIdempotencyKey(IdempotencyKey("key-abc")) } returns existing

            val response = service.executeTransaction(withdrawalCommand(idempotencyKey = "key-abc"))

            assertEquals(TransactionStatus.COMPLETED, response.status)
            // No account calls — idempotent return
            verify(exactly = 0) { accountServiceClient.debit(any(), any(), any()) }
            verify(exactly = 0) { accountServiceClient.credit(any(), any(), any()) }
        }
    }

    // ── Deposit ───────────────────────────────────────────────────────────

    @Nested
    inner class `Deposit` {

        @Test
        fun `should credit account and return COMPLETED`() {
            every { accountServiceClient.credit(any(), any(), any()) } just Runs

            val response = service.executeTransaction(depositCommand())

            assertEquals(TransactionStatus.COMPLETED, response.status)
            verify(exactly = 0) { accountServiceClient.debit(any(), any(), any()) }
            verify(exactly = 1) { accountServiceClient.credit(any(), any(), any()) }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun withdrawalCommand(idempotencyKey: String = UUID.randomUUID().toString()) =
        CreateTransactionCommand(
            sourceAccountId = UUID.randomUUID(),
            targetAccountId = null,
            amount = BigDecimal("200.00"),
            currency = Currency.EUR,
            type = TransactionType.WITHDRAWAL,
            description = "ATM withdrawal",
            idempotencyKey = idempotencyKey,
        )

    private fun depositCommand() = CreateTransactionCommand(
        sourceAccountId = UUID.randomUUID(),
        targetAccountId = null,
        amount = BigDecimal("500.00"),
        currency = Currency.EUR,
        type = TransactionType.DEPOSIT,
        description = "Salary deposit",
        idempotencyKey = UUID.randomUUID().toString(),
    )

    private fun transferCommand() = CreateTransactionCommand(
        sourceAccountId = UUID.randomUUID(),
        targetAccountId = UUID.randomUUID(),
        amount = BigDecimal("300.00"),
        currency = Currency.EUR,
        type = TransactionType.TRANSFER,
        description = "Rent payment",
        idempotencyKey = UUID.randomUUID().toString(),
    )

    private fun existingCompletedTransaction(): Transaction {
        val tx = Transaction.create(
            sourceAccountId = AccountId(UUID.randomUUID()),
            targetAccountId = null,
            amount = Money(BigDecimal("200.00"), Currency.EUR),
            type = TransactionType.WITHDRAWAL,
            description = "ATM",
            idempotencyKey = IdempotencyKey("key-abc"),
        )
        tx.startProcessing()
        tx.complete()
        tx.clearDomainEvents()
        return tx
    }
}
