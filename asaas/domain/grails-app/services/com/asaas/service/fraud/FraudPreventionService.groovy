package com.asaas.service.fraud

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.customer.Customer
import com.asaas.domain.riskAnalysis.RiskAnalysisRequest
import com.asaas.domain.user.User
import com.asaas.domain.user.UserCookie
import com.asaas.riskAnalysis.RiskAnalysisReason
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class FraudPreventionService {

    def asyncActionService
    def customerInteractionService
    def riskAnalysisRequestService
    def riskAnalysisTriggerCacheService

    public void saveCheckIfCustomerOrRelatedAreSuspectedOfFraudAsyncAction(Long currentUserId, String userCookieValue) {
        AsyncActionType asyncActionType = AsyncActionType.CHECK_IF_CUSTOMER_OR_RELATED_ARE_SUSPECTED_OF_FRAUD
        Map asyncActionData = [:]
        asyncActionData.userId = currentUserId
        asyncActionData.userCookieValue = userCookieValue

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

        asyncActionService.save(asyncActionType, asyncActionData)
    }

    public void processCheckIfCustomerOrRelatedAreSuspectedOfFraud() {
        final Integer maxItemsPerCycle = 100

        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.CHECK_IF_CUSTOMER_OR_RELATED_ARE_SUSPECTED_OF_FRAUD, maxItemsPerCycle)
        if (!asyncActionDataList) return

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                Long userId = asyncActionData.userId
                String userCookieValue = asyncActionData.userCookieValue

                checkIfCustomerOrRelatedAreSuspectedOfFraud(userId, userCookieValue)

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "FraudPreventionService.processCheckIfCustomerOrRelatedAreSuspectedOfFraud > Erro ao checar Customer e seus relacionados como suspeita de fraude. AsyncActionId: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public Customer toggleSuspectedOfFraud(Customer customer, Boolean suspectedOfFraud, String additionalInfo) {
        customer.suspectedOfFraud = suspectedOfFraud
        customer.save(failOnError: true)

        String reason
        if (customer.suspectedOfFraud) {
            reason = Utils.getMessageProperty("riskAnalysisReason.label.CUSTOMER_SET_AS_SUSPECTED_OF_FRAUD")
            saveAnalysisWithNewTransactionIfNecessary(customer.id, RiskAnalysisReason.CUSTOMER_SET_AS_SUSPECTED_OF_FRAUD)
            setRelatedCustomersAsSuspectedOfFraud(customer, additionalInfo)
        } else {
            reason = Utils.getMessageProperty("riskAnalysisReason.label.CUSTOMER_SET_AS_NOT_SUSPECTED_OF_FRAUD")
        }

        if (additionalInfo) reason += " ${additionalInfo}"
        customerInteractionService.save(customer, reason)
        return customer
    }

    private void setCustomerAsSuspectedOfFraudIfPossible(Customer customer) {
        if (customer.hasUserWithSysAdminRole()) return

        Boolean hasAlreadyBeenLabeledAsSuspect = customer.suspectedOfFraud != null
        if (hasAlreadyBeenLabeledAsSuspect) return

        toggleSuspectedOfFraud(customer, true, null)
    }

    private void setRelatedCustomersAsSuspectedOfFraud(Customer customer, String additionalInfo) {
        if (customer.hasUserWithSysAdminRole()) return

        List<Long> relatedCustomersByUserCookie = UserCookie.getRelatedCustomers(customer)

        for (Long relatedCustomerId in relatedCustomersByUserCookie) {
            Customer relatedCustomer = Customer.get(relatedCustomerId)

            if (relatedCustomer.suspectedOfFraud == null) {
                String reason = Utils.getMessageProperty("riskAnalysisReason.label.CUSTOMER_SET_AS_SUSPECTED_OF_FRAUD_BY_ASSOCIATION", [customer.email, customer.id])
                relatedCustomer.suspectedOfFraud = true

                if (additionalInfo) reason += " ${additionalInfo}"
                customerInteractionService.save(relatedCustomer, reason)
                saveAnalysisWithNewTransactionIfNecessary(customer.id, RiskAnalysisReason.CUSTOMER_SET_AS_SUSPECTED_OF_FRAUD_BY_ASSOCIATION)
            }
        }
    }

    private void checkIfCustomerOrRelatedAreSuspectedOfFraud(Long userId, String userCookieValue) {
        Customer customer = User.query([column: "customer", ignoreCustomer: true, id: userId]).get()
        Boolean existingSuspectOfFraudRelatedCustomer = UserCookie.withSuspectedOfFraudCustomer([exists: true,
                                                                                                 value: userCookieValue,
                                                                                                 "customer[ne]": customer]).get().asBoolean()

        if (existingSuspectOfFraudRelatedCustomer) {
            setCustomerAsSuspectedOfFraudIfPossible(customer)
        } else if (customer.suspectedOfFraud) {
            setRelatedCustomersAsSuspectedOfFraud(customer, Utils.getMessageProperty("customerInteraction.suspectedOfFraudFlaggedByCheckRelatedWithSuspect"))
        }
    }

    private void saveAnalysisWithNewTransactionIfNecessary(Long customerId, RiskAnalysisReason reason) {
        if (!riskAnalysisTriggerCacheService.getInstance(reason).enabled) return

        Utils.withNewTransactionAndRollbackOnError({
            Customer customer = Customer.get(customerId)
            if (!customer) return
            if (customer.accountDisabled()) return

            RiskAnalysisRequest riskAnalysisRequest = riskAnalysisRequestService.save(customer, reason, null)
            if (riskAnalysisRequest.hasErrors()) throw new ValidationException("Erro na validação da análise", riskAnalysisRequest.errors)
        }, [logErrorMessage: "FraudPreventionService.saveAnalysisWithNewTransaction >> Falha ao salvar análise com o motivo [${reason}] para o cliente [${customerId}]"])
    }
}
