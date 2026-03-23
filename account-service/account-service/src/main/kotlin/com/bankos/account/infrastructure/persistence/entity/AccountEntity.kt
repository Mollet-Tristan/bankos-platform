package com.bankos.account.infrastructure.persistence.entity

import com.bankos.account.domain.model.*
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * AccountEntity — JPA Entity
 *
 * This is the INFRASTRUCTURE representation of an Account.
 * It is deliberately separated from the domain [Account] aggregate.
 *
 * Why separate?
 *  - The domain model has rich behavior, invariants, and domain events.
 *  - The JPA entity is a plain data holder for the ORM.
 *  - This separation allows the persistence model to evolve
 *    independently (e.g. add audit columns, indexes) without
 *    polluting the domain with JPA annotations.
 *
 * The [JpaAccountAdapter] handles the mapping in both directions.
 */
@Entity
@Table(
    name = "accounts",
    indexes = [
        Index(name = "idx_accounts_owner_id", columnList = "owner_id"),
        Index(name = "idx_accounts_status", columnList = "status"),
    ]
)
class AccountEntity(
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    val id: UUID,

    @Column(name = "owner_id", nullable = false)
    val ownerId: String,

    @Column(name = "currency", nullable = false, length = 3)
    @Enumerated(EnumType.STRING)
    val currency: Currency,

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    var balance: BigDecimal,

    @Column(name = "status", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    var status: AccountStatus,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Version
    @Column(name = "version")
    var version: Long = 0,
) {
    // ── Mapping to domain ─────────────────────────────────────────────────

    fun toDomain(): Account = Account.reconstitute(
        id = AccountId(id),
        ownerId = ownerId,
        currency = currency,
        balance = balance,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(account: Account) = AccountEntity(
            id = account.id.value,
            ownerId = account.ownerId,
            currency = account.currency,
            balance = account.balance,
            status = account.status,
            createdAt = account.createdAt,
            updatedAt = account.updatedAt,
        )
    }
}
