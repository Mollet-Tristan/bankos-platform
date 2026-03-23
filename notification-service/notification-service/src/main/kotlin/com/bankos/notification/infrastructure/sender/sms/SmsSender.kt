package com.bankos.notification.infrastructure.sender.sms

import com.bankos.notification.domain.model.Notification
import com.bankos.notification.domain.model.NotificationChannel
import com.bankos.notification.domain.port.ChannelSender
import com.bankos.notification.domain.port.RecipientResolver
import com.bankos.notification.domain.port.RetryableSendException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * SmsSender — stub implementation of [ChannelSender] for the SMS channel.
 *
 * This class demonstrates the Open/Closed principle of Hexagonal Architecture:
 * adding SMS support required:
 *  1. This new adapter class
 *  2. The `SMS` value in [NotificationChannel] enum
 *  3. Adding SMS creation in [NotificationFactory.buildNotifications]
 *
 * Zero changes to [NotificationService], domain model, or any other adapter.
 * Spring auto-discovers this bean and adds it to the `channelSenders` list.
 *
 * ─── Production implementation note ──────────────────────────────────────────
 *
 * A real implementation would integrate with:
 *  - Twilio SDK (`com.twilio.sdk:twilio`)
 *  - AWS SNS (`software.amazon.awssdk:sns`)
 *  - Or any EU-compliant SMS gateway
 *
 * This stub is enabled/disabled via `bankos.notification.sms.enabled` property.
 * When disabled, the bean is not registered and no SMS channel notifications
 * will be created by the factory.
 *
 * ADR-010: channel adapters are conditionally loaded — no dead code in production
 * when a channel is not yet configured.
 */
@Component
@ConditionalOnProperty(name = ["bankos.notification.sms.enabled"], havingValue = "true")
class SmsSender(
    private val recipientResolver: RecipientResolver,
) : ChannelSender {

    private val log = LoggerFactory.getLogger(SmsSender::class.java)

    override val channel = NotificationChannel.SMS

    override fun send(notification: Notification) {
        val phoneNumber = recipientResolver.resolvePhone(notification.recipientId)
            ?: throw com.bankos.notification.domain.port.NonRetryableSendException(
                "No phone number found for recipient=${notification.recipientId}"
            )

        // TODO: integrate real SMS gateway (Twilio, AWS SNS)
        // For demo purposes, we log and simulate success
        log.info(
            "[SMS STUB] Would send to={} type={} body={}",
            phoneNumber, notification.type,
            notification.body.take(160), // SMS 160 char limit
        )

        // Simulate occasional transient failure for demo
        if (System.currentTimeMillis() % 10L == 0L) {
            throw RetryableSendException("SMS gateway rate limit exceeded (simulated)")
        }
    }
}
