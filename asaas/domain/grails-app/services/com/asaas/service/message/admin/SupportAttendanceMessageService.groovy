package com.asaas.service.message.admin

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.customer.Customer
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class SupportAttendanceMessageService {

    def asyncActionService
    def customerMessageService

    public void sendSupportAttendanceEmail() {
        final Integer maxItemsPerCycle = 10

        for (Map asyncActionData : asyncActionService.listPending(AsyncActionType.SEND_SUPPORT_ATTENDANCE_EMAIL, maxItemsPerCycle)) {
            Boolean hasError = false

            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(Utils.toLong(asyncActionData.customerId))
                String supportProtocolProtocol = asyncActionData.supportProtocolProtocol
                Date supportAttendanceDateCreated = CustomDateUtils.fromString(asyncActionData.dateCreated, "dd/MM/yyyy HH:mm")

                customerMessageService.notifyCustomerAboutSupportAttendance(customer, supportProtocolProtocol, supportAttendanceDateCreated)

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "SupportAttendanceMessageService.sendSupportAttendanceEmail >> Erro no envio de email de protocolo de atendimento. ID: ${asyncActionData.asyncActionId}",
                onError: { hasError = true }])

            if (hasError) {
                AsyncActionStatus asyncActionStatus = asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId).status
                if (asyncActionStatus.isCancelled()) {
                    AsaasLogger.error("SupportAttendanceMessageService.sendSupportAttendanceEmail >> Foi cancelado o envio do email de protocolo de atendimento ao cliente, por ultrapassar o máximo de tentativas. [asyncActionId: ${asyncActionData.asyncActionId}]")
                }
            }
        }
    }
}
