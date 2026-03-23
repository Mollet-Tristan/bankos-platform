package com.bankos.notification.domain.event

import com.bankos.notification.domain.model.*
import java.time.Instant
import java.util.UUID

sealed interface NotificationDomainEvent {
    val eventId: UUID
    val notificationId: NotificationId
    val occurredAt: Instant
}

data class NotificationScheduled(
    override val eventId: UUID = UUID.randomUUID(),
    override val notificationId: NotificationId,
    override val occurredAt: Instant,
    val recipientId: RecipientId,
    val channel: NotificationChannel,
    val type: NotificationType,
    val sourceEventId: SourceEventId,
) : NotificationDomainEvent

data class NotificationDelivered(
    override val eventId: UUID = UUID.randomUUID(),
    override val notificationId: NotificationId,
    override val occurredAt: Instant,
    val recipientId: RecipientId,
    val channel: NotificationChannel,
    val type: NotificationType,
) : NotificationDomainEvent

data class NotificationFailed(
    override val eventId: UUID = UUID.randomUUID(),
    override val notificationId: NotificationId,
    override val occurredAt: Instant,
    val recipientId: RecipientId,
    val channel: NotificationChannel,
    val type: NotificationType,
    val error: String,
    val attempts: Int,
    val permanent: Boolean,
) : NotificationDomainEvent
