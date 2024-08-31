package com.asaas.service.pix

import com.asaas.checkout.CheckoutValidator
import com.asaas.criticalaction.CriticalActionType
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.asaasconfig.AsaasConfig
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFeature
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerPixConfig
import com.asaas.domain.pix.PixAddressKeyOwnershipConfirmation
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.pix.PixAddressKeyDeleteReason
import com.asaas.pix.PixAddressKeyType
import com.asaas.pix.adapter.addresskey.AddressKeyAdapter
import com.asaas.pix.adapter.addresskey.PixAddressKeyListAdapter
import com.asaas.pix.adapter.addresskey.PixCustomerAddressKeyAdapter
import com.asaas.pix.adapter.addresskey.PixCustomerInfoAdapter
import com.asaas.pix.validator.PixAddressKeyValidator
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PixAddressKeyService {

    def asyncActionService
    def criticalActionService
    def pixAddressKeyManagerService
    def pixAddressKeyOwnershipConfirmationService
    def pixAddressKeyTimeRecordLogService

    public AddressKeyAdapter get(Customer customer, String pixKey, PixAddressKeyType type) {
        if (!new CheckoutValidator(customer).customerCanUsePix()) throw new BusinessException("A sua conta ainda não está totalmente aprovada para utilizar o Pix.")

        BusinessValidation businessValidation = PixAddressKeyValidator.validate(pixKey, type)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        AddressKeyAdapter adapter = pixAddressKeyManagerService.findExternallyPixAddressKey(pixKey, type, customer)

        return adapter
    }

    public PixCustomerAddressKeyAdapter find(String id, Customer customer) {
        return pixAddressKeyManagerService.find(id, customer)
    }

    public PixAddressKeyListAdapter list(Customer customer, Map filters, Integer limit, Integer offset) {
        return pixAddressKeyManagerService.list(customer, filters, limit, offset)
    }

    public PixAddressKeyOwnershipConfirmation saveOwnershipConfirmation(Customer customer, String pixKey, PixAddressKeyType type) {
        Date requestedAt = new Date()

        pixKey = PixUtils.parseKey(pixKey, type)

        Map validatedMap = validateIfCustomerCanRequestPixAddressKey(customer)
        if (!validatedMap.isValid) throw new BusinessException(validatedMap.errorMessage)

        PixAddressKeyOwnershipConfirmation ownershipConfirmation = pixAddressKeyOwnershipConfirmationService.save(customer, pixKey, type)
        pixAddressKeyTimeRecordLogService.saveTimeRecordLogForOwnershipConfirmation(pixKey, requestedAt)

        return ownershipConfirmation
    }

    public PixCustomerAddressKeyAdapter save(Customer customer, String pixKey, PixAddressKeyType type, Map params, Boolean saveSynchronously) {
        pixKey = PixUtils.parseKey(pixKey, type)

        validateSave(customer, pixKey, type, params)

        return pixAddressKeyManagerService.save(customer, pixKey, type, saveSynchronously)
    }

    public void validateSave(Customer customer, String pixKey, PixAddressKeyType type, Map params) {
        if (type != PixAddressKeyType.EVP && !pixKey) throw new BusinessException("Informe a chave que deseja cadastrar.")

        Map validateMap = validateIfCustomerCanRequestPixAddressKey(customer)
        if (!validateMap.isValid) throw new BusinessException(validateMap.errorMessage)

        if (isRequiredOwnershipConfirmation(customer, pixKey, type)) {
            PixAddressKeyOwnershipConfirmation confirmedPixAddressKeyOwnershipConfirmation = pixAddressKeyOwnershipConfirmationService.confirm(customer, pixKey, type, params.ownershipConfirmationToken?.toString()?.trim())
            if (confirmedPixAddressKeyOwnershipConfirmation.hasErrors()) throw new BusinessException(confirmedPixAddressKeyOwnershipConfirmation.errors.allErrors.defaultMessage[0])
        }
    }

    public Map validateIfCustomerCanRequestPixAddressKey(Customer customer) {
        if (AsaasEnvironment.isDevelopment()) return [isValid: true]

        CheckoutValidator checkoutValidator = new CheckoutValidator(customer)
        if (!checkoutValidator.customerCanUsePix()) {
            return [isValid: false, errorMessage: Utils.getMessageProperty("pix.denied.proofOfLife.${ customer.getProofOfLifeType() }.notApproved")]
        }

        Map responseMap = pixAddressKeyManagerService.validateIfCustomerCanRequestPixAddressKey(customer)
        if (!responseMap.success) {
            return [isValid: false, errorMessage: responseMap.errorMessage]
        }

        return [isValid: true]
    }

    public PixCustomerAddressKeyAdapter delete(Customer customer, String id, PixAddressKeyDeleteReason deleteReason, Map params) {
        CustomerCriticalActionConfig customerCriticalActionConfig = CustomerCriticalActionConfig.query([customerId: customer.id]).get()
        if (customerCriticalActionConfig?.isPixTransactionAuthorizationEnabled()) {
            String hash = buildCriticalActionHash(customer, id)
            BusinessValidation businessValidation = criticalActionService.authorizeSynchronousWithNewTransaction(customer.id, Utils.toLong(params.groupId), params.token, CriticalActionType.PIX_DELETE_ADDRESS_KEY, hash)
            if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())
        }

        return pixAddressKeyManagerService.delete(customer, id, deleteReason)
    }

    public CriticalActionGroup requestDeleteAuthorizationToken(Customer customer, String id) {
        String hash = buildCriticalActionHash(customer, id)
        return criticalActionService.saveAndSendSynchronous(customer, CriticalActionType.PIX_DELETE_ADDRESS_KEY, hash)
    }

    public Boolean shouldEncourageAddressKeyRegistration(Customer customer, List<PixCustomerAddressKeyAdapter> pixAddressKeyList) {
        if (pixAddressKeyList.size() == 0 && CustomerFeature.isPixWithAsaasKeyEnabled(customer.id)) return true

        return false
    }

    public void createFirstCustomerAddressKey() {
        for (Map asyncActionData in asyncActionService.listPendingCreatePixAddressKey()) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.get(asyncActionData.customerId)

                if (!canCreateAutomaticCustomerPixAddressKey(customer)) {
                    throw new BusinessException("Cliente ${customer.id} não pode ter sua primeira Chave Pix EVP criada pelo sistema")
                }

                save(customer, "", PixAddressKeyType.EVP, [:], false)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "AddressKeyService.createFirstCustomerAddressKey() -> Erro ao processar AsyncAction [${asyncActionData.asyncActionId}]", onError: { hasError = true }])

            if (hasError) {
                asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
            }
        }
    }

    public Boolean canCreateAutomaticCustomerPixAddressKey(Customer customer) {
        CheckoutValidator checkoutValidator = new CheckoutValidator(customer)
        if (!checkoutValidator.customerCanUsePix()) return false

        CustomerPixConfig customerPixConfig = CustomerPixConfig.query([customer: customer]).get()
        if (customerPixConfig) {
            if (customerPixConfig.canReceivePayment) return false
            if (PixUtils.paymentReceivingWithPixDisabled(customer)) return false
        }

        return true
    }

    public Boolean offerAutomaticPixAddressKeyCreationOnWizard(Customer customer) {
        CustomerPixConfig customerPixConfig = CustomerPixConfig.query([customer: customer]).get()
        if (customerPixConfig) {
            if (customerPixConfig.canReceivePayment) return false
            if (PixUtils.paymentReceivingWithPixDisabled(customer)) return false
        }

        return true
    }

    public PixCustomerInfoAdapter getCustomerAddressKeyInfoList(Customer customer) {
        Boolean isPixEnabled = AsaasConfig.getInstance().pixEnabled
        if (!isPixEnabled) return null

        CheckoutValidator checkoutValidator = new CheckoutValidator(customer)
        if (!checkoutValidator.customerCanUsePix()) return null

        return pixAddressKeyManagerService.getCustomerAddressKeyInfoList(customer)
    }

    private String buildCriticalActionHash(Customer customer, String id) {
        String operation = ""
        operation += customer.id.toString()
        operation += id
        if (!operation) throw new RuntimeException("Operação não suportada!")
        return operation.encodeAsMD5()
    }

    private Boolean isRequiredOwnershipConfirmation(Customer customer, String key, PixAddressKeyType keyType) {
        if (!PixAddressKeyType.getRequiresOwnershipConfirmationToken().contains(keyType)) return false

        if (keyType.isEmail()) {
            String allowedDomain = CustomerParameter.getStringValue(customer, CustomerParameterName.BYPASS_PIX_ADDRESS_KEY_EMAIL_OWNERSHIP_CONFIRMATION)
            if (allowedDomain && key.toLowerCase().trim().endsWith(allowedDomain)) return false
        }

        return true
    }

}
