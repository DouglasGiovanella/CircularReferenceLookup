package com.asaas.service.childaccountconfig

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.utils.Utils
import grails.transaction.Transactional
import sun.reflect.generics.reflectiveObjects.NotImplementedException

@Transactional
class ChildAccountCustomerParameterReplicationService {

    def asyncActionService
    def customerParameterConfigService

    public void start() {
        for (Map asyncActionData in asyncActionService.listPendingChildAccountCustomerParameterReplication()) {
            Utils.withNewTransactionAndRollbackOnError ({
                Long accountOwnerId = asyncActionData.accountOwnerId
                String name = asyncActionData.name
                ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: CustomerParameter.simpleName, name: name]).get()
                applyParameter(childAccountParameter, accountOwnerId)
                if (!hasCustomerToReplicate(childAccountParameter, accountOwnerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ChildAccountCustomerParameterReplicationService.start >> Erro no processamento da replicação das configurações de CustomerParameter [${asyncActionData.name}] para as contas filhas. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyParameter(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        final Integer maximumNumberOfDataPerMigration = 2000
        List<Long> childAccountIdList = Customer.createCriteria().list(max: maximumNumberOfDataPerMigration, buildCriteriaForChildAccountsWithoutReplicatedConfiguration(childAccountParameter, accountOwnerId))

        Utils.forEachWithFlushSession(childAccountIdList, 50, { Long customerId ->
            Customer childAccount = Customer.get(customerId)
            customerParameterConfigService.applyParameter(childAccount, childAccountParameter)
        })
    }

    private Boolean hasCustomerToReplicate(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        return Customer.createCriteria().list(buildCriteriaForChildAccountsWithoutReplicatedConfiguration(childAccountParameter, accountOwnerId)).asBoolean()
    }

    private Closure buildCriteriaForChildAccountsWithoutReplicatedConfiguration(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        String valueType = getCustomerParameterValueType(CustomerParameterName.valueOf(childAccountParameter.name).valueType.simpleName)
        Object value = getParameterValue(valueType, childAccountParameter.value)
        return {
            projections {
                property("id")
            }

            eq("deleted", false)
            eq("accountOwner.id", accountOwnerId)
            or {
                notExists CustomerParameter.where {
                    setAlias("customerParameter")

                    eqProperty("customerParameter.customer.id", "this.id")
                    eq("customerParameter.name", CustomerParameterName.valueOf(childAccountParameter.name))
                }.id()
                exists CustomerParameter.where {
                    setAlias("customerParameter")

                    eqProperty("customerParameter.customer.id", "this.id")
                    eq("customerParameter.name", CustomerParameterName.valueOf(childAccountParameter.name))
                    ne("${valueType}", value)
                }.id()
            }
        }
    }

    private String getCustomerParameterValueType(String valueType) {
        switch (valueType) {
            case Boolean.simpleName:
                return "value"
            case BigDecimal.simpleName:
                return "numericValue"
            case String.simpleName:
                return "stringValue"
            default:
                throw new NotImplementedException()
        }
    }

    private Object getParameterValue(String valueType, Object value) {
        switch (valueType) {
            case "value":
                return Utils.toBoolean(value)
            case "numericValue":
                return Utils.toBigDecimal(value)
            case "stringValue":
                return value.toString()
            default:
                throw new NotImplementedException()
        }
    }
}
