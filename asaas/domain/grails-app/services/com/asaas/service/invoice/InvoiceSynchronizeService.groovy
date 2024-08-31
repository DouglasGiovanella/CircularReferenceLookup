package com.asaas.service.invoice

import com.asaas.domain.invoice.Invoice
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.invoice.api.manager.CancelInvoiceRequestManager
import com.asaas.integration.invoice.api.manager.CreateInvoiceRequestManager
import com.asaas.invoice.InvoiceErrorHandler
import com.asaas.invoice.InvoiceSynchronizeMessage
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class InvoiceSynchronizeService {

	def invoiceService
    def invoiceAuthorizationRequestService

	public Invoice synchronize(Long invoiceId) {
		if (!invoiceId) return

		Invoice.withNewTransaction { status ->
			Invoice invoice

			try {
				invoice = Invoice.get(invoiceId)
                if (invoice.deleted) throw new Exception("Não foi possível sincronizar a Nota Fiscal ${invoice.id} pois essa nota foi removida.")

				if (invoice.effectiveDate.after(new Date().clearTime())) {
					invoice.effectiveDate = new Date().clearTime()
					invoice.save(flush: true, failOnError: true)
				}

				CreateInvoiceRequestManager createInvoiceRequestManager = new CreateInvoiceRequestManager(invoice)
				createInvoiceRequestManager.execute()

                if (createInvoiceRequestManager.success) {
                    invoiceService.setAsSuccessfullySynchronized(invoice, createInvoiceRequestManager.getInvoiceId())
				} else {
                    String errorMessage = InvoiceErrorHandler.buildErrorMessage(invoice, createInvoiceRequestManager.getErrorResponse())

                    if (InvoiceSynchronizeMessage.isSynchronizeMessage(errorMessage)) {
                        invoiceService.setAsSynchronized(invoice, errorMessage)
                    } else {
                        invoiceService.setAsError(invoice, errorMessage)
                    }
				}

                if (!AsaasEnvironment.isProduction()) {
                    invoiceAuthorizationRequestService.simulateAuthorization(invoice)
                }
			} catch (SocketTimeoutException socketTimeoutException) {
                AsaasLogger.error("InvoiceSynchronizeService.synchronize >> Erro ao sincronizar a Nota Fiscal ${invoiceId}", socketTimeoutException)
            } catch (Exception exception) {
                invoiceService.setAsError(invoice, InvoiceErrorHandler.buildErrorMessage(invoice, exception.getMessage()?.take(255)))
                AsaasLogger.error("InvoiceSynchronizeService.synchronize >> Error synchronizing INVOICE [${invoice.id}]", exception)
            }

            return invoice
		}
    }

    public Invoice cancel(Invoice invoice) {

		try {
			CancelInvoiceRequestManager cancelInvoiceRequestManager = new CancelInvoiceRequestManager(invoice)
			cancelInvoiceRequestManager.execute()

			if (cancelInvoiceRequestManager.success) {
				invoiceService.setAsProccessingCancelation(invoice)
			} else {
				invoiceService.setAsCancelationDenied(invoice, cancelInvoiceRequestManager.getErrorResponse())
			}
        } catch (Exception exception) {
            invoiceService.setAsCancelationDenied(invoice, "Ocorreu um erro no cancelamento da nota fiscal.")
            AsaasLogger.error("${this.class.simpleName}.cancel >> Error on cancelling & synchronizing INVOICE [${invoice.id}]", exception)
        }
		return invoice
    }
}
