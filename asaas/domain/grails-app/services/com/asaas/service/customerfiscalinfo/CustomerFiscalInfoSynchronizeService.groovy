package com.asaas.service.customerfiscalinfo

import com.asaas.domain.customer.CustomerFiscalInfo
import com.asaas.domain.file.AsaasFile
import com.asaas.log.AsaasLogger
import com.asaas.utils.FileUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import com.asaas.integration.invoice.api.manager.CustomerFiscalInfoRequestManager
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class CustomerFiscalInfoSynchronizeService {

    public Map synchronizeCustomerFiscalInfo(Long customerFiscalInfoId, Map customerInvoiceCredentials, Boolean synchronizeRps) {
        try {
            CustomerFiscalInfo customerFiscalInfo = CustomerFiscalInfo.get(customerFiscalInfoId)
            CustomerFiscalInfoRequestManager customerFiscalInfoRequestManager = new CustomerFiscalInfoRequestManager(customerFiscalInfo.customer, customerInvoiceCredentials, synchronizeRps)
            customerFiscalInfoRequestManager.create()

            if (customerFiscalInfoRequestManager.success) {
                return [success: true, externalId: customerFiscalInfoRequestManager.getCompanyId()]
            }

            AsaasLogger.warn("${this.class.simpleName}.synchronizeCustomerFiscalInfo >> Erro ao sincronizar configuração fiscal do cliente [${customerFiscalInfo?.customer?.id}] responseHttpStatus: ${customerFiscalInfoRequestManager.getResponseHttpStatus()} mensagem: ${customerFiscalInfoRequestManager.getErrorResponse()}")
            return [success: false, error: customerFiscalInfoRequestManager.getErrorResponse()]
        } catch (Exception exception) {
            AsaasLogger.error("${this.class.simpleName}.synchronizeCustomerFiscalInfo >> Falha ao sincronizar configuração fiscal ID [${customerFiscalInfoId}]", exception)
            return [success: false, error: "Ocorreu um erro ao atualizar as suas configurações. Por favor, tente novamente em alguns minutos."]
        }
    }

    public Map synchronizeDigitalCertificate(Long customerFiscalInfoId, CommonsMultipartFile certificateFile, String certificatePassword) {
        CustomerFiscalInfo customerFiscalInfo
        try {
            if (!isValidCertificateFile(certificateFile)) {
                return [success: false, error: Utils.getMessageProperty("customerFiscalInfo.accessData.certificate.error.invalid")]
            }

            customerFiscalInfo = CustomerFiscalInfo.get(customerFiscalInfoId)
            CustomerFiscalInfoRequestManager customerFiscalInfoRequestManager = new CustomerFiscalInfoRequestManager(customerFiscalInfo.customer)
            customerFiscalInfoRequestManager.updateCertificate(certificateFile, certificatePassword)

            if (customerFiscalInfoRequestManager.success) {
                return [success: true]
            } else {
                return [success: false, error: customerFiscalInfoRequestManager.getErrorResponse(true)]
            }
        } catch (Exception exception) {
            AsaasLogger.error("${this.class.simpleName}.synchronizeDigitalCertificate >> Error synchronizing digital certificate from ${customerFiscalInfo?.customer?.providerName} [${customerFiscalInfo?.customer?.id}]", exception)
                    return [success: false, error: "Ocorreu um erro ao atualizar o seu certificado digital. Por favor, tente novamente em alguns minutos."]
        }
	}

	public Map synchronizeLogo(Long customerFiscalInfoId, AsaasFile logoFile) {
		CustomerFiscalInfo customerFiscalInfo
		try {
			customerFiscalInfo = CustomerFiscalInfo.get(customerFiscalInfoId)
			CustomerFiscalInfoRequestManager customerFiscalInfoRequestManager = new CustomerFiscalInfoRequestManager(customerFiscalInfo.customer)
			customerFiscalInfoRequestManager.updateLogo(logoFile)

			if (customerFiscalInfoRequestManager.success) {
				return [success: true]
			} else {
				return [success: false, error: customerFiscalInfoRequestManager.getErrorResponse(true)]
			}
        } catch (Exception exception) {
            AsaasLogger.error("${this.class.simpleName}.synchronizeLogo >> Error synchronizing logo from ${customerFiscalInfo?.customer?.providerName} [${customerFiscalInfo?.customer?.id}]", exception)
            return [success: false, error: "Ocorreu um erro ao atualizar a sua logo. Por favor, tente novamente em alguns minutos."]
        }
	}

    public Boolean isValidCertificateFile(CommonsMultipartFile certificateFile) {
        if (!certificateFile?.contentType) return false

        final List<String> acceptedFileTypes = ["p12", "pfx"]
        String fileExtensionName = FileUtils.getFileExtension(certificateFile.getOriginalFilename())

        if (acceptedFileTypes.contains(fileExtensionName.toLowerCase())) return true

        return false
    }
}
