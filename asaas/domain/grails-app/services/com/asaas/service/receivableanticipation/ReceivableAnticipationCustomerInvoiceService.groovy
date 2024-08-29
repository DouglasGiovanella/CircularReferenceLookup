package com.asaas.service.receivableanticipation

import com.asaas.billinginfo.BillingType
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.invoice.InvoiceItem
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationDocument
import com.asaas.invoice.InvoiceStatus
import com.asaas.receivableanticipation.ReceivableAnticipationDenialReason
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationCustomerInvoiceService {

    def receivableAnticipationDocumentService
    def receivableAnticipationService

    public void denyReceivableAnticipationAwaitingInvoiceAttachment(Invoice invoice) {
        ReceivableAnticipationDenialReason denialReason = ReceivableAnticipationDenialReason.INVOICE_CANCELLED_BEFORE_ATTACHMENT
        String denialObservation = denialReason.getLabel()

        List<Long> receivableAnticipationIdList = listReceivableAnticipationAwaitingInvoiceAttachment(invoice, null)
        for (Long anticipationId : receivableAnticipationIdList) {
            receivableAnticipationService.deny(anticipationId, denialObservation, denialReason)
        }
    }

    public Long getFirstReceivableAnticipationAwaitingInvoiceAttachment(Invoice invoice) {
        final maxItems = 1
        List<Long> receivableAnticipationIdList = listReceivableAnticipationAwaitingInvoiceAttachment(invoice, maxItems)

        if (receivableAnticipationIdList.isEmpty()) return null
        return receivableAnticipationIdList.first()
    }

    public Boolean denyBankslipAnticipationAwaitingInvoiceAttachmentWithPaymentDueDateOutOfAllowedTime() {
        final Integer maxItemsPerExecution = 100

        List<Long> anticipationIdList = ReceivableAnticipation.query([
            column: "id",
            billingType: BillingType.BOLETO,
            status: ReceivableAnticipationStatus.AWAITING_AUTOMATIC_ATTACHMENT_ASAAS_ISSUED_INVOICE,
            "payment.dueDate[lt]": ReceivableAnticipation.getMinimumDateAllowed(),
            disableSort: true
        ]).list(max: maxItemsPerExecution)

        ReceivableAnticipationDenialReason denialReason = ReceivableAnticipationDenialReason.PAYMENT_DUE_DATE_NOT_IN_ALLOWED_TIME
        String denialObservation = denialReason.getLabel()

        for (Long anticipationId : anticipationIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                receivableAnticipationService.deny(anticipationId, denialObservation, denialReason)
            }, [logErrorMessage: "${this.class.getSimpleName()}.denyBankslipAnticipationAwaitingInvoiceAttachmentWithPaymentDueDateOutOfAllowedTime >> Não foi possível negar a antecipação ${anticipationId}"])
        }

        return anticipationIdList.size() >= maxItemsPerExecution
    }

    public void processAnticipationsAwaitingAutomaticAttachmentAsaasIssuedInvoice() {
        final Integer maxItemsPerExecution = 50
        List<Long> readyToAttachInvoiceReceivableAnticipationIdList = listReadyToAttachInvoiceIdList(maxItemsPerExecution)

        for (Long anticipationId : readyToAttachInvoiceReceivableAnticipationIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipation anticipation = ReceivableAnticipation.get(anticipationId)
                receivableAnticipationDocumentService.setAsaasIssuedInvoiceAsAnticipationDocument(anticipation)
                receivableAnticipationService.updateStatusToPending(anticipation, "Vinculada a nota fiscal emitida no asaas como documento da antecipação.")
            }, [logErrorMessage: "${this.class.getSimpleName()}.processAnticipationsAwaitingAutomaticAttachmentAsaasIssuedInvoice >> Falha ao vincular a nota fiscal como documento da antecipação [${anticipationId}]."])
        }
    }

    private List<Long> listReadyToAttachInvoiceIdList(Integer max) {
        return ReceivableAnticipation.createCriteria().list(max: max) {
            projections { property "id" }

            createAlias("payment", "raPayment")

            eq("deleted", false)
            eq("status", ReceivableAnticipationStatus.AWAITING_AUTOMATIC_ATTACHMENT_ASAAS_ISSUED_INVOICE)
            'in'("billingType", [BillingType.BOLETO, BillingType.PIX])

            exists InvoiceItem.where {
                setAlias("invoiceItem")
                createAlias("invoice", "invoice")

                eq("deleted", false)
                eqProperty("invoiceItem.customer.id", "this.customer.id")
                eq("invoice.status", InvoiceStatus.AUTHORIZED)
                or {
                    eqProperty("invoiceItem.payment.id", "raPayment.id")
                    eqProperty("invoiceItem.installment.id", "raPayment.installment.id")
                }
            }.id()
        }
    }

    private List<Long> listReceivableAnticipationAwaitingInvoiceAttachment(Invoice invoice, Integer max) {
        return ReceivableAnticipation.createCriteria().list(max: max) {
            projections {
                property "id"
            }
            createAlias("payment", "anticipationPayment")

            'in'("status", [ReceivableAnticipationStatus.AWAITING_AUTOMATIC_ATTACHMENT_ASAAS_ISSUED_INVOICE, ReceivableAnticipationStatus.SCHEDULED])
            'in'("billingType", [BillingType.BOLETO, BillingType.PIX])
            eq("customer.id", invoice.customer.id)

            exists InvoiceItem.where {
                setAlias("invoiceItem")
                createAlias("invoice", "invoice")

                eq("deleted", false)
                eq("invoice.id", invoice.id)
                eqProperty("invoiceItem.customer.id", "this.customer.id")
                or {
                    eqProperty("invoiceItem.payment.id", "anticipationPayment.id")
                    eqProperty("invoiceItem.installment.id", "anticipationPayment.installment.id")
                }
            }.id()

            notExists ReceivableAnticipationDocument.where {
                setAlias("anticicipationDocument")

                eqProperty("anticicipationDocument.receivableAnticipation.id", "this.id")
                eq("deleted", false)
            }.id()
        }
    }
}
