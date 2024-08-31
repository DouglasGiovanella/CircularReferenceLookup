package com.asaas.service.feenegotiation

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.CustomerParameter
import com.asaas.feenegotiation.FeeNegotiationFloorRepository
import com.asaas.feenegotiation.adapter.FeeNegotiationFloorAdapter
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigGroup
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.feenegotiation.FeeNegotiationFloor
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationType
import com.asaas.feenegotiation.FeeNegotiationValueType
import com.asaas.feenegotiation.adapter.ChildAccountKnownYourCustomerFeeNegotiationFloorAdapter
import com.asaas.feenegotiation.adapter.CreditBureauReportFeeNegotiationFloorAdapter
import com.asaas.feenegotiation.adapter.FixedFeeNegotiationFloorAdapter
import com.asaas.feenegotiation.builder.FeeNegotiationFloorSearchBuilder
import com.asaas.userpermission.AdminUserPermissionName
import com.asaas.userpermission.AdminUserPermissionUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class FeeNegotiationFloorService {

    def customerReceivableAnticipationConfigService

    public Map buildInfo(CustomerDealInfo customerDealInfo) {
        Boolean canManageCustomerDealInfo = AdminUserPermissionUtils.allowed(AdminUserPermissionName.MANAGE_CUSTOMER_DEAL_INFO)
        if (!canManageCustomerDealInfo) return [:]
        if (!customerDealInfo.feeNegotiationType.isCommercialAnalyst()) return [:]

        return [
            bankSlipFloor: buildProductFeeNegotiationFloor(FeeNegotiationProduct.BANK_SLIP, null),
            receivableAnticipationFloor: buildProductFeeNegotiationFloor(FeeNegotiationProduct.RECEIVABLE_ANTICIPATION, null),
            pixCreditFloor: buildProductFeeNegotiationFloor(FeeNegotiationProduct.PIX_CREDIT, null),
            creditCardFloor: buildProductFeeNegotiationFloor(FeeNegotiationProduct.CREDIT_CARD, customerDealInfo.customer.getMcc()),
            paymentMessagingNotificationFloorAdapter: new FixedFeeNegotiationFloorAdapter(FeeNegotiationProduct.PAYMENT_MESSAGING_NOTIFICATION),
            whatsappNotificationFloorAdapter: new FixedFeeNegotiationFloorAdapter(FeeNegotiationProduct.WHATSAPP_NOTIFICATION),
            pixDebitFloorAdapter: new FixedFeeNegotiationFloorAdapter(FeeNegotiationProduct.PIX_DEBIT),
            childAccountKnownYourCustomerFloorAdapter: new ChildAccountKnownYourCustomerFeeNegotiationFloorAdapter(FeeNegotiationProduct.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER, customerDealInfo.customer.getMcc()),
            serviceInvoiceFloorAdapter: new FixedFeeNegotiationFloorAdapter(FeeNegotiationProduct.SERVICE_INVOICE),
            creditBureauReportFeeNegotiationFloorAdapter: new CreditBureauReportFeeNegotiationFloorAdapter(FeeNegotiationProduct.CREDIT_BUREAU_REPORT),
            transferFloorAdapter: new FixedFeeNegotiationFloorAdapter(FeeNegotiationProduct.TRANSFER),
            dunningCreditBureauFloorAdapter: new FixedFeeNegotiationFloorAdapter(FeeNegotiationProduct.DUNNING_CREDIT_BUREAU),
            phoneCallNotificationFloorAdapter: new FixedFeeNegotiationFloorAdapter(FeeNegotiationProduct.PHONE_CALL_NOTIFICATION)
        ]
    }

    public FeeNegotiationFloor validateNegotiationValue(FeeNegotiationProduct product, String productFeeType, BigDecimal value, FeeNegotiationValueType valueType, CustomerDealInfo customerDealInfo, CustomerDealInfoFeeConfigGroup feeConfigGroup) {
        if (product.isReceivableAnticipation()) {
            Long customerId = customerDealInfo.customer.id

            CustomerReceivableAnticipationConfig validatedReceivableAnticipationConfig = customerReceivableAnticipationConfigService.validateFeeValue(customerId, value, null)
            if (validatedReceivableAnticipationConfig.hasErrors()) return DomainUtils.copyAllErrorsFromObject(validatedReceivableAnticipationConfig, new FeeNegotiationFloor())

            if (CustomerParameter.getValue(customerId, CustomerParameterName.BYPASS_FEE_VALUE_VALIDATION_FOR_NEGOTIATED_CUSTOMER)) return new FeeNegotiationFloor()
        }

        FeeNegotiationType feeNegotiationType = customerDealInfo.feeNegotiationType
        if (!feeNegotiationType.isCommercialAnalyst()) return new FeeNegotiationFloor()

        BigDecimal baseValue = findMaxBaseValue(product, feeConfigGroup, customerDealInfo, productFeeType)

        return validateNegotiationBaseValue(baseValue, product, productFeeType, value, valueType, feeNegotiationType)
    }

    public Boolean allowsZeroableValue(FeeNegotiationType feeNegotiationType, FeeNegotiationProduct feeNegotiationProduct, String productFeeType) {
        if (!feeNegotiationType.isAllowedZeroableFee()) return false
        if (!feeNegotiationProduct.isCreditCard()) return false
        if (productFeeType != "fixedFee") return false

        return true
    }

    public void delete(FeeNegotiationFloor feeNegotiationFloor) {
        feeNegotiationFloor.deleted = true
        feeNegotiationFloor.save(failOnError: true)
    }

    public void saveOrUpdate(FeeNegotiationFloorAdapter feeNegotiationFloorAdapter) {
        Map search = [product: feeNegotiationFloorAdapter.product, productFeeType: feeNegotiationFloorAdapter.productFeeType, type: feeNegotiationFloorAdapter.type, valueType: feeNegotiationFloorAdapter.valueType]
        if (feeNegotiationFloorAdapter.feeNegotiationMcc) search.feeNegotiationMcc = feeNegotiationFloorAdapter.feeNegotiationMcc

        FeeNegotiationFloor feeNegotiationFloor = FeeNegotiationFloorRepository.query(search).get()

        if (feeNegotiationFloor) {
            update(feeNegotiationFloor, feeNegotiationFloorAdapter)
            return
        }

        save(feeNegotiationFloorAdapter)
    }

    private BigDecimal findMaxBaseValue(FeeNegotiationProduct product, CustomerDealInfoFeeConfigGroup feeConfigGroup, CustomerDealInfo customerDealInfo, String productFeeType) {
        String mcc = customerDealInfo.customer.getMcc()
        Map searchParams = [productFeeType: productFeeType, mcc: mcc, feeConfigGroup: feeConfigGroup]
        Map search = FeeNegotiationFloorSearchBuilder.buildSearch(product, searchParams)

        if (search.containsKey("sortInfo")) {
            Map sortInfo = search.sortInfo
            search.remove("sortInfo")

            return FeeNegotiationFloorRepository.query(search).column("value").sort(sortInfo.sort, sortInfo.order).get()
        }

        return FeeNegotiationFloorRepository.query(search).column("value").get()
    }

    private void save(FeeNegotiationFloorAdapter feeNegotiationFloorAdapter) {
        FeeNegotiationFloor validatedFeeNegotiationFloor = validateSave(feeNegotiationFloorAdapter)
        if (validatedFeeNegotiationFloor.hasErrors()) throw new ValidationException(null, validatedFeeNegotiationFloor.errors)

        FeeNegotiationFloor feeNegotiationFloor = new FeeNegotiationFloor()
        feeNegotiationFloor.product = feeNegotiationFloorAdapter.product
        feeNegotiationFloor.productFeeType = feeNegotiationFloorAdapter.productFeeType
        feeNegotiationFloor.value = feeNegotiationFloorAdapter.value
        feeNegotiationFloor.valueType = feeNegotiationFloorAdapter.valueType
        feeNegotiationFloor.type = feeNegotiationFloorAdapter.type
        feeNegotiationFloor.feeNegotiationMcc = feeNegotiationFloorAdapter.feeNegotiationMcc
        feeNegotiationFloor.save(failOnError: true)
    }

    private void update(FeeNegotiationFloor feeNegotiationFloor, FeeNegotiationFloorAdapter feeNegotiationFloorAdapter) {
        FeeNegotiationFloor validatedFeeNegotiationFloor = validateSave(feeNegotiationFloorAdapter)
        if (validatedFeeNegotiationFloor.hasErrors()) throw new ValidationException(null, validatedFeeNegotiationFloor.errors)

        feeNegotiationFloor.value = feeNegotiationFloorAdapter.value
        feeNegotiationFloor.save(failOnError: true)
    }

    private FeeNegotiationFloor validateSave(FeeNegotiationFloorAdapter feeNegotiationFloorAdapter) {
        FeeNegotiationFloor validatedFeeNegotiationFloor = validateValue(feeNegotiationFloorAdapter.value, feeNegotiationFloorAdapter.valueType)

        if (!feeNegotiationFloorAdapter.product) DomainUtils.addError(validatedFeeNegotiationFloor, Utils.getMessageProperty("feeNegotiationFloor.invalid.product"))
        if (!feeNegotiationFloorAdapter.productFeeType) DomainUtils.addError(validatedFeeNegotiationFloor, Utils.getMessageProperty("feeNegotiationFloor.invalid.productFeeType"))
        if (!feeNegotiationFloorAdapter.type) DomainUtils.addError(validatedFeeNegotiationFloor, Utils.getMessageProperty("feeNegotiationFloor.invalid.type"))

        return validatedFeeNegotiationFloor
    }

    private FeeNegotiationFloor validateValue(BigDecimal value, FeeNegotiationValueType valueType) {
        FeeNegotiationFloor validatedFeeNegotiationFloor = new FeeNegotiationFloor()
        if (!valueType) return DomainUtils.addError(validatedFeeNegotiationFloor, Utils.getMessageProperty("feeNegotiationFloor.invalid.valueType"))

        final BigDecimal minFixedFeeValue = 0
        final BigDecimal maxFixedFeeValue = 1
        final BigDecimal minPercentageFeeValue = 0
        final BigDecimal maxPercentageFeeValue = 5

        if (value == null) DomainUtils.addError(validatedFeeNegotiationFloor, Utils.getMessageProperty("feeNegotiationFloor.invalid.value"))
        if (valueType.isFixed() && (value < minFixedFeeValue || value > maxFixedFeeValue)) DomainUtils.addError(validatedFeeNegotiationFloor, Utils.getMessageProperty("feeNegotiationFloor.outOfRange.fixed.value", [minFixedFeeValue, maxFixedFeeValue]))
        if (valueType.isPercentage() && (value < minPercentageFeeValue || value > maxPercentageFeeValue)) DomainUtils.addError(validatedFeeNegotiationFloor, Utils.getMessageProperty("feeNegotiationFloor.outOfRange.percentage.value", [minPercentageFeeValue, maxPercentageFeeValue]))

        return validatedFeeNegotiationFloor
    }

    private FeeNegotiationFloor validateNegotiationBaseValue(BigDecimal baseValue, FeeNegotiationProduct product, String productFeeType, BigDecimal value, FeeNegotiationValueType valueType, FeeNegotiationType feeNegotiationType) {
        String formattedBaseValue = valueType.isFixed() ? FormUtils.formatCurrencyWithMonetarySymbol(baseValue) : FormUtils.formatWithPercentageSymbol(baseValue, product.getPercentageDecimalScale(productFeeType))

        FeeNegotiationFloor validatedDomain = new FeeNegotiationFloor()

        Boolean allowedZeroableValue = allowsZeroableValue(feeNegotiationType, product, productFeeType)
        if (allowedZeroableValue && value == 0) return validatedDomain

        if (!value) DomainUtils.addError(validatedDomain, "É necessário informar o valor")

        if (productFeeType == "maximumFee") {
            if (value > baseValue) return DomainUtils.addError(validatedDomain, Utils.getMessageProperty("customerDealInfoFeeConfig.${product}.${productFeeType}.label") + ": o valor informado está acima do limite mínimo permitido: ${formattedBaseValue}")
            return validatedDomain
        }

        if (value < baseValue) return DomainUtils.addError(validatedDomain, Utils.getMessageProperty("customerDealInfoFeeConfig.${product}.${productFeeType}.label") + ": o valor informado está abaixo do limite mínimo permitido: ${formattedBaseValue}")

        return validatedDomain
    }

    private Map buildProductFeeNegotiationFloor(FeeNegotiationProduct product, String mcc) {
        Map productFeeNegotiationFloorInfo = [:]

        Map searchParams = [mcc: mcc]
        Map search = FeeNegotiationFloorSearchBuilder.buildSearch(product, searchParams)

        List<FeeNegotiationFloor> feeNegotiationFloorList = FeeNegotiationFloorRepository.query(search).list()
        for (FeeNegotiationFloor feeNegotiationFloor : feeNegotiationFloorList) {
            productFeeNegotiationFloorInfo[feeNegotiationFloor.productFeeType] = feeNegotiationFloor
        }

        return productFeeNegotiationFloorInfo
    }
}
