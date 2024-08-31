package com.asaas.service.invoice

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.invoice.InvoiceAuthorizationRequest
import com.asaas.exception.BusinessException
import com.asaas.invoice.InvoiceErrorHandler
import com.asaas.invoice.InvoiceStatus
import com.asaas.log.AsaasLogger
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class InvoiceService {

    def receivableAnticipationCustomerInvoiceService
    def customerMessageService
    def invoiceExtraInfoService
    def invoiceTaxInfoService
    def pushNotificationRequestInvoiceService

    public void setAsSuccessfullySynchronized(Invoice invoice, String externalId) {
        invoice.status = InvoiceStatus.SYNCHRONIZED
        invoice.externalId = externalId
        invoice.statusDescription = null

        if (invoice.mustUpdateEffectiveDate()) invoice.effectiveDate = CustomDateUtils.getInstanceOfCalendar().getTime().clearTime()

        invoice.save(flush: true, failOnError: true)

        notifyInvoiceStatusChange(invoice)
    }

    public void setAsProccessingCancelation(Invoice invoice) {
        invoice.status = InvoiceStatus.PROCESSING_CANCELLATION
        invoice.statusDescription = null

        invoice.save(failOnError: true)

        notifyInvoiceStatusChange(invoice)
    }

    public void setAsCancelationDenied(Invoice invoice, String statusDescription) {
        invoice.status = InvoiceStatus.CANCELLATION_DENIED
        invoice.statusDescription = Utils.truncateString(statusDescription, Invoice.constraints.statusDescription.maxSize)

        invoice.save(flush: true, failOnError: true)

        notifyInvoiceStatusChange(invoice)
    }

    public Invoice setAsCanceled(Invoice invoice) {
        invoice.status = InvoiceStatus.CANCELLED
        invoice.statusDescription = null
        invoice.save(flush: true, failOnError: true)

        notifyInvoiceStatusChange(invoice)
        return invoice
    }

    public void setAsError(Invoice invoice) {
    	invoice.status = InvoiceStatus.ERROR
        invoice.save(flush: true, failOnError: true)

        notifyInvoiceStatusChange(invoice)
    }

    public void setAsError(Invoice invoice, String statusDescription) {
        invoice.status = InvoiceStatus.ERROR
        invoice.statusDescription = Utils.truncateString(statusDescription, Invoice.constraints.statusDescription.maxSize)

        invoice.save(flush: true, failOnError: true)

        notifyInvoiceStatusChange(invoice)
    }

    public void setAsSynchronized(Invoice invoice, String statusDescription) {
        invoice.status = InvoiceStatus.SYNCHRONIZED
        invoice.statusDescription = statusDescription
        invoice.save(flush: true, failOnError: true)

        notifyInvoiceStatusChange(invoice)
    }

    public void setAsPending(Invoice invoice) {
        if (invoice.status.isPending()) return

        if (invoice.status.isWaitingOverduePayment()) invoice.effectiveDate = new Date().clearTime()
        invoice.status = InvoiceStatus.PENDING
        invoice.save(flush: true, failOnError: true)
    }

    public Invoice updateInvoiceFromAuthorizationRequest(InvoiceAuthorizationRequest invoiceAuthorizationRequest) {
        Invoice invoice = invoiceAuthorizationRequest.invoice
        if (invoice.deleted) throw new Exception("Não foi possível atualizar a Nota Fiscal ${invoice.id}, pois essa nota foi removida.")

        AsaasLogger.info("InvoiceService.updateInvoiceFromAuthorizationRequest >> Atualizando Invoice [Id: ${invoice.id}] para o Status [${invoiceAuthorizationRequest.invoiceStatus}]")

        InvoiceStatus newInvoiceStatus = invoiceAuthorizationRequest.invoiceStatus
        validateInvoiceStatusUpdateFromAuthorizationRequest(invoice, newInvoiceStatus)

        if (newInvoiceStatus.isError()) {
            Boolean municipalResponseOverrodeIss = overrideInfoAndSetStatusToPending(invoice, invoiceAuthorizationRequest.invoiceStatusDescription)
            if (municipalResponseOverrodeIss) return invoice
        }

        invoice.status = newInvoiceStatus
        invoice.statusDescription = InvoiceErrorHandler.buildErrorMessage(invoice, invoiceAuthorizationRequest.invoiceStatusDescription)
        invoice.pdfUrl = invoiceAuthorizationRequest.invoicePdfUrl
        invoice.xmlUrl = invoiceAuthorizationRequest.invoiceXmlUrl
        invoice.number = invoiceAuthorizationRequest.invoiceNumber
        invoice.validationCode = invoiceAuthorizationRequest.invoiceValidationCode
        invoice.rpsSerie = invoiceAuthorizationRequest.invoiceRpsSerie
        invoice.rpsNumber = invoiceAuthorizationRequest.invoiceRpsNumber

        if (!invoice.externalId) invoice.externalId = invoiceAuthorizationRequest.invoiceExternalId
        if (invoice.status.isAuthorized()) invoiceExtraInfoService.saveAuthorizationDate(invoice, new Date())

        invoice.save(flush: true, failOnError: true)

        notifyInvoiceStatusChange(invoice)

        if (!invoice.status.isError() && !invoice.status.isCancellationDenied()) return invoice

        if (!invoice.isAsaasInvoice()) customerMessageService.notifyCustomerAboutInvoiceDenied(buildInvoiceInfoForMail(invoice.id))

        if (!InvoiceErrorHandler.isBusinessError(invoiceAuthorizationRequest.invoiceStatusDescription)) AsaasLogger.warn("${this.class.simpleName}.updateInvoiceFromAuthorizationRequest >> A nota fiscal de ID ${invoice.id} está com status [${Utils.getMessageProperty("InvoiceStatus.${invoice.status}")}]. Motivo do erro: [${invoice.statusDescription}]")
        return invoice
    }

    public Map buildInvoiceInfoForMail(Long invoiceId) {
        Invoice invoice = Invoice.read(invoiceId)

        return [
            invoice: invoice,
            receivableAnticipationAwaitingInvoiceAttachmentId: receivableAnticipationCustomerInvoiceService.getFirstReceivableAnticipationAwaitingInvoiceAttachment(invoice)
        ]
    }

    private void validateInvoiceStatusUpdateFromAuthorizationRequest(Invoice invoice, InvoiceStatus newInvoiceStatus) {
        if (invoice.status.isSynchronized()) return
        if (invoice.status.isAuthorized() && newInvoiceStatus.isAuthorized()) throw new BusinessException("A Nota Fiscal [Id: ${invoice.id}] não pode ser autorizada pois já está autorizada.")

        if (newInvoiceStatus.isAuthorized()) {
            if (invoice.status.isAuthorizable()) {
                Boolean invoiceWasSynchronizedInCurrentMonth = (invoice.effectiveDate >= CustomDateUtils.getFirstDayOfCurrentMonth().clearTime())
                if (!invoiceWasSynchronizedInCurrentMonth) throw new BusinessException("A Nota Fiscal [Id: ${invoice.id}, Status: ${invoice.status}, Dt. Emissão: ${invoice.effectiveDate.clearTime()}] não pode ser autorizada pois foi emitida em outro período fiscal.")

                return
            }

            throw new BusinessException("A Nota Fiscal [Id: ${invoice.id}, Status: ${invoice.status}] não pode ser autorizada.")
        }
    }

    private Boolean overrideInfoAndSetStatusToPending(Invoice invoice, String statusDescription) {
        Boolean overrideInvoiceInfoEnabled = CustomerParameter.getValue(invoice.customer, CustomerParameterName.OVERRIDE_INVOICE_INFO_WITH_MUNICIPAL_ERROR_RESPONSE)
        if (!overrideInvoiceInfoEnabled) return false

        BigDecimal newIss = InvoiceErrorHandler.getIssToOverride(statusDescription)
        if (newIss == null) return false
        if (newIss == invoice.taxInfo.issTax) return false

        AsaasLogger.info("InvoiceService.overrideWithCityDataAndResyncronize() >> Atualizando ISS da Invoice [${invoice.id}] para ${newIss}. Com o seguinte erro retornado pela prefeitura: ${statusDescription}")

        invoiceTaxInfoService.updateIssTax(invoice, newIss)
        setAsPending(invoice)

        return true
    }

    private void notifyInvoiceStatusChange(Invoice invoice) {
        if (invoice.isAsaasInvoice()) return

        switch (invoice.status) {
            case InvoiceStatus.SYNCHRONIZED:
                pushNotificationRequestInvoiceService.save(PushNotificationRequestEvent.INVOICE_SYNCHRONIZED, invoice)
                break
            case InvoiceStatus.AUTHORIZED:
                pushNotificationRequestInvoiceService.save(PushNotificationRequestEvent.INVOICE_AUTHORIZED, invoice)
                break
            case InvoiceStatus.ERROR:
                pushNotificationRequestInvoiceService.save(PushNotificationRequestEvent.INVOICE_ERROR, invoice)
                break
            case InvoiceStatus.CANCELLED:
                pushNotificationRequestInvoiceService.save(PushNotificationRequestEvent.INVOICE_CANCELED, invoice)
                break
            case InvoiceStatus.CANCELLATION_DENIED:
                pushNotificationRequestInvoiceService.save(PushNotificationRequestEvent.INVOICE_CANCELLATION_DENIED, invoice)
                break
            case InvoiceStatus.PROCESSING_CANCELLATION:
                pushNotificationRequestInvoiceService.save(PushNotificationRequestEvent.INVOICE_PROCESSING_CANCELLATION, invoice)
                break
        }
    }

    public void delete(Invoice invoice) {
        invoice.deleted = true
        invoice.save(flush: true, failOnError: true)

        Invoice.executeUpdate("update InvoiceItem item set item.deleted = true, lastUpdated = :lastUpdated where item.invoice.id = :id", [id: invoice.id, lastUpdated: new Date()])
    }

    public void resend(Invoice invoice) {
        invoice.status = InvoiceStatus.PENDING
        invoice.save(flush: true, failOnError: true)
    }

    public void applyInterestPlusFineValueIfNecessary(Invoice invoice) {
        if (!invoice?.canApplyInterestPlusFineValue()) return

        if (invoice.getPayment().receivedValueIsDifferentFromExpected()) {
            customerMessageService.notifyCustomerAboutInvoiceInterestValueProblem(invoice)
        } else {
            invoice.value += invoice.getInterestPlusFineValue()
            invoice.observations += "\n\n" + invoice.buildInterestPlusFineDescription()
            invoice.save(flush: true, failOnError: true)
        }
    }
}
