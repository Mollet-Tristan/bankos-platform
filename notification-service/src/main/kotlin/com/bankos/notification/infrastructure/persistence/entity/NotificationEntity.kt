package com.bankos.notification.infrastructure.persistence.entity

import com.bankos.notification.domain.model.*
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "notifications",
    indexes = [
        Index(name = "idx_notifications_recipient",     columnList = "recipient_id"),
        Index(name = "idx_notifications_status",        columnList = "status"),
        Index(name = "idx_notifications_source_event",  columnList = "source_event_id"),
        Index(name = "idx_notifications_created_at",    columnList = "created_at"),
    ]
)
class NotificationEntity(
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    val id: UUID,

    @Column(name = "recipient_id", nullable = false)
    val recipientId: String,

    @Column(name = "channel", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    val channel: NotificationChannel,

    @Column(name = "type", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    val type: NotificationType,

    @Column(name = "subject", nullable = false)
    val subject: String,

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    val body: String,

    @Column(name = "source_event_id", nullable = false)
    val sourceEventId: UUID,

    @Column(name = "source_event_type", nullable = false, length = 60)
    val sourceEventType: String,

    @Column(name = "status", nullable = false, length = 25)
    @Enumerated(EnumType.STRING)
    var status: NotificationStatus,

    @Column(name = "attempts", nullable = false)
    var attempts: Int,

    @Column(name = "last_error", columnDefinition = "TEXT")
    var lastError: String?,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Version
    @Column(name = "version")
    var version: Long = 0,
) {
    fun toDomain(): Notification = Notification.reconstitute(
        id = NotificationId(id),
        recipientId = RecipientId(recipientId),
        channel = channel,
        type = type,
        subject = subject,
        body = body,
        sourceEventId = SourceEventId(sourceEventId),
        sourceEventType = sourceEventType,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        attempts = attempts,
        lastError = lastError,
    )

    companion object {
        fun fromDomain(n: Notification) = NotificationEntity(
            id = n.id.value,
            recipientId = n.recipientId.value,
            channel = n.channel,
            type = n.type,
            subject = n.subject,
            body = n.body,
            sourceEventId = n.sourceEventId.value,
            sourceEventType = n.sourceEventType,
            status = n.status,
            attempts = n.attempts,
            lastError = n.lastError,
            createdAt = n.createdAt,
            updatedAt = n.updatedAt,
        )
    }
}
