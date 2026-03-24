package com.bankos.account.infrastructure.web.controller

import com.bankos.account.application.dto.*
import com.bankos.account.application.service.AccountService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * AccountController — REST Adapter (Driving side)
 *
 * This is the entry point from the outside world.
 * It translates HTTP concerns (request/response, status codes, auth)
 * into application commands and delegates to [AccountService].
 *
 * Security: all endpoints require a valid JWT (via API Gateway).
 * Role-based access is enforced with @PreAuthorize.
 *
 * ADR-001: The API Gateway validates the JWT before forwarding here.
 * This controller trusts the forwarded principal.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Account management operations")
@SecurityRequirement(name = "bearerAuth")
class AccountController(
    private val accountService: AccountService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_BACKOFFICE')")
    @Operation(summary = "Open a new bank account")
    fun openAccount(
        @Valid @RequestBody request: OpenAccountRequest,
    ): AccountResponse =
        accountService.openAccount(request.toCommand())

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_BACKOFFICE')")
    @Operation(summary = "Get account details by ID")
    fun getAccount(@PathVariable id: UUID): AccountResponse =
        accountService.getAccount(id)

    @GetMapping("/{id}/balance")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_BACKOFFICE')")
    @Operation(summary = "Get current balance")
    fun getBalance(@PathVariable id: UUID): BalanceResponse =
        accountService.getBalance(id)

    @GetMapping
    @PreAuthorize("hasRole('ROLE_BACKOFFICE') or hasRole('ROLE_ADMIN')")
    @Operation(summary = "List all accounts (paginated)")
    fun listAccounts(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PagedResponse<AccountResponse> =
        accountService.listAccounts(page, size)

    @GetMapping("/owner/{ownerId}")
    @PreAuthorize("hasRole('ROLE_BACKOFFICE') or hasRole('ROLE_ADMIN')")
    @Operation(summary = "Get accounts by owner ID")
    fun getAccountsByOwner(@PathVariable ownerId: String): List<AccountResponse> =
        accountService.getAccountsByOwner(ownerId)

    @PostMapping("/{id}/debit")
    @PreAuthorize("hasRole('ROLE_BACKOFFICE')")
    @Operation(summary = "Debit an account")
    fun debit(
        @PathVariable id: UUID,
        @Valid @RequestBody request: DebitRequest,
    ): AccountResponse =
        accountService.debit(DebitAccountCommand(id, request.amount, request.reference))

    @PostMapping("/{id}/credit")
    @PreAuthorize("hasRole('ROLE_BACKOFFICE')")
    @Operation(summary = "Credit an account")
    fun credit(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CreditRequest,
    ): AccountResponse =
        accountService.credit(CreditAccountCommand(id, request.amount, request.reference))

    @PostMapping("/{id}/freeze")
    @PreAuthorize("hasRole('ROLE_BACKOFFICE') or hasRole('ROLE_ADMIN')")
    @Operation(summary = "Freeze an account")
    fun freeze(@PathVariable id: UUID): AccountResponse =
        accountService.freezeAccount(id)

    @PostMapping("/{id}/unfreeze")
    @PreAuthorize("hasRole('ROLE_BACKOFFICE') or hasRole('ROLE_ADMIN')")
    @Operation(summary = "Unfreeze an account")
    fun unfreeze(@PathVariable id: UUID): AccountResponse =
        accountService.unfreezeAccount(id)
}

// ── Request bodies (separate from DTOs to decouple HTTP from application) ────

data class OpenAccountRequest(
    val ownerId: String,
    val currency: com.bankos.account.domain.model.Currency,
    val initialDeposit: java.math.BigDecimal = java.math.BigDecimal.ZERO,
) {
    fun toCommand() = OpenAccountCommand(ownerId, currency, initialDeposit)
}

data class DebitRequest(
    val amount: java.math.BigDecimal,
    val reference: String,
)

data class CreditRequest(
    val amount: java.math.BigDecimal,
    val reference: String,
)
