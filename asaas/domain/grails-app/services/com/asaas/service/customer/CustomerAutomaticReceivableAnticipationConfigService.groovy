package com.asaas.service.customer

import com.asaas.domain.customer.Customer
import com.asaas.domain.customerreceivableanticipationconfig.CustomerAutomaticReceivableAnticipationConfig
import com.asaas.exception.BusinessException
import com.asaas.utils.RequestUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerAutomaticReceivableAnticipationConfigService {

    def customerAlertNotificationService
    def customerInteractionService
    def customerReceivableAnticipationConfigService
    def hubspotEventService
    def receivableAnticipationAgreementService
    def receivableAnticipationBatchService
    def receivableAnticipationValidationCacheService

    public Boolean hasAutomaticReceivableAnticipation(Customer customer) {
        if (customer.isLegalPerson()) return true

        return receivableAnticipationValidationCacheService.isAutomaticActivated(customer.id)
    }

    public Boolean isCustomerEligibleToDiscount(Customer customer) {
        if (!customer.isLegalPerson()) return false

        return !receivableAnticipationValidationCacheService.isFirstUse(customer.id)
    }

    public Boolean activate(Customer customer) {
        validateActivateAutomaticAnticipation(customer)

        receivableAnticipationAgreementService.saveIfNecessary(customer, RequestUtils.getRequest())

        CustomerAutomaticReceivableAnticipationConfig config = CustomerAutomaticReceivableAnticipationConfig.query([customerId: customer.id]).get()
        if (!config) {
            config = new CustomerAutomaticReceivableAnticipationConfig()
            config.customer = customer
        }

        config.lastActivationDate = new Date()
        config.active = true
        config.save(flush: true, failOnError: true)

        receivableAnticipationValidationCacheService.evictIsAutomaticActivated(customer.id)

        customerReceivableAnticipationConfigService.updateAutomaticAnticipationCreditCardFeeDiscount(config)

        receivableAnticipationBatchService.saveSimulationBatchToAutomaticAnticipationIfNecessary(customer)

        customerAlertNotificationService.notifyAutomaticAnticipationActivated(config)

        hubspotEventService.trackCustomerAutomaticAnticipationAction(customer, true)

        customerInteractionService.save(customer, "Antecipação Automática habilitada")

        return config.active
    }

    public Boolean deactivate(Long customerId) {
        CustomerAutomaticReceivableAnticipationConfig config = CustomerAutomaticReceivableAnticipationConfig.query([customerId: customerId]).get()
        config.lastDeactivationDate = new Date()
        config.active = false
        config.save(flush: true, failOnError: true)

        receivableAnticipationValidationCacheService.evictIsAutomaticActivated(config.customer.id)

        customerReceivableAnticipationConfigService.updateAutomaticAnticipationCreditCardFeeDiscount(config)

        customerAlertNotificationService.notifyAutomaticAnticipationDeactivated(config)

        hubspotEventService.trackCustomerAutomaticAnticipationAction(config.customer, false)

        customerInteractionService.save(config.customer, "Antecipação Automática desabilitada")

        return config.active
    }

    public void createSimulationBatchToCustomersWithPaymentAnticipable() {
        final Integer maxItems = 100
        List<Long> customerIdList = CustomerAutomaticReceivableAnticipationConfig.activated([column: "customer.id", "paymentCreditCardAnticipable[exists]": true, "simulationBatch[notExists]": true, disableSort: true]).list(max: maxItems)
        for (Long customerId in customerIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.get(customerId)
                receivableAnticipationBatchService.saveSimulationBatchToAutomaticAnticipationIfNecessary(customer)
            }, [logErrorMessage: "CustomerAutomaticReceivableAnticipationConfigService.createSimulationBatchToCustomersWithPaymentAnticipable >> Erro ao criar batch para antecipação automática [customerId: ${customerId}]"])
        }
    }

    private void validateActivateAutomaticAnticipation(Customer customer) {
        if (!customer.isLegalPerson()) throw new BusinessException("A antecipação automática está disponível apenas para contas do tipo pessoa jurídica.")
        if (receivableAnticipationValidationCacheService.isFirstUse(customer.id)) throw new BusinessException("Você ainda não cumpriu todas as etapas necessárias para utilizar a antecipação.")
    }
}
