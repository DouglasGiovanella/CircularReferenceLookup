package com.asaas.service.bill

import com.asaas.bill.BillStatus
import com.asaas.bill.BillStatusReason
import com.asaas.domain.bill.Bill
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class BillAsyncActionService {

    def asyncActionService
    def customerAlertNotificationService
    def customerMessageService

    public void createScheduledBillsNotificationQueue() {
        Date scheduleDate = CustomDateUtils.getNextBusinessDay()

        Map queryParams = [:]
        queryParams.ignoreCustomer = true
        queryParams.scheduleDate = scheduleDate
        queryParams.status = BillStatus.SCHEDULED
        queryParams.disableSort = true

        List<Map> customerMapList = Bill.groupedByCustomer(queryParams).list()

        Utils.forEachWithFlushSession(customerMapList, 50, { Map customerMap ->
            Utils.withNewTransactionAndRollbackOnError ( {
                List<Long> billIdList = Bill.query(queryParams + [column: "id", customerId: customerMap.customerId]).list()

                asyncActionService.saveScheduledBillNotification(billIdList, customerMap.customerId, CustomDateUtils.fromDate(scheduleDate))
            }, [logErrorMessage: "BillAsyncActionService.createScheduledBillsNotificationQueue >>> Erro ao adicionar notificação à fila para o customer [ID: ${customerMap.customerId}] sobre o pague contas agendado."])
        })
    }

    public void notifyScheduledBills() {
        Integer maxItens = 200

        List<Map> scheduledBillNotificationAsyncActionList = asyncActionService.listScheduledBillNotification(maxItens)

        Utils.forEachWithFlushSession(scheduledBillNotificationAsyncActionList, 50, { Map asyncActionDataMap ->
            Utils.withNewTransactionAndRollbackOnError ( {
                Date scheduleDate = CustomDateUtils.fromString(asyncActionDataMap.scheduleDate)
                List<Bill> billList = Bill.query([customerId: Utils.toLong(asyncActionDataMap.customerId), idList: asyncActionDataMap.billIdList]).list()

                customerMessageService.notifyScheduledBill(billList.first().customer, scheduleDate, billList)
                customerAlertNotificationService.notifyBillScheduledDate(billList.first().customer, scheduleDate, billList.size())

                asyncActionService.delete(asyncActionDataMap.asyncActionId)
            },
                [
                    logErrorMessage: "BillAsyncActionService.notifyScheduledBills >>> Erro ao notificar o customer [ID: ${asyncActionDataMap.customerId}] sobre o pague contas agendado.",
                    onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionDataMap.asyncActionId) }
                ]
            )
        })
    }

    public void createFailureNotificationQueueForInsufficientBalance() {
        Map queryParams = [:]
        queryParams.ignoreCustomer = true
        queryParams.scheduleDate = new Date().clearTime()
        queryParams.status = BillStatus.FAILED
        queryParams.statusReason = BillStatusReason.CUSTOMER_WITHOUT_BALANCE
        queryParams.disableSort = true

        List<Map> customerMapList = Bill.groupedByCustomer(queryParams).list()

        Utils.forEachWithFlushSession(customerMapList, 50, { Map customerMap ->
            Utils.withNewTransactionAndRollbackOnError ( {
                List<Long> billIdList = Bill.query(queryParams + [column: "id", customerId: customerMap.customerId]).list()

                asyncActionService.saveBillFailuresNotificationForInsufficientBalance(billIdList, customerMap.customerId)
            }, [logErrorMessage: "BillAsyncActionService.createFailureNotificationQueueForInsufficientBalance >>> Erro ao adicionar notificação à fila para o customer [ID: ${customerMap.customerId}] sobre a falha no pagamento da conta."])
        })
    }

    public void notifyFailuresForInsufficientBalance() {
        Integer maxItens = 200

        List<Map> billFailuresForInsufficientBalanceAsyncActionList = asyncActionService.listBillFailuresNotificationForInsufficientBalance(maxItens)

        Utils.forEachWithFlushSession(billFailuresForInsufficientBalanceAsyncActionList, 50, { Map asyncActionDataMap ->
            Utils.withNewTransactionAndRollbackOnError ( {
                List<Bill> billList = Bill.query([customerId: Utils.toLong(asyncActionDataMap.customerId), idList: asyncActionDataMap.billIdList]).list()

                customerMessageService.notifyBillFailureForInsufficientBalance(billList, billList.first().customer)
                customerAlertNotificationService.notifyBillFailureForInsufficientBalance(billList, billList.first().customer)

                asyncActionService.delete(asyncActionDataMap.asyncActionId)
            },
                [
                    logErrorMessage: "BillAsyncActionService.notifyFailuresForInsufficientBalance >>> Erro ao notificar o customer [ID: ${asyncActionDataMap.customerId}] sobre a falha no pagamento da conta.",
                    onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionDataMap.asyncActionId) }
                ]
            )
        })
    }
}
