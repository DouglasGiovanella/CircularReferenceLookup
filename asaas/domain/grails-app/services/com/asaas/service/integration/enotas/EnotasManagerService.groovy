package com.asaas.service.integration.enotas

import com.asaas.domain.customer.CustomerFiscalInfo
import com.asaas.domain.invoice.Invoice
import com.asaas.enotas.EnotasManager
import com.asaas.integration.invoice.api.dto.CompanyDTO
import com.asaas.integration.invoice.api.dto.InvoiceDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class EnotasManagerService {

    def getInvoice(Invoice invoice) {
        String externalId = CustomerFiscalInfo.getExternalId(invoice.customerId)
        String path = "/empresas/${externalId}/nfes/porIdExterno/${invoice.id.toString()}"

        EnotasManager manager = new EnotasManager()

        try {
            manager.get(path)

            if (!manager.isSuccessful()) throw new RuntimeException("EnotasManagerService.getInvoice -> Falha na requisição GET.")
        } catch (Exception exception) {
            AsaasLogger.error("EnotasManagerService.getInvoice -> Falha na requisição GET. Customer: [${invoice.customerId}]. Invoice: [${invoice.id}]. Status: [${manager.statusCode}]. ErrorMessage: [${manager.errorMessage}]")
            throw exception
        }

        InvoiceDTO invoiceDto = GsonBuilderUtils.buildClassFromJson((manager.responseBody as JSON).toString(), InvoiceDTO)

        return invoiceDto
    }

    public CompanyDTO getCompanyInfo(Long customerId) {
        String externalId = CustomerFiscalInfo.getExternalId(customerId)
        String path = "/empresas/${externalId}"

        EnotasManager manager = new EnotasManager()

        try {
            manager.get(path)

            if (!manager.isSuccessful()) throw new RuntimeException("EnotasManagerService.getCompanyInfo -> Falha na requisição GET.")
        } catch (Exception exception) {
            AsaasLogger.error("EnotasManagerService.getCompanyInfo -> Falha na requisição GET. Customer: [${customerId}]. Status: [${manager.statusCode}]. ErrorMessage: [${manager.errorMessage}]")
            throw exception
        }

        CompanyDTO companyDto = GsonBuilderUtils.buildClassFromJson((manager.responseBody as JSON).toString(), CompanyDTO)

        return companyDto
    }
}
