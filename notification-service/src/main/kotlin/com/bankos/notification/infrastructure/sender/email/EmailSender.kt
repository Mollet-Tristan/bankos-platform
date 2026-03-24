package com.bankos.notification.infrastructure.sender.email

import com.bankos.notification.domain.model.Notification
import com.bankos.notification.domain.model.NotificationChannel
import com.bankos.notification.domain.model.NotificationType
import com.bankos.notification.domain.port.ChannelSender
import com.bankos.notification.domain.port.NonRetryableSendException
import com.bankos.notification.domain.port.RecipientResolver
import com.bankos.notification.domain.port.RetryableSendException
import jakarta.mail.MessagingException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context

/**
 * EmailSender — implements [ChannelSender] for the EMAIL channel.
 *
 * Uses Spring's [JavaMailSender] + Thymeleaf for HTML email rendering.
 *
 * ─── Template strategy ────────────────────────────────────────────────────────
 *
 * Each [NotificationType] maps to a Thymeleaf template:
 *   TRANSACTION_COMPLETED  → templates/email/transaction-completed.html
 *   TRANSACTION_FAILED     → templates/email/transaction-failed.html
 *   ...
 *
 * If a template is not found, we fall back to the plain-text body on the
 * Notification aggregate. This prevents a missing template from blocking delivery.
 *
 * ─── Failure classification ───────────────────────────────────────────────────
 *
 * | Error                        | Retryable? | Why                             |
 * |------------------------------|------------|---------------------------------|
 * | SMTP connection timeout      | Yes        | Transient infra issue           |
 * | Invalid recipient address    | No         | Will never succeed              |
 * | Mailbox full                 | Yes        | May clear up                    |
 * | Authentication failure       | No         | Config issue, retry won't help  |
 *
 * ADR-010: failure classification is the responsibility of the adapter,
 * not the domain. The domain only sees [NonRetryableSendException] or
 * [RetryableSendException].
 */
@Component
class EmailSender(
    private val mailSender: JavaMailSender,
    private val templateEngine: TemplateEngine,
    private val recipientResolver: RecipientResolver,
    @Value("\${bankos.notification.email.from:noreply@bankos.demo}") private val fromAddress: String,
) : ChannelSender {

    private val log = LoggerFactory.getLogger(EmailSender::class.java)

    override val channel = NotificationChannel.EMAIL

    override fun send(notification: Notification) {
        val recipientEmail = recipientResolver.resolveEmail(notification.recipientId)
            ?: throw NonRetryableSendException(
                "No email address found for recipient=${notification.recipientId}"
            )

        val htmlBody = renderTemplate(notification)

        try {
            val message = mailSender.createMimeMessage()
            MimeMessageHelper(message, true, "UTF-8").apply {
                setTo(recipientEmail)
                setFrom(fromAddress)
                setSubject(notification.subject)
                setText(htmlBody, true)
            }
            mailSender.send(message)
            log.debug("Email sent to={} type={}", recipientEmail, notification.type)
        } catch (ex: MailSendException) {
            val errorMsg = ex.message ?: ""
            when {
                errorMsg.contains("Invalid Addresses", ignoreCase = true) ||
                errorMsg.contains("550", ignoreCase = true) ->
                    throw NonRetryableSendException("Invalid email address: $recipientEmail", ex)

                errorMsg.contains("535", ignoreCase = true) ->
                    throw NonRetryableSendException("SMTP authentication failed — check config", ex)

                else -> throw RetryableSendException("SMTP error: $errorMsg", ex)
            }
        } catch (ex: MessagingException) {
            throw RetryableSendException("Failed to build email message: ${ex.message}", ex)
        }
    }

    private fun renderTemplate(notification: Notification): String {
        val templateName = notification.type.toTemplateName()
        return try {
            val ctx = Context().apply {
                setVariable("subject", notification.subject)
                setVariable("body", notification.body)
                setVariable("recipientId", notification.recipientId.value)
                setVariable("notificationId", notification.id.value)
                setVariable("type", notification.type.name)
            }
            templateEngine.process("email/$templateName", ctx)
        } catch (ex: Exception) {
            log.warn("Template '$templateName' not found, falling back to plain text body")
            "<html><body><pre>${notification.body}</pre></body></html>"
        }
    }

    private fun NotificationType.toTemplateName() = when (this) {
        NotificationType.TRANSACTION_COMPLETED   -> "transaction-completed"
        NotificationType.TRANSACTION_FAILED      -> "transaction-failed"
        NotificationType.TRANSACTION_COMPENSATED -> "transaction-compensated"
        NotificationType.ACCOUNT_CREATED         -> "account-created"
        NotificationType.ACCOUNT_FROZEN          -> "account-frozen"
    }
}
