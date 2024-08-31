package com.asaas.service.customer

import com.asaas.customer.CustomerInvoiceConfigCacheVO
import com.asaas.customer.CustomerInvoiceConfigStatus
import com.asaas.customer.InvoiceCustomerInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerInvoiceConfig
import com.asaas.domain.customer.CustomerNotificationConfig
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.file.TemporaryFile
import com.asaas.domain.picture.Picture
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationMessageType
import com.asaas.utils.ColorUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

import org.apache.commons.io.FilenameUtils
import org.codehaus.groovy.grails.web.util.TypeConvertingMap
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class CustomerInvoiceConfigService {

    def customerConfigService
    def customerInvoiceConfigAdminService
    def customerInvoiceConfigCacheService
	def fileService
	def pictureService
	def temporaryFileService

    public Boolean customerInfoIsToBeShowed(Customer customer, InvoiceCustomerInfo customerInfo) {
        return getCustomerInfoToBeShowed(customer).contains(customerInfo)
    }

    private List<InvoiceCustomerInfo> getCustomerInfoToBeShowed(Customer customer) {
        CustomerInvoiceConfigCacheVO customerInvoiceConfigCacheVO = customerInvoiceConfigCacheService.byCustomerId(customer.id)
        if (customerInvoiceConfigCacheVO?.invoiceCustomerInfoList) {
            return customerInvoiceConfigCacheVO.invoiceCustomerInfoList
        }

        return CustomerInvoiceConfig.showDefaultCustomerInvoiceInfo(customer)
    }

	public CustomerInvoiceConfig save(Customer customer, Map params) {
        Boolean removeBrand = Utils.toBoolean(params.removeBrand)

		CustomerInvoiceConfig validatedCustomerInvoiceConfig = validateIfFieldsHasChanged(customer, params)
		if (validatedCustomerInvoiceConfig.hasErrors()) return validatedCustomerInvoiceConfig

        validatedCustomerInvoiceConfig = validateSaveParams(customer, params)
        if (validatedCustomerInvoiceConfig.hasErrors()) return validatedCustomerInvoiceConfig

        CustomerInvoiceConfig latestAwaitingApprovalConfig = CustomerInvoiceConfig.awaitingApproval([customer: customer]).get()

        deleteAwaitingApprovalIfExists(customer)
        deleteRejectedIfExists(customer)

        if (params.notificationMessageType && customer.customerConfig.notificationMessageType != NotificationMessageType.valueOf(params.notificationMessageType)) {
            customerConfigService.changeNotificationMessageType(customer, NotificationMessageType.valueOf(params.notificationMessageType))
        }

        CustomerInvoiceConfig latestApprovedConfig = CustomerInvoiceConfig.findLatestApproved(customer)

        CustomerInvoiceConfig configWithNewColors = new CustomerInvoiceConfig(latestApprovedConfig?.properties)
        configWithNewColors.properties["providerInfoOnTop", "primaryColor", "secondaryColor", "customerInfoFontColor"] = params
        configWithNewColors.observations = null
        configWithNewColors.customer = customer
        configWithNewColors.status = CustomerInvoiceConfigStatus.AWAITING_APPROVAL

        if (removeBrand) {
            configWithNewColors.logoFile = null
            configWithNewColors.logoPicture = null
            configWithNewColors.bankSlipLogoPicture = null
            configWithNewColors.invoiceLogoPicture = null
            configWithNewColors.emailLogoPicture = null
        }

        configWithNewColors.save(failOnError: true)

        String observations = Utils.getMessageProperty("system.automaticApproval.description")

        configWithNewColors = customerInvoiceConfigAdminService.approve(configWithNewColors, observations, true)
        if (configWithNewColors.hasErrors()) return configWithNewColors

        TemporaryFile temporaryLogoFile = temporaryFileService.find(customer, params.temporaryLogoFileId)

        Boolean isRequiredSaveLogo = shouldSaveLogo(removeBrand, temporaryLogoFile, configWithNewColors, latestAwaitingApprovalConfig)

        if (!isRequiredSaveLogo) {
            if (!removeBrand) saveBankSlipLogoPicture(configWithNewColors)
            return configWithNewColors
        }

        CustomerInvoiceConfig configWithLogo = new CustomerInvoiceConfig(configWithNewColors.properties)
        configWithLogo.observations = null
        configWithLogo.status = CustomerInvoiceConfigStatus.AWAITING_APPROVAL
        configWithLogo.save(failOnError: true)

        if (temporaryLogoFile) {
            configWithLogo = saveLogoAndPicture(configWithLogo, temporaryLogoFile)
        } else {
            configWithLogo = replicateLogoAndPicture(configWithLogo, latestAwaitingApprovalConfig)
        }

        configWithLogo = customerInvoiceConfigAdminService.approve(configWithLogo, observations, true)

        return configWithLogo
    }

	public CustomerInvoiceConfig validateSaveParams(Customer customer, Map params) {
		CustomerInvoiceConfig latestCustomerInvoiceConfig = CustomerInvoiceConfig.findLatest(customer)
		CustomerInvoiceConfig customerInvoiceConfig = new CustomerInvoiceConfig()

		params.temporaryLogoFileId = new TypeConvertingMap(params).long("temporaryLogoFileId")

        if (Boolean.valueOf(params.providerInfoOnTop)) {
            if (!latestCustomerInvoiceConfig?.primaryColor && !params.primaryColor) {
				DomainUtils.addFieldError(customerInvoiceConfig, "primaryColor", "required")
			}

			if (!latestCustomerInvoiceConfig?.secondaryColor && !params.secondaryColor) {
				DomainUtils.addFieldError(customerInvoiceConfig, "secondaryColor", "required")
			}

			if (!latestCustomerInvoiceConfig?.customerInfoFontColor && !params.customerInfoFontColor) {
				DomainUtils.addFieldError(customerInvoiceConfig, "customerInfoFontColor", "required")
			}

            if (!Utils.toBoolean(params.removeBrand) && !latestCustomerInvoiceConfig?.logoFile && !params.temporaryLogoFileId) {
                DomainUtils.addFieldError(customerInvoiceConfig, "logoFile", "required")
            }
        }

        if (!Utils.toBoolean(params.removeBrand) && params.temporaryLogoFileId) {
            customerInvoiceConfig.errors.addAllErrors(validateLogoFile(customer, params.temporaryLogoFileId)?.errors)
        }

		if (params.primaryColor && !ColorUtils.isHexadecimalColorCode(params.primaryColor)) {
			DomainUtils.addError(customerInvoiceConfig, "A cor de fundo da logo é inválida.")
		}

		if (params.secondaryColor && !ColorUtils.isHexadecimalColorCode(params.secondaryColor)) {
			DomainUtils.addError(customerInvoiceConfig, "A cor de fundo de dados comerciais é inválida.")
		}

		if (params.customerInfoFontColor && !ColorUtils.isHexadecimalColorCode(params.customerInfoFontColor)) {
			DomainUtils.addError(customerInvoiceConfig, "A cor do texto é inválida.")
		}

        if (params.customerInfoFontColor && params.secondaryColor && !ColorUtils.testContrast(params.customerInfoFontColor, params.secondaryColor, 0.8)) {
            DomainUtils.addError(customerInvoiceConfig, "Baixa visibilidade das informações. Por favor, altere a cor do topo (fundo) ou a cor do texto para aumentar a visibilidade.")
        }

		return customerInvoiceConfig
	}

	public CustomerInvoiceConfig validateLogoFile(Customer customer, Long temporaryLogoFileId) {
        CustomerInvoiceConfig customerInvoiceConfig = new CustomerInvoiceConfig()

		TemporaryFile temporaryLogoFile = temporaryFileService.find(customer, temporaryLogoFileId)

        if (!temporaryLogoFile) {
            DomainUtils.addFieldError(customerInvoiceConfig, "logoFile", "required")
            return customerInvoiceConfig
        }

		if (!["png", "jpg", "jpeg"].contains(FilenameUtils.getExtension(temporaryLogoFile.originalName).toLowerCase())) {
			DomainUtils.addError(customerInvoiceConfig, "Formato do arquivo inválido. Faça upload de uma imagem.")
			return customerInvoiceConfig
		}

        return customerInvoiceConfig
	}

	public Map saveTemporaryLogoFile(Customer customer, params) {
        CommonsMultipartFile temporaryLogoFile

        if (params.logoFile instanceof CommonsMultipartFile) {
            temporaryLogoFile = params.logoFile
        }

        if (!temporaryLogoFile) {
            return [success: false, message: "Falha no recebimento do arquivo. Entre em contato com o suporte."]
        }

        TemporaryFile tempFile = temporaryFileService.save(customer, temporaryLogoFile, false)

        CustomerInvoiceConfig customerInvoiceConfig = validateLogoFile(customer, tempFile.id)
        if (customerInvoiceConfig.hasErrors()) {
            return [success: false, message: customerInvoiceConfig.errors.allErrors[0].defaultMessage]
        }

        return [success: true, message: "Logo salva com sucesso", temporaryLogoFileId: tempFile.id]
    }

    public Picture saveInvoiceLogoPicture(CustomerInvoiceConfig customerInvoiceConfig) {
        final Integer invoiceLogoMaximumWidth = 530
        final Integer invoiceLogoMaximumHeight = 447

        try {
            AsaasFile logoFile = customerInvoiceConfig.getLogo()
            if (!logoFile) return

            File resizedFile = pictureService.resizeMaintainingAspectRatio(logoFile, invoiceLogoMaximumWidth, invoiceLogoMaximumHeight)

            Picture invoiceLogo = pictureService.save(customerInvoiceConfig.customer, resizedFile)

            customerInvoiceConfig.invoiceLogoPicture = invoiceLogo
            customerInvoiceConfig.save(failOnError: true)

            return invoiceLogo
        } catch (Exception e) {
            AsaasLogger.error("Ocorreu um erro ao criar logo da nota fiscal para o cliente [${customerInvoiceConfig.customer.id}].", e)
            return null
        }
    }

    public Picture saveBankSlipLogoPicture(CustomerInvoiceConfig customerInvoiceConfig) {
        try {
            AsaasFile logoFile = customerInvoiceConfig.getLogo()
            if (!logoFile) return

            Map params = [:]
            params.extentWidth = 560
            params.extentHeight = 477

            if (customerInvoiceConfig.primaryColor) {
                params.backgroundColorToJpg = customerInvoiceConfig.primaryColor
                params.backgroundColorToResize = customerInvoiceConfig.primaryColor
            }

            File resizedFile = pictureService.resizeMaintainingAspectRatio(logoFile, CustomerInvoiceConfig.BANK_SLIP_LOGO_MAXIMUM_WIDTH, CustomerInvoiceConfig.BANK_SLIP_LOGO_MAXIMUM_HEIGHT, params)

            Picture bankSlipLogo = pictureService.save(customerInvoiceConfig.customer, resizedFile)

            customerInvoiceConfig.bankSlipLogoPicture = bankSlipLogo
            customerInvoiceConfig.save(failOnError: true)

            return bankSlipLogo
        } catch (Exception e) {
            AsaasLogger.error("Ocorreu um erro ao criar logo do boleto para o cliente [${customerInvoiceConfig.customer.id}].", e)
            return null
        }
    }

    public Picture saveEmailLogoPicture(CustomerInvoiceConfig customerInvoiceConfig) {
        try {
            AsaasFile logoFile = customerInvoiceConfig.getLogo()
            if (!logoFile) return

            File resizedFile = pictureService.resizeMaintainingAspectRatio(logoFile, CustomerNotificationConfig.LOGO_MAX_WIDTH, CustomerNotificationConfig.LOGO_MAX_HEIGHT)

            Picture emailLogo = pictureService.save(customerInvoiceConfig.customer, resizedFile)

            customerInvoiceConfig.emailLogoPicture = emailLogo
            customerInvoiceConfig.save(failOnError: true)

            return emailLogo
        } catch (Exception e) {
            AsaasLogger.error("Ocorreu um erro ao criar logo do email para o cliente [${customerInvoiceConfig.customer.id}].", e)
            return null
        }
    }

    private CustomerInvoiceConfig validateIfFieldsHasChanged(Customer customer, Map params) {
        CustomerInvoiceConfig latestCustomerInvoiceConfig = CustomerInvoiceConfig.findLatest(customer)
        CustomerInvoiceConfig customerInvoiceConfig = new CustomerInvoiceConfig()

        params.temporaryLogoFileId = new TypeConvertingMap(params).long("temporaryLogoFileId")

        if (latestCustomerInvoiceConfig?.providerInfoOnTop == Boolean.valueOf(params.providerInfoOnTop)
                && latestCustomerInvoiceConfig?.primaryColor == params.primaryColor
                && latestCustomerInvoiceConfig?.secondaryColor == params.secondaryColor
                && latestCustomerInvoiceConfig?.customerInfoFontColor == params.customerInfoFontColor
                && (latestCustomerInvoiceConfig?.readLogoBytes() == temporaryFileService.find(customer, params.temporaryLogoFileId)?.getFileBytes())
                && (params.notificationMessageType && customer.customerConfig.notificationMessageType == NotificationMessageType.valueOf(params.notificationMessageType))) {
            DomainUtils.addError(customerInvoiceConfig, "Para personalizar a sua fatura e as mensagens para o seu cliente altere alguma das informações.")
        }

        return customerInvoiceConfig
    }

    private void deleteRejectedIfExists(Customer customer) {
        CustomerInvoiceConfig rejectedCustomerInvoiceConfig = CustomerInvoiceConfig.findLatestRejected(customer)
        if (rejectedCustomerInvoiceConfig) {
            rejectedCustomerInvoiceConfig.deleted = true
            rejectedCustomerInvoiceConfig.save(failOnError: true)
        }
    }

    private void deleteAwaitingApprovalIfExists(Customer customer) {
        CustomerInvoiceConfig customerInvoiceConfig = CustomerInvoiceConfig.awaitingApproval([customer: customer]).get()
        if (customerInvoiceConfig) {
            customerInvoiceConfig.deleted = true
            customerInvoiceConfig.save(failOnError: true)
        }
    }

    private Boolean shouldSaveLogo(Boolean removeBrand, TemporaryFile temporaryLogoFile, CustomerInvoiceConfig configWithNewColors, CustomerInvoiceConfig latestAwaitingApprovalConfig) {
        if (removeBrand) return false

        if (temporaryLogoFile && configWithNewColors?.logoFile?.getFileBytes() != temporaryLogoFile.getFileBytes()) return true

        if (latestAwaitingApprovalConfig) return true

        return false
    }

    private CustomerInvoiceConfig saveLogoAndPicture(CustomerInvoiceConfig configWithLogo, TemporaryFile temporaryLogoFile) {
        configWithLogo.logoFile = fileService.saveFile(configWithLogo.customer, null, temporaryLogoFile)
        configWithLogo.logoPicture = pictureService.save(configWithLogo.customer, temporaryLogoFile, null)
        saveBankSlipLogoPicture(configWithLogo)
        saveInvoiceLogoPicture(configWithLogo)
        saveEmailLogoPicture(configWithLogo)

        return configWithLogo
    }

    private CustomerInvoiceConfig replicateLogoAndPicture(CustomerInvoiceConfig configWithLogo, CustomerInvoiceConfig latestAwaitingApprovalConfig) {
        configWithLogo.logoFile = latestAwaitingApprovalConfig.logoFile
        configWithLogo.logoPicture = latestAwaitingApprovalConfig.logoPicture
        configWithLogo.bankSlipLogoPicture = latestAwaitingApprovalConfig.bankSlipLogoPicture
        configWithLogo.invoiceLogoPicture = latestAwaitingApprovalConfig.invoiceLogoPicture
        configWithLogo.emailLogoPicture = latestAwaitingApprovalConfig.emailLogoPicture
        configWithLogo.save(failOnError: true)

        return configWithLogo
    }
}
