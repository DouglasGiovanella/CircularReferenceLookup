package com.asaas.service.receivableanticipation

import com.asaas.domain.customer.Customer
import com.asaas.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfigChangeOrigin
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.receivableanticipation.ReceivableAnticipationLimitRecalculation
import com.asaas.receivableanticipation.ReceivableAnticipationLimitRecalculationStatus
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ProcessReceivableAnticipationLimitRecalculationService {

    def asyncActionService
    def customerReceivableAnticipationConfigService
    def receivableAnticipationLimitRecalculationService

    public void processNextRecalculationDate() {
        final Integer maxItemsPerCycle = 500
        List<Map> asyncActionDataList = asyncActionService.listPendingRecalculateCreditCardAnticipationLimitWithPercentage(maxItemsPerCycle)
        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.get(asyncActionData.customerId)
                if (!customer) throw new BusinessException("ProcessReceivableAnticipationLimitRecalculationService.processNextRecalculationDate >> Não foi possível encontrar o cliente [asyncActionId: ${asyncActionData.asyncActionId}, customerId: ${asyncActionData.customerId}]")

                CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
                if (!customerReceivableAnticipationConfig.hasCreditCardPercentage()) {
                    asyncActionService.delete(asyncActionData.asyncActionId)
                    return
                }

                Boolean nextRecalculationDateWithPriority = Utils.toBoolean(asyncActionData.nextRecalculationDateWithPriority)

                Date nextRecalculationDate = nextRecalculationDateWithPriority ?
                    new Date() : receivableAnticipationLimitRecalculationService.calculateNextRecalculationDate(customer)

                ReceivableAnticipationLimitRecalculation recalculation = ReceivableAnticipationLimitRecalculation.query([customerId: customer.id]).get()
                if (recalculation) {
                    receivableAnticipationLimitRecalculationService.setNextRecalculationDate(recalculation, nextRecalculationDate)
                } else {
                    receivableAnticipationLimitRecalculationService.save(customer, nextRecalculationDate)
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [
                logErrorMessage: "ProcessReceivableAnticipationLimitRecalculationService.processNextRecalculationDate >> Erro ao atualizar a próxima data de recalculo [asyncActionId: ${asyncActionData.asyncActionId}, customerId: ${asyncActionData.customerId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }
            ])
        }
    }

    public void processRecalculateCreditCardLimitWithPercentage() {
        Map search = [
            column: "id",
            status: ReceivableAnticipationLimitRecalculationStatus.PENDING,
            "recalculationDate[le]": new Date(),
            sort: "recalculationDate",
            order: "asc"
        ]

        final Integer maxItemsPerCycle = 500
        final Integer numberOfThreads = 2
        final Integer numberOfGroupIdPerThread = Math.ceil(maxItemsPerCycle/numberOfThreads)
        final Integer flushEvery = 50
        List<Long> recalculationIdList = ReceivableAnticipationLimitRecalculation.query(search).list(max: maxItemsPerCycle)

        ThreadUtils.processWithThreadsOnDemand(recalculationIdList, numberOfGroupIdPerThread, { List<String> recalculationSubIdList ->
            Utils.forEachWithFlushSession(recalculationSubIdList, flushEvery, { Long recalculationId ->
                Boolean hasError = false

                Utils.withNewTransactionAndRollbackOnError({
                    ReceivableAnticipationLimitRecalculation recalculation = ReceivableAnticipationLimitRecalculation.get(recalculationId)
                    CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(recalculation.customer.id)
                    if (customerReceivableAnticipationConfig.hasCreditCardPercentage()) {
                        BigDecimal creditCardLimit = receivableAnticipationLimitRecalculationService.calculateCreditCardLimitWithPercentage(recalculation, customerReceivableAnticipationConfig)

                        customerReceivableAnticipationConfigService.setCreditCardAnticipationLimit(recalculation.customer, UserUtils.getCurrentUser(), creditCardLimit, CustomerReceivableAnticipationConfigChangeOrigin.RECALCULATE_CREDIT_CARD_ANTICIPATION_LIMIT_WITH_PERCENTAGE)
                    }
                    receivableAnticipationLimitRecalculationService.setAsDone(recalculation)
                }, [ignoreStackTrace: true,
                    onError: { Exception exception ->
                        if (Utils.isLock(exception)) return

                        AsaasLogger.error("ProcessReceivableAnticipationLimitRecalculationService.processRecalculateCreditCardLimitWithPercentage >> Erro ao recalcular o limite [recalculationId: ${recalculationId}]")
                        hasError = true
                    }
                ])

                if (hasError) {
                    Utils.withNewTransactionAndRollbackOnError({
                        ReceivableAnticipationLimitRecalculation recalculation = ReceivableAnticipationLimitRecalculation.get(recalculationId)
                        receivableAnticipationLimitRecalculationService.setAsError(recalculation)
                    }, [logErrorMessage: "ProcessReceivableAnticipationLimitRecalculationService.processRecalculateCreditCardLimitWithPercentage >> Erro ao setar o status ERROR para o recalculo [recalculationId: ${recalculationId}]"])
                }
            })
        })
    }
}
