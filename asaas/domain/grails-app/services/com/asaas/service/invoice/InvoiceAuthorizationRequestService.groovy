package com.asaas.service.invoice

import com.asaas.domain.customer.CustomerFiscalInfo
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.invoice.InvoiceAuthorizationRequest
import com.asaas.exception.BusinessException
import com.asaas.integration.invoice.api.dto.InvoiceDTO
import com.asaas.integration.invoice.api.vo.WebhookRequestInvoiceVO
import com.asaas.invoice.InvoiceAuthorizationRequestStatus
import com.asaas.invoice.InvoiceIssuerType
import com.asaas.invoice.InvoiceStatus
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class InvoiceAuthorizationRequestService {

    def customerFiscalInfoService
    def customerInvoiceService
    def invoiceService

    public InvoiceAuthorizationRequest save(WebhookRequestInvoiceVO webhookRequestInvoiceVO, InvoiceDTO invoiceDto) {
        InvoiceAuthorizationRequest invoiceAuthorizationRequest = new InvoiceAuthorizationRequest()
        Invoice invoice

        if (webhookRequestInvoiceVO) {
            invoice = Invoice.get(webhookRequestInvoiceVO.invoiceId)
            invoiceAuthorizationRequest.invoiceStatus = InvoiceStatus.fromWebhookRequest(webhookRequestInvoiceVO.status)
            invoiceAuthorizationRequest.invoiceStatusDescription = webhookRequestInvoiceVO.statusDescription
            invoiceAuthorizationRequest.invoicePdfUrl = webhookRequestInvoiceVO.pdfUrl
            invoiceAuthorizationRequest.invoiceXmlUrl = webhookRequestInvoiceVO.xmlUrl
            invoiceAuthorizationRequest.invoiceNumber = webhookRequestInvoiceVO.number
            invoiceAuthorizationRequest.invoiceValidationCode = webhookRequestInvoiceVO.validationCode
            invoiceAuthorizationRequest.invoiceRpsSerie = webhookRequestInvoiceVO.rpsSerie
            invoiceAuthorizationRequest.invoiceRpsNumber = webhookRequestInvoiceVO.rpsNumber
            invoiceAuthorizationRequest.invoiceExternalId = webhookRequestInvoiceVO.externalIdentifier
        } else if (invoiceDto) {
            invoice = Invoice.get(invoiceDto.idExterno)
            invoiceAuthorizationRequest.invoiceStatus = InvoiceStatus.fromPartnerStatus(invoiceDto.status)
            invoiceAuthorizationRequest.invoiceStatusDescription = invoiceDto.motivoStatus
            invoiceAuthorizationRequest.invoicePdfUrl = invoiceDto.linkDownloadPDF
            invoiceAuthorizationRequest.invoiceXmlUrl = invoiceDto.linkDownloadXML
            invoiceAuthorizationRequest.invoiceNumber = invoiceDto.numero
            invoiceAuthorizationRequest.invoiceValidationCode = invoiceDto.codigoVerificacao
            invoiceAuthorizationRequest.invoiceRpsSerie = invoiceDto.serieRps
            invoiceAuthorizationRequest.invoiceRpsNumber = invoiceDto.numeroRps
            invoiceAuthorizationRequest.invoiceExternalId = invoiceDto.id
        } else {
            throw new RuntimeException("InvoiceAuthorizationRequestService.save -> Deve ser enviado um webhookRequestInvoiceVO ou InvoiceDTO.")
        }

        invoiceAuthorizationRequest.invoice = invoice
        invoiceAuthorizationRequest.invoiceIssuerType = invoice.isAsaasInvoice() ? InvoiceIssuerType.ASAAS : InvoiceIssuerType.CUSTOMER
        invoiceAuthorizationRequest.status = InvoiceAuthorizationRequestStatus.PENDING
        invoiceAuthorizationRequest.save(failOnError: true)

        return invoiceAuthorizationRequest
    }

    public void processCustomerInvoiceAuthorizationRequest() {
        Integer numberOfThreads = 3
        Integer maxNumberOfAuthorizationPerThread = 20
        Integer maxNumberOfAuthorization = (maxNumberOfAuthorizationPerThread * numberOfThreads)

        List<Long> customerIdList = InvoiceAuthorizationRequest.query([
            distinct: "invoice.customer.id",
            status: InvoiceAuthorizationRequestStatus.PENDING,
            invoiceIssuerType: InvoiceIssuerType.CUSTOMER,
            disableSort: true
        ]).list(max: maxNumberOfAuthorization)
        if (!customerIdList) return

        Utils.processWithThreads(customerIdList, numberOfThreads, { List<Long> customerIdListFromThread ->
            List<Long> pendingAuthorizationIdList = InvoiceAuthorizationRequest.query([column: "id", status: InvoiceAuthorizationRequestStatus.PENDING, invoiceIssuerType: InvoiceIssuerType.CUSTOMER, "customerId[in]": customerIdListFromThread, sort: "id", order: "asc"]).list(max: maxNumberOfAuthorizationPerThread)

            for (Long authorizationId in pendingAuthorizationIdList) {
                processInvoiceAuthorizationRequest(authorizationId)
            }
        })
    }

    public void processAuthorization(InvoiceAuthorizationRequest authorizationRequest) {
        Invoice invoice = invoiceService.updateInvoiceFromAuthorizationRequest(authorizationRequest)

        customerInvoiceService.afterUpdateStatus(invoice)
        customerFiscalInfoService.updateInvoiceFromAuthorizationRequest(invoice, authorizationRequest)

        authorizationRequest.status = InvoiceAuthorizationRequestStatus.PROCESSED
        authorizationRequest.save(failOnError: true)
    }

    public InvoiceAuthorizationRequest simulateAuthorization(Invoice invoice) {
        AsaasLogger.info("doSimulationAuthorizedInvoice -> Criando simulação de retorno de NF [Id. ${invoice.id}]")

        InvoiceAuthorizationRequest invoiceAuthorizationRequest = new InvoiceAuthorizationRequest()
        invoiceAuthorizationRequest.invoice = invoice
        invoiceAuthorizationRequest.invoiceNumber = invoiceAuthorizationRequest.invoiceRpsNumber
        invoiceAuthorizationRequest.invoiceExternalId = invoice.externalId
        invoiceAuthorizationRequest.invoiceValidationCode = invoice.validationCode
        invoiceAuthorizationRequest.invoiceStatus = InvoiceStatus.AUTHORIZED
        invoiceAuthorizationRequest.invoiceIssuerType = InvoiceIssuerType.CUSTOMER
        invoiceAuthorizationRequest.status = InvoiceAuthorizationRequestStatus.PENDING

        Map customerFiscalInfoMap = CustomerFiscalInfo.query([columnList: ["rpsSerie", "rpsNumber"], customerId: invoice.customerId]).get()
        if (!customerFiscalInfoMap) throw new BusinessException("Cliente não possui informações fiscais preenchidas")

        invoiceAuthorizationRequest.invoiceRpsSerie = customerFiscalInfoMap.rpsSerie
        invoiceAuthorizationRequest.invoiceRpsNumber = Utils.toInteger(customerFiscalInfoMap.rpsNumber) + 1

        invoiceAuthorizationRequest.save(failOnError: true)

        return invoiceAuthorizationRequest
    }

    private void processInvoiceAuthorizationRequest(Long invoiceAuthorizationRequestId) {
        AsaasLogger.info("InvoiceAuthorizationRequestService.processInvoiceAuthorizationRequest >> Processando [Id. ${ invoiceAuthorizationRequestId }]")

        Boolean hasErrors = false
        Utils.withNewTransactionAndRollbackOnError({
            InvoiceAuthorizationRequest authorizationRequest = InvoiceAuthorizationRequest.get(invoiceAuthorizationRequestId)
            processAuthorization(authorizationRequest)
        }, [ignoreStackTrace: true, onError: { Exception exception ->
            Exception exceptionCause = exception.getCause() ?: exception

            if (exceptionCause instanceof SocketTimeoutException) {
                AsaasLogger.warn("InvoiceAuthorizationRequestService.processInvoiceAuthorizationRequest >> Timeout ao realizar download do arquivo da nota [Id. ${ invoiceAuthorizationRequestId }]", exceptionCause)
                return
            }

            hasErrors = true

            if (exceptionCause instanceof BusinessException) return
            AsaasLogger.error("InvoiceAuthorizationRequestService.processInvoiceAuthorizationRequest >> Erro ao processar a Autorização da nota [Id. ${ invoiceAuthorizationRequestId }]", exceptionCause)
        }])

        if (!hasErrors) return

        Utils.withNewTransactionAndRollbackOnError({
            InvoiceAuthorizationRequest authorizationRequest = InvoiceAuthorizationRequest.get(invoiceAuthorizationRequestId)
            authorizationRequest.status = InvoiceAuthorizationRequestStatus.ERROR
            authorizationRequest.save(failOnError: true)
        })
    }
}
