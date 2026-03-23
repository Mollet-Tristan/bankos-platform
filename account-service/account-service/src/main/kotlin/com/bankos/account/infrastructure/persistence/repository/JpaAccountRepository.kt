package com.bankos.account.infrastructure.persistence.repository

import com.bankos.account.domain.model.AccountStatus
import com.bankos.account.infrastructure.persistence.entity.AccountEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

/**
 * Spring Data JPA repository.
 * Lives in infrastructure — the domain never sees this interface.
 */
interface JpaAccountRepository : JpaRepository<AccountEntity, UUID> {

    fun findByOwnerId(ownerId: String): List<AccountEntity>

    fun findAllBy(pageable: Pageable): List<AccountEntity>

    fun countByStatus(status: AccountStatus): Long

    @Query("SELECT a FROM AccountEntity a WHERE a.ownerId = :ownerId AND a.status = :status")
    fun findByOwnerIdAndStatus(ownerId: String, status: AccountStatus): List<AccountEntity>
}
