package com.asaas.service.childaccountconfig

import com.asaas.customercommissionconfig.adapter.CustomerCommissionReceivableAnticipationConfigAdapter
import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ChildAccountCustomerReceivableAnticipationConfigReplicationService {

    def asyncActionService
    def customerReceivableAnticipationConfigParameterService

    public void start() {
        for (Map asyncActionData in asyncActionService.listPendingChildAccountCustomerReceivableAnticipationConfigReplication()) {
            Utils.withNewTransactionAndRollbackOnError({
                Long accountOwnerId = asyncActionData.accountOwnerId
                String name = asyncActionData.name
                ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: CustomerReceivableAnticipationConfig.simpleName, name: name]).get()
                applyParameter(childAccountParameter, accountOwnerId)
                if (!hasCustomerToReplicate(childAccountParameter, accountOwnerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ChildAccountCustomerReceivableAnticipationConfigReplicationService.start >> Erro no processamento da replicação de taxas/configurações de CustomerReceivableAnticipationConfig [${asyncActionData.name}] para as contas filhas. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyParameter(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        Map search = buildQueryParamsForReceivableAnticipationConfigReplication(accountOwnerId, childAccountParameter)

        final Integer maximumNumberOfDataPerMigration = 2000
        List<Long> customerIdList = CustomerReceivableAnticipationConfig.query(search).list(max: maximumNumberOfDataPerMigration)

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Customer childAccount = Customer.get(customerId)
            customerReceivableAnticipationConfigParameterService.applyParameter(childAccount, childAccountParameter)
        })
    }

    private Boolean hasCustomerToReplicate(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        Map search = buildQueryParamsForReceivableAnticipationConfigReplication(accountOwnerId, childAccountParameter)
        search.exists = true

        return CustomerReceivableAnticipationConfig.query(search).get().asBoolean()
    }

    private Map buildQueryParamsForReceivableAnticipationConfigReplication(Long accountOwnerId, ChildAccountParameter childAccountParameter) {
        if (!CustomerReceivableAnticipationConfig.ENABLED_ANTICIPATION_CONFIG_LIST.contains(childAccountParameter.name)) {
            return buildQueryParamsForReceivableAnticipationFeeReplication(accountOwnerId, childAccountParameter.name, Utils.toBigDecimal(childAccountParameter.value))
        }

        Map search = [column: "customer.id", accountOwnerId: accountOwnerId]

        if (childAccountParameter.name == "bankSlipEnabled") {
            search."bankSlipEnabled[ne]" = Utils.toBoolean(childAccountParameter.value)
        }

        if (childAccountParameter.name == "creditCardEnabled") {
            search."creditCardEnabled[ne]" = Utils.toBoolean(childAccountParameter.value)
        }

        return search
    }

    private Map buildQueryParamsForReceivableAnticipationFeeReplication(Long accountOwnerId, String fieldName, BigDecimal value) {
        Customer accountOwner = Customer.read(accountOwnerId)
        CustomerCommissionReceivableAnticipationConfigAdapter customerCommissionReceivableAnticipationConfigAdapter = CustomerCommissionReceivableAnticipationConfigAdapter.buildByCommissionedCustomerId(accountOwner.id)

        Map search = [column: "customer.id", accountOwnerId: accountOwnerId]

        if (value) {
            if (fieldName == 'creditCardDetachedDailyFee') {
                search."creditCardDetachedDailyFee[ne]" = value + (customerCommissionReceivableAnticipationConfigAdapter?.creditCardDetachedDailyPercentage ?: 0.0)
                return search
            }

            if (fieldName == 'creditCardInstallmentDailyFee') {
                search."creditCardInstallmentDailyFee[ne]" = value + (customerCommissionReceivableAnticipationConfigAdapter?.creditCardInstallmentDailyPercentage ?: 0.0)
                return search
            }

            if (fieldName == 'bankSlipDailyFee') {
                search."bankSlipDailyFee[ne]" = value + (customerCommissionReceivableAnticipationConfigAdapter?.bankSlipDailyPercentage ?: 0.0)
                return search
            }
        }

        search."creditCardPercentage[ne]" = value

        return search
    }

}
