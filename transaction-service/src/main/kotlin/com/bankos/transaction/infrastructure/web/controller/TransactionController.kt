package com.bankos.transaction.infrastructure.web.controller

import com.bankos.transaction.application.dto.*
import com.bankos.transaction.application.service.TransactionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * TransactionController — REST Adapter (driving side)
 *
 * Exposes the transaction use cases over HTTP.
 * Idempotency-Key header is extracted here and forwarded to the command.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions", description = "Financial transaction operations")
@SecurityRequirement(name = "bearerAuth")
class TransactionController(
    private val transactionService: TransactionService,
) {

    /**
     * Submit a new transaction.
     *
     * The Idempotency-Key header is REQUIRED for safe retries.
     * Submitting the same key twice returns the original result without re-processing.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ROLE_BACKOFFICE') or hasRole('ROLE_USER')")
    @Operation(
        summary = "Submit a transaction",
        description = "Execute a WITHDRAWAL, DEPOSIT, or TRANSFER. Idempotency-Key header required.",
    )
    fun createTransaction(
        @Valid @RequestBody request: TransactionRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): TransactionResponse =
        transactionService.executeTransaction(request.toCommand(idempotencyKey))

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_BACKOFFICE')")
    @Operation(summary = "Get transaction by ID")
    fun getTransaction(@PathVariable id: UUID): TransactionResponse =
        transactionService.getTransaction(id)

    @GetMapping("/account/{accountId}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_BACKOFFICE')")
    @Operation(summary = "List transactions for an account")
    fun getTransactionsByAccount(
        @PathVariable accountId: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PagedResponse<TransactionResponse> =
        transactionService.getTransactionsByAccount(accountId, page, size)

    @GetMapping
    @PreAuthorize("hasRole('ROLE_BACKOFFICE') or hasRole('ROLE_ADMIN')")
    @Operation(summary = "List all transactions (paginated, admin)")
    fun listTransactions(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PagedResponse<TransactionResponse> =
        transactionService.listTransactions(page, size)
}

// ── Request body ──────────────────────────────────────────────────────────────

data class TransactionRequest(
    val sourceAccountId: UUID,
    val targetAccountId: UUID?,
    val amount: java.math.BigDecimal,
    val currency: com.bankos.transaction.domain.model.Currency,
    val type: com.bankos.transaction.domain.model.TransactionType,
    val description: String,
) {
    fun toCommand(idempotencyKey: String) = CreateTransactionCommand(
        sourceAccountId = sourceAccountId,
        targetAccountId = targetAccountId,
        amount = amount,
        currency = currency,
        type = type,
        description = description,
        idempotencyKey = idempotencyKey,
    )
}
