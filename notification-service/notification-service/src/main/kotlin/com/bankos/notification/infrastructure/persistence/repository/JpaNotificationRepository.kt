package com.bankos.notification.infrastructure.persistence.repository

import com.bankos.notification.domain.model.NotificationStatus
import com.bankos.notification.infrastructure.persistence.entity.NotificationEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface JpaNotificationRepository : JpaRepository<NotificationEntity, UUID> {

    fun findBySourceEventId(sourceEventId: UUID): List<NotificationEntity>

    fun findByRecipientId(recipientId: String, pageable: Pageable): List<NotificationEntity>

    @Query("""
        SELECT n FROM NotificationEntity n 
        WHERE n.status = 'FAILED' 
        AND n.attempts < 3
        ORDER BY n.updatedAt ASC
    """)
    fun findRetryable(pageable: Pageable): List<NotificationEntity>
}
