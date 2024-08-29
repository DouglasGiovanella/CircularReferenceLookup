package com.asaas.service.customerinvoiceconfigadmin

import com.asaas.customer.CustomerInvoiceConfigRejectReason
import com.asaas.customer.CustomerInvoiceConfigStatus
import com.asaas.customer.InvoiceCustomerInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerInvoiceConfig
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.utils.ColorUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import sun.reflect.generics.reflectiveObjects.NotImplementedException

@Transactional
class CustomerInvoiceConfigAdminService {

    def customerAlertNotificationService
    def customerFiscalInfoService
    def customerInvoiceConfigCacheService
    def customerInteractionService
    def customerMessageService
    def notificationDispatcherCustomerOutboxService
    def treasureDataService

    public CustomerInvoiceConfig save(Customer customer, List<String> customerInfoList, String customerInfoFontColor) {
        CustomerInvoiceConfig currentCustomerInfo = CustomerInvoiceConfig.findCurrent(customer)
        if (currentCustomerInfo) return update(currentCustomerInfo, customerInfoList, customerInfoFontColor)

        CustomerInvoiceConfig validatedCustomerInvoiceConfig = validateSave(customer, customerInfoList, customerInfoFontColor)
        if (validatedCustomerInvoiceConfig.hasErrors()) return validatedCustomerInvoiceConfig

        CustomerInvoiceConfig customerInvoiceConfig = new CustomerInvoiceConfig()
        customerInvoiceConfig.customerInfo = customerInfoList.join(",")
        customerInvoiceConfig.providerInfoOnTop = true
        customerInvoiceConfig.customer = customer
        customerInvoiceConfig.status = CustomerInvoiceConfigStatus.APPROVED
        customerInvoiceConfig.observations = Utils.getMessageProperty("system.automaticApproval.description")

        if (customerInfoFontColor) {
            customerInvoiceConfig.customerInfoFontColor = customerInfoFontColor
        }

        customerInvoiceConfig.save(failOnError: true)

        customerInvoiceConfigCacheService.evictbyCustomerId(customerInvoiceConfig.customer.id)
        notificationDispatcherCustomerOutboxService.onCustomerInvoiceConfigUpdated(customerInvoiceConfig)

        return customerInvoiceConfig
    }

    public CustomerInvoiceConfig finishAnalysis(Long id, CustomerInvoiceConfigStatus status, CustomerInvoiceConfigRejectReason rejectReason) {
        CustomerInvoiceConfig customerInvoiceConfig = CustomerInvoiceConfig.get(id)
        if (!status) return DomainUtils.addError(customerInvoiceConfig, "É necessário informar o parecer do analista")

        switch (status) {
            case CustomerInvoiceConfigStatus.APPROVED:
                customerInvoiceConfig = approve(customerInvoiceConfig, null, false)
                break
            case CustomerInvoiceConfigStatus.REJECTED:
                customerInvoiceConfig = reject(customerInvoiceConfig, rejectReason)
                break
            default:
                throw new NotImplementedException("O status selecionado não é válido para finalizar a análise")
        }

        if (!customerInvoiceConfig.hasErrors()) treasureDataService.track(customerInvoiceConfig.customer, TreasureDataEventType.CUSTOMER_INVOICE_CONFIG_ANALYZED, [customerInvoiceConfigId: customerInvoiceConfig.id])

        return customerInvoiceConfig
    }

    public CustomerInvoiceConfig approve(CustomerInvoiceConfig customerInvoiceConfig, String observations, Boolean isAutomaticallyApproved) {
        BusinessValidation validatedBusiness = canApproveOrReject(customerInvoiceConfig)
        if (!validatedBusiness.isValid()) return DomainUtils.addError(customerInvoiceConfig, validatedBusiness.getFirstErrorMessage())
        if (customerInvoiceConfig.status.isApproved()) {
            return DomainUtils.addError(customerInvoiceConfig, Utils.getMessageProperty("customerInvoiceConfig.error.alreadyApproved"))
        }

        deleteApprovedIfExists(customerInvoiceConfig.customer)

        customerInvoiceConfig.status = CustomerInvoiceConfigStatus.APPROVED
        customerInvoiceConfig.observations = observations
        customerInvoiceConfig.save(failOnError: true)

        if (!isAutomaticallyApproved) {
            customerInteractionService.save(customerInvoiceConfig.customer, "Personalização da fatura aprovada")
        }

        customerFiscalInfoService.synchronizeLogoIfNecessary(customerInvoiceConfig.customer)
        customerInvoiceConfigCacheService.evictbyCustomerId(customerInvoiceConfig.customer.id)
        notificationDispatcherCustomerOutboxService.onCustomerInvoiceConfigUpdated(customerInvoiceConfig)

        return customerInvoiceConfig
    }

    public BusinessValidation canApproveOrReject(CustomerInvoiceConfig customerInvoiceConfig) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!customerInvoiceConfig) {
            validatedBusiness.addError("customerInvoiceConfig.error.notFound")
            return validatedBusiness
        }

        if (customerInvoiceConfig.status.isRejected()) {
            String customerInvoiceConfigStatusMessage = Utils.getMessageProperty("CustomerInvoiceConfigStatus.approvalStatus.${customerInvoiceConfig.status}")
            validatedBusiness.addError("customerInvoiceConfig.error.isNotPossibleAnalyze", [customerInvoiceConfigStatusMessage])
            return validatedBusiness
        }

        List<Long> customerInvoiceConfigAvailableToAnalyzeList = CustomerInvoiceConfig.query(
            [column    : "id",
             customer  : customerInvoiceConfig.customer,
             statusList: [CustomerInvoiceConfigStatus.APPROVED, CustomerInvoiceConfigStatus.AWAITING_APPROVAL],
             sort      : "id",
             order     : "desc"]
        ).list() as List<Long>
        Boolean isCurrentEnabled = customerInvoiceConfigAvailableToAnalyzeList.any { Long currentId -> currentId.equals(customerInvoiceConfig.id) }

        if (!isCurrentEnabled) {
            validatedBusiness.addError("customerInvoiceConfig.error.isNotCurrentEnabled")
            return validatedBusiness
        }

        return validatedBusiness
    }

    private CustomerInvoiceConfig reject(CustomerInvoiceConfig customerInvoiceConfig, CustomerInvoiceConfigRejectReason rejectReason) {
        BusinessValidation validatedBusiness = validateBeforeReject(customerInvoiceConfig, rejectReason)
        if (!validatedBusiness.isValid()) return DomainUtils.addError(customerInvoiceConfig, validatedBusiness.getFirstErrorMessage())

        String reason = Utils.getMessageProperty("CustomerInvoiceConfigRejectReason.${rejectReason}")
        customerInvoiceConfig.status = CustomerInvoiceConfigStatus.REJECTED
        customerInvoiceConfig.observations = reason
        customerInvoiceConfig.save(failOnError: true)

        customerInteractionService.saveWithReason(customerInvoiceConfig.customer, "Personalização da fatura rejeitada", reason)
        customerMessageService.sendInvoiceConfigRejectionReason(customerInvoiceConfig, rejectReason)
        customerAlertNotificationService.notifyInvoiceConfigRejected(customerInvoiceConfig.customer)

        return customerInvoiceConfig
    }

    private BusinessValidation validateBeforeReject(CustomerInvoiceConfig customerInvoiceConfig, CustomerInvoiceConfigRejectReason rejectReason) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!rejectReason) {
            validatedBusiness.addError("default.null.message", ["motivo de reprovação"])
            return validatedBusiness
        }

        return canApproveOrReject(customerInvoiceConfig)
    }

    private CustomerInvoiceConfig update(CustomerInvoiceConfig currentCustomerInfo, List<String> customerInfoList, String customerInfoFontColor) {
        CustomerInvoiceConfig validatedCustomerInfo = validateUpdate(currentCustomerInfo, customerInfoList, customerInfoFontColor)
        if (validatedCustomerInfo.hasErrors()) return validatedCustomerInfo

        currentCustomerInfo.customerInfo = customerInfoList.join(",")
        currentCustomerInfo.providerInfoOnTop = true
        currentCustomerInfo.save(failOnError: true)

        customerInvoiceConfigCacheService.evictbyCustomerId(currentCustomerInfo.customer.id)
        notificationDispatcherCustomerOutboxService.onCustomerInvoiceConfigUpdated(currentCustomerInfo)

        return currentCustomerInfo
    }

    public CustomerInvoiceConfig updatePublicInfo(Long customerInvoiceConfigId, Map params) {
        Map parsedParams = parsePublicCustomerInvoiceInfoParams(params)

        CustomerInvoiceConfig validatedCustomerInvoiceConfig = validateCustomerInvoiceConfigPublicInfo(parsedParams)

        if (validatedCustomerInvoiceConfig.hasErrors()) return validatedCustomerInvoiceConfig

        CustomerInvoiceConfig customerInvoiceConfig = CustomerInvoiceConfig.get(customerInvoiceConfigId)
        customerInvoiceConfig.publicName = parsedParams.publicName
        customerInvoiceConfig.publicEmail = parsedParams.publicEmail
        customerInvoiceConfig.save(flush: true, failOnError: true)

        Customer customer = customerInvoiceConfig.customer
        customer.publicPhoneNumbers = parsedParams.publicPhoneNumbers
        customer.save(flush: true, failOnError: true)

        return customerInvoiceConfig
    }

    private CustomerInvoiceConfig validateCustomerInvoiceConfigPublicInfo(Map params) {
        CustomerInvoiceConfig validatedCustomerInvoiceConfig = new CustomerInvoiceConfig()

        if (params.publicEmail) {
            if (!Utils.emailIsValid(params.publicEmail)) {
                DomainUtils.addError(validatedCustomerInvoiceConfig, "O email público informado é inválido.")
            }
        }

        if (params.publicPhoneNumbers) {
            Integer numberOfPublicPhoneNumbers = (params.publicPhoneNumbers.tokenize(',')).size()

            if (numberOfPublicPhoneNumbers > 2) {
                DomainUtils.addError(validatedCustomerInvoiceConfig, "Quantidade limite de telefones públicos excedido.")
            }
        }
        return validatedCustomerInvoiceConfig
    }

    private Map parsePublicCustomerInvoiceInfoParams(Map params) {
        if (params.publicEmail) {
            params.publicEmail = params.publicEmail.trim()
        }

        return params
    }

    private CustomerInvoiceConfig validateUpdate(CustomerInvoiceConfig currentCustomerInfo, List<String> customerInfoList, String customerInfoFontColor) {
        CustomerInvoiceConfig validatedCustomerInvoiceConfig = new CustomerInvoiceConfig()

        if (!currentCustomerInfo.status.isApproved()) {
            DomainUtils.addError(validatedCustomerInvoiceConfig, Utils.getMessageProperty("required.com.asaas.domain.customer.CustomerInvoiceConfig.statusApproved"))
            return validatedCustomerInvoiceConfig
        }

        return validateSave(currentCustomerInfo.customer, customerInfoList, customerInfoFontColor)
    }

    private CustomerInvoiceConfig validateSave(Customer customer, List<String> customerInfoList, String customerInfoFontColor) {
        CustomerInvoiceConfig validatedCustomerInvoiceConfig = new CustomerInvoiceConfig()

        if (!customerInfoList) {
            DomainUtils.addError(validatedCustomerInvoiceConfig, "Selecione as informações que deverão aparecer na fatura.")
            return validatedCustomerInvoiceConfig
        }

        if (!customerInfoList.contains(InvoiceCustomerInfo.NAME.toString())) {
            DomainUtils.addError(validatedCustomerInvoiceConfig, "É obrigatório incluir o nome do cliente.")
            return validatedCustomerInvoiceConfig
        }

        if (customer.isLegalPerson() && !customerInfoList.contains(InvoiceCustomerInfo.CPF_CNPJ.toString())) {
            DomainUtils.addError(validatedCustomerInvoiceConfig, "É obrigatório incluir CNPJ nas faturas de pessoas jurídicas.")
            return validatedCustomerInvoiceConfig
        }

		if (customerInfoFontColor && !ColorUtils.isHexadecimalColorCode(customerInfoFontColor)) {
			DomainUtils.addError(customerInvoiceConfig, "A cor informada é inválida.")
		}

        for (String customerInfoString : customerInfoList) {
            InvoiceCustomerInfo customerInfo = InvoiceCustomerInfo.convert(customerInfoString)

            if (!InvoiceCustomerInfo.values().contains(customerInfo)) {
                DomainUtils.addError(validatedCustomerInvoiceConfig, "O campo ${customerInfoString} é inválido.")
                return validatedCustomerInvoiceConfig
            }
        }

        return validatedCustomerInvoiceConfig
    }

    private void deleteApprovedIfExists(Customer customer) {
        CustomerInvoiceConfig currentCustomerInvoiceConfig = CustomerInvoiceConfig.findLatestApproved(customer)
        if (currentCustomerInvoiceConfig) {
            currentCustomerInvoiceConfig.deleted = true
            currentCustomerInvoiceConfig.save(failOnError: true)
        }
    }
}
