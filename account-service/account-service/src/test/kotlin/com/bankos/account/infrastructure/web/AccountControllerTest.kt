package com.bankos.account.infrastructure.web

import com.bankos.account.application.dto.*
import com.bankos.account.application.service.AccountService
import com.bankos.account.domain.model.*
import com.bankos.account.infrastructure.web.controller.AccountController
import com.bankos.account.infrastructure.web.controller.GlobalExceptionHandler
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

/**
 * AccountControllerTest — Web layer slice test
 *
 * Uses @WebMvcTest to load only the web layer (no full Spring context).
 * Tests HTTP concerns: status codes, request validation, auth, JSON mapping.
 *
 * Business logic is NOT tested here — that's AccountServiceTest's job.
 */
@WebMvcTest(AccountController::class)
@Import(GlobalExceptionHandler::class)
class AccountControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var accountService: AccountService

    @TestConfiguration
    class MockConfig {
        @Bean
        fun accountService() = mockk<AccountService>()
    }

    private val sampleId = UUID.randomUUID()
    private val sampleResponse = AccountResponse(
        id = sampleId,
        ownerId = "user-abc",
        currency = Currency.EUR,
        balance = BigDecimal("1000.00"),
        status = AccountStatus.ACTIVE,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    // ── GET /accounts/{id} ────────────────────────────────────────────────

    @Nested
    inner class `GET account by id` {

        @Test
        @WithMockUser(roles = ["USER"])
        fun `should return 200 with account details`() {
            every { accountService.getAccount(sampleId) } returns sampleResponse

            mockMvc.get("/api/v1/accounts/$sampleId")
                .andExpect {
                    status { isOk() }
                    content { contentType(MediaType.APPLICATION_JSON) }
                    jsonPath("$.id") { value(sampleId.toString()) }
                    jsonPath("$.balance") { value(1000.00) }
                    jsonPath("$.status") { value("ACTIVE") }
                }
        }

        @Test
        fun `should return 401 when no authentication`() {
            mockMvc.get("/api/v1/accounts/$sampleId")
                .andExpect { status { isUnauthorized() } }
        }

        @Test
        @WithMockUser(roles = ["USER"])
        fun `should return 404 when account not found`() {
            every { accountService.getAccount(sampleId) } throws AccountNotFoundException(AccountId(sampleId))

            mockMvc.get("/api/v1/accounts/$sampleId")
                .andExpect { status { isNotFound() } }
        }
    }

    // ── POST /accounts ────────────────────────────────────────────────────

    @Nested
    inner class `POST open account` {

        @Test
        @WithMockUser(roles = ["USER"])
        fun `should return 201 on successful account creation`() {
            every { accountService.openAccount(any()) } returns sampleResponse

            val body = mapOf(
                "ownerId" to "user-abc",
                "currency" to "EUR",
                "initialDeposit" to 0,
            )

            mockMvc.post("/api/v1/accounts") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }.andExpect {
                status { isCreated() }
                jsonPath("$.ownerId") { value("user-abc") }
            }
        }

        @Test
        @WithMockUser(roles = ["USER"])
        fun `should return 400 when ownerId is blank`() {
            val body = mapOf("ownerId" to "", "currency" to "EUR")

            mockMvc.post("/api/v1/accounts") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.title") { value("Validation Failed") }
            }
        }
    }

    // ── POST /accounts/{id}/debit ─────────────────────────────────────────

    @Nested
    inner class `POST debit` {

        @Test
        @WithMockUser(roles = ["BACKOFFICE"])
        fun `should return 200 on successful debit`() {
            val debited = sampleResponse.copy(balance = BigDecimal("700.00"))
            every { accountService.debit(any()) } returns debited

            val body = mapOf("amount" to 300.00, "reference" to "TX-001")

            mockMvc.post("/api/v1/accounts/$sampleId/debit") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }.andExpect {
                status { isOk() }
                jsonPath("$.balance") { value(700.00) }
            }
        }

        @Test
        @WithMockUser(roles = ["BACKOFFICE"])
        fun `should return 422 when insufficient funds`() {
            every { accountService.debit(any()) } throws InsufficientFundsException(
                AccountId(sampleId), BigDecimal("50.00"), BigDecimal("300.00")
            )

            val body = mapOf("amount" to 300.00, "reference" to "TX-002")

            mockMvc.post("/api/v1/accounts/$sampleId/debit") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }.andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.title") { value("Insufficient Funds") }
            }
        }

        @Test
        @WithMockUser(roles = ["USER"])  // USER cannot debit — only BACKOFFICE
        fun `should return 403 when USER role tries to debit`() {
            val body = mapOf("amount" to 100.00, "reference" to "TX-003")

            mockMvc.post("/api/v1/accounts/$sampleId/debit") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }.andExpect {
                status { isForbidden() }
            }
        }
    }
}
