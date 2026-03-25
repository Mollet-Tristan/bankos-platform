package com.bankos.account.application.dto

import com.bankos.account.domain.model.AccountStatus
import com.bankos.account.domain.model.Currency
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// ── Commands (write side) ──────────────────────────────────────────────────────

data class OpenAccountCommand(
    @field:NotBlank(message = "ownerId is required")
    val ownerId: String,

    @field:NotNull(message = "currency is required")
    val currency: Currency,

    @field:DecimalMin(value = "0.0", message = "initialDeposit must be >= 0")
    val initialDeposit: BigDecimal = BigDecimal.ZERO,
)

data class DebitAccountCommand(
    val accountId: UUID,

    @field:DecimalMin(value = "0.01", inclusive = true, message = "amount must be > 0")
    val amount: BigDecimal,

    @field:NotBlank(message = "reference is required")
    val reference: String,
)

data class CreditAccountCommand(
    val accountId: UUID,

    @field:DecimalMin(value = "0.01", inclusive = true, message = "amount must be > 0")
    val amount: BigDecimal,

    @field:NotBlank(message = "reference is required")
    val reference: String,
)

// ── Responses (read side) ─────────────────────────────────────────────────────

data class AccountResponse(
    val id: UUID,
    val ownerId: String,
    val currency: Currency,
    val balance: BigDecimal,
    val status: AccountStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class BalanceResponse(
    val accountId: UUID,
    val balance: BigDecimal,
    val currency: Currency,
    val status: AccountStatus,
)

data class PagedResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val total: Int,
)
