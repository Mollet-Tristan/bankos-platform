package com.bankos.transaction.infrastructure.persistence.adapter

import com.bankos.transaction.domain.model.*
import com.bankos.transaction.domain.port.TransactionRepository
import com.bankos.transaction.infrastructure.persistence.entity.TransactionEntity
import com.bankos.transaction.infrastructure.persistence.repository.JpaTransactionRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component

@Component
class JpaTransactionAdapter(
    private val jpa: JpaTransactionRepository,
) : TransactionRepository {

    override fun save(transaction: Transaction): Transaction {
        val entity = jpa.findById(transaction.id.value)
            .map { existing ->
                existing.apply {
                    status = transaction.status
                    failureReason = transaction.failureReason
                    updatedAt = transaction.updatedAt
                }
            }
            .orElseGet { TransactionEntity.fromDomain(transaction) }
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: TransactionId): Transaction? =
        jpa.findById(id.value).map { it.toDomain() }.orElse(null)

    override fun findByIdempotencyKey(key: IdempotencyKey): Transaction? =
        jpa.findByIdempotencyKey(key.value)?.toDomain()

    override fun findBySourceAccountId(accountId: AccountId, page: Int, size: Int): List<Transaction> =
        jpa.findBySourceAccountId(
            accountId.value,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")),
        ).map { it.toDomain() }

    override fun findAll(page: Int, size: Int): List<Transaction> =
        jpa.findAllBy(
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")),
        ).map { it.toDomain() }

    override fun countByStatus(status: TransactionStatus): Long =
        jpa.countByStatus(status)
}
