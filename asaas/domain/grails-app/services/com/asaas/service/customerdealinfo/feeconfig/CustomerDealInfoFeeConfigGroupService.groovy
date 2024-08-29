package com.asaas.service.customerdealinfo.feeconfig

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigGroupRepository
import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigGroup
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigItem
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional
import grails.validation.ValidationException

import org.apache.commons.lang3.NotImplementedException

@Transactional
class CustomerDealInfoFeeConfigGroupService {

    def asyncActionService
    def childAccountParameterService
    def childAccountParameterInteractionService
    def customerDealInfoFeeConfigItemService

    public CustomerDealInfoFeeConfigGroup saveOrUpdate(Map params) {
        CustomerDealInfoFeeConfigGroup feeConfigGroup = CustomerDealInfoFeeConfigGroup.get(params.id)

        if (!feeConfigGroup && !params.replicationType.isOnlyChildAccountWithDynamicMcc()) {
            Map search = [
                customerDealInfo: params.customerDealInfo,
                product: params.product,
                replicationType: params.replicationType
            ]
            feeConfigGroup = CustomerDealInfoFeeConfigGroupRepository.query(search).get()
        }

        if (feeConfigGroup) {
            if (feeConfigGroup.replicationType != params.replicationType) feeConfigGroup = update(feeConfigGroup, params.replicationType)
            return feeConfigGroup
        }

        return save(params.customerDealInfo, params.product, params.replicationType)
    }

    public void deleteAll(CustomerDealInfo customerDealInfo) {
        Map search = [customerDealInfoId: customerDealInfo.id, "replicationType[ne]": FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT_WITH_DYNAMIC_MCC]
        List<Map> customerDealInfoFeeConfigGroupInfoList = CustomerDealInfoFeeConfigGroupRepository.query(search).column(["id", "replicationType"]).list()
        if (!customerDealInfoFeeConfigGroupInfoList) return

        Boolean childAccountParameterDeleted = false
        for (Map customerDealInfoFeeConfigGroupInfo : customerDealInfoFeeConfigGroupInfoList) {
            Long customerDealInfoFeeConfigGroupId = customerDealInfoFeeConfigGroupInfo.id
            FeeNegotiationReplicationType replicationType = customerDealInfoFeeConfigGroupInfo.replicationType

            if (replicationType.isApplicableForChildAccount()) {
                List<CustomerDealInfoFeeConfigItem> feeConfigItemList = CustomerDealInfoFeeConfigItem.query([groupId: customerDealInfoFeeConfigGroupId]).list()
                if (feeConfigItemList) {
                    childAccountParameterDeleted = deleteChildAccountParameter(customerDealInfo.customer.id, feeConfigItemList)
                }
            }

            delete(customerDealInfoFeeConfigGroupId)
            asyncActionService.save(AsyncActionType.APPLY_DEFAULT_FEE_CONFIG_FOR_NEGOTIATION_DELETION, [customerDealInfoFeeConfigGroupId: customerDealInfoFeeConfigGroupId])
        }

        if (childAccountParameterDeleted) {
            childAccountParameterInteractionService.saveDeletion(customerDealInfo.customer, "remoção da negociação")
        }
    }

    public void delete(Long id) {
        customerDealInfoFeeConfigItemService.deleteAll(id)

        CustomerDealInfoFeeConfigGroup feeConfigGroup = CustomerDealInfoFeeConfigGroup.get(id)

        feeConfigGroup.deleted = true
        feeConfigGroup.save(failOnError: true)
    }

    private Boolean deleteChildAccountParameter(Long customerDealInfoCustomerId, List<CustomerDealInfoFeeConfigItem> feeConfigItemList) {
        FeeNegotiationProduct product = feeConfigItemList.first().group.product

        Map search = [:]
        search.accountOwnerId = customerDealInfoCustomerId
        search.type = product.getDomainSimpleName()

        Boolean childAccountParameterDeleted = false
        for (CustomerDealInfoFeeConfigItem feeConfigItem : feeConfigItemList) {
            search.name = findChildAccountParameterName(product, feeConfigItem.productFeeType)

            ChildAccountParameter childAccountParameter = ChildAccountParameter.query(search).get()
            if (!childAccountParameter) throw new RuntimeException("ChildAccountParameter não encontrado. [search: ${search}]")
            if (childAccountParameter.value != feeConfigItem.value.toString()) throw new RuntimeException("ChildAccountParameter possui um valor divergente da negociação")

            childAccountParameterService.delete(childAccountParameter)
            childAccountParameterDeleted = true
        }

        return childAccountParameterDeleted
    }

    private String findChildAccountParameterName(FeeNegotiationProduct product, String productFeeType) {
        if (!product.isFromCustomerFeeDomain()) return productFeeType

        switch (product) {
            case FeeNegotiationProduct.CREDIT_BUREAU_REPORT:
                if (productFeeType == "legalPersonFee") return "creditBureauReportLegalPersonFee"
                if (productFeeType == "naturalPersonFee") return "creditBureauReportNaturalPersonFee"

                throw new NotImplementedException("ChildAccountParameter não encontrado. [product: ${product}, productFeeType: ${productFeeType}]")
            case FeeNegotiationProduct.DUNNING_CREDIT_BUREAU:
                return "dunningCreditBureauFeeValue"
            case FeeNegotiationProduct.PAYMENT_MESSAGING_NOTIFICATION:
                return "paymentMessagingNotificationFeeValue"
            case FeeNegotiationProduct.PHONE_CALL_NOTIFICATION:
                return "phoneCallNotificationFee"
            case FeeNegotiationProduct.PIX_DEBIT:
                return "pixDebitFee"
            case FeeNegotiationProduct.SERVICE_INVOICE:
                return "invoiceValue"
            case FeeNegotiationProduct.TRANSFER:
                return "transferValue"
            case FeeNegotiationProduct.WHATSAPP_NOTIFICATION:
                return "whatsappNotificationFee"
            default:
                throw new NotImplementedException("ChildAccountParameter não encontrado. [product: ${product}, productFeeType: ${productFeeType}]")
        }
    }

    private CustomerDealInfoFeeConfigGroup save(CustomerDealInfo customerDealInfo, FeeNegotiationProduct product, FeeNegotiationReplicationType replicationType) {
        CustomerDealInfoFeeConfigGroup validatedDomain = validateSave(customerDealInfo, product, replicationType)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerDealInfoFeeConfigGroup feeConfigGroup = new CustomerDealInfoFeeConfigGroup()

        feeConfigGroup.customerDealInfo = customerDealInfo
        feeConfigGroup.product = product
        feeConfigGroup.replicationType = replicationType
        feeConfigGroup.save(failOnError: true)

        deleteOldConfigIfNecessary(feeConfigGroup)

        return feeConfigGroup
    }

    private CustomerDealInfoFeeConfigGroup update(CustomerDealInfoFeeConfigGroup feeConfigGroup, FeeNegotiationReplicationType replicationType) {
        CustomerDealInfoFeeConfigGroup validatedDomain = validateUpdate(feeConfigGroup, replicationType)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        feeConfigGroup.replicationType = replicationType
        feeConfigGroup.save(failOnError: true)

        deleteOldConfigIfNecessary(feeConfigGroup)

        return feeConfigGroup
    }

    private void deleteOldConfigIfNecessary(CustomerDealInfoFeeConfigGroup currentFeeConfigGroup) {
        Map search = [:]
        search.customerDealInfo = currentFeeConfigGroup.customerDealInfo
        search.product = currentFeeConfigGroup.product

        if (currentFeeConfigGroup.replicationType.isAccountOwnerAndChildAccount()) {
            search."replicationType[in]" = [FeeNegotiationReplicationType.CUSTOMER, FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT]
        } else {
            search.replicationType = FeeNegotiationReplicationType.ACCOUNT_OWNER_AND_CHILD_ACCOUNT
        }

        List<Long> oldFeeConfigGroupIdList = CustomerDealInfoFeeConfigGroupRepository.query(search).column("id").list()
        for (Long oldFeeConfigGroupId : oldFeeConfigGroupIdList) {
            delete(oldFeeConfigGroupId)
        }
    }

    private CustomerDealInfoFeeConfigGroup validateSave(CustomerDealInfo customerDealInfo, FeeNegotiationProduct product, FeeNegotiationReplicationType replicationType) {
        CustomerDealInfoFeeConfigGroup validatedDomain = new CustomerDealInfoFeeConfigGroup()

        if (!customerDealInfo) DomainUtils.addError(validatedDomain, "É necessário informar o cliente negociado")

        if (!product) DomainUtils.addError(validatedDomain, "É necessário informar o produto")

        if (!replicationType) DomainUtils.addError(validatedDomain, "É necessário informar o tipo de replicação")

        return validatedDomain
    }

    private CustomerDealInfoFeeConfigGroup validateUpdate(CustomerDealInfoFeeConfigGroup feeConfigGroup, FeeNegotiationReplicationType replicationType) {
        CustomerDealInfoFeeConfigGroup validatedDomain = new CustomerDealInfoFeeConfigGroup()

        if (!feeConfigGroup) DomainUtils.addError(validatedDomain, "O grupo de negociação não existe")

        if (!replicationType) DomainUtils.addError(validatedDomain, "É necessário informar o tipo de replicação")

        return validatedDomain
    }
}
