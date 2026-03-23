package com.bankos.transaction.infrastructure.persistence.repository

import com.bankos.transaction.domain.model.TransactionStatus
import com.bankos.transaction.infrastructure.persistence.entity.TransactionEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface JpaTransactionRepository : JpaRepository<TransactionEntity, UUID> {
    fun findByIdempotencyKey(key: String): TransactionEntity?
    fun findBySourceAccountId(accountId: UUID, pageable: Pageable): List<TransactionEntity>
    fun findAllBy(pageable: Pageable): List<TransactionEntity>
    fun countByStatus(status: TransactionStatus): Long
}
