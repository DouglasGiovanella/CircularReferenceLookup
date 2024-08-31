package com.asaas.service.asaasmoney

import com.asaas.api.auth.ApiAuthResultVO
import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.api.ApiRequestLogOriginEnum
import com.asaas.domain.customer.Customer
import com.asaas.environment.AsaasEnvironment
import com.asaas.user.UserUtils
import com.asaas.utils.RequestUtils
import grails.transaction.Transactional
import org.springframework.web.context.request.RequestContextHolder

import javax.servlet.http.HttpServletRequest

@Transactional
class AsaasMoneyService {

    def apiAuthService

    public Boolean isAsaasMoneyRequest() {
        if (!ApiRequestLogOriginEnum.convert(RequestUtils.getHeader("origin"))?.isAsaasMoney()) return false
        if ((RequestUtils.getHeader("Access-Token") == AsaasApplicationHolder.config.asaas.asaasMoney.receiveRequestAccessToken)) return true

        Customer asaasMoneyCustomer = getAsaasMoneyCustomerFromRequest()

        if (!asaasMoneyCustomer) return false
        if (asaasMoneyCustomer.id != AsaasApplicationHolder.config.asaas.asaasMoney.customer.id) return false

        if (AsaasEnvironment.isSandbox()) return true

        return true
    }

    private Customer getAsaasMoneyCustomerFromRequest() {
        HttpServletRequest request = RequestContextHolder.getRequestAttributes()?.currentRequest
        if (!request) return null

        String asaasMoneyAccessTokenFromHeader = request.getHeader("asaas_money_access_token")
        if (!asaasMoneyAccessTokenFromHeader) return null

        ApiAuthResultVO apiAuthResultVo = apiAuthService.authenticate(asaasMoneyAccessTokenFromHeader)

        Long asaasMoneyCustomerId
        if (apiAuthResultVo?.apiConfigCacheVO) asaasMoneyCustomerId = apiAuthResultVo.customer.id

        if (asaasMoneyCustomerId == AsaasApplicationHolder.config.asaas.asaasMoney.customer.id) return Customer.get(asaasMoneyCustomerId)

        throw new RuntimeException("Uso indevido do mecanismo de autorização para o AsaasMoney através da ApiKey do cliente: ${UserUtils.getCurrentUser()?.customer?.id}")
    }
}
