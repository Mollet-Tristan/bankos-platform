package com.bankos.account.infrastructure.persistence.adapter

import com.bankos.account.domain.model.Account
import com.bankos.account.domain.model.AccountId
import com.bankos.account.domain.model.AccountStatus
import com.bankos.account.domain.port.AccountRepository
import com.bankos.account.infrastructure.persistence.entity.AccountEntity
import com.bankos.account.infrastructure.persistence.repository.JpaAccountRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component

/**
 * JpaAccountAdapter — implements the domain port [AccountRepository]
 *
 * This adapter is the bridge between the domain and JPA.
 * It is annotated with @Component (Spring) but the domain never imports it.
 *
 * Pattern applied: Anti-Corruption Layer — domain ↔ entity mapping
 * happens here, keeping both sides clean.
 */
@Component
class JpaAccountAdapter(
    private val jpa: JpaAccountRepository,
) : AccountRepository {

    override fun save(account: Account): Account {
        val entity = jpa.findById(account.id.value)
            .map { existing ->
                // Update mutable fields on the existing managed entity
                existing.apply {
                    balance = account.balance
                    status = account.status
                    updatedAt = account.updatedAt
                }
            }
            .orElseGet { AccountEntity.fromDomain(account) }

        return jpa.save(entity).toDomain()
    }

    override fun findById(id: AccountId): Account? =
        jpa.findById(id.value).map { it.toDomain() }.orElse(null)

    override fun findByOwnerId(ownerId: String): List<Account> =
        jpa.findByOwnerId(ownerId).map { it.toDomain() }

    override fun findAll(page: Int, size: Int): List<Account> =
        jpa.findAllBy(PageRequest.of(page, size)).map { it.toDomain() }

    override fun existsById(id: AccountId): Boolean =
        jpa.existsById(id.value)

    override fun countByStatus(status: AccountStatus): Long =
        jpa.countByStatus(status)
}
