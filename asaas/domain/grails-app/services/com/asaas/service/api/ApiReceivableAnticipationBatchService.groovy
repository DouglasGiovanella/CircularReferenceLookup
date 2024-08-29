package com.asaas.service.api

import com.asaas.api.ApiCustomerRegisterStatusParser
import com.asaas.api.ApiMobileUtils
import com.asaas.api.ApiReceivableAnticipationBatchParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customerreceivableanticipationconfig.CustomerAutomaticReceivableAnticipationConfig
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipationsimulation.ReceivableAnticipationSimulationBatch
import com.asaas.receivableanticipation.validator.ReceivableAnticipationValidationClosures
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ApiReceivableAnticipationBatchService extends ApiBaseService {

    def apiResponseBuilderService
    def receivableAnticipationBatchService
    def receivableAnticipationPartnerConfigService

    public Map getInfo(Map params) {
        Customer customer = getProviderInstance(params)
        Boolean canAnticipateBankSlip = customer.canAnticipateBoleto()

        receivableAnticipationPartnerConfigService.save(customer)

        Map responseItem = [:]
        responseItem.hasCreatedPayments = customer.hasCreatedPayments()
        responseItem.hasPartnerSettlementAwaitingCreditForTooLong = ReceivableAnticipationValidationClosures.isCustomerWithPartnerSettlementAwaitingCreditForTooLong(customer)

        responseItem.canAnticipateBankSlip = canAnticipateBankSlip
        responseItem.canAnticipateCreditCard = customer.canAnticipateCreditCard()
        responseItem.canAnticipatePix = canAnticipateBankSlip
        responseItem.hasCustomerAccountWithNullPhone = CustomerAccount.query([exists: true, customerId: customer.id, "phone[isNull]": true, disableSort: true]).get().asBoolean()
        responseItem.hasCustomerAccountWithNullCpfCnpj = CustomerAccount.query([exists: true, customerId: customer.id, "cpfCnpj[isNull]": true, disableSort: true]).get().asBoolean()

        Map anticipationTotalValues = receivableAnticipationBatchService.buildAnticipationTotalValues(customer)

        CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
        CustomerAutomaticReceivableAnticipationConfig automaticReceivableAnticipation = CustomerAutomaticReceivableAnticipationConfig.query([customerId: customer.id]).get()

        Boolean hasReceivableAnticipationPercentage = receivableAnticipationConfig.hasCreditCardPercentage()
        responseItem.hasAnticipationPercentage = hasReceivableAnticipationPercentage
        responseItem.hasAutomaticAnticipationActivated = automaticReceivableAnticipation?.active

        responseItem.totalAvailableBalance = anticipationTotalValues.totalBalanceAvailable

        responseItem.bankSlipLimit = ApiReceivableAnticipationBatchParser.buildBankSlipLimit(anticipationTotalValues, receivableAnticipationConfig)
        responseItem.creditCardLimit = ApiReceivableAnticipationBatchParser.buildCreditCardLimit(anticipationTotalValues, receivableAnticipationConfig)
        responseItem.pixLimit = ApiReceivableAnticipationBatchParser.buildPixLimit(anticipationTotalValues, receivableAnticipationConfig)

        return apiResponseBuilderService.buildSuccess(responseItem)
    }

    public Map listPaymentsAndInstallmentsAvailableForAnticipation(Map params) {
        Customer customer = getProviderInstance(params)

        Map parsedParams = ApiReceivableAnticipationBatchParser.parsePaymentsAndInstallmentsAvailableForAnticipationRequest(params)

        BigDecimal anticipableValue = 0.0
        if (params.containsKey("value")) {
            anticipableValue = Utils.toBigDecimal(params.value)
        }

        ReceivableAnticipationSimulationBatch simulation = receivableAnticipationBatchService.simulateBatchRequest(customer, anticipableValue, parsedParams)

        return apiResponseBuilderService.buildSuccess(ApiReceivableAnticipationBatchParser.buildPaymentsAndInstallmentsAvailableForAnticipationResponse(simulation))
    }
}
