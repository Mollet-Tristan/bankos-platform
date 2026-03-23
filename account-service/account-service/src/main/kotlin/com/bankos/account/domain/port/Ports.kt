package com.bankos.account.domain.port

import com.bankos.account.domain.event.AccountDomainEvent
import com.bankos.account.domain.model.Account
import com.bankos.account.domain.model.AccountId
import com.bankos.account.domain.model.AccountStatus

/**
 * AccountRepository — Output Port (driven port)
 *
 * This interface is defined in the DOMAIN layer.
 * It expresses what the domain needs from persistence,
 * without knowing anything about JPA, SQL, or PostgreSQL.
 *
 * The JPA implementation lives in infrastructure/persistence/adapter/
 * and is injected by Spring. The domain never imports it.
 *
 * This is the core of Hexagonal Architecture (Ports & Adapters).
 */
interface AccountRepository {
    fun save(account: Account): Account
    fun findById(id: AccountId): Account?
    fun findByOwnerId(ownerId: String): List<Account>
    fun findAll(page: Int, size: Int): List<Account>
    fun existsById(id: AccountId): Boolean
    fun countByStatus(status: AccountStatus): Long
}

/**
 * EventPublisher — Output Port
 *
 * ADR-004: After a debit/credit, domain events are published to Kafka
 * so that downstream consumers (NotificationService, AuditService)
 * can react without being coupled to the Account Service's lifecycle.
 *
 * The KafkaEventPublisher in infrastructure/ implements this interface.
 */
interface EventPublisher {
    fun publish(events: List<AccountDomainEvent>)
}
