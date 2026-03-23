package com.bankos.transaction.application.dto

import com.bankos.transaction.domain.model.Currency
import com.bankos.transaction.domain.model.TransactionStatus
import com.bankos.transaction.domain.model.TransactionType
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// ── Commands ──────────────────────────────────────────────────────────────────

data class CreateTransactionCommand(
    @field:NotNull val sourceAccountId: UUID,
    val targetAccountId: UUID?,
    @field:DecimalMin("0.01") val amount: BigDecimal,
    @field:NotNull val currency: Currency,
    @field:NotNull val type: TransactionType,
    @field:NotBlank val description: String,
    /** Caller-provided key for idempotency. Use a UUID per attempt. */
    @field:NotBlank val idempotencyKey: String,
)

// ── Responses ─────────────────────────────────────────────────────────────────

data class TransactionResponse(
    val id: UUID,
    val sourceAccountId: UUID,
    val targetAccountId: UUID?,
    val amount: BigDecimal,
    val currency: Currency,
    val type: TransactionType,
    val status: TransactionStatus,
    val description: String,
    val failureReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PagedResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val total: Int,
)
