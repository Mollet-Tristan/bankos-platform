package com.bankos.notification.infrastructure.sender.email

import com.bankos.notification.domain.model.RecipientId
import com.bankos.notification.domain.port.RecipientResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * StubRecipientResolver — stub implementation of [RecipientResolver].
 *
 * In this demo, the recipientId is the Keycloak `sub` claim (a UUID string).
 * A real implementation would:
 *  1. Call a User Profile Service (REST) to fetch email/phone
 *  2. Or maintain a local read model updated via Kafka events from a UserService
 *
 * The local read model approach (option 2) is preferred in a microservices context
 * because it avoids a synchronous dependency on a Profile Service at notification time.
 * If the Profile Service is down, we would still have cached contact info.
 *
 * ADR-012 (TODO): Document recipient resolution strategy.
 *
 * For the portfolio demo: we return a deterministic fake email derived from the
 * recipientId so that the system is functional end-to-end without a real user store.
 */
@Component
class StubRecipientResolver : RecipientResolver {

    private val log = LoggerFactory.getLogger(StubRecipientResolver::class.java)

    override fun resolveEmail(recipientId: RecipientId): String? {
        // Demo: map recipientId → fake email for end-to-end testing
        val email = "user+${recipientId.value.take(8)}@bankos.demo"
        log.debug("Resolved email for recipient={} → {}", recipientId, email)
        return email
    }

    override fun resolvePhone(recipientId: RecipientId): String? {
        // Demo: no real phone numbers available
        log.debug("Phone resolution not implemented for recipient={}", recipientId)
        return null
    }
}
