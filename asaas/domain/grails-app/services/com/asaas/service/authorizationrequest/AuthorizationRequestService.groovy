package com.asaas.service.authorizationrequest

import com.asaas.api.ApiMobileUtils
import com.asaas.authorizationrequest.AuthorizationRequestActionType
import com.asaas.authorizationrequest.AuthorizationRequestType
import com.asaas.customer.CustomerParameterName
import com.asaas.customerexternalauthorization.CustomerExternalAuthorizationRequestConfigType
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.user.UserUtils
import grails.transaction.Transactional

@Transactional
class AuthorizationRequestService {

    def customerExternalAuthorizationRequestConfigService

    public AuthorizationRequestType findAuthorizationRequestType(Customer customer, AuthorizationRequestActionType action) {
        Boolean isForceExternalAuthorization = isExternalAuthorizationEnabledAndForceUse(customer, action)

        if (isForceExternalAuthorization) {
            return AuthorizationRequestType.EXTERNAL_AUTHORIZATION
        }

        if (isApiRequest(customer)) {
            return findTypeForApiRequest(customer, action)
        }

        return findTypeForNonApiRequest(customer, action)
    }

    private Boolean isCriticalActionEnableForPix(Customer customer) {
        CustomerCriticalActionConfig customerCriticalActionConfig = CustomerCriticalActionConfig.query([customerId: customer.id, readOnly: true]).get()
        return customerCriticalActionConfig?.isPixTransactionAuthorizationEnabled()
    }

    private AuthorizationRequestType findTypeForApiRequest(Customer customer, AuthorizationRequestActionType action) {
        if (isExternalAuthorizationEnabled(customer, action)) {
            return AuthorizationRequestType.EXTERNAL_AUTHORIZATION
        }

        if (isCriticalActionEnabled(customer, action)) {
            return AuthorizationRequestType.CRITICAL_ACTION
        }

        return AuthorizationRequestType.NONE
    }

    private AuthorizationRequestType findTypeForNonApiRequest(Customer customer, AuthorizationRequestActionType action) {
        if (isCriticalActionEnabled(customer, action)) {
            return AuthorizationRequestType.CRITICAL_ACTION
        }

        if (isExternalAuthorizationEnabled(customer, action)) {
            return AuthorizationRequestType.EXTERNAL_AUTHORIZATION
        }

        return AuthorizationRequestType.NONE
    }

    private Boolean isExternalAuthorizationEnabled(Customer customer, AuthorizationRequestActionType action) {
        CustomerExternalAuthorizationRequestConfigType externalAuthorizationType = getExternalAuthorizationEnum(action)
        return customerExternalAuthorizationRequestConfigService.hasConfigEnabled(customer, externalAuthorizationType)
    }

    private Boolean isExternalAuthorizationEnabledAndForceUse(Customer customer, AuthorizationRequestActionType action) {
        CustomerExternalAuthorizationRequestConfigType externalAuthorizationType = getExternalAuthorizationEnum(action)
        return customerExternalAuthorizationRequestConfigService.hasConfigEnabledAndForceUse(customer, externalAuthorizationType)
    }

    private Boolean isCriticalActionEnabled(Customer customer, AuthorizationRequestActionType action) {
        if (action.isPixType()) {
            return isCriticalActionEnableForPix(customer)
        }

        String columnName = getColumnCriticalActionConfig(action)
        return CustomerCriticalActionConfig.query([column: columnName, customerId: customer.id]).get()
    }

    private CustomerExternalAuthorizationRequestConfigType getExternalAuthorizationEnum(AuthorizationRequestActionType action) {
        if (action == AuthorizationRequestActionType.MOBILE_PHONE_RECHARGE_SAVE) {
            return CustomerExternalAuthorizationRequestConfigType.MOBILE_PHONE_RECHARGE
        }

        if (action == AuthorizationRequestActionType.BILL_SAVE) {
            return CustomerExternalAuthorizationRequestConfigType.BILL
        }

        if (action.isCreditTransferRequestSave() || action.isInternalTransferSave() || action.isPixTransferSave()) {
            return CustomerExternalAuthorizationRequestConfigType.TRANSFER
        }

        if (action.isPixTransferQrCodeSave()) {
            return CustomerExternalAuthorizationRequestConfigType.PIX_QR_CODE
        }

        throw new IllegalArgumentException("A action ${action} não está mapeada para o fluxo de autorização de requisições.")
    }

    private String getColumnCriticalActionConfig(AuthorizationRequestActionType action) {
        if (action == AuthorizationRequestActionType.MOBILE_PHONE_RECHARGE_SAVE) {
            return "mobilePhoneRecharge"
        }

        if (action == AuthorizationRequestActionType.BILL_SAVE) {
            return "bill"
        }

        if (action.isCreditTransferRequestSave() || action.isInternalTransferSave()) {
            return "transfer"
        }

        throw new IllegalArgumentException("A action ${action} não está mapeada para o fluxo de autorização de requisições.")
    }

    private Boolean isApiRequest(Customer customer) {
        Boolean isWhiteLabel = CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)
        Boolean isChildAccount = customer.accountOwner ? true : false

        if (isWhiteLabel && isChildAccount) {
            return true
        }

        Boolean isMobileAppRequest = ApiMobileUtils.isMobileAppRequest()

        if (UserUtils.actorIsApiKey() && !isMobileAppRequest) {
            return true
        }

        return false
    }
}
