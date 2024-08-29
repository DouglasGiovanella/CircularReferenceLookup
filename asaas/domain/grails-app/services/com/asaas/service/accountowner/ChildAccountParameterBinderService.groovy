package com.asaas.service.accountowner

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.creditcard.CreditCardFeeConfig
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerConfig
import com.asaas.domain.customer.CustomerFeature
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.internalloan.InternalLoanConfig
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.pix.PixCreditFee
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.exception.BusinessException
import grails.transaction.Transactional
import sun.reflect.generics.reflectiveObjects.NotImplementedException

@Transactional
class ChildAccountParameterBinderService {

    def asyncActionService
    def bankSlipFeeParameterService
    def creditCardFeeConfigParameterService
    def criticalActionConfigParameterService
    def customerAttributesParameterService
    def customerConfigParameterService
    def customerExternalAuthorizationRequestConfigReplicationService
    def customerFeatureService
    def customerFeatureParameterService
    def customerFeeParameterService
    def customerParameterConfigService
    def customerReceivableAnticipationConfigParameterService
    def customerTransferConfigService
    def internalLoanConfigParameterService
    def pixTransactionCheckoutLimitParameterService
    def pixCreditFeeParameterService

    public void applyAllParameters(Customer accountOwner, Customer childAccount) {
        bankSlipFeeParameterService.applyAllParameters(accountOwner, childAccount)
        criticalActionConfigParameterService.applyAllParameters(accountOwner, childAccount)
        customerParameterConfigService.applyAllParameters(accountOwner, childAccount)
        customerFeeParameterService.applyAllParameters(accountOwner, childAccount)
        customerAttributesParameterService.applyAllParameters(accountOwner, childAccount)
        customerReceivableAnticipationConfigParameterService.applyAllParameters(accountOwner, childAccount)
        creditCardFeeConfigParameterService.applyAllParameters(accountOwner, childAccount)
        pixTransactionCheckoutLimitParameterService.applyAllParameters(accountOwner, childAccount)
        pixCreditFeeParameterService.applyAllParameters(accountOwner, childAccount)
        internalLoanConfigParameterService.applyAllParameters(accountOwner, childAccount)
        customerFeatureService.togglePixWithAsaasKeyToChildAccount(accountOwner, childAccount)
        customerConfigParameterService.applyAllParameters(accountOwner, childAccount)
        customerFeatureParameterService.applyAllParameters(accountOwner, childAccount)

        customerExternalAuthorizationRequestConfigReplicationService.replicateAllForChildAccount(accountOwner.id, childAccount.id)
        customerTransferConfigService.replicateAccountOwnerConfigIfNecessary(childAccount)
    }

    public void applyParameterForAllChildAccounts(Long accountOwnerId, String fieldName, String type) {
        ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: type, name: fieldName]).get()

        if (!childAccountParameter) return

        switch (type) {
            case Customer.simpleName:
                asyncActionService.save(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_ATTRIBUTES_REPLICATION, [accountOwnerId: accountOwnerId, name: fieldName])
                break
            case CustomerFee.simpleName:
                asyncActionService.saveChildAccountCustomerFeeReplication(accountOwnerId, fieldName)
                break
            case CustomerReceivableAnticipationConfig.simpleName:
                asyncActionService.saveChildAccountCustomerReceivableAnticipationConfigReplication(accountOwnerId, fieldName)
                break
            case BankSlipFee.simpleName:
                asyncActionService.saveChildAccountBankSlipFeeReplication(accountOwnerId, fieldName)
                break
            case PixCreditFee.simpleName:
                asyncActionService.saveChildAccountPixCreditFeeReplication(accountOwnerId, fieldName)
                break
            case CreditCardFeeConfig.simpleName:
                asyncActionService.saveChildAccountCreditCardFeeReplication(accountOwnerId, fieldName)
                break
            case PixTransactionCheckoutLimit.simpleName:
                asyncActionService.saveChildAccountPixTransactionCheckoutLimitReplication(accountOwnerId, fieldName)
                break
            case CustomerCriticalActionConfig.simpleName:
                asyncActionService.saveChildAccountCriticalActionConfigReplication(accountOwnerId, fieldName)
                break
            case CustomerParameter.simpleName:
                asyncActionService.saveChildAccountCustomerParameterReplication(accountOwnerId, fieldName)
                break
            case InternalLoanConfig.simpleName:
                asyncActionService.saveChildAccountInternalLoanConfigReplication(accountOwnerId)
                break
            case CustomerConfig.simpleName:
                asyncActionService.saveChildAccountCustomerConfigReplication(accountOwnerId, fieldName)
                break
            case CustomerFeature.simpleName:
                asyncActionService.saveChildAccountCustomerFeatureReplication(accountOwnerId, fieldName)
                break
            default:
                throw new NotImplementedException()
        }
    }

    public void applyParameterListForAllChildAccounts(Long accountOwnerId, List<String> fieldNameList, String type) {
        if (!fieldNameList) throw new BusinessException("Nenhum campo foi selecionado.")

        for (String fieldName : fieldNameList) {
            applyParameterForAllChildAccounts(accountOwnerId, fieldName, type)
        }
    }
}
