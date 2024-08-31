package com.asaas.service.pix

import com.asaas.billinginfo.BillingType
import com.asaas.checkout.CheckoutValidator
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFeature
import com.asaas.domain.customer.CustomerPixConfig
import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.PixAsaasQrCode
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.pix.adapter.qrcode.PixImmediateQrCodeListAdapter
import com.asaas.pix.adapter.qrcode.PixQrCodeTransactionListAdapter
import com.asaas.pix.adapter.qrcode.QrCodeAdapter
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PixQrCodeService {

    def billingTypeService
    def hermesQrCodeManagerService
    def pixAsaasQrCodeService

    private final Integer maximumAdditionalInfoListSize = 50
    private final Integer maximumAdditionalInfoNameLength = 50
    private final Integer maximumAdditionalInfoValueLength = 200
    private final Integer maximumDescriptionLength = 140
    private final Integer maximumPayerNameLength = 200
    private final Integer maximumPixKeyLength = 77

    public PixQrCodeTransactionListAdapter listTransactions(Customer customer, String conciliationIdentifier, Integer offset, Integer limit) {
        return hermesQrCodeManagerService.listTransactions(customer.id, conciliationIdentifier, offset, limit)
    }

    public QrCodeAdapter decode(String payload, Customer customer) {
        CheckoutValidator checkoutValidator = new CheckoutValidator(customer)
        checkoutValidator.isPixTransaction = true

        if (!checkoutValidator.customerCanUsePix()) {
            throw new BusinessException(Utils.getMessageProperty("pix.denied.proofOfLife.${ customer.getProofOfLifeType() }.notApproved"))
        }

        if (!PixUtils.isQrCodePayloadValid(payload)) throw new BusinessException("Não foi possível decodificar o QR Code. Payload inválido.")

        return hermesQrCodeManagerService.decode(payload, customer)
    }

    public Map createStaticQrCode(Customer customer, Map pixQrCodeParams) {
        CheckoutValidator checkoutValidator = new CheckoutValidator(customer)
        checkoutValidator.isPixTransaction = true

        if (!bypassCheckoutValidatorToCreateStaticQrCode(customer) && !checkoutValidator.customerCanUsePix()) {
            throw new BusinessException(Utils.getMessageProperty("pix.denied.proofOfLife.${ customer.getProofOfLifeType() }.notApproved"))
        }

        if (pixQrCodeParams.permittedPayerCpfCnpj) {
            if (!CpfCnpjUtils.validate(pixQrCodeParams.permittedPayerCpfCnpj.toString())) throw new BusinessException("Documento para restrição de recebimento do pagador é inválido")
        }

        return hermesQrCodeManagerService.createStaticQrCode(customer.id, pixQrCodeParams)
    }

    public void delete(Customer customer, String conciliationIdentifier) {
        if (!conciliationIdentifier) throw new BusinessException("O identificador do QR Code deve ser informado.")

        CheckoutValidator checkoutValidator = new CheckoutValidator(customer)
        checkoutValidator.isPixTransaction = true

        if (!checkoutValidator.customerCanUsePix()) throw new BusinessException(Utils.getMessageProperty("pix.denied.proofOfLife.${customer.getProofOfLifeType()}.notApproved"))

        hermesQrCodeManagerService.deleteStatic(customer.id, conciliationIdentifier)
    }

    public Map createDynamicQrCode(Payment payment) {
        BusinessValidation validatedBusiness = validateCanBeGenerated(payment)
        if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

        return hermesQrCodeManagerService.createDynamicQrCode(payment)
    }

    public Map createImmediateQrCode(Long customerId, Map pixQrCodeParams) {
        BusinessValidation validatedBusiness = validateCreateImmediate(pixQrCodeParams)
        if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

        return hermesQrCodeManagerService.createImmediateQrCode(customerId, pixQrCodeParams)
    }

    public Map getImmediate(Long customerId, String conciliationIdentifier) {
        if (!PixUtils.isValidConciliationIdentifier(conciliationIdentifier)) {
            throw new BusinessException(Utils.getMessageProperty("pixQrCode.validateImmediate.error.invalidConciliationIdentifier"))
        }

        return hermesQrCodeManagerService.getImmediate(customerId, conciliationIdentifier)
    }

    public PixImmediateQrCodeListAdapter listImmediate(Long customerId, Map filters) {
        return hermesQrCodeManagerService.listImmediate(customerId, filters)
    }

    public Map updateImmediate(Long customerId, Map pixQrCodeParams) {
        BusinessValidation validatedBusiness = validateUpdateImmediate(pixQrCodeParams)
        if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

        return hermesQrCodeManagerService.updateImmediate(customerId, pixQrCodeParams)
    }

    public Map createQrCodeForPayment(Payment payment) {
        Map pixQrCodeInfo = [:]

        if (CustomerPixConfig.customerCanReceivePaymentWithPixOwnKey(payment.provider)) {
            Map response = createDynamicQrCode(payment)

            pixQrCodeInfo.success = response.success
            pixQrCodeInfo.encodedImage = response.encodedImage
            pixQrCodeInfo.payload = response.payload
            pixQrCodeInfo.expirationDate = response.expirationDate
        } else if (CustomerFeature.isPixWithAsaasKeyEnabled(payment.provider.id) || payment.billingType.isPix()) {
            PixAsaasQrCode pixQrCode = pixAsaasQrCodeService.saveIfNecessary(payment)
            if (pixQrCode.hasErrors() || pixQrCode.status.isInvalidToPay()) {
                throw new BusinessException("Não foi possível gerar o QrCode no momento. Tente novamente mais tarde.")
            }

            pixQrCodeInfo.success = true
            pixQrCodeInfo.encodedImage = pixQrCode.buildQrCodeEncodedImage()
            pixQrCodeInfo.payload = pixQrCode.payload
            pixQrCodeInfo.expirationDate = pixQrCode.expirationDate
        } else {
            throw new BusinessException("Você não possui uma chave Pix cadastrada para recebimentos de cobranças via Pix.")
        }
        return pixQrCodeInfo
    }

    public String getDynamicQrCodeJws(Payment payment) {
        BusinessValidation validatedBusiness = validateCanBeGenerated(payment)
        if (!validatedBusiness.isValid()) return null

        hermesQrCodeManagerService.getDynamicQrCodeJws(payment)
    }

    public String getImmediateQrCodeJws(String publicId) {
        hermesQrCodeManagerService.getImmediateQrCodeJws(publicId)
    }

    public BusinessValidation validateCanBeGenerated(Payment payment) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!payment.canBeReceived()) {
            validatedBusiness.addError("pixQrCode.validateCanBeGenerated.error.paymentCannotBeReceived")
            return validatedBusiness
        }

        Boolean allowedBillingTypePix = billingTypeService.getAllowedBillingTypeList(payment).contains(BillingType.PIX)
        if (!allowedBillingTypePix) {
            validatedBusiness.addError("pixQrCode.validateCanBeGenerated.error.notAllowedBillingTypePix")
            return validatedBusiness
        }

        return validatedBusiness
    }

    public Map mockDynamicQrCodeIfPossible(Payment payment) {
        if (!PixUtils.paymentReceivingWithPixEnabled(payment.provider)) return null

        return hermesQrCodeManagerService.mockDynamicQrCode()
    }

    private Boolean bypassCheckoutValidatorToCreateStaticQrCode(Customer customer) {
        final List<Long> BYPASS_CUSTOMER_ID_LIST = [
            2375888L, 2527914L, 2582938L, 2582946L, 2582948L, 2582950L, 2582952L, 2582954L, 3306741L, 3745589L, 2583224L, 2690360L
        ]

        if (BYPASS_CUSTOMER_ID_LIST.contains(customer.id)) return true
        if (BYPASS_CUSTOMER_ID_LIST.contains(customer.accountOwnerId)) return true

        return false
    }

    private validateCreateImmediate(Map pixQrCodeParams) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (pixQrCodeParams.conciliationIdentifier && !PixUtils.isValidConciliationIdentifier(pixQrCodeParams.conciliationIdentifier)) {
            validatedBusiness.addError("pixQrCode.validateImmediate.error.invalidConciliationIdentifier")
            return validatedBusiness
        }

        if (pixQrCodeParams.expirationSeconds <= 0) {
            validatedBusiness.addError("pixQrCode.validateImmediate.error.expirationSecondsLowerOrEqualToZero")
            return validatedBusiness
        }

        if (!pixQrCodeParams.value || pixQrCodeParams.value <= 0) {
            validatedBusiness.addError("pixQrCode.validateImmediate.error.invalidValue")
            return validatedBusiness
        }

        if (pixQrCodeParams.pixKey.length() > maximumPixKeyLength || !PixUtils.buildKeyType(pixQrCodeParams.pixKey)) {
            validatedBusiness.addError("pixQrCode.validateImmediate.error.invalidPixKey")
            return validatedBusiness
        }

        if (pixQrCodeParams.description && pixQrCodeParams.description.length() > maximumDescriptionLength) {
            validatedBusiness.addError("pixQrCode.validateImmediate.error.descriptionExceedsMaximumLength")
            return validatedBusiness
        }

        validatedBusiness = validateImmediatePayerInfo(pixQrCodeParams.payerName, pixQrCodeParams.payerCpfCnpj)
        if (!validatedBusiness.isValid()) return validatedBusiness

        if (pixQrCodeParams.additionalInfoList) {
            validatedBusiness = validateAdditionalInfo(pixQrCodeParams.additionalInfoList)
        }

        return validatedBusiness
    }

    private validateUpdateImmediate(Map pixQrCodeParams) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!PixUtils.isValidConciliationIdentifier(pixQrCodeParams.conciliationIdentifier)) {
            validatedBusiness.addError("pixQrCode.validateImmediate.error.invalidConciliationIdentifier")
            return validatedBusiness
        }

        if (pixQrCodeParams.expirationSeconds && pixQrCodeParams.expirationSeconds <= 0) {
            validatedBusiness.addError("pixQrCode.validateImmediate.error.expirationSecondsLowerOrEqualToZero")
            return validatedBusiness
        }

        if (pixQrCodeParams.value && pixQrCodeParams.value <= 0) {
            validatedBusiness.addError("pixQrCode.validateImmediate.error.invalidValue")
            return validatedBusiness
        }

        if (pixQrCodeParams.pixKey) {
            if (pixQrCodeParams.pixKey.length() > maximumPixKeyLength || !PixUtils.buildKeyType(pixQrCodeParams.pixKey)) {
                validatedBusiness.addError("pixQrCode.validateImmediate.error.invalidPixKey")
                return validatedBusiness
            }
        }

        if (pixQrCodeParams.description && pixQrCodeParams.description.length() > maximumDescriptionLength) {
            validatedBusiness.addError("pixQrCode.validateImmediate.error.descriptionExceedsMaximumLength")
            return validatedBusiness
        }

        validatedBusiness = validateImmediatePayerInfo(pixQrCodeParams.payerName, pixQrCodeParams.payerCpfCnpj)
        if (!validatedBusiness.isValid()) return validatedBusiness

        if (pixQrCodeParams.additionalInfoList) {
            validatedBusiness = validateAdditionalInfo(pixQrCodeParams.additionalInfoList)
        }

        return validatedBusiness
    }

    private BusinessValidation validateImmediatePayerInfo(String payerName, String payerCpfCnpj) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        Boolean hasInformedPayerInfo = payerName || payerCpfCnpj
        Boolean hasCompletePayerInfo = payerName && payerCpfCnpj
        if (hasInformedPayerInfo && !hasCompletePayerInfo) {
            validatedBusiness.addError("pixQrCode.validateImmediate.error.invalidPayerInfo")
            return validatedBusiness
        }

        if (payerCpfCnpj && !CpfCnpjUtils.validate(payerCpfCnpj)) {
            validatedBusiness.addError("pixQrCode.validateImmediate.error.invalidPayerCpfCnpj")
            return validatedBusiness
        }

        if (payerName && payerName.length() > maximumPayerNameLength) {
            validatedBusiness.addError("pixQrCode.validateImmediate.error.invalidPayerName")
            return validatedBusiness
        }

        return validatedBusiness
    }

    private BusinessValidation validateAdditionalInfo(List<Map> additionalInfoList) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (additionalInfoList.size() > maximumAdditionalInfoListSize) {
            validatedBusiness.addError("pixQrCode.validateImmediate.error.additionalInfoListExceedsMaximumSize")
            return validatedBusiness
        }

        for (Map additionalInfo : additionalInfoList) {
            if (!additionalInfo.name) {
                validatedBusiness.addError("pixQrCode.validateImmediate.error.invalidAdditionalInfoName")
                break
            }

            if (additionalInfo.name.length() > maximumAdditionalInfoNameLength) {
                validatedBusiness.addError("pixQrCode.validateImmediate.error.additionalInfoNameExceedsMaximumLength")
                break
            }

            if (!additionalInfo.value) {
                validatedBusiness.addError("pixQrCode.validateImmediate.error.invalidAdditionalInfoValue")
                break
            }

            if (additionalInfo.value.length() > maximumAdditionalInfoValueLength) {
                validatedBusiness.addError("pixQrCode.validateImmediate.error.additionalInfoValueExceedsMaximumLength")
                break
            }
        }

        return validatedBusiness
    }
}
