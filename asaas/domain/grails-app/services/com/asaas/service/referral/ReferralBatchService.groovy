package com.asaas.service.referral

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.Referral
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.referral.adapter.SaveReferralAdapter
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class ReferralBatchService {

    def createReferralService
    def referralService

    public void inviteFriendList(Customer customer, List<String> invitedInfoList) {
        validateSaveReferralList(customer, invitedInfoList.size())

        for (String invitedInfo : invitedInfoList) {
            Boolean isEmail = invitedInfo.matches('.*[a-zA-Z].*')

            String email = isEmail ? invitedInfo : null
            String phone = !isEmail ? invitedInfo : null

            SaveReferralAdapter adapter = SaveReferralAdapter.buildFromInvitedContact(invitedInfo, email, phone)
                .setInvitedByCustomer(customer)

            createReferralService.save(adapter)
        }
    }

    public void inviteAllCustomerAccounts(Customer customer) {
        List<CustomerAccount> listOfCustomerAccount = CustomerAccount.canBeInvitedList(customer, referralService.getRemainingReferralCount(customer))

        if (listOfCustomerAccount.isEmpty()) return

        try {
            for (CustomerAccount customerAccount : listOfCustomerAccount) {
                SaveReferralAdapter adapter = SaveReferralAdapter.buildFromCustomerAccount(customer, customerAccount)

                createReferralService.save(adapter)
            }
        } catch (Exception exception) {
            AsaasLogger.error("ReferralBatchService.inviteAllCustomerAccounts >>> Erro ao salvar dados das contas dos clientes. customerId: [${customer.id}] customerAccounts: [${listOfCustomerAccount}]", exception)
        }
    }

    public void saveInvitations(Customer customer, List<Map> invitationsList) {
        validateSaveReferralList(customer, invitationsList.size())

        try {
            for (Map invitationData : invitationsList) {
                SaveReferralAdapter adapter = SaveReferralAdapter.buildFromInvitedContact(invitationData.name, invitationData.email, invitationData.phone)
                    .setInvitedByCustomer(customer)

                createReferralService.save(adapter)
            }
        } catch (Exception exception) {
            AsaasLogger.error("ReferralBatchService.saveInvitations >>> Erro ao salvar lista de clientes. customerId: [${customer}] invitationsList: [${invitationsList}", exception)
        }
    }

    public List<Map> validateSaveInvitationParams(Customer customer, List<Map> invitationsList) {
        List<Map> referralErrorsList = []

        for (Map invitationData : invitationsList) {
            SaveReferralAdapter adapter = SaveReferralAdapter.buildFromInvitedContact(invitationData.name, invitationData.email, invitationData.phone)
                .setInvitedByCustomer(customer)

            Referral referral = createReferralService.validate(adapter)

            if (referral.hasErrors()) {
                referralErrorsList.add([error: referral.errors.allErrors.first().defaultMessage, boxInvitationClass: invitationData.boxInvitationClass])
            }
        }

        return referralErrorsList
    }

    private BusinessValidation canSendReferral(Customer customer, Integer usersToBeInvited) {
        BusinessValidation validatedBusiness = new BusinessValidation()
        Integer remainingReferrals = referralService.getRemainingReferralCount(customer)

        if (usersToBeInvited > remainingReferrals) {
            validatedBusiness.addError("referral.cannotSendReferral.message", [remainingReferrals])
        }

        return validatedBusiness
    }

    private void validateSaveReferralList(Customer customer, Integer invitedCount) {
        BusinessValidation validatedBusiness = canSendReferral(customer, invitedCount)

        if (!validatedBusiness.isValid()) {
            AsaasLogger.warn("ReferralBatchService.validateSaveReferralList >>> O cliente tentou convidar [${invitedCount}] clientes, ultrapassando seu limite atual de [${referralService.getRemainingReferralCount(customer)}] clientes. customerId: [${customer.id}]")
            throw new BusinessException(validatedBusiness.getFirstErrorMessage())
        }
    }
}
