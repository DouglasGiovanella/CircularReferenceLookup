package com.asaas.service.api

import com.asaas.api.ApiAuthorizationDeviceParser
import com.asaas.api.ApiCriticalActionParser
import com.asaas.api.ApiMobileUtils
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.DisabledReason
import com.asaas.customeracquisitionchannel.CustomerAcquisitionChannelOption
import com.asaas.domain.login.UserKnownDevice
import com.asaas.log.AsaasLogger
import com.asaas.onboarding.AccountActivationOrigin
import com.asaas.user.UserUtils
import com.asaas.userdevicesecurity.UserDeviceSecurityVO
import com.asaas.domain.accountactivationrequest.AccountActivationRequest
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.customeracquisitionchannel.CustomerAcquisitionChannel
import com.asaas.exception.SmsFailException
import com.asaas.utils.UserKnownDeviceUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiCustomerService extends ApiBaseService {

    def accountActivationRequestService
    def accountActivationService
    def apiBankAccountInfoService
    def apiProviderService
    def apiResponseBuilderService
    def asaasSegmentioService
    def customerAcquisitionChannelService
    def customerParameterService
    def customerStatusService
    def grailsApplication
    def messageService
    def mobileAppTokenService

    public Map updateConfig(Map params) {
        Customer customer = getProviderInstance(params)

        if (params.containsKey("enableAutomaticTransfer")) {
            customerParameterService.save(customer, CustomerParameterName.AUTOMATIC_TRANSFER, Utils.toBoolean(params.enableAutomaticTransfer))
        }

        return apiResponseBuilderService.buildSuccess([success: true])
    }

    def activationRequest(params) {
        Customer.withNewTransaction { status ->
            try {
                if(!params.phone)
                    return apiResponseBuilderService.buildErrorFrom("invalid_phone", "Informe um número de celular válido.")

                Customer customer = Customer.get(params.provider)

                AccountActivationRequest accountActivationRequest = accountActivationRequestService.save(customer, params.email, params.phone)

                if (accountActivationRequest.hasErrors()) {
                    return apiResponseBuilderService.buildErrorList(accountActivationRequest)
                } else {
                    asaasSegmentioService.track(customer.id, "Mobile  :: AccountActivation :: Fone para ativação informado", [customerPhone: params.phone])
                    return apiResponseBuilderService.buildSuccess([message:  "Enviamos um SMS com o código para o seu celular. Por favor informe-o no campo abaixo."])
                }
            } catch (SmsFailException e) {
                status.setRollbackOnly()
                return apiResponseBuilderService.buildErrorFrom("invalid_phone", "O número de celular informado é inválido. Por favor, verique-o e tente novamente.")
            } catch (Exception exception) {
                status.setRollbackOnly()
                AsaasLogger.error("ApiCustomerService.activationRequest >> Erro inesperado", exception)
                return apiResponseBuilderService.buildErrorFrom("unknow.error", "unknow.error")
            }
        }
    }

    def validateActivationCode(params) {
        Utils.withNewTransactionAndRollbackOnError({
            Customer customer = getProviderInstance(params)
            UserKnownDevice currentDevice = UserKnownDeviceUtils.getCurrentDevice(UserUtils.getCurrentUser().id)
            UserDeviceSecurityVO userDeviceSecurityVO = new UserDeviceSecurityVO(currentDevice, false)
            AccountActivationOrigin accountActivationOrigin = AccountActivationOrigin.convert(params.activationOrigin)

            Map responseMap = [success: true]

            if (ApiMobileUtils.getApplicationType().isAsaas()) {
                AuthorizationDevice authorizationDevice = accountActivationService.activateAndSaveMobileAppToken(customer, params.activationCode, params.deviceModelName, userDeviceSecurityVO, accountActivationOrigin)

                responseMap.authorizationDevice = ApiAuthorizationDeviceParser.buildResponseItem(authorizationDevice)
                responseMap.authorizationDevice.secretKey = mobileAppTokenService.encryptSecretKey(authorizationDevice)
            } else {
                AuthorizationDevice authorizationDevice = accountActivationService.activateAndSaveSmsToken(customer, params.activationCode, userDeviceSecurityVO, accountActivationOrigin)
                responseMap.authorizationDevice = ApiAuthorizationDeviceParser.buildResponseItem(authorizationDevice)
            }

            return apiResponseBuilderService.buildSuccess(responseMap)
        }, [onError: { error -> throw error }, ignoreStackTrace: true])
    }

    def getAccountInfo(params) {
        Map responseItem = [:]

        Customer customer = Customer.get(params.provider)

        AsaasLogger.info("ApiCustomerService.getAccountInfo >>> Endpoint acessado pelo customer [${customer.id}]")

        responseItem.customerInfo = apiProviderService.find(params)
        responseItem.bankAccountInfo = apiBankAccountInfoService.find(params)
        responseItem.criticalActionInfo = ApiCriticalActionParser.buildExtraDataItem(customer)

        return apiResponseBuilderService.buildSuccess(responseItem)
    }


    def notifyAsaasTeamAboutAndroidAppRatingOrRelevantObservations(params) {
        int maxRatingAllowed = 4
        int relevantObservationLength = 10

        if (Integer.valueOf(params.rating) <= maxRatingAllowed || params.observations?.length() > relevantObservationLength) {
            messageService.notifyAsaasTeamAboutAndroidAppRatingOrRelevantObservations(getProviderInstance(params), Integer.valueOf(params.rating), params.observations)
        }

        return apiResponseBuilderService.buildSuccess([success: true])
    }

    def saveCustomerAcquisitionChannel(params) {
        CustomerAcquisitionChannel customerAcquisitionChannel = customerAcquisitionChannelService.save(getProviderInstance(params), CustomerAcquisitionChannelOption.convert(params.acquisitionChannelOption), params.otherChannelOptionDescription)

        if (customerAcquisitionChannel.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(customerAcquisitionChannel)
        }

        return apiResponseBuilderService.buildSuccess([success: true])
    }

    def validateIfAccountCanBeDisabled(params) {
        Map validationsMap = customerStatusService.validateIfAccountCanBeDisabled(getProviderInstance(params), UserUtils.getCurrentUser(), null)
        Map responseMap = validationsMap.clone().each { it.value = true }

        if (responseMap.blockedBalance) {
            responseMap.judicialLockedBalanceAmount = validationsMap.blockedBalance.arguments.first()
        }

        responseMap.accountCancellationMaxBalance = grailsApplication.config.asaas.accountCancellation.maxBalance

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    def disableAccount(params) {
        Customer customer = getProviderInstance(params)

        if (!params.excludeReason) {
            params.excludeReason = "Motivo de encerramento de conta não informado"
        }

        if (!params.disabledReasonType || params.disabledReasonType == "OTHER") {
            params.disabledReasonType = DisabledReason.OTHERS
        } else {
            params.disabledReasonType = DisabledReason.convert(params.disabledReasonType)
        }

        CriticalAction criticalAction = customerStatusService.disableAccount(customer, UserUtils.getCurrentUser(), params.disabledReasonType, params.excludeReason)
        return apiResponseBuilderService.buildSuccess(ApiCriticalActionParser.buildResponseItem(criticalAction))
    }
}
