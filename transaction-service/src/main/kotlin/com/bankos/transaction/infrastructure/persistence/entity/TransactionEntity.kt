package com.bankos.transaction.infrastructure.persistence.entity

import com.bankos.transaction.domain.model.*
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "transactions",
    indexes = [
        Index(name = "idx_transactions_source_account", columnList = "source_account_id"),
        Index(name = "idx_transactions_status", columnList = "status"),
        Index(name = "idx_transactions_idempotency_key", columnList = "idempotency_key", unique = true),
        Index(name = "idx_transactions_created_at", columnList = "created_at"),
    ]
)
class TransactionEntity(
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    val id: UUID,

    @Column(name = "source_account_id", nullable = false)
    val sourceAccountId: UUID,

    @Column(name = "target_account_id")
    val targetAccountId: UUID?,

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    val amount: BigDecimal,

    @Column(name = "currency", nullable = false, length = 3)
    @Enumerated(EnumType.STRING)
    val currency: Currency,

    @Column(name = "type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    val type: TransactionType,

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var status: TransactionStatus,

    @Column(name = "description", nullable = false)
    val description: String,

    @Column(name = "idempotency_key", nullable = false, unique = true)
    val idempotencyKey: String,

    @Column(name = "failure_reason")
    var failureReason: String?,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Version
    @Column(name = "version")
    var version: Long = 0,
) {
    fun toDomain(): Transaction = Transaction.reconstitute(
        id = TransactionId(id),
        sourceAccountId = AccountId(sourceAccountId),
        targetAccountId = targetAccountId?.let { AccountId(it) },
        amount = Money(amount, currency),
        type = type,
        description = description,
        idempotencyKey = IdempotencyKey(idempotencyKey),
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        failureReason = failureReason,
    )

    companion object {
        fun fromDomain(tx: Transaction) = TransactionEntity(
            id = tx.id.value,
            sourceAccountId = tx.sourceAccountId.value,
            targetAccountId = tx.targetAccountId?.value,
            amount = tx.amount.value,
            currency = tx.amount.currency,
            type = tx.type,
            status = tx.status,
            description = tx.description,
            idempotencyKey = tx.idempotencyKey.value,
            failureReason = tx.failureReason,
            createdAt = tx.createdAt,
            updatedAt = tx.updatedAt,
        )
    }
}
