package com.asaas.service.asyncmail

import grails.plugin.asyncmail.AsynchronousMailMessage
import grails.plugin.asyncmail.AsynchronousMailProcessService
import grails.plugin.asyncmail.MessageStatus
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailException
import org.springframework.mail.MailParseException
import org.springframework.mail.MailPreparationException

class AsaasAsynchronousMailProcessService extends AsynchronousMailProcessService {

    @Override
    void processEmailMessage(long messageId) {
        boolean useFlushOnSave = grailsApplication.config.asynchronous.mail.useFlushOnSave

        AsynchronousMailMessage message = AsynchronousMailMessage.read(messageId)
        log.trace("Found a message: " + message.toString())

        final Date now = new Date()

        Long id = message.id
        Long version = message.version
        Date lastAttemptDate = now
        Date sentDate = null
        Integer attemptsCount = message.attemptsCount
        MessageStatus status

        Date attemptDate = new Date(now.getTime() - message.attemptInterval)
        boolean canAttempt = message.hasAttemptedStatus() && message.lastAttemptDate.before(attemptDate)

        if (message.hasCreatedStatus() || canAttempt) {
            attemptsCount++
            status = MessageStatus.ERROR

            if (useFlushOnSave) updateAsynchronousMailMessage(id, attemptsCount, lastAttemptDate, sentDate, status, ++version)

            try {
                log.trace("Attempt to send the message with id=${message.id}.")
                asynchronousMailSendService.send(message)
                sentDate = now
                status = MessageStatus.SENT
                log.trace("The message with id=${message.id} was sent successfully.")
            } catch (MailException e) {
                log.warn("Attempt to send the message with id=${message.id} was failed.", e)
                canAttempt = attemptsCount < message.maxAttemptsCount
                boolean fatalException = e instanceof MailParseException || e instanceof MailPreparationException
                if (canAttempt && !fatalException) {
                    status = MessageStatus.ATTEMPTED
                }

                if (e instanceof MailAuthenticationException) {
                    throw e
                }
            } finally {
                updateAsynchronousMailMessage(id, attemptsCount, lastAttemptDate, sentDate, status, ++version)
            }

            if (status == MessageStatus.SENT && message.markDelete) {
                AsynchronousMailMessage.executeUpdate("delete from AsynchronousMailMessage amm where amm.id = :id", [id: id])
                log.trace("The message with id=${id} was deleted.")
            }
        }
    }

    private void updateAsynchronousMailMessage(Long id, Integer attemptsCount, Date lastAttemptDate, Date sentDate, MessageStatus status, Long version) {
        Map updateParams = [attemptsCount: attemptsCount, lastAttemptDate: lastAttemptDate, status: status, version: version, id: id]
        if (sentDate) updateParams.sentDate = sentDate

        String updateQuery = buildUpdateQuery(sentDate)

        AsynchronousMailMessage.executeUpdate(updateQuery, updateParams)
    }

    private String buildUpdateQuery(Date sentDate) {
        StringBuilder updateQuery = new StringBuilder()
        updateQuery.append("update AsynchronousMailMessage amm")
        updateQuery.append("   set amm.attemptsCount = :attemptsCount")
        updateQuery.append("     , amm.lastAttemptDate = :lastAttemptDate")
        if (sentDate) updateQuery.append("     , amm.sentDate = :sentDate")
        updateQuery.append("     , amm.status = :status")
        updateQuery.append("     , amm.version = :version ")
        updateQuery.append(" where amm.id = :id")

        return updateQuery.toString()
    }
}
