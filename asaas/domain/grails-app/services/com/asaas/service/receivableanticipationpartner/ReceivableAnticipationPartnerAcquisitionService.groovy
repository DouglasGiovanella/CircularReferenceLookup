package com.asaas.service.receivableanticipationpartner

import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationItem
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisition
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisitionItem
import com.asaas.exception.receivableanticipation.CannotCancelAnticipationPartnerAcquisitionException
import com.asaas.exception.receivableanticipation.ReceivableAnticipationDuplicatedSettlementException
import com.asaas.receivableanticipationpartner.adapter.CreditAuthorizationInfoAdapter
import com.asaas.log.AsaasLogger
import com.asaas.receivableanticipation.ReceivableAnticipationCalculator
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisitionStatus
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerDocumentStatus
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationPartnerAcquisitionService {

    def mandatoryCustomerAccountNotificationService
    def paymentFeeService
    def receivableAnticipationPartnerSettlementService
    def receivableAnticipationVortxService

    public void processPendingAcquisitions() {
        Date lastExecutionOfDay = CustomDateUtils.setTime(new Date(), 15, 55, 0)
        Date now = new Date()

        Boolean sendAllAvailablePartnerAcquisitions = now >= lastExecutionOfDay

        receivableAnticipationVortxService.processPendingAcquisitions(sendAllAvailablePartnerAcquisitions)
    }

    public ReceivableAnticipationPartnerAcquisition save(ReceivableAnticipation anticipation, ReceivableAnticipationPartner partner) {
        ReceivableAnticipationPartnerAcquisition anticipationPartnerAcquisition = new ReceivableAnticipationPartnerAcquisition()

        anticipationPartnerAcquisition.customer = anticipation.customer
        anticipationPartnerAcquisition.receivableAnticipation = anticipation
        anticipationPartnerAcquisition.partner = partner
        anticipationPartnerAcquisition.status = ReceivableAnticipationPartnerAcquisitionStatus.AWAITING_APPROVAL
        anticipationPartnerAcquisition.value = anticipation.netValue
        anticipationPartnerAcquisition.totalValue = anticipationPartnerAcquisition.value
        anticipationPartnerAcquisition.customerAccountCpfCnpj = anticipation.customerAccount.cpfCnpj
        anticipationPartnerAcquisition.documentStatus = ReceivableAnticipationPartnerDocumentStatus.AWAITING_SEND

        if (!partner.isVortx()) anticipationPartnerAcquisition.documentStatus = ReceivableAnticipationPartnerDocumentStatus.IGNORED

        anticipationPartnerAcquisition.save(flush: true, failOnError: true)

        return anticipationPartnerAcquisition
    }

    public ReceivableAnticipationPartnerAcquisition approve(ReceivableAnticipationPartnerAcquisition partnerAcquisition) {
        if (partnerAcquisition.status != ReceivableAnticipationPartnerAcquisitionStatus.AWAITING_APPROVAL) throw new RuntimeException("Essa antecipação via parceiro não está esperando aprovação.")

        partnerAcquisition = saveItemsAndRecalculate(partnerAcquisition)
        partnerAcquisition.status = ReceivableAnticipationPartnerAcquisitionStatus.PENDING
        partnerAcquisition.save(failOnError: true)

        return partnerAcquisition
    }

    public void credit(ReceivableAnticipationPartnerAcquisition partnerAcquisition) {
        if (!partnerAcquisition) return

        ReceivableAnticipation anticipation = partnerAcquisition.receivableAnticipation
        if (!anticipation.status.isCredited()) throw new RuntimeException("A partnerAcquisition [${partnerAcquisition.id}] não pode ser creditada")

        if (partnerAcquisition.status.isAwaitingApproval()) partnerAcquisition = saveItemsAndRecalculate(partnerAcquisition)

        partnerAcquisition.status = ReceivableAnticipationPartnerAcquisitionStatus.CREDITED
        partnerAcquisition.save(failOnError: true)

        receivableAnticipationPartnerSettlementService.save(partnerAcquisition)
    }

    public void cancel(ReceivableAnticipation anticipation) {
        ReceivableAnticipationPartnerAcquisition partnerAcquisition = anticipation.partnerAcquisition
        if (!partnerAcquisition) return

        if (!partnerAcquisition.status.isCancellable()) {
            String errorMessage = "A antecipação via parceiro [${partnerAcquisition.id}] não pode ser cancelada pois está com o status ${partnerAcquisition.status}"
            AsaasLogger.error(errorMessage)
            throw new CannotCancelAnticipationPartnerAcquisitionException(errorMessage)
        }

        partnerAcquisition.documentStatus = ReceivableAnticipationPartnerDocumentStatus.IGNORED
        partnerAcquisition.status = ReceivableAnticipationPartnerAcquisitionStatus.CANCELLED
        partnerAcquisition.paymentFee = 0
        partnerAcquisition.save(failOnError: true)
    }

    public void debit(Payment payment, Boolean forceToConsumeBalance) {
        List<ReceivableAnticipationPartnerAcquisitionStatus> acquisitionStatusList = [ReceivableAnticipationPartnerAcquisitionStatus.CREDITED]
        if (payment.billingType.isCreditCard() && payment.installment) {
            acquisitionStatusList = [ReceivableAnticipationPartnerAcquisitionStatus.CREDITED, ReceivableAnticipationPartnerAcquisitionStatus.OVERDUE]
        }

        ReceivableAnticipationPartnerAcquisition partnerAcquisition = ReceivableAnticipationPartnerAcquisitionItem.query([column: "partnerAcquisition", payment: payment, "partnerAcquisitionStatusList[in]": acquisitionStatusList]).get()

        Boolean mustConsumeBalance = forceToConsumeBalance ?: validatePaymentToEnableSettlementWithBalanceConsume(payment)
        Boolean mustConsumeBalanceWithPaymentValueConsume = payment.isReceived()
        BigDecimal maxSettlementValue = payment.value
        if (partnerAcquisition && !partnerAcquisition.partner.isVortx()) maxSettlementValue = payment.netValue

        if (partnerAcquisition && mustConsumeBalance) {
            try {
                if (payment.billingType.isCreditCard() && payment.installment) {
                    receivableAnticipationPartnerSettlementService.debit(partnerAcquisition, payment, maxSettlementValue, [consumeFromPayment: false])
                } else {
                    receivableAnticipationPartnerSettlementService.debit(partnerAcquisition, payment, null, [consumeFromPayment: false])
                }
            } catch (ReceivableAnticipationDuplicatedSettlementException exception) {
                AsaasLogger.warn("Não foi possível debitar a aquisição", exception)
            }

            if (partnerAcquisition.status == ReceivableAnticipationPartnerAcquisitionStatus.DEBITED) return
            partnerAcquisition.status = ReceivableAnticipationPartnerAcquisitionStatus.OVERDUE
            partnerAcquisition.save(failOnError: true)

            mandatoryCustomerAccountNotificationService.enableCustomerAccountPhoneCallNotificationIfNecessary(payment.customerAccount)
            return
        }

        if (!mustConsumeBalance && !mustConsumeBalanceWithPaymentValueConsume) AsaasLogger.warn("A antecipação da cobrança foi debitada com status incorreto. [id: ${payment.id}, status: ${payment.status}]")
        if (!mustConsumeBalanceWithPaymentValueConsume) return

        if (partnerAcquisition && partnerAcquisition.status.isCredited()) {
            receivableAnticipationPartnerSettlementService.debit(partnerAcquisition, payment, maxSettlementValue, [consumeFromPayment: true])
        } else {
            receivableAnticipationPartnerSettlementService.debitOverdueSettlements(payment.provider, payment)
        }
    }

    public void confirm(ReceivableAnticipationPartnerAcquisition partnerAcquisition) {
        partnerAcquisition.confirmedDate = new Date()
        partnerAcquisition.save(failOnError: true)
    }

    private Boolean validatePaymentToEnableSettlementWithBalanceConsume(Payment payment) {
        if (payment.isPending()) return true
        if (payment.isOverdue()) return true
        if (payment.isRefunded()) return true
        if (payment.isRefundRequested()) return true
        if (payment.isReceivedInCash()) return true

        return false
    }

    private ReceivableAnticipationPartnerAcquisition saveItemsAndRecalculate(ReceivableAnticipationPartnerAcquisition partnerAcquisition) {
        saveItems(partnerAcquisition)

        List<ReceivableAnticipationPartnerAcquisitionItem> acquisitionItemList = partnerAcquisition.getPartnerAcquisitionItemList()

        partnerAcquisition.paymentFee = acquisitionItemList.sum { it.paymentFee }
        partnerAcquisition.paymentFeeDiscount = acquisitionItemList.sum { it.paymentFeeDiscount }
        partnerAcquisition.feeDiscount = acquisitionItemList.sum { it.feeDiscount }
        partnerAcquisition.value = partnerAcquisition.receivableAnticipation.netValue
        partnerAcquisition.totalValue = acquisitionItemList.sum { it.totalValue }

        return partnerAcquisition
    }

    private void saveItems(ReceivableAnticipationPartnerAcquisition partnerAcquisition) {
        ReceivableAnticipation anticipation = partnerAcquisition.receivableAnticipation
        ReceivableAnticipationPartner partner = partnerAcquisition.partner

        for (ReceivableAnticipationItem anticipationItem : anticipation.items) {
            Payment payment = anticipationItem.payment
            BigDecimal paymentFee = payment.getAsaasValue()

            ReceivableAnticipationPartnerAcquisitionItem acquisitionItem = new ReceivableAnticipationPartnerAcquisitionItem()

            if (anticipation.installment && anticipation.billingType.isCreditCard()) {
                acquisitionItem.partnerId = "I${payment.id}A${anticipation.id}"
                acquisitionItem.totalValue = payment.value
            } else {
                acquisitionItem.partnerId = anticipation.id
                acquisitionItem.totalValue = ReceivableAnticipationCalculator.calculateAcquisitionItemTotalValueWithDiscountApplied(anticipation)
            }

            acquisitionItem.payment = payment
            acquisitionItem.partnerAcquisition = partnerAcquisition
            acquisitionItem.dueDate = anticipationItem.estimatedCreditDate
            acquisitionItem.emissionDate = payment.dateCreated
            acquisitionItem.paymentFee = paymentFee
            acquisitionItem.paymentFeeDiscount = paymentFeeService.getFeeDiscountApplied(payment)
            acquisitionItem.feeDiscount = ReceivableAnticipationCalculator.getFeeDiscountApplied(anticipationItem)
            BigDecimal fee = anticipationItem.fee
            BigDecimal netValue = anticipationItem.value - fee - acquisitionItem.feeDiscount
            acquisitionItem.valueRequestToPartner = getValueRequestToPartner(netValue, partner, acquisitionItem)
            acquisitionItem.save(failOnError: true)

            payment.netValue = payment.value
            payment.save(failOnError: true)
        }
    }

    private BigDecimal getValueRequestToPartner(BigDecimal netValue, ReceivableAnticipationPartner partner, ReceivableAnticipationPartnerAcquisitionItem receivableAnticipationPartnerAcquisitionItem) {
        if (partner.isVortx()) return netValue + receivableAnticipationPartnerAcquisitionItem.paymentFee
        return netValue - receivableAnticipationPartnerAcquisitionItem.paymentFeeDiscount
    }
}
