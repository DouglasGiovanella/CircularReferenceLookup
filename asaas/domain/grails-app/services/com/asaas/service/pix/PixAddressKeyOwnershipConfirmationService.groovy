package com.asaas.service.pix

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.crypto.Crypter
import com.asaas.crypto.CrypterAlgorithm
import com.asaas.domain.customer.Customer
import com.asaas.domain.pix.PixAddressKeyOwnershipConfirmation
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.pix.PixAddressKeyOwnershipConfirmationStatus
import com.asaas.pix.PixAddressKeyType
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.apache.commons.lang.RandomStringUtils

@Transactional
class PixAddressKeyOwnershipConfirmationService {

    def customerMessageService
    def smsSenderService

    protected PixAddressKeyOwnershipConfirmation save(Customer customer, String pixKey, PixAddressKeyType type) {
        pixKey = PixUtils.parseKey(pixKey, type)

        validateSave(customer, pixKey, type)

        PixAddressKeyOwnershipConfirmation existingOwnershipConfirmation = PixAddressKeyOwnershipConfirmation.awaitingConfirmation([customer: customer, pixKey: pixKey, type: type]).get()
        if (existingOwnershipConfirmation) cancel(existingOwnershipConfirmation)

        PixAddressKeyOwnershipConfirmation ownershipConfirmation = new PixAddressKeyOwnershipConfirmation()
        ownershipConfirmation.customer = customer
        ownershipConfirmation.type = type
        ownershipConfirmation.pixKey = pixKey
        ownershipConfirmation.token = buildToken()
        ownershipConfirmation.attempts = 0
        ownershipConfirmation.expirationDate = CustomDateUtils.sumMinutes(new Date(), PixAddressKeyOwnershipConfirmation.MAX_EXPIRATION_MINUTES)
        ownershipConfirmation.status = PixAddressKeyOwnershipConfirmationStatus.PENDING
        ownershipConfirmation.save(failOnError: true)

        sendToken(ownershipConfirmation)

        return ownershipConfirmation
    }

    private void validateSave(Customer customer, String pixKey, PixAddressKeyType type) {
        if (!PixAddressKeyType.getRequiresOwnershipConfirmationToken().contains(type)) throw new BusinessException("Esta chave não requer confirmação via token.")
    }

    private String buildToken() {
        final Integer tokenLenght = 6
        String token = RandomStringUtils.randomNumeric(tokenLenght)

        Crypter crypter = new Crypter(AsaasApplicationHolder.config.pix.ownershipconfirmation.tokenCrypterSecret, CrypterAlgorithm.AES)
        crypter.useFixedIv = true

        return crypter.encryptAES(token).encryptedString
    }

    private String decryptToken(String token) {
        Crypter crypter = new Crypter(AsaasApplicationHolder.config.pix.ownershipconfirmation.tokenCrypterSecret, CrypterAlgorithm.AES)
        crypter.useFixedIv = true
        return crypter.decryptAES(token, null)
    }

    public void resendToken(Long id, Customer customer) {
        sendToken(PixAddressKeyOwnershipConfirmation.find(id, customer))
    }

    private void sendToken(PixAddressKeyOwnershipConfirmation ownershipConfirmation) {
        String decryptedToken = decryptToken(ownershipConfirmation.token)

        switch (ownershipConfirmation.type) {
            case PixAddressKeyType.EMAIL:
                customerMessageService.sendPixAddressKeyOwnershipConfirmation(ownershipConfirmation.customer, decryptedToken, ownershipConfirmation.pixKey)
                break
            case PixAddressKeyType.PHONE:
                String smsMessage = "Informe o código ${decryptedToken} para cadastrar sua chave Pix. Em caso de duvidas entre em contato com nosso suporte."
                String smsToPhone = PhoneNumberUtils.removeBrazilAreaCode(Utils.removeNonNumeric(ownershipConfirmation.pixKey))
                smsSenderService.send(smsMessage, smsToPhone, true, [isSecret: true])
                break
            default:
                throw new RuntimeException("Tipo de confirmação não parametrizado.")
        }
    }

    public void cancelExpiredOwnershipConfirmations() {
        List<Long> expiredIdList = PixAddressKeyOwnershipConfirmation.query([column: "id", status: PixAddressKeyOwnershipConfirmationStatus.PENDING, "expirationDate[lt]": new Date()]).list()

        for (Long expiredId : expiredIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                cancel(PixAddressKeyOwnershipConfirmation.get(expiredId))
            }, [logErrorMessage: "PixAddressKeyOwnershipConfirmationService -> Erro ao cancelar confirmação expirada. [expiredId: ${expiredId}]"])
        }
    }

    private PixAddressKeyOwnershipConfirmation cancel(PixAddressKeyOwnershipConfirmation ownershipConfirmation) {
        ownershipConfirmation.status = PixAddressKeyOwnershipConfirmationStatus.CANCELLED
        ownershipConfirmation.deleted = true
        ownershipConfirmation.save(failOnError: true)
        return ownershipConfirmation
    }

    protected PixAddressKeyOwnershipConfirmation confirm(Customer customer, String pixKey, PixAddressKeyType type, String token) {
        if (!PixAddressKeyType.getRequiresOwnershipConfirmationToken().contains(type)) throw new BusinessException("O tipo de chave não requer confirmação.")
        if (!token) throw new BusinessException("O token de confirmação não foi informado.")

        PixAddressKeyOwnershipConfirmation ownershipConfirmation = PixAddressKeyOwnershipConfirmation.query([customer: customer, pixKey: pixKey, type: type]).get()
        if (!ownershipConfirmation) throw new BusinessException("Não foi encontrado uma confirmação pendente para esta chave.")
        if (!ownershipConfirmation.canBeConfirmed()) throw new BusinessException("A confirmação de posse desta chave não pode mais ser confirmada.")

        Boolean tokenIsValid = (decryptToken(ownershipConfirmation.token) == token)
        if (tokenIsValid) {
            ownershipConfirmation.confirmationUser = UserUtils.getCurrentUser()
            ownershipConfirmation.status = PixAddressKeyOwnershipConfirmationStatus.CONFIRMED
            ownershipConfirmation.save(failOnError: true)
        } else {
            ownershipConfirmation.attempts++
            ownershipConfirmation.save(failOnError: true, flush: true)

            String errorMessage = "O token informado é inválido."
            if (ownershipConfirmation.canBeConfirmed()) {
                errorMessage += "Você possui mais ${ownershipConfirmation.getRemainingAttempts()} tentativa(s) para confirmar a posse desta chave."
            } else {
                ownershipConfirmation = cancel(ownershipConfirmation)
            }
            DomainUtils.addError(ownershipConfirmation, errorMessage)
        }
        return ownershipConfirmation
    }

}
