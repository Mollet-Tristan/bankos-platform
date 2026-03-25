package com.bankos.transaction.infrastructure.web.controller

import com.bankos.transaction.domain.model.InvalidTransactionStateException
import com.bankos.transaction.domain.model.TransactionNotFoundException
import com.bankos.transaction.domain.model.DuplicateTransactionException
import com.bankos.transaction.domain.port.AccountServiceException
import com.bankos.transaction.domain.port.InsufficientFundsRemoteException
import com.bankos.transaction.domain.port.AccountFrozenRemoteException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(TransactionNotFoundException::class)
    fun handleNotFound(ex: TransactionNotFoundException) =
        problem(HttpStatus.NOT_FOUND, "Transaction Not Found", ex.message)

    @ExceptionHandler(InvalidTransactionStateException::class)
    fun handleInvalidState(ex: InvalidTransactionStateException) =
        problem(HttpStatus.CONFLICT, "Invalid Transaction State", ex.message)

    @ExceptionHandler(DuplicateTransactionException::class)
    fun handleDuplicate(ex: DuplicateTransactionException) =
        problem(HttpStatus.CONFLICT, "Duplicate Transaction", ex.message)

    @ExceptionHandler(InsufficientFundsRemoteException::class)
    fun handleInsufficientFunds(ex: InsufficientFundsRemoteException) =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient Funds", ex.message)

    @ExceptionHandler(AccountFrozenRemoteException::class)
    fun handleFrozen(ex: AccountFrozenRemoteException) =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "Account Frozen", ex.message)

    @ExceptionHandler(AccountServiceException::class)
    fun handleAccountServiceError(ex: AccountServiceException): ProblemDetail {
        log.error("Account Service communication error", ex)
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Upstream Service Unavailable", ex.message)
    }

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(ex: MissingRequestHeaderException) =
        problem(HttpStatus.BAD_REQUEST, "Missing Required Header", ex.message)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException) =
        problem(
            HttpStatus.BAD_REQUEST,
            "Validation Failed",
            ex.bindingResult.fieldErrors.joinToString(", ") { "${it.field}: ${it.defaultMessage}" },
        )

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ProblemDetail {
        log.error("Unexpected error", ex)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred")
    }

    private fun problem(status: HttpStatus, title: String, detail: String?) =
        ProblemDetail.forStatus(status).apply {
            this.title = title
            this.detail = detail
            setProperty("timestamp", Instant.now())
        }
}
