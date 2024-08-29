package com.asaas.service.webhook

import com.asaas.billinginfo.BillingType
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.customer.CustomerBankSlipBeneficiary
import com.asaas.domain.payment.Payment
import com.asaas.domain.webhook.WebhookRequest
import com.asaas.domain.webhook.WebhookRequestProvider
import com.asaas.domain.webhook.WebhookRequestType
import com.asaas.integration.smartBank.vo.SmartBankBankSlipNotificationWebhookVO
import com.asaas.integration.smartBank.vo.SmartBankBeneficiaryNotificationWebhookVO
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.asaas.webhook.WebhookRequestStatus
import grails.transaction.Transactional

@Transactional
class SmartBankWebhookRequestService {

    def webhookRequestService

    def paymentConfirmRequestService

    def messageService

    public void processPendingBankSlipNotification() {
        try {
            BoletoBank smartBankBoletoBank = BoletoBank.query([bankCode: SupportedBank.SMARTBANK.code()]).get()

            List<WebhookRequest> pendingWebhookRequestList = WebhookRequest.query([requestProvider: WebhookRequestProvider.SMARTBANK, requestType: WebhookRequestType.SMARTBANK_BANKSLIP_NOTIFICATION, statusList: [WebhookRequestStatus.PENDING]]).list()

            List<Map> paidItems = []

            Integer webhookRequestTotal = pendingWebhookRequestList.size()
            Integer webhookRequestCount = 1

            for (WebhookRequest webhookRequest in pendingWebhookRequestList) {
                if (setNullResponseAsIgnored(webhookRequest)) continue

                SmartBankBankSlipNotificationWebhookVO webhookVO = new SmartBankBankSlipNotificationWebhookVO(webhookRequest.requestBody)
                webhookVO.parse()

                if (webhookVO.status?.isReceived()) {
                    paidItems.add([nossoNumero: webhookVO.nossoNumero, value: webhookVO.paymentValue, date: webhookVO.paymentDate, creditDate: new Date().clearTime(), receiverBankCode: webhookVO.paymentBankCode, receiverAgency: webhookVO.paymentAgency])
                } else if (webhookVO.registrationStatus) {
                    Payment.withNewTransaction {
                        Payment payment = Payment.get(webhookVO.paymentId)
                        if (payment && payment.nossoNumero == webhookVO.nossoNumero && payment.boletoBank == smartBankBoletoBank) {
                            payment.updateRegistrationStatus(webhookVO.registrationStatus)
                        }
                    }
                }

                webhookRequestService.setAsProcessed(webhookRequest)

                AsaasLogger.info("Webhook SMARTBANK_BANKSLIP_NOTIFICATION [${webhookRequest.id}] : nossoNumero [${webhookVO.nossoNumero}] - ${webhookRequestCount}/${webhookRequestTotal}")
                webhookRequestCount++
            }

            if (paidItems) {
                paymentConfirmRequestService.saveList(BillingType.BOLETO, smartBankBoletoBank.bank, smartBankBoletoBank, paidItems, null)
            }
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao processar webhook de notificação de boletos do SmartBank", exception)
        }
    }

    public void processPendingBeneficiaryNotification() {
        try {
            List<WebhookRequest> pendingWebhookRequestList = WebhookRequest.query([requestProvider: WebhookRequestProvider.SMARTBANK, requestType: WebhookRequestType.SMARTBANK_BENEFICIARY_NOTIFICATION, statusList: [WebhookRequestStatus.PENDING]]).list()

            for (WebhookRequest webhookRequest in pendingWebhookRequestList) {
                if (setNullResponseAsIgnored(webhookRequest)) continue

                SmartBankBeneficiaryNotificationWebhookVO webhookVO = new SmartBankBeneficiaryNotificationWebhookVO(webhookRequest.requestBody)
                webhookVO.parse()

                CustomerBankSlipBeneficiary customerBankSlipBeneficiary = CustomerBankSlipBeneficiary.query([externalIdentifier: webhookVO.externalIdentifier]).get()

                if (!customerBankSlipBeneficiary) {
                    webhookRequestService.setAsIgnored(webhookRequest)
                    AsaasLogger.error("Webhook SMARTBANK_BENEFICIARY_NOTIFICATION [${webhookRequest.id}] ignorado : não foi encontrado o customerBankSlipBeneficiary")
                    continue
                }

                customerBankSlipBeneficiary.status = webhookVO.status
                customerBankSlipBeneficiary.save(flush: true, failOnError: true)

                webhookRequestService.setAsProcessed(webhookRequest)

                AsaasLogger.info("Webhook SMARTBANK_BENEFICIARY_NOTIFICATION [${webhookRequest.id}] : externalIdentifier [${webhookVO.externalIdentifier}] -> ${webhookVO.status}")
            }
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao processar webhook de registro de beneficiário no SmartBank", exception)
        }
    }

    private Boolean setNullResponseAsIgnored(WebhookRequest webhookRequest) {
        if (Utils.isEmptyOrNull(webhookRequest.requestBody?.trim())) {
            webhookRequestService.setAsIgnored(webhookRequest)
            return true
        }

        return false
    }

}
