package com.asaas.service.mail

import com.asaas.domain.customer.Customer
import com.asaas.domain.email.AsaasSecurityMailMessage
import com.asaas.domain.email.AsaasSecurityMailMessageBcc
import com.asaas.domain.email.RelevantMailHistory
import com.asaas.domain.login.UserKnownDevice
import com.asaas.domain.loginlinkvalidationrequest.LoginLinkValidationRequest
import com.asaas.domain.transfer.Transfer
import com.asaas.domain.user.User
import com.asaas.email.AsaasMailMessageStatus
import com.asaas.email.AsaasMailMessageType
import com.asaas.email.adapter.AsaasSecurityMailMessageAdapter
import com.asaas.email.adapter.RelevantMailHistoryAdapter
import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.sendgrid.exception.SendGridException
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.EmailUtils
import com.asaas.utils.Utils
import grails.async.Promise

import java.text.SimpleDateFormat

import static grails.async.Promises.task
import static grails.async.Promises.waitAll

class AsaasSecurityMailMessageService {

    def customerMessageService
    def messageService
    def grailsApplication
    def grailsLinkGenerator
    def relevantMailHistoryService
    def sendGridManagerService

	private AsaasSecurityMailMessage save(AsaasSecurityMailMessageAdapter mailAdapter) {
        AsaasSecurityMailMessage asaasSecurityMailMessage = new AsaasSecurityMailMessage()
        asaasSecurityMailMessage.mailTo = mailAdapter.mailTo
        asaasSecurityMailMessage.toName = mailAdapter.toName
        asaasSecurityMailMessage.mailFrom = grailsApplication.config.asaas.security.sender
        asaasSecurityMailMessage.fromName = grailsApplication.config.company.name
        asaasSecurityMailMessage.subject = mailAdapter.subject
        asaasSecurityMailMessage.text = mailAdapter.text
        asaasSecurityMailMessage.html = mailAdapter.isHtml
        asaasSecurityMailMessage.shouldBeDeleted = mailAdapter.shouldBeDeleted ?: false
        asaasSecurityMailMessage.mailHistoryEntry = mailAdapter.mailHistoryEntry

        asaasSecurityMailMessage.save(failOnError: true)

        for (String bccMail : mailAdapter.bccList) {
            AsaasSecurityMailMessageBcc asaasSecurityMailMessageBcc = new AsaasSecurityMailMessageBcc()
            asaasSecurityMailMessageBcc.asaasSecurityMailMessage = asaasSecurityMailMessage
            asaasSecurityMailMessageBcc.email = bccMail

            asaasSecurityMailMessageBcc.save(failOnError: true)
        }

        return asaasSecurityMailMessage
    }

    public void processPending() {
        List<Long> securityMailIdList = AsaasSecurityMailMessage.query([status: AsaasMailMessageStatus.PENDING, column: "id"]).list(max: 100)

        if (securityMailIdList.size() == 0) return

        processPendingInThreads(securityMailIdList)
    }

    public void sendUserMfaCode(User user, String mfaCode) {
        try {
            String subject = "Código de verificação Asaas"
            String emailBody = messageService.buildTemplate("/mailTemplate/mfa/mfaCode", [mfaCode: mfaCode])
            String emailBodyToSaveHistory = messageService.buildTemplate("/mailTemplate/mfa/mfaCode", [mfaCode: ""])
            String recipientName = user.name ?: user.username
            String emailBodyWithDefaultTemplate = messageService.buildDefaultTemplate(recipientName, subject, emailBody, null)

            String sender = grailsApplication.config.asaas.security.sender
            AsaasMailMessageType mailType = AsaasMailMessageType.MFA_CODE

            RelevantMailHistory mailHistoryEntry = relevantMailHistoryService.save(user.customer, sender, user.email, null, subject, emailBodyToSaveHistory, mailType)

            AsaasSecurityMailMessageAdapter mailAdapter = new AsaasSecurityMailMessageAdapter(user.email, [], recipientName, subject, emailBodyWithDefaultTemplate, mailHistoryEntry)
            mailAdapter.shouldBeDeleted = true

            send(mailAdapter, user.customer)
        } catch (Exception exception) {
            AsaasLogger.error("AsaasSecurityMailMessageService.sendUserMfaCode >> Erro ao enviar email de autenticação em duas etapas. UserId ${user.id}", exception)
        }
    }

    public void sendUserEmailValidationCode(User user, String code) {
        try {
            String subject = "Código de verificação"
            String emailBody = messageService.buildTemplate("/mailTemplate/emailvalidation/emailValidation", [code: code])
            String recipientName = user.name ?: user.username
            String emailBodyWithDefaultTemplate = messageService.buildDefaultTemplate(recipientName, subject, emailBody, null)

            AsaasSecurityMailMessageAdapter mailAdapter = new AsaasSecurityMailMessageAdapter(user.email, [], recipientName, subject, emailBodyWithDefaultTemplate, null)
            mailAdapter.shouldBeDeleted = true

            send(mailAdapter, user.customer)
        } catch (Exception exception) {
            AsaasLogger.error("AsaasSecurityMailMessageService.sendUserEmailValidationCode >> Erro ao enviar email de validação. UserId ${user.id}", exception)
        }
    }

    public void sendForgotPasswordMail(User user, String url) {
        try {
            if (!customerMessageService.canSendMessage(user.customer, true)) return

            String templatePath = "/mailTemplate/forgotPassword"
            String emailBody = messageService.buildTemplate(templatePath, [url: url, user: user])
            String emailBodyToSaveHistory = messageService.buildTemplate(templatePath, [url: "", user: user])
            String recipientName = user.name ?: user.username
            String subject = "Você esqueceu sua senha?"
            String emailBodyWithDefaultTemplate = messageService.buildDefaultTemplate(recipientName, subject, emailBody, null)

            String sender = grailsApplication.config.asaas.security.sender
            AsaasMailMessageType mailType = AsaasMailMessageType.FORGOT_PASSWORD

            RelevantMailHistory mailHistoryEntry = relevantMailHistoryService.save(user.customer, sender, user.email, null, subject, emailBodyToSaveHistory, mailType)

            AsaasSecurityMailMessageAdapter mailAdapter = new AsaasSecurityMailMessageAdapter(user.email, [], recipientName, subject, emailBodyWithDefaultTemplate, mailHistoryEntry)
            mailAdapter.shouldBeDeleted = true

            send(mailAdapter, user.customer)
        } catch (Exception e) {
            AsaasLogger.error("AsaasSecurityMailMessageService.sendForgotPasswordMail >> Nao foi possivel enviar o e-mail de reset de senha. UserId [${user.id}].", e)
        }
    }

    public void notifyNewUserKnownDevice(UserKnownDevice userKnownDevice) {
        if (!customerMessageService.canSendMessage(userKnownDevice.user.customer, true)) return

        String location = [userKnownDevice.getCityDescription(),
                           userKnownDevice.getStateDescription(),
                           userKnownDevice.getCountryDescription()].findAll { it }.join(", ")

        String date = CustomDateUtils.fromDate(userKnownDevice.dateCreated, "dd 'de' MMMM 'de' yyyy HH:mm:ss") + " (Horário de Brasília)"

        Map model = [
            isWeb: userKnownDevice.platform.isWeb(),
            username: userKnownDevice.user.username,
            device: userKnownDevice.device,
            operatingSystem: userKnownDevice.operatingSystem,
            location: location,
            date: date,
            phone: grailsApplication.config.asaas.phone,
            mobilePhone: grailsApplication.config.asaas.mobilephone,
            email: grailsApplication.config.asaas.contato
        ]

        String emailSubject = buildNewUserKnownDeviceMailSubject(userKnownDevice)

        String mailTo = userKnownDevice.user.email
        String recipientName = userKnownDevice.user.name
        String emailBody = messageService.buildTemplate("/mailTemplate/newUserKnownDevice", model)
        String emailBodyWithDefaultTemplate = messageService.buildDefaultTemplate(recipientName, emailSubject, emailBody, null)

        String sender = grailsApplication.config.asaas.security.sender
        AsaasMailMessageType mailType = AsaasMailMessageType.NEW_USER_KNOWN_DEVICE

        RelevantMailHistory mailHistoryEntry = relevantMailHistoryService.save(userKnownDevice.user.customer, sender, mailTo, null, emailSubject, emailBody, mailType)

        AsaasSecurityMailMessageAdapter mailAdapter = new AsaasSecurityMailMessageAdapter(mailTo, [], recipientName, emailSubject, emailBodyWithDefaultTemplate, mailHistoryEntry)

        send(mailAdapter, userKnownDevice.user.customer)
    }

    public void notifyDisableAccount(Customer customer, String customerOriginalEmail) {
        try {
            if (!customerMessageService.canSendMessage(customer, true)) return
            Map model = [
                accountNumber: customer.getAccountNumber(),
                phone: grailsApplication.config.asaas.phone,
                mobilePhone: grailsApplication.config.asaas.mobilephone,
                email: grailsApplication.config.asaas.contato
            ]

            String mailTo = customerOriginalEmail
            String recipientName = customer.getProviderName()
            String emailSubject = "Encerramento de conta"
            String emailBody = messageService.buildTemplate("/mailTemplate/notifyDisableAccount", model)
            String emailBodyWithDefaultTemplate = messageService.buildDefaultTemplate(recipientName, emailSubject, emailBody, null)

            String sender = grailsApplication.config.asaas.security.sender
            AsaasMailMessageType mailType = AsaasMailMessageType.DISABLE_ACCOUNT

            RelevantMailHistory mailHistoryEntry = relevantMailHistoryService.save(customer, sender, mailTo, null, emailSubject, emailBody, mailType)

            AsaasSecurityMailMessageAdapter mailAdapter = new AsaasSecurityMailMessageAdapter(mailTo, [], recipientName, emailSubject, emailBodyWithDefaultTemplate, mailHistoryEntry)

            send(mailAdapter, customer)
        }  catch (Exception exception) {
            AsaasLogger.error("AsaasSecurityMailMessageService.notifyDisableAccount >> Erro ao enviar o email sobre encerramento de conta do customer [${customer.id}]", exception)
        }
    }

    public void sendAddUserMail(String url, User user) {
        try {
            String templatePath = "/mailTemplate/addUser"
            String mailTo = user.email
            String recipientName = user.name
            String mailSubject = "Bem-vindo ao ASAAS"
            String emailBody = messageService.buildTemplate(templatePath, [url: url, user: user])
            String emailBodyToSaveHistory = messageService.makeTemplate(templatePath, [url: "", user: user])
            String emailBodyWithDefaultTemplate = messageService.buildDefaultTemplate(recipientName, mailSubject, emailBody, null)

            String sender = grailsApplication.config.asaas.security.sender
            AsaasMailMessageType mailType = AsaasMailMessageType.ADD_USER

            RelevantMailHistory mailHistoryEntry = relevantMailHistoryService.save(user.customer, sender, mailTo, null, mailSubject, emailBodyToSaveHistory, mailType)

            AsaasSecurityMailMessageAdapter mailAdapter = new AsaasSecurityMailMessageAdapter(mailTo, [], recipientName, mailSubject, emailBodyWithDefaultTemplate, mailHistoryEntry)
            mailAdapter.shouldBeDeleted = true

            send(mailAdapter, user.customer)
        } catch (Exception exception) {
            AsaasLogger.error("AsaasSecurityMailMessageService.sendAddUserMail >> Erro ao enviar o email sobre adicao de usuario. User [${user.id}]", exception)
        }
    }

    public void notifyCheckoutOnUntrustedDevice(Customer customer, Transfer transfer) {
        if (!customerMessageService.canSendMessage(customer, true)) return
        try {
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("dd 'de' MMMM 'de' yyyy HH:mm:ss z", new Locale("pt", "BR"))
            simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT-3"))
            String date = simpleDateFormat.format(transfer.dateCreated)

            Map model = [
                name: customer.name,
                date: date,
                value: transfer.getNetValue(),
                phone: grailsApplication.config.asaas.phone,
                mobilePhone: grailsApplication.config.asaas.mobilephone,
                email: grailsApplication.config.asaas.contato
            ]

            String mailTo = customer.email.toLowerCase()
            String recipientName = messageService.getRecipientName(customer)
            String emailSubject = "Alerta de segurança Asaas. Transferência em novo dispositivo."
            String emailBody = messageService.buildTemplate("/mailTemplate/notifyCheckoutOnUntrustedDevice", model)
            String emailBodyWithDefaultTemplate = messageService.buildDefaultTemplate(recipientName, emailSubject, emailBody, null)

            List<String> adminUsersEmailList = grailsApplication.mainContext.userService.getAdminUsernames(customer)
            adminUsersEmailList.remove(mailTo)

            String sender = grailsApplication.config.asaas.security.sender
            AsaasMailMessageType mailType = AsaasMailMessageType.CHECKOUT_ON_UNTRUSTED_DEVICE

            RelevantMailHistory mailHistoryEntry = relevantMailHistoryService.save(customer, sender, mailTo, adminUsersEmailList, emailSubject, emailBody, mailType)

            AsaasSecurityMailMessageAdapter mailAdapter = new AsaasSecurityMailMessageAdapter(mailTo, adminUsersEmailList, recipientName, emailSubject, emailBodyWithDefaultTemplate, mailHistoryEntry)

            send(mailAdapter, customer)
        } catch (Exception exception) {
            AsaasLogger.error("AsaasSecurityMailMessageService.notifyCheckoutOnUntrustedDevice >> Erro ao enviar e-mail de notificação de transação feita em dispositivo não confiável. CustomerId [${customer.id}].", exception)
        }
    }

    public void notifyUserPasswordExpiration(User user, Integer daysToExpire) {
        if (hasLastLoginExceededDeadline(user.lastLogin)) return
        if (!customerMessageService.canSendMessage(user.customer, true)) return

        String emailSubject = "Sua senha irá expirar"

        Map model = [
            phone: grailsApplication.config.asaas.phone,
            mobilePhone: grailsApplication.config.asaas.mobilephone,
            email: grailsApplication.config.asaas.contato,
            url: grailsLinkGenerator.link(controller: 'config', action: 'index', params: [tab: "yourAccount", yourAccountTab: "change-password"], absolute: true),
            daysToExpire: daysToExpire
        ]

        String mailTo = user.email
        String recipientName = user.name ?: user.username
        String emailBody = messageService.buildTemplate("/mailTemplate/passwordExpirationAlert", model)
        String emailBodyWithDefaultTemplate = messageService.buildDefaultTemplate(recipientName, emailSubject, emailBody, null)

        String sender = grailsApplication.config.asaas.security.sender
        AsaasMailMessageType mailType = AsaasMailMessageType.PASSWORD_EXPIRATION

        RelevantMailHistory mailHistoryEntry = relevantMailHistoryService.save(user.customer, sender, mailTo, null, emailSubject, emailBody, mailType)

        AsaasSecurityMailMessageAdapter mailAdapter = new AsaasSecurityMailMessageAdapter(mailTo, [], recipientName, emailSubject, emailBodyWithDefaultTemplate, mailHistoryEntry)
        mailAdapter.shouldBeDeleted = true

        send(mailAdapter, user.customer)
    }

    public void notifyUserCreation(User newUser) {
        try {
            Map model = [
                maskedUsername: EmailUtils.formatEmailWithMask(newUser.username),
                maskedCustomerCpfCnpj: newUser.customer.cpfCnpj ? CpfCnpjUtils.maskCpfCnpjForPublicVisualization(newUser.customer.cpfCnpj) : null
            ]
            String emailSubject = "Novo usuário " + EmailUtils.formatEmailWithMask(newUser.username) + " criado com sucesso"
            String mailTo = newUser.customer.email.toLowerCase()
            String recipientName = messageService.getRecipientName(newUser.customer)
            String emailBody = messageService.buildTemplate("/mailTemplate/newUserCreation", model)
            String emailBodyWithDefaultTemplate = messageService.buildDefaultTemplate(recipientName, emailSubject, emailBody, null)

            List<String> adminUserEmailList = grailsApplication.mainContext.userService.getAdminUsernames(newUser.customer)
            adminUserEmailList.remove(mailTo)

            String sender = grailsApplication.config.asaas.security.sender
            AsaasMailMessageType mailType = AsaasMailMessageType.USER_CREATION

            RelevantMailHistory mailHistoryEntry = relevantMailHistoryService.save(newUser.customer, sender, mailTo, adminUserEmailList, emailSubject, emailBody, mailType)

            AsaasSecurityMailMessageAdapter mailAdapter = new AsaasSecurityMailMessageAdapter(mailTo, adminUserEmailList, recipientName, emailSubject, emailBodyWithDefaultTemplate, mailHistoryEntry)

            send(mailAdapter, newUser.customer)
        } catch (Exception exception) {
            AsaasLogger.error("AsaasSecurityMailMessageService.notifyUserCreation >> Erro ao enviar email de criação de novo usuário. User [${newUser.id}]", exception)
        }
    }

    public void notifySelfieUpload(User user) {
        try {
            String maskedUsername = EmailUtils.formatEmailWithMask(user.username)
            String emailSubject = "O usuário " + maskedUsername + " acaba de inserir com sucesso uma nova imagem de selfie"
            String mailTo = user.customer.email.toLowerCase()
            String recipientName = messageService.getRecipientName(user.customer)
            String emailBody = messageService.buildTemplate("/mailTemplate/newSelfieUpload", [maskedUsername: maskedUsername])
            String emailBodyWithDefaultTemplate = messageService.buildDefaultTemplate(recipientName, emailSubject, emailBody, null)

            List<String> adminUserEmailList = grailsApplication.mainContext.userService.getAdminUsernames(user.customer)
            adminUserEmailList.remove(mailTo)

            String sender = grailsApplication.config.asaas.security.sender
            AsaasMailMessageType mailType = AsaasMailMessageType.SELFIE_UPLOAD

            RelevantMailHistory mailHistoryEntry = relevantMailHistoryService.save(user.customer, sender, mailTo, adminUserEmailList, emailSubject, emailBody, mailType)

            AsaasSecurityMailMessageAdapter mailAdapter = new AsaasSecurityMailMessageAdapter(mailTo, adminUserEmailList, recipientName, emailSubject, emailBodyWithDefaultTemplate, mailHistoryEntry)

            send(mailAdapter, user.customer)
        } catch (Exception exception) {
            AsaasLogger.error("AsaasSecurityMailMessageService.notifySelfieUpload >> Erro ao enviar email de inserção de nova imagem de Selfie. User [${user.id}]", exception)
        }
    }

    public void notifyUserLoginLinkValidationRequest(LoginLinkValidationRequest loginLinkValidationRequest, Integer linkExpirationTime) {
        User user = loginLinkValidationRequest.user

        try {
            Map model = [
                maskedUsername: EmailUtils.formatEmailWithMask(user.username),
                linkExpirationTime: linkExpirationTime,
                link: loginLinkValidationRequest.buildLoginLink()
            ]

            String emailSubject = "Continue com o acesso à sua conta - ${CustomDateUtils.fromDate(new Date(), "dd/MM HH:mm")}"
            String mailTo = user.email
            String recipientName = user.name ?: user.username
            String emailBody = messageService.buildTemplate("/mailTemplate/loginlinkvalidationrequest/loginLinkValidationRequest", model)
            String emailBodyWithDefaultTemplate = messageService.buildDefaultTemplate(recipientName, emailSubject, emailBody, null)

            model.remove("link")
            String emailBodyToSaveHistory = messageService.buildTemplate("/mailTemplate/loginlinkvalidationrequest/loginLinkValidationRequest", model)
            String sender = grailsApplication.config.asaas.security.sender

            RelevantMailHistoryAdapter mailHistoryAdapter = new RelevantMailHistoryAdapter(user.customer, sender, mailTo, [], emailSubject, emailBodyToSaveHistory)

            RelevantMailHistory mailHistoryEntry = relevantMailHistoryService.save(mailHistoryAdapter, loginLinkValidationRequest)

            AsaasSecurityMailMessageAdapter mailAdapter = new AsaasSecurityMailMessageAdapter(mailTo, [], recipientName, emailSubject, emailBodyWithDefaultTemplate, mailHistoryEntry)
            mailAdapter.shouldBeDeleted = true

            send(mailAdapter, user.customer)
        } catch (Exception exception) {
            AsaasLogger.error("AsaasSecurityMailMessageService.notifyUserLoginLinkValidationRequest >> Erro ao enviar email do link de login. User [${user.id}]", exception)
        }
    }

    private void changeRecipientsIfNecessary(AsaasSecurityMailMessageAdapter mailAdapter, Customer customer) {
        if (!AsaasEnvironment.isSandbox()) return
        if (!customer.accountOwner) return

        mailAdapter.mailTo = customer.accountOwner.email
        mailAdapter.bccList = []
    }

    private void send(AsaasSecurityMailMessageAdapter mailAdapter, Customer customer) {
        changeRecipientsIfNecessary(mailAdapter, customer)

        if (!AsaasEnvironment.isProduction()) {
            String sender = grailsApplication.config.asaas.security.sender
            messageService.send(sender, mailAdapter.mailTo, mailAdapter.bccList, mailAdapter.subject, mailAdapter.text, mailAdapter.isHtml)
            return
        }

        save(mailAdapter)
    }

    private void processPendingInThreads(List<Long> securityMailIdList) {
        final int amountInThread = 20

        List<Promise> promiseList = []

        securityMailIdList.collate(amountInThread).each { List<Long> sublistIds ->
            List<Long> items = sublistIds.collect()

            items.each { Long id -> updateStatus(id, AsaasMailMessageStatus.PROCESSING) }

            promiseList << task { processItems(items) }
        }

        waitAll(promiseList)
    }

    private void updateStatus(Long id, AsaasMailMessageStatus status) {
        Utils.withNewTransactionAndRollbackOnError( {
            AsaasSecurityMailMessage asaasSecurityMailMessage = AsaasSecurityMailMessage.get(id)
            asaasSecurityMailMessage.status = status
            asaasSecurityMailMessage.save(failOnError: true)
        })
    }

    private void processItems(List<Long> securityMailIdList) {
        for (Long asaasSecurityMailId : securityMailIdList) {
            AsaasSecurityMailMessage.withNewTransaction { status ->
                AsaasSecurityMailMessage asaasSecurityMailMessage = AsaasSecurityMailMessage.get(asaasSecurityMailId)
                try {
                    asaasSecurityMailMessage.attempts = asaasSecurityMailMessage.attempts + 1
                    Boolean sentSuccessfully = sendGridManagerService.sendFromSecurityContext(asaasSecurityMailMessage)
                    if (sentSuccessfully) {
                        asaasSecurityMailMessage.status = AsaasMailMessageStatus.SENT
                        asaasSecurityMailMessage.sentDate = new Date()
                    } else {
                        asaasSecurityMailMessage.status = AsaasMailMessageStatus.ERROR
                        AsaasLogger.info("AsaasSecurityMailMessageService.processItems >> Não foi possível enviar AsaasSecurityMailMessage [${asaasSecurityMailId}]")
                    }
                } catch (SendGridException sendGridException) {
                    AsaasLogger.error("AsaasSecurityMailMessageService.processItems >> SendGridException on AsaasSecurityMailMessage [${asaasSecurityMailId}]", sendGridException)
                    asaasSecurityMailMessage.status = AsaasMailMessageStatus.PENDING
                    if (asaasSecurityMailMessage.attempts >= asaasSecurityMailMessage.MAX_ATTEMPTS) {
                        asaasSecurityMailMessage.status = AsaasMailMessageStatus.NETWORK_ERROR
                    }
                } catch (Exception exception) {
                    AsaasLogger.error("AsaasSecurityMailMessageService.processItems >> Unknown error on AsaasSecurityMailMessage [${asaasSecurityMailId}]", exception)
                    asaasSecurityMailMessage.status = AsaasMailMessageStatus.ERROR
                } finally {
                    asaasSecurityMailMessage.save(flush: true, failOnError: true)
                }

                deleteIfNecessary(asaasSecurityMailMessage)
            }
        }
    }

    private void deleteIfNecessary(AsaasSecurityMailMessage asaasSecurityMailMessage) {
        if (shouldDeleteEmail(asaasSecurityMailMessage)) {
            asaasSecurityMailMessage.delete()
        }
    }

    private Boolean shouldDeleteEmail(AsaasSecurityMailMessage email) {
        if (!email.shouldBeDeleted) return false
        if (email.status.isSent()) return true
        if (email.attempts >= AsaasSecurityMailMessage.MAX_ATTEMPTS) {
            AsaasLogger.warn("AsaasSecurityMailMessageService.shouldDeleteEmail >> Email do contexto de seguranca ultrapassou limite de tentativas de ser enviado e poderia ser deletado. AsaasSecurityMailMessageId [${email.id}].")
        }
        return false
    }

    private Boolean hasLastLoginExceededDeadline(Date lastLoginDate) {
        if (!lastLoginDate) return true

        final Integer daysToEnableMail = 60
        Calendar deadline = Calendar.getInstance()
        deadline.add(Calendar.DAY_OF_MONTH, -daysToEnableMail)

        if (lastLoginDate.before(deadline.getTime())) return true

        return false
    }

    private String buildNewUserKnownDeviceMailSubject(UserKnownDevice userKnownDevice) {
        String emailSubject = "Alerta de segurança Asaas. "
        emailSubject += "Login realizado em novo ${userKnownDevice.getDeviceType()}"

        if (userKnownDevice.city && userKnownDevice.state && userKnownDevice.country) {
            emailSubject += " próximo a ${userKnownDevice.getCityDescription()}, ${userKnownDevice.getStateDescription()}"
            return emailSubject
        }

        if (userKnownDevice.platform.isWeb()) {
            emailSubject += " ${userKnownDevice.device} em ${userKnownDevice.operatingSystem}"
        } else {
            emailSubject += " em ${userKnownDevice.device}"
        }

        return emailSubject
    }
}
