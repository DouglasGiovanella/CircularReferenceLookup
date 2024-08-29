package com.asaas.service.bill

import com.asaas.bill.BillStatus
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.bill.Bill
import com.asaas.domain.bill.BillPaymentBatchFileItem
import com.asaas.domain.bill.BillPaymentTransaction
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class BillAdminService {

    def billService

    public void authorizeAllWaitingRiskAuthorization(Customer customer) {
        Boolean isCheckoutDisabled = CustomerParameter.queryValue([customer: customer, name: CustomerParameterName.CHECKOUT_DISABLED]).get().asBoolean()
        if (isCheckoutDisabled) throw new BusinessException("Não é possível autorizar o pagamento de contas de clientes com o saque bloqueado.")

        List<Long> billIdList = Bill.query([column: "id", customer: customer, status: BillStatus.WAITING_RISK_AUTHORIZATION]).list()
        for (Long billId in billIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                Bill bill = Bill.get(billId)

                if (bill.valueDebited) {
                    billService.setAsPending(bill)
                } else {
                    billService.setAsScheduled(bill)
                }
            }, [logErrorMessage: "BillAdminService.authorizeAllWaitingRiskAuthorization >> Falha ao autorizar o pagamento da conta [${billId}]"])
        }
    }

    public void setManuallyForBankProcessing(Long id) {
        Bill bill = Bill.get(id)

        if (!bill.status.isPending()) throw new BusinessException(Utils.getMessageProperty("bill.manuallyBankProcessing.error"))

        bill.sentManuallyBankProcessing = true
        bill.save(failOnError: true)
    }

    public void updatePendingBillsToWaitingRiskAuthorization(Customer customer) {
        List<Bill> billList = Bill.query([customer: customer, statusList: [BillStatus.PENDING, BillStatus.SCHEDULED]]).list()

        for (Bill bill : billList) {
            bill.status = BillStatus.WAITING_RISK_AUTHORIZATION
            bill.statusReason = null
            bill.save(failOnError: true)
        }
    }

    public Bill cancelBankProcessing(Bill bill, String adminUserEmail, String reason) {
        BillPaymentTransaction billPaymentTransaction = BillPaymentTransaction.query([billId: bill.id]).get()
        if (billPaymentTransaction) {
            throw new BusinessException("Não é possivel cancelar pagamento em processamento via gateway [${billPaymentTransaction.gateway}].")
        }

        BillPaymentBatchFileItem billPaymentBatchFileItem = BillPaymentBatchFileItem.query([bill: bill, order: 'desc']).get()
        if (!billPaymentBatchFileItem) {
            throw new BusinessException("Não foi encontrado o item de remessa deste pagamento.")
        }

        if (billPaymentBatchFileItem.billPaymentBatchFile.billPaymentBank.bank.code != SupportedBank.SAFRA.code()) {
            throw new BusinessException("Somente é possível cancelar pagamento em processamento no banco Safra.")
        }

        if (CustomDateUtils.calculateDifferenceInHours(billPaymentBatchFileItem.billPaymentBatchFile.transmissionDate, new Date()) < 1) {
            throw new BusinessException("Espere pelo menos 1 hora depois da transmissão da remessa para cancelar o pagamento que está em processamento bancário.")
        }

        if (!bill.status.isBankProcessing()) {
            throw new BusinessException("Não é possível cancelar: o pagamento não está em processamento bancário.")
        }

        User currentUser = UserUtils.getCurrentUser()
        if (currentUser.username != adminUserEmail) {
            throw new BusinessException("Email de confirmação inválido!")
        }

        if (!reason) {
            return DomainUtils.addError(bill, Utils.getMessageProperty("bill.cancel.error.validation.reason"))
        }

        bill.cancellationReason = reason

        return billService.executeCancellation(bill, false)
    }
}
