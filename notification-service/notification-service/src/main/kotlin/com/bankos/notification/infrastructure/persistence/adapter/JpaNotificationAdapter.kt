package com.bankos.notification.infrastructure.persistence.adapter

import com.bankos.notification.domain.model.*
import com.bankos.notification.domain.port.NotificationRepository
import com.bankos.notification.infrastructure.persistence.entity.NotificationEntity
import com.bankos.notification.infrastructure.persistence.repository.JpaNotificationRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component

@Component
class JpaNotificationAdapter(
    private val jpa: JpaNotificationRepository,
) : NotificationRepository {

    override fun save(notification: Notification): Notification {
        val entity = jpa.findById(notification.id.value)
            .map { existing ->
                existing.apply {
                    status = notification.status
                    attempts = notification.attempts
                    lastError = notification.lastError
                    updatedAt = notification.updatedAt
                }
            }
            .orElseGet { NotificationEntity.fromDomain(notification) }
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: NotificationId): Notification? =
        jpa.findById(id.value).map { it.toDomain() }.orElse(null)

    override fun findBySourceEventId(sourceEventId: SourceEventId): List<Notification> =
        jpa.findBySourceEventId(sourceEventId.value).map { it.toDomain() }

    override fun findPendingRetries(limit: Int): List<Notification> =
        jpa.findRetryable(PageRequest.of(0, limit)).map { it.toDomain() }

    override fun findByRecipientId(recipientId: RecipientId, page: Int, size: Int): List<Notification> =
        jpa.findByRecipientId(
            recipientId.value,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")),
        ).map { it.toDomain() }
}
