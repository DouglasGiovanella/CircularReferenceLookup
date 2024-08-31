package com.asaas.service.api.pix

import com.asaas.api.ApiCriticalActionParser
import com.asaas.api.ApiIncidentReportParser
import com.asaas.api.ApiMobileUtils
import com.asaas.api.pix.ApiPixAddressKeyParser
import com.asaas.api.pix.ApiPixAsaasClaimParser
import com.asaas.checkout.CheckoutValidator
import com.asaas.checkout.CustomerCheckoutFee
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.asaasconfig.AsaasConfig
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.pix.PixAddressKeyOwnershipConfirmation
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.exception.BusinessException
import com.asaas.pix.PixAddressKeyDeleteReason
import com.asaas.pix.PixAddressKeyType
import com.asaas.pix.adapter.addresskey.AddressKeyAdapter
import com.asaas.pix.adapter.addresskey.PixAddressKeyListAdapter
import com.asaas.pix.adapter.addresskey.PixCustomerAddressKeyAdapter
import com.asaas.pix.adapter.addresskey.PixCustomerInfoAdapter
import com.asaas.pix.adapter.claim.PixCustomerAddressKeyClaimAdapter
import com.asaas.service.api.ApiBaseService
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiPixAddressKeyService extends ApiBaseService {

    def apiResponseBuilderService
    def bankAccountInfoService
    def customerCheckoutLimitService
    def pixAddressKeyAsaasClaimService
    def pixAddressKeyOwnershipConfirmationService
    def pixAddressKeyService

    public Map index(Map params) {
        Customer customer = getProviderInstance(params)

        Map indexData = [:]

        CheckoutValidator checkoutValidator = new CheckoutValidator(customer)
        indexData.canUsePix = checkoutValidator.customerCanUsePix()
        indexData.pixAvailability = ApiIncidentReportParser.buildPixAvailability()
        indexData.shouldChargeDebitFeeForTransfers = CustomerCheckoutFee.shouldChargePixDebitFeeForTransfer(customer)
        indexData.accountBalance = FinancialTransaction.getCustomerBalance(customer)
        indexData.initialNightlyHourConfig = PixTransactionCheckoutLimit.getInitialNightlyHourConfig(customer)


        if (ApiMobileUtils.appSupportsPixLimitsAsyncCheckout() && CustomerParameter.getValue(customer, CustomerParameterName.PIX_ASYNC_CHECKOUT)) {
            indexData.hasAsyncCheckout = true
        } else {
            indexData.accountRemainingDailyCheckoutValue = customerCheckoutLimitService.getAvailableDailyCheckout(customer)
            indexData.pixCheckoutLimit = buildPixCheckoutLimit(customer)
            indexData.pixCashValueCheckoutLimit = buildPixCashValueCheckoutLimit(customer)
        }

        PixCustomerInfoAdapter pixCustomerInfoAdapter = pixAddressKeyService.getCustomerAddressKeyInfoList(customer)

        if (pixCustomerInfoAdapter) {
            indexData.hasRequestedAddressKey = pixCustomerInfoAdapter.pixAddressKeyList.any() || pixCustomerInfoAdapter.claimList.any()
            indexData.hasActiveAddressKeys = pixCustomerInfoAdapter.pixAddressKeyList.any { it.status.isActive() }
            indexData.shouldEncourageAddressKeyRegistration = pixAddressKeyService.shouldEncourageAddressKeyRegistration(customer, pixCustomerInfoAdapter.pixAddressKeyList)
        } else {
            indexData.hasRequestedAddressKey = false
            indexData.hasActiveAddressKeys = false
            indexData.shouldEncourageAddressKeyRegistration = false
        }

        indexData.hasSavedBankAccounts = BankAccountInfo.approvedBankAccountInfoWithPaymentServiceProviderLinked(customer, [exists: true]).get().asBoolean()
        indexData.canSaveBankAccountInfo = bankAccountInfoService.customerCanSaveBankAccountInfoFromPixTransaction(customer)
        indexData.proofOfLifeType = customer.getProofOfLifeType().toString()
        indexData.limitDaysToRequestRefund = PixTransaction.LIMIT_DAYS_TO_REQUEST_REFUND
        indexData.hasCreditTransactionWithoutPaymentEnabled = CustomerParameter.getValue(customer, CustomerParameterName.ENABLE_PIX_CREDIT_TRANSACTION_WITHOUT_PAYMENT)
        indexData.hasDebitTransactions = PixTransaction.customerHasDebitTransactions(customer)

        return apiResponseBuilderService.buildSuccess(indexData)
    }

    private Map buildPixCheckoutLimit(Customer customer) {
        Map pixCheckoutLimit = [:]

        pixCheckoutLimit.maxDaytimeLimitPerTransaction = PixTransactionCheckoutLimit.calculateDaytimeLimitPerTransaction(customer)
        pixCheckoutLimit.maxNightlyLimitPerTransaction = PixTransactionCheckoutLimit.calculateNightlyLimitPerTransaction(customer)
        pixCheckoutLimit.remainingDaytimeLimit = PixTransactionCheckoutLimit.calculateCurrentDayRemainingDaytimeCheckoutLimit(customer)
        pixCheckoutLimit.remainingNightlyLimit = PixTransactionCheckoutLimit.calculateCurrentDayRemainingNightlyCheckoutLimit(customer)

        return pixCheckoutLimit
    }

    private Map buildPixCashValueCheckoutLimit(Customer customer) {
        Map pixCashValueCheckoutLimit = [:]

        pixCashValueCheckoutLimit.maxDaytimeLimitPerTransaction = PixTransactionCheckoutLimit.calculateCashValueDaytimePerTransaction(customer)
        pixCashValueCheckoutLimit.maxNightlyLimitPerTransaction = PixTransactionCheckoutLimit.calculateCashValueNightlyLimitPerTransaction(customer)
        pixCashValueCheckoutLimit.remainingDaytimeLimit = PixTransactionCheckoutLimit.calculateRemainingCashValueDaytimeLimit(customer)
        pixCashValueCheckoutLimit.remainingNightlyLimit = PixTransactionCheckoutLimit.calculateRemainingCashValueNightlyLimit(customer)

        return pixCashValueCheckoutLimit
    }

    public Map show(Map params) {
        PixCustomerAddressKeyAdapter pixCustomerAddressKeyAdapter = pixAddressKeyService.find(params.id, getProviderInstance(params))

        if (!pixCustomerAddressKeyAdapter) return apiResponseBuilderService.buildNotFoundItem()
        return apiResponseBuilderService.buildSuccess(ApiPixAddressKeyParser.buildResponseItem(pixCustomerAddressKeyAdapter, [:]))
    }

    public Map list(Map params) {
        Map filters = ApiPixAddressKeyParser.parseListFilters(params)

        PixAddressKeyListAdapter pixAddressKeyListAdapter = pixAddressKeyService.list(getProviderInstance(params), filters, getLimit(params), getOffset(params))
        return apiResponseBuilderService.buildSuccess(ApiPixAddressKeyParser.buildResponseItem(pixAddressKeyListAdapter, [:]))
    }

    public Map findExternal(Map params) {
        Map parsedFields = ApiPixAddressKeyParser.parseFindExternalParams(params)
        AddressKeyAdapter addressKeyAdapter = pixAddressKeyService.get(getProviderInstance(params), parsedFields.key, parsedFields.type)

        if (!addressKeyAdapter) return apiResponseBuilderService.buildNotFoundItem()

        return apiResponseBuilderService.buildSuccess(ApiPixAddressKeyParser.buildResponseItem(addressKeyAdapter))
    }

    public Map getCustomerAddressKeyInfoList(Map params) {
        withValidation({
            Customer customer = getProviderInstance(params)
            PixCustomerInfoAdapter pixCustomerInfoAdapter = pixAddressKeyService.getCustomerAddressKeyInfoList(customer)

            if (!pixCustomerInfoAdapter) throw new BusinessException(Utils.getMessageProperty("pix.denied.proofOfLife.${ customer.getProofOfLifeType() }.notApproved"))

            return apiResponseBuilderService.buildSuccess(ApiPixAddressKeyParser.buildResponseItem(pixCustomerInfoAdapter, [:]))
        })
    }

    public Map save(Map params) {
        Customer customer = getProviderInstance(params)
        Map parsedFields = ApiPixAddressKeyParser.parseSaveParams(params)

        if (!isAddressKeyTypeAllowed(customer, parsedFields.type)) return apiResponseBuilderService.buildErrorFrom("invalid_type", "O tipo de chave informado não é suportada")

        PixCustomerAddressKeyAdapter pixCustomerAddressKeyAdapter = pixAddressKeyService.save(customer, parsedFields.key, parsedFields.type, parsedFields.extraData, true)

        return apiResponseBuilderService.buildSuccess(ApiPixAddressKeyParser.buildResponseItem(pixCustomerAddressKeyAdapter, [:]))
    }

    public Map requestOwnershipConfirmation(Map params) {
        withValidation({
            Map parsedFields = ApiPixAddressKeyParser.parseRequestOwnershipConfirmationParams(params)
            PixAddressKeyOwnershipConfirmation ownershipConfirmation = pixAddressKeyService.saveOwnershipConfirmation(getProviderInstance(params), parsedFields.key, parsedFields.type)

            if (ownershipConfirmation.hasErrors()) {
                return apiResponseBuilderService.buildErrorList(ownershipConfirmation)
            }

            return apiResponseBuilderService.buildSuccess([id: ownershipConfirmation.id])
        })
    }

    public Map resendOwnershipConfirmation(Map params) {
        withValidation({
            pixAddressKeyOwnershipConfirmationService.resendToken(Utils.toLong(params.id), getProviderInstance(params))
            return apiResponseBuilderService.buildSuccess([:])
        })
    }

    public Map delete(Map params) {
        Map parsedFields = ApiPixAddressKeyParser.parseDeleteParams(params)

        PixCustomerAddressKeyAdapter pixCustomerAddressKeyAdapter = pixAddressKeyService.delete(getProviderInstance(params), params.id, PixAddressKeyDeleteReason.USER_REQUESTED, parsedFields.criticalActionData)

        return apiResponseBuilderService.buildSuccess(ApiPixAddressKeyParser.buildResponseItem(pixCustomerAddressKeyAdapter, [:]))
    }

    public Map requestDeleteToken(Map params) {
        CriticalActionGroup synchronousGroup = pixAddressKeyService.requestDeleteAuthorizationToken(getProviderInstance(params), params.id)

        return apiResponseBuilderService.buildSuccess(ApiCriticalActionParser.buildGroupResponseItem(synchronousGroup))
    }

    public Map canRequestKey(Map params) {
        withValidation({
            Map validatedMap = pixAddressKeyService.validateIfCustomerCanRequestPixAddressKey(getProviderInstance(params))

            Map response = [canRequest: validatedMap.isValid, denialReason: validatedMap.isValid ? null : validatedMap.errorMessage]

            return apiResponseBuilderService.buildSuccess(response)
        })
    }

    public Map requestClaim(Map params) {
        withValidation({
            Map fields = ApiPixAddressKeyParser.parseClaimRequestParams(params)
            PixCustomerAddressKeyClaimAdapter pixCustomerAddressKeyClaimAdapter = pixAddressKeyAsaasClaimService.save(getProviderInstance(params), fields.key, fields.keyType, fields.ownershipConfirmationToken)

            return apiResponseBuilderService.buildSuccess(ApiPixAsaasClaimParser.buildResponseItem(pixCustomerAddressKeyClaimAdapter, [:]))
        })
    }

    public Map validateOwnership(Map params) {
        Customer customer = getProviderInstance(params)
        Map parsedFields = ApiPixAddressKeyParser.parseValidateOwnershipParams(params)

        if (!CpfCnpjUtils.validate(parsedFields.cpfCnpj)) throw new BusinessException("Necessário informar um CPF ou CNPJ válido para validar a titularidade da chave")

        AddressKeyAdapter addressKeyAdapter = pixAddressKeyService.get(customer, parsedFields.key, parsedFields.type)

        if (!addressKeyAdapter) return apiResponseBuilderService.buildNotFoundItem()

        Boolean allowUnmaskedCpfCnpj = CustomerParameter.getValue(customer.id, CustomerParameterName.ALLOW_UNMASKED_CPF_CNPJ_ON_API)

        return apiResponseBuilderService.buildSuccess(ApiPixAddressKeyParser.buildValidateOwnershipResponseItem(addressKeyAdapter, parsedFields.cpfCnpj, allowUnmaskedCpfCnpj))
    }

    private withValidation(Closure action) {
        if (ApiMobileUtils.isMobileAppRequest()) return action()

        return apiResponseBuilderService.buildNotFoundItem()
    }

    private Boolean isAddressKeyTypeAllowed(Customer customer, PixAddressKeyType addressKeyType) {
        if (addressKeyType == PixAddressKeyType.EVP) return true
        if (ApiMobileUtils.isMobileAppRequest()) return true

        if (addressKeyType == PixAddressKeyType.EMAIL) {
            if (CustomerParameter.getStringValue(customer, CustomerParameterName.BYPASS_PIX_ADDRESS_KEY_EMAIL_OWNERSHIP_CONFIRMATION)) return true
        }

        return false
    }
}
