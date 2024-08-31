package com.asaas.service.creditpolicy

import com.asaas.billinginfo.BillingType
import com.asaas.creditpolicy.CreditPolicyProfileStatus
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.creditpolicy.CreditPolicyProfile
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.payment.Payment
import com.asaas.integration.sage.enums.CreditPolicyName
import com.asaas.payment.PaymentStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CreditPolicyProfileService {

    def customerReceivableAnticipationConfigService
    def sageManagerService

    public void disable(CreditPolicyProfile profile) {
        profile.status = CreditPolicyProfileStatus.DISABLED
        profile.save(failOnError: true)

        sageManagerService.disableCreditPolicyProfile(profile)
    }

    public CreditPolicyProfile save(Customer customer, CreditPolicyName policy) {
        CreditPolicyProfile validatedProfile = validateSave(customer, policy)
        if (validatedProfile.hasErrors()) throw new ValidationException("Não foi possível salvar um novo perfil para o customer [${customer.id}] na politica: [${policy}]", validatedProfile.errors)

        CreditPolicyProfile profile = new CreditPolicyProfile()
        profile.customer = customer
        profile.creditPolicyName = policy
        profile.status = CreditPolicyProfileStatus.AWAITING_REGISTRATION
        profile.save(failOnError: true)

        return profile
    }

    public void processAwaitingRegistration() {
        final Integer maxItemsPerExecution = 100
        List<Long> profileIdList = CreditPolicyProfile.query([column: "id", status: CreditPolicyProfileStatus.AWAITING_REGISTRATION]).list(max: maxItemsPerExecution)

        for (Long profileId : profileIdList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                CreditPolicyProfile profile = CreditPolicyProfile.get(profileId)
                submitRegistration(profile)
            }, [
                logErrorMessage: "CreditPolicyProfileService.processAwaitingRegistration >> Erro durante envio do perfil para o motor de crédito, profileId: [${profileId}]",
                onError: { hasError = true }])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    setAsError(CreditPolicyProfile.get(profileId))
                }, [logErrorMessage: "CreditPolicyProfileService.processAwaitingRegistration >> Não foi possível setar como erro, profileId: [${profileId}]"])
            }
        }
    }

    public void saveForCustomersWithRecentlyConfirmedCreditCardPayments() {
        final Integer rangeBetweenDatesInMinutes = 5
        Date finalDate = new Date()
        Date startDate = CustomDateUtils.sumMinutes(finalDate, rangeBetweenDatesInMinutes * -1)

        Map search = [:]
        search.distinct = "provider.id"
        search.status = PaymentStatus.CONFIRMED
        search.billingType = BillingType.MUNDIPAGG_CIELO
        search."lastUpdated[ge]" = startDate
        search.confirmedDate = finalDate.clearTime()
        search.disableSort = true

        List<Long> customerIdList = Payment.query(search).list()
        for (Long customerId : customerIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(customerId)

                if (!canBeIncludeInPolicy(customer, CreditPolicyName.ANTICIPATION_CREDIT_CARD).isValid()) return

                Boolean hasCreditCardPercentage = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id).hasCreditCardPercentage()
                if (hasCreditCardPercentage) return

                save(customer, CreditPolicyName.ANTICIPATION_CREDIT_CARD)
            }, [logErrorMessage: "CreditPolicyProfileService.saveForCustomersWithRecentlyConfirmedCreditCardPayments --> Falha ao criar um perfil de politica para o customer: [${customerId}]"])
        }
    }

    public void setAsActive(CreditPolicyProfile profile) {
        profile.status = CreditPolicyProfileStatus.ACTIVE
        profile.save(failOnError: true)
    }

    private void submitRegistration(CreditPolicyProfile profile) {
        sageManagerService.registerCreditPolicyProfile(profile)

        setAsActive(profile)
    }

    private void setAsError(CreditPolicyProfile profile) {
        profile.status = CreditPolicyProfileStatus.ERROR
        profile.save(failOnError: true)
    }

    private CreditPolicyProfile validateSave(Customer customer, CreditPolicyName policy) {
        CreditPolicyProfile profile = new CreditPolicyProfile()

        BusinessValidation policyValidation = canBeIncludeInPolicy(customer, policy)
        if (!policyValidation.isValid()) {
            DomainUtils.addError(profile, policyValidation.getFirstErrorMessage())
            return profile
        }

        return profile
    }

    private BusinessValidation canBeIncludeInPolicy(Customer customer, CreditPolicyName policy) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (customer.isNaturalPerson()) {
            businessValidation.addError("creditPolicy.invalidForNaturalPerson")
            return businessValidation
        }

        Boolean isWhiteLabel = CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)
        if (isWhiteLabel) {
            businessValidation.addError("creditPolicy.invalidForWhiteLabel")
            return businessValidation
        }

        Boolean isChildOfWhiteLabel = customer.accountOwner ? CustomerParameter.getValue(customer.accountOwner, CustomerParameterName.WHITE_LABEL) : false
        if (isChildOfWhiteLabel) {
            businessValidation.addError("creditPolicy.invalidForAccountOwnerWhiteLabel")
            return businessValidation
        }

        Boolean alreadyExistsProfileForCustomer = CreditPolicyProfile.query([exists: true, customerId: customer.id, creditPolicyName: policy, disableSort: true]).get().asBoolean()
        if (alreadyExistsProfileForCustomer) {
            businessValidation.addError("creditPolicy.alreadyExistsProfileForCustomer")
            return businessValidation
        }

        return businessValidation
    }
}
