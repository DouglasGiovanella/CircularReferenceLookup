package com.asaas.service.invoice

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.customer.Customer
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.invoice.InvoiceAuthorizationRequest
import com.asaas.exception.BusinessException
import com.asaas.integration.invoice.api.dto.InvoiceDTO
import com.asaas.invoice.InvoiceOriginType
import com.asaas.invoice.InvoiceStatus
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerInvoiceAdminService {

    def asyncActionService
    def customerInvoiceService
    def enotasManagerService
    def invoiceAuthorizationRequestService

    public void resynchronize(Long invoiceId) {
        Invoice invoice = Invoice.get(invoiceId)

        if (!invoice.status.isError()) {
            throw new BusinessException("Somente notas com o status Erro na emissão podem ser emitidas.")
        }

        customerInvoiceService.synchronize(invoice.id)
    }

    public InvoiceStatus updateInvoiceFromPartner(Long invoiceId) {
        Invoice invoice = Invoice.get(invoiceId)
        InvoiceDTO invoiceDto = enotasManagerService.getInvoice(invoice)
        InvoiceStatus status = InvoiceStatus.fromPartnerStatus(invoiceDto.status)

        if (status.isError() && !invoiceDto.motivoStatus) {
            throw new BusinessException("InvoiceAuthorizationRequestService.save -> A nota ${invoice.id} consta com status de erro, mas não possui descrição do mesmo.")
        }

        if (status.isPending() || status.isProcessingCancellation()) return status

        InvoiceAuthorizationRequest invoiceAuthorizationRequest = invoiceAuthorizationRequestService.save(null, invoiceDto)

        invoiceAuthorizationRequestService.processAuthorization(invoiceAuthorizationRequest)

        return status
    }

    public void cancelNotAuthorizedCustomerInvoices() {
        final Integer maxItemsPerCycle = 20

        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.CANCEL_NOT_AUTHORIZED_CUSTOMER_INVOICE, maxItemsPerCycle)
        if (!asyncActionDataList) return

        List<Long> asyncActionSuccessList = []
        List<Long> asyncActionErrorList = []

        for (Map asyncActionData : asyncActionDataList) {
            try {
                Customer customer = Customer.read(asyncActionData.customerId)

                Boolean allCustomerInvoicesWereCancelled = cancelAllNotAuthorized(customer)
                if (!allCustomerInvoicesWereCancelled) continue

                asyncActionSuccessList.add(asyncActionData.asyncActionId)
            } catch (Exception exception) {
                if (Utils.isLock(exception)) continue

                asyncActionErrorList.add(asyncActionData.asyncActionId)
                AsaasLogger.error("CustomerInvoiceAdminService.cancelNotAuthorizedCustomerInvoices >> Erro durante o processamento da AsyncAction [${ asyncActionData.asyncActionId }]", exception)
            }
        }

        asyncActionService.deleteList(asyncActionSuccessList)
        asyncActionService.setListAsError(asyncActionErrorList)
    }

    private Boolean cancelAllNotAuthorized(Customer customer) {
        final Integer maxItemsPerCycle = 1000

        List<Long> customerInvoiceIdList = Invoice.query([
            column: "id",
            originType: InvoiceOriginType.DETACHED,
            customer: customer,
            statusList: InvoiceStatus.listCancelableByAccountDisablingStatus(),
            disableSort: true
        ]).list(max: maxItemsPerCycle)

        if (customerInvoiceIdList.size() < maxItemsPerCycle) {
            customerInvoiceIdList += Invoice.query([
                column: "id",
                "originType[ne]": InvoiceOriginType.DETACHED,
                customer: customer,
                statusList: [InvoiceStatus.PENDING, InvoiceStatus.SYNCHRONIZED],
                disableSort: true
            ]).list(max: maxItemsPerCycle - customerInvoiceIdList.size())
        }

        if (!customerInvoiceIdList) return true

        final Integer batchSize = 100
        final Integer flushEvery = 100
        Boolean hasErrors = false

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(customerInvoiceIdList, batchSize, flushEvery, { Long customerInvoiceId ->
            Invoice customerInvoice = Invoice.get(customerInvoiceId)
            customerInvoiceService.cancelByAccountDisabling(customerInvoice)
        }, [
            appendBatchToLogErrorMessage: true,
            logErrorMessage: "CustomerInvoiceAdminService.cancelAllNotAuthorized >> Erro ao cancelar Notas Fiscais: ",
            onError: { hasErrors = true }
        ])

        Boolean allCustomerInvoicesWereCancelled = !hasErrors && customerInvoiceIdList.size() < maxItemsPerCycle
        return allCustomerInvoicesWereCancelled
    }
}
