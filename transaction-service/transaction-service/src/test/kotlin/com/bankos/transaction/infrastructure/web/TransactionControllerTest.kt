package com.bankos.transaction.infrastructure.web

import com.bankos.transaction.application.dto.PagedResponse
import com.bankos.transaction.application.dto.TransactionResponse
import com.bankos.transaction.application.service.TransactionService
import com.bankos.transaction.domain.model.*
import com.bankos.transaction.infrastructure.web.controller.GlobalExceptionHandler
import com.bankos.transaction.infrastructure.web.controller.TransactionController
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@WebMvcTest(TransactionController::class)
@Import(GlobalExceptionHandler::class)
class TransactionControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var transactionService: TransactionService

    @TestConfiguration
    class MockConfig {
        @Bean fun transactionService() = mockk<TransactionService>()
    }

    private val sampleId = UUID.randomUUID()
    private val sampleAccountId = UUID.randomUUID()

    private val completedResponse = TransactionResponse(
        id = sampleId,
        sourceAccountId = sampleAccountId,
        targetAccountId = null,
        amount = BigDecimal("200.00"),
        currency = Currency.EUR,
        type = TransactionType.WITHDRAWAL,
        status = TransactionStatus.COMPLETED,
        description = "ATM withdrawal",
        failureReason = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private val validBody = mapOf(
        "sourceAccountId" to sampleAccountId,
        "targetAccountId" to null,
        "amount" to 200.00,
        "currency" to "EUR",
        "type" to "WITHDRAWAL",
        "description" to "ATM withdrawal",
    )

    // ── POST /transactions ────────────────────────────────────────────────

    @Nested
    inner class `POST create transaction` {

        @Test
        @WithMockUser(roles = ["BACKOFFICE"])
        fun `should return 201 with Idempotency-Key header`() {
            every { transactionService.executeTransaction(any()) } returns completedResponse

            mockMvc.post("/api/v1/transactions") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(validBody)
                header("Idempotency-Key", UUID.randomUUID().toString())
            }.andExpect {
                status { isCreated() }
                jsonPath("$.status") { value("COMPLETED") }
                jsonPath("$.amount") { value(200.00) }
            }
        }

        @Test
        @WithMockUser(roles = ["BACKOFFICE"])
        fun `should return 400 when Idempotency-Key header is missing`() {
            mockMvc.post("/api/v1/transactions") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(validBody)
                // No Idempotency-Key header
            }.andExpect {
                status { isBadRequest() }
            }
        }

        @Test
        fun `should return 401 when not authenticated`() {
            mockMvc.post("/api/v1/transactions") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(validBody)
                header("Idempotency-Key", UUID.randomUUID().toString())
            }.andExpect {
                status { isUnauthorized() }
            }
        }

        @Test
        @WithMockUser(roles = ["BACKOFFICE"])
        fun `should return FAILED response (not 4xx) when account has insufficient funds`() {
            // Insufficient funds is a BUSINESS outcome, not an error.
            // The transaction is FAILED but the HTTP response is 201.
            val failedResponse = completedResponse.copy(
                status = TransactionStatus.FAILED,
                failureReason = "Insufficient funds",
            )
            every { transactionService.executeTransaction(any()) } returns failedResponse

            mockMvc.post("/api/v1/transactions") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(validBody)
                header("Idempotency-Key", UUID.randomUUID().toString())
            }.andExpect {
                status { isCreated() }
                jsonPath("$.status") { value("FAILED") }
                jsonPath("$.failureReason") { value("Insufficient funds") }
            }
        }

        @Test
        @WithMockUser(roles = ["BACKOFFICE"])
        fun `should return 503 when Account Service is unavailable`() {
            every { transactionService.executeTransaction(any()) } throws
                com.bankos.transaction.domain.port.AccountServiceException("Circuit breaker open")

            mockMvc.post("/api/v1/transactions") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(validBody)
                header("Idempotency-Key", UUID.randomUUID().toString())
            }.andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.title") { value("Upstream Service Unavailable") }
            }
        }

        @Test
        @WithMockUser(roles = ["BACKOFFICE"])
        fun `should return COMPENSATED when saga compensation was triggered`() {
            val compensatedResponse = completedResponse.copy(
                status = TransactionStatus.COMPENSATED,
                failureReason = "Credit to target failed after debit",
            )
            every { transactionService.executeTransaction(any()) } returns compensatedResponse

            mockMvc.post("/api/v1/transactions") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(validBody)
                header("Idempotency-Key", UUID.randomUUID().toString())
            }.andExpect {
                status { isCreated() }
                jsonPath("$.status") { value("COMPENSATED") }
            }
        }
    }

    // ── GET /transactions/{id} ────────────────────────────────────────────

    @Nested
    inner class `GET transaction by id` {

        @Test
        @WithMockUser(roles = ["USER"])
        fun `should return 200 with transaction details`() {
            every { transactionService.getTransaction(sampleId) } returns completedResponse

            mockMvc.get("/api/v1/transactions/$sampleId")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(sampleId.toString()) }
                    jsonPath("$.type") { value("WITHDRAWAL") }
                    jsonPath("$.currency") { value("EUR") }
                }
        }

        @Test
        @WithMockUser(roles = ["USER"])
        fun `should return 404 when transaction not found`() {
            every { transactionService.getTransaction(sampleId) } throws
                TransactionNotFoundException(TransactionId(sampleId))

            mockMvc.get("/api/v1/transactions/$sampleId")
                .andExpect { status { isNotFound() } }
        }
    }

    // ── GET /transactions/account/{accountId} ─────────────────────────────

    @Nested
    inner class `GET transactions by account` {

        @Test
        @WithMockUser(roles = ["USER"])
        fun `should return paginated transaction list`() {
            every {
                transactionService.getTransactionsByAccount(sampleAccountId, 0, 20)
            } returns PagedResponse(listOf(completedResponse), 0, 20, 1)

            mockMvc.get("/api/v1/transactions/account/$sampleAccountId")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.content[0].id") { value(sampleId.toString()) }
                    jsonPath("$.total") { value(1) }
                }
        }
    }
}
