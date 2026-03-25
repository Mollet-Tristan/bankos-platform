package com.bankos.notification.infrastructure.web.controller

import com.bankos.notification.domain.model.*
import com.bankos.notification.domain.port.NotificationRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

/**
 * NotificationAdminController — read-only REST API for ops/backoffice.
 *
 * This service has no external API contract — it is purely event-driven.
 * This controller exists for:
 *  - Debugging delivery issues (check status, last error, attempts)
 *  - Backoffice support (verify a notification was sent)
 *  - Portfolio demo (prove the service has an inspectable state)
 *
 * No security config here for simplicity — in production, restrict to ADMIN role.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications (Admin)", description = "Read-only notification inspection")
class NotificationAdminController(
    private val notificationRepository: NotificationRepository,
) {
    @GetMapping("/{id}")
    @Operation(summary = "Get notification by ID")
    fun getById(@PathVariable id: UUID): NotificationView {
        val n = notificationRepository.findById(NotificationId(id))
            ?: throw NotificationNotFoundException(NotificationId(id))
        return n.toView()
    }

    @GetMapping("/recipient/{recipientId}")
    @Operation(summary = "List notifications for a recipient")
    fun getByRecipient(
        @PathVariable recipientId: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): List<NotificationView> =
        notificationRepository
            .findByRecipientId(RecipientId(recipientId), page, size)
            .map { it.toView() }

    private fun Notification.toView() = NotificationView(
        id = id.value, recipientId = recipientId.value,
        channel = channel, type = type, subject = subject,
        status = status, attempts = attempts, lastError = lastError,
        createdAt = createdAt, updatedAt = updatedAt,
    )
}

data class NotificationView(
    val id: UUID, val recipientId: String,
    val channel: NotificationChannel, val type: NotificationType,
    val subject: String, val status: NotificationStatus,
    val attempts: Int, val lastError: String?,
    val createdAt: Instant, val updatedAt: Instant,
)

@RestControllerAdvice
class NotificationExceptionHandler {
    @ExceptionHandler(NotificationNotFoundException::class)
    fun handleNotFound(ex: NotificationNotFoundException) =
        ProblemDetail.forStatus(404).apply {
            title = "Notification Not Found"; detail = ex.message
            setProperty("timestamp", Instant.now())
        }
}
