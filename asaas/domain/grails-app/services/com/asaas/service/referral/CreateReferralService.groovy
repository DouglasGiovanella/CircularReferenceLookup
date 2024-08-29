package com.asaas.service.referral

import com.asaas.user.UserUtils
import com.asaas.validation.BusinessValidation
import com.asaas.customer.CustomerValidator
import com.asaas.domain.customer.Customer
import com.asaas.utils.DomainUtils
import com.asaas.email.EmailValidator
import com.asaas.utils.PhoneNumberUtils
import com.asaas.domain.Referral
import com.asaas.referral.ReferralStatus
import com.asaas.referral.adapter.SaveReferralAdapter

import grails.transaction.Transactional
import grails.validation.ValidationException

import org.apache.commons.lang.RandomStringUtils

@Transactional
class CreateReferralService {

    private static final REFERRAL_TOKEN_LENGTH = 5

    def referralService

    public Referral save(SaveReferralAdapter adapter) {
        Referral referral = validate(adapter)

        if (referral.hasErrors()) throw new ValidationException("Erro ao salvar o referral", referral.errors)

        referral = adapter.inviteMethod.isPersonalLink() ? new Referral() : buildReferral()

        referral.invitedByCustomer = adapter.invitedByCustomer
        referral.invitedCustomerAccount = adapter.invitedCustomerAccount
        referral.invitedCustomer = adapter.invitedCustomer
        referral.invitedName = adapter.invitedName
        referral.invitedEmail = adapter.invitedEmail
        referral.invitedPhone = adapter.invitedPhone
        referral.inviteMethod = adapter.inviteMethod
        referral.campaignName = adapter.campaignName
        referral.lastInvitationDate = adapter.lastInvitationDate
        referral.originPromotionalCode = adapter.originPromotionalCode
        referral.status = adapter.inviteMethod.isGoldTicket() ? ReferralStatus.REGISTERED : ReferralStatus.PENDING

        referral.save(flush: true, failOnError: true)

        if (adapter.invitedCustomer) {
            referralService.updateReferralStatusToRegisteredIfExists(adapter.invitedCustomer, referral.token)
            return referral
        }

        referralService.notifyInvitationCreated(referral)

        return referral
    }

    public Referral validate(SaveReferralAdapter adapter) {
        Referral referral = new Referral()

        if (adapter.invitedByCustomer && adapter.invitedByCustomer.cannotUseReferral()) {
            DomainUtils.addError(referral, "Você não pode realizar novas indicações.")
        }

        if (adapter.inviteMethod?.isGoldTicket() || adapter.inviteMethod?.isPersonalLink()) return referral

        if (!adapter.invitedName) {
            DomainUtils.addError(referral, "O nome do indicado deve ser informado.")
        }

        if (!adapter.invitedEmail && !adapter.invitedPhone) {
            DomainUtils.addError(referral, "O e-mail ou o telefone do indicado deve ser informado.")
        }

        if (adapter.invitedEmail) {
            if (!EmailValidator.isValid(adapter.invitedEmail)) {
                DomainUtils.addError(referral, "O e-mail informado é inválido.")
            } else if (emailIsAlreadyInUse(adapter.invitedEmail)) {
                DomainUtils.addError(referral, "Já existe uma conta no Asaas utilizando o e-mail informado.")
            } else if (adapter.invitedByCustomer && Referral.emailAlreadyInvited(adapter.invitedByCustomer, adapter.invitedEmail)) {
                DomainUtils.addError(referral, "Já existe um convite para esse e-mail.")
            }
        }

        if (adapter.invitedPhone && !PhoneNumberUtils.validateMobilePhone(adapter.invitedPhone)) {
            DomainUtils.addError(referral, "O número de celular informado é inválido.")
        }

        if (adapter.invitedByCustomer && adapter.invitedPhone && Referral.phoneAlreadyInvited(adapter.invitedByCustomer, adapter.invitedPhone)) {
            DomainUtils.addError(referral, "Já existe um convite para esse número de celular.")
        }

        if (adapter.invitedCustomerAccount && customerAccountAlreadyIndicated(adapter.invitedCustomerAccount.id)) {
            DomainUtils.addError(referral, "Este cliente já foi indicado, não é possível indicar novamente.")
        }

        if (!adapter.inviteMethod) {
            DomainUtils.addError(referral, "O método de convite deve ser informado.")
        }

        return referral
    }

    public void processReferralOnCustomerCreation(Customer invitedCustomer, String referralToken, Long invitedByCustomerId) {
        if (referralToken) {
            referralService.updateReferralStatusToRegisteredIfExists(invitedCustomer, referralToken)
            return
        }

        if (!invitedByCustomerId) return

        Customer invitedByCustomer = Customer.read(invitedByCustomerId)

        if (!invitedByCustomer) return

        SaveReferralAdapter adapter = SaveReferralAdapter.buildFromCustomerLink(invitedByCustomer, invitedCustomer)
        save(adapter)
    }


    private Referral buildReferral() {
        Referral referral = new Referral()

        referral.token = generateToken()
        referral.createdBy = UserUtils.getCurrentUser()

        return referral
    }

    private String generateToken() {
        String token = RandomStringUtils.randomAlphanumeric(REFERRAL_TOKEN_LENGTH).toUpperCase()

        Boolean tokenAlreadyExists = Referral.query([exists: true, token: token, includeDeleted: true]).get().asBoolean()
        if (tokenAlreadyExists) return generateToken()

        return token
    }

    private Boolean emailIsAlreadyInUse(String email) {
        CustomerValidator customerValidator = new CustomerValidator()
        BusinessValidation businessValidation = customerValidator.validateEmailCanBeUsed(email, true)

        return !businessValidation.isValid()
    }

    private Boolean customerAccountAlreadyIndicated(Long customerAccountId) {
        return Referral.query([exists: true, invitedCustomerAccountId: customerAccountId, includeDeleted: true]).get().asBoolean()
    }
}
