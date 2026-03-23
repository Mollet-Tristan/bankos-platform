package com.bankos.account.infrastructure.web.controller

import com.bankos.account.domain.model.AccountNotFoundException
import com.bankos.account.domain.model.AccountNotActiveException
import com.bankos.account.domain.model.InsufficientFundsException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

/**
 * GlobalExceptionHandler
 *
 * Maps domain exceptions → RFC 7807 Problem Details responses.
 *
 * This keeps the domain clean (no HTTP imports in Account.kt)
 * and centralizes error formatting in the infrastructure layer.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(AccountNotFoundException::class)
    fun handleNotFound(ex: AccountNotFoundException): ProblemDetail =
        ProblemDetail.forStatus(HttpStatus.NOT_FOUND).apply {
            title = "Account Not Found"
            detail = ex.message
            setProperty("timestamp", Instant.now())
        }

    @ExceptionHandler(InsufficientFundsException::class)
    fun handleInsufficientFunds(ex: InsufficientFundsException): ProblemDetail =
        ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY).apply {
            title = "Insufficient Funds"
            detail = ex.message
            setProperty("timestamp", Instant.now())
        }

    @ExceptionHandler(AccountNotActiveException::class)
    fun handleNotActive(ex: AccountNotActiveException): ProblemDetail =
        ProblemDetail.forStatus(HttpStatus.CONFLICT).apply {
            title = "Account Not Active"
            detail = ex.message
            setProperty("timestamp", Instant.now())
        }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatus(HttpStatus.BAD_REQUEST).apply {
            title = "Invalid Request"
            detail = ex.message
            setProperty("timestamp", Instant.now())
        }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ProblemDetail =
        ProblemDetail.forStatus(HttpStatus.BAD_REQUEST).apply {
            title = "Validation Failed"
            detail = ex.bindingResult.fieldErrors.joinToString(", ") {
                "${it.field}: ${it.defaultMessage}"
            }
            setProperty("timestamp", Instant.now())
        }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ProblemDetail {
        log.error("Unexpected error", ex)
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR).apply {
            title = "Internal Server Error"
            detail = "An unexpected error occurred"
            setProperty("timestamp", Instant.now())
        }
    }
}
