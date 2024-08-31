package com.asaas.service.authorizationtoken

import com.asaas.authorizationdevice.AuthorizationDeviceType
import com.asaas.authorizationtoken.AuthorizationTokenType
import com.asaas.domain.accountactivationrequest.AccountActivationRequest
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.exception.ResourceNotFoundException
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import grails.transaction.Transactional
import org.springframework.web.context.request.RequestContextHolder

@Transactional
class AuthorizationTokenAdminService {

    def adminAccessTrackingService
    def criticalActionService

    public String getAuthorizationToken(Long id, AuthorizationTokenType type, Long customerId) {
        String authorizationToken

        switch (type) {
            case AuthorizationTokenType.CRITICAL_ACTION:
                authorizationToken = CriticalActionGroup.query([id: id, customerId: customerId]).get()?.getDecryptedAuthorizationToken()
                break
            case AuthorizationTokenType.ACTIVE_AUTHORIZATION_DEVICE:
                authorizationToken = AuthorizationDevice.pending([id: id, customerId: customerId, type: AuthorizationDeviceType.SMS_TOKEN]).get()?.getDecryptedActivationToken()
                break
            case AuthorizationTokenType.DISABLE_AUTHORIZATION_DEVICE:
                authorizationToken = AuthorizationDevice.active([id: id, customerId: customerId, type: AuthorizationDeviceType.SMS_TOKEN, "deactivationToken[isNotNull]": true]).get()?.getDecryptedDeactivationToken()
                break
            case AuthorizationTokenType.ACCOUNT_ACTIVATION_REQUEST:
                authorizationToken = AccountActivationRequest.query([id: id, customerId: customerId, used: false, valid: true, order: "desc"]).get()?.getDecryptedToken()
                break
            default:
                throw new RuntimeException("AuthorizationTokenType inválido.")
        }

        if (!authorizationToken) throw new ResourceNotFoundException("Token não encontrado.")

        adminAccessTrackingService.save(RequestContextHolder.requestAttributes.params, id.toString(), customerId)

        return authorizationToken
    }

    public CriticalActionGroup authorizeCriticalAction(Long customerId, List<Long> criticalActionIdList) {
        Customer customer = Customer.get(customerId)

        CriticalAction criticalAction = CriticalAction.find(customer, criticalActionIdList.first())

        CriticalActionGroup criticalActionGroup = criticalAction.group ?: criticalActionService.group(customer, criticalActionIdList)

        AsaasLogger.info("Ação crítica [${criticalActionGroup.id}] do customer [${criticalActionGroup.customer.id}] sendo autorizada pelo user [${UserUtils.getCurrentUser().username}]")

        return criticalActionService.processGroupAuthorization(criticalActionGroup)
    }
}
