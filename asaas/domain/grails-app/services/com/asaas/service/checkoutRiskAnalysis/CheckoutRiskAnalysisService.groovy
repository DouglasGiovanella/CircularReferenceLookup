package com.asaas.service.checkoutRiskAnalysis

import com.asaas.checkoutRiskAnalysis.CheckoutRiskAnalysisReason
import com.asaas.checkoutRiskAnalysis.CheckoutRiskAnalysisReasonObject
import com.asaas.checkoutRiskAnalysis.adapter.CheckoutRiskAnalysisInfoAdapter
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.apiaccesscontrol.ApiAccessControl
import com.asaas.domain.api.knownrequestpattern.CustomerKnownApiRequestPattern
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.customerknownremoteip.CustomerKnownRemoteIp
import com.asaas.domain.transfer.Transfer
import com.asaas.log.AsaasLogger
import com.asaas.transfer.TransferStatus
import grails.transaction.Transactional

@Transactional
class CheckoutRiskAnalysisService {

    def asaasSegmentioService
    def checkoutRiskAnalysisConfigCacheService
    def customerExternalAuthorizationRequestConfigService
    def customerKnownApiRequestPatternService
    def customerKnownRemoteIpService
    def trustedCustomerKnownRemoteIpScheduleService
    def trustedUserKnownDeviceScheduleService

    public Boolean checkIfCheckoutIsSuspectedOfFraud(CheckoutRiskAnalysisReasonObject reasonObject, CheckoutRiskAnalysisInfoAdapter checkoutInfoAdapter) {
        try {
            if (CustomerParameter.getValue(checkoutInfoAdapter.customer, CustomerParameterName.BYPASS_CHECKOUT_RISK_ANALYSIS)) return false

            if (checkoutInfoAdapter.eventOrigin?.isApi()) {
                return checkIfApiCheckoutIsSuspectedOfFraud(reasonObject, checkoutInfoAdapter)
            }

            if (!checkoutInfoAdapter.user) return false

            if (!checkoutInfoAdapter.userKnownDevice) {
                AsaasLogger.warn("CheckoutRiskAnalysisService.checkIfCheckoutIsSuspectedOfFraud >> Dispositivo não encontrado: [${reasonObject.objectName}], CustomerId: [${checkoutInfoAdapter.customer.id}], Valor: [${checkoutInfoAdapter.value}], Destino: [${checkoutInfoAdapter.destinationBankAccountCpfCnpj}], EventOriginType: [${checkoutInfoAdapter.eventOrigin}]")
                return false
            }

            if (checkoutInfoAdapter.userKnownDevice.trustedToCheckout) return false

            if (shouldScheduleToBeTrusted(checkoutInfoAdapter)) {
                trustedUserKnownDeviceScheduleService.saveIfNecessary(checkoutInfoAdapter.userKnownDevice)

                return false
            }

            AsaasLogger.warn("CheckoutRiskAnalysisService.checkIfCheckoutIsSuspectedOfFraud >> Transação suspeita: [${reasonObject.objectName}], CustomerId: [${checkoutInfoAdapter.customer.id}], Valor: [${checkoutInfoAdapter.value}], Destino: [${checkoutInfoAdapter.destinationBankAccountCpfCnpj}], UserKnownDeviceID: [${checkoutInfoAdapter.userKnownDevice.id}]")

            if (!checkoutRiskAnalysisConfigCacheService.getInstance(CheckoutRiskAnalysisReason.NOT_TRUSTED_DEVICE).enabled) return false

            return true
        } catch (Exception exception) {
            AsaasLogger.error("CheckoutRiskAnalysisService.checkIfCheckoutIsSuspectedOfFraud -> Erro ao verificar se checkout e suspeito. Customer [${checkoutInfoAdapter.customer}]", exception)
            return false
        }
    }

    private Boolean checkIfApiCheckoutIsSuspectedOfFraud(CheckoutRiskAnalysisReasonObject reasonObject, CheckoutRiskAnalysisInfoAdapter checkoutInfoAdapter) {
        Customer customer = checkoutInfoAdapter.customer
        if (!reasonObject.isPixTransaction()) return false

        if (checkoutInfoAdapter.pixTransactionOriginType.isQrCode() && customerExternalAuthorizationRequestConfigService.hasPixQrCodeConfigEnabled(customer)) return false
        if (customerExternalAuthorizationRequestConfigService.hasTransferConfigEnabled(customer)) return false

        CustomerKnownRemoteIp customerKnownRemoteIp = customerKnownRemoteIpService.findOrCreate(customer, checkoutInfoAdapter.remoteIp, checkoutInfoAdapter.eventOrigin)
        if (customerKnownRemoteIp.trustedToCheckout) return false

        if (shouldSetAsTrustedImmediately(checkoutInfoAdapter)) {
            customerKnownRemoteIpService.setAsTrustedToCheckoutInBatch([customerKnownRemoteIp.id])

            return false
        }

        if (shouldScheduleToBeTrusted(checkoutInfoAdapter)) {
            trustedCustomerKnownRemoteIpScheduleService.saveIfNecessary(customerKnownRemoteIp)

            return false
        }

        trackCheckIfApiCheckoutIsSuspectedOfFraud(reasonObject, checkoutInfoAdapter)
        if (!checkoutRiskAnalysisConfigCacheService.getInstance(CheckoutRiskAnalysisReason.NOT_TRUSTED_API_REMOTE_IP).enabled) return false

        return true
    }

    private Boolean shouldScheduleToBeTrusted(CheckoutRiskAnalysisInfoAdapter checkoutInfoAdapter) {
        if (!checkoutInfoAdapter.destinationBankAccountCpfCnpj) return false

        if (checkoutInfoAdapter.customer.cpfCnpj == checkoutInfoAdapter.destinationBankAccountCpfCnpj) return true

        Boolean hasTransferToSameDestination = Transfer.query([
            exists: true,
            customer: checkoutInfoAdapter.customer,
            destinationBankAccountCpfCnpj:  checkoutInfoAdapter.destinationBankAccountCpfCnpj,
            status: TransferStatus.DONE]).get().asBoolean()

        return hasTransferToSameDestination
    }

    private Boolean shouldSetAsTrustedImmediately(CheckoutRiskAnalysisInfoAdapter checkoutInfoAdapter) {
        Boolean hasApiAccessControl = ApiAccessControl.query([exists: true, "customerId[in]": buildCustomerIdListToConsiderApiAccessControl(checkoutInfoAdapter)]).get().asBoolean()
        if (hasApiAccessControl) {
            AsaasLogger.info("CheckoutRiskAnalysisService.shouldSetAsTrustedImmediately >> ApiAcessControl rule. Customer [${checkoutInfoAdapter.customer.id}] RemoteIp [${checkoutInfoAdapter.remoteIp}] Value [${checkoutInfoAdapter.value}] Destination [${checkoutInfoAdapter.destinationBankAccountCpfCnpj}]")
            return true
        }

        if (isTrustedApiRequestPattern(checkoutInfoAdapter)) {
            AsaasLogger.info("CheckoutRiskAnalysisService.shouldSetAsTrustedImmediately >> CustomerKnownApiRequestPattern rule. Customer [${checkoutInfoAdapter.customer.id}] RemoteIp [${checkoutInfoAdapter.remoteIp}] Value [${checkoutInfoAdapter.value}] Destination [${checkoutInfoAdapter.destinationBankAccountCpfCnpj}]")
            return true
        }

        return false
    }

    private List<Long> buildCustomerIdListToConsiderApiAccessControl(CheckoutRiskAnalysisInfoAdapter checkoutInfoAdapter) {
        List<Long> customerIdList = [checkoutInfoAdapter.customer.id]
        if (!checkoutInfoAdapter.customer.accountOwner) return customerIdList

        customerIdList.add(checkoutInfoAdapter.customer.accountOwner.id)

        return customerIdList
    }

    private void trackCheckIfApiCheckoutIsSuspectedOfFraud(CheckoutRiskAnalysisReasonObject reasonObject, CheckoutRiskAnalysisInfoAdapter checkoutInfoAdapter) {
        Map trackInfo = [:]
        trackInfo.action = "checkIfCheckoutIsSuspectedOfFraudForApi"
        trackInfo.isSuspectedOfFraud = "true"
        trackInfo.object = reasonObject.objectName
        trackInfo.value = checkoutInfoAdapter.value
        trackInfo.destinationBankAccountCpfCnpj = checkoutInfoAdapter.destinationBankAccountCpfCnpj
        trackInfo.remoteIp = checkoutInfoAdapter.remoteIp
        trackInfo.eventOrigin = checkoutInfoAdapter.eventOrigin
        asaasSegmentioService.track(checkoutInfoAdapter.customer.id, "checkout_risk_analysis", trackInfo)
    }

    private Boolean isTrustedApiRequestPattern(CheckoutRiskAnalysisInfoAdapter checkoutInfoAdapter) {
        if (checkoutInfoAdapter.savedCustomerKnownApiRequestPattern) {
            return checkoutInfoAdapter.savedCustomerKnownApiRequestPattern.trusted
        }

        if (!checkoutInfoAdapter.customerKnownApiRequestPatternAdapter) {
            AsaasLogger.warn("CheckoutRiskAnalysisService.isTrustedApiRequestPattern - Não foi possível verificar fraude em transação do customer [${checkoutInfoAdapter.customer.id}], value: [${checkoutInfoAdapter.value}]")
            return false
        }

        String pattern = customerKnownApiRequestPatternService.encodePattern(checkoutInfoAdapter.customerKnownApiRequestPatternAdapter.current)
        Map searchParams = [column: "trusted", customer: checkoutInfoAdapter.customer, pattern: pattern]
        return CustomerKnownApiRequestPattern.query(searchParams).get().asBoolean()
    }
}
