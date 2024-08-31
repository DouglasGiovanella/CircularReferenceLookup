package com.asaas.service.receivableanticipationpartner

import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisition
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisitionItem
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerConfig
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerConfigDocument
import com.asaas.domain.webhook.WebhookRequest
import com.asaas.domain.webhook.WebhookRequestProvider
import com.asaas.file.adapter.FileAdapter
import com.asaas.integration.vortx.manager.VortxManager
import com.asaas.integration.vortx.object.VortxWebhook
import com.asaas.integration.vortx.object.VortxWebhookStatus
import com.asaas.integration.vortx.object.VortxWebhookType
import com.asaas.integration.vortx.parser.VortxWebhookParser
import com.asaas.log.AsaasLogger
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisitionStatus
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerConfigStatus
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerDocumentStatus
import com.asaas.receivableanticipationpartner.adapter.CreateCustomerAdapter
import com.asaas.receivableanticipationpartner.adapter.CustomerAdapter
import com.asaas.receivableanticipationpartner.adapter.CustomerDocumentAdapter
import com.asaas.receivableanticipationpartner.adapter.RegistrationPartnerAdapter
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationVortxService {

    def customerDocumentFileProxyService
    def messageService
    def receivableAnticipationService
    def receivableAnticipationPartnerConfigService
    def receivableAnticipationPartnerStatementService
    def oceanManagerService
    def webhookRequestService

    public void processPendingRegistration() {
        List<Long> anticipationPartnerConfigIdList = ReceivableAnticipationPartnerConfig.query([column: 'id', vortxStatus: ReceivableAnticipationPartnerConfigStatus.PENDING]).list()
        if (!anticipationPartnerConfigIdList) return

        for (Long anticipationPartnerConfigId : anticipationPartnerConfigIdList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipationPartnerConfig anticipationPartnerConfig = ReceivableAnticipationPartnerConfig.get(anticipationPartnerConfigId)

                if (anticipationPartnerConfig.oceanRegistrationEnabled) {
                    sendRegistrationPartnerByOcean(anticipationPartnerConfig)
                    return
                }

                sendRegistrationPartnerByVortx(anticipationPartnerConfig)
            }, [
                logErrorMessage: "ReceivableAnticipationVortxService.processPendingRegistration >> Erro no cadastro de cliente ${anticipationPartnerConfigId}",
                onError: { hasError = true }
            ])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    ReceivableAnticipationPartnerConfig customerReceivableAnticipationConfig = ReceivableAnticipationPartnerConfig.get(anticipationPartnerConfigId)
                    customerReceivableAnticipationConfig.vortxStatus = ReceivableAnticipationPartnerConfigStatus.ERROR
                    customerReceivableAnticipationConfig.save(failOnError: true)
                })
            }
        }
    }

    public void processPendingAcquisitions(Boolean sendAllAvailablePartnerAcquisitions) {
        final Integer minAcquisitionsPerBatch = 100
        final Integer maxAcquisitionsPerBatch = 350

        Map params = [:]
        if (!sendAllAvailablePartnerAcquisitions) params.max = maxAcquisitionsPerBatch

        List<Long> anticipationPartnerAcquisitionIdList = ReceivableAnticipationPartnerAcquisition.pending([column: 'id', partner: ReceivableAnticipationPartner.VORTX, order: "asc"]).list(params)
        if (!sendAllAvailablePartnerAcquisitions && anticipationPartnerAcquisitionIdList.size() < minAcquisitionsPerBatch) return

        sendPartnerAcquisitionBatch(anticipationPartnerAcquisitionIdList)
    }

    public void sendPartnerAcquisitionBatch(List<Long> anticipationPartnerAcquisitionIdList) {
        Boolean vortxResult
        String temporaryBatchIdentifier = UUID.randomUUID().toString()

        for (Long partnerAcquisitionId : anticipationPartnerAcquisitionIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipationPartnerAcquisition partnerAcquisition = ReceivableAnticipationPartnerAcquisition.get(partnerAcquisitionId)
                partnerAcquisition.status = ReceivableAnticipationPartnerAcquisitionStatus.AWAITING_PARTNER_CONFIRMATION
                partnerAcquisition.batchExternalIdentifier = temporaryBatchIdentifier
                partnerAcquisition.save(failOnError: true)
            }, [logErrorMessage: "sendPartnerAcquisitionBatch - Erro ao processar a partnerAcquisition [${partnerAcquisitionId}]."])
        }

        Utils.withNewTransactionAndRollbackOnError({
            List<ReceivableAnticipationPartnerAcquisition> anticipationPartnerAcquisitionList = ReceivableAnticipationPartnerAcquisition.query([batchExternalIdentifier: temporaryBatchIdentifier, status: ReceivableAnticipationPartnerAcquisitionStatus.AWAITING_PARTNER_CONFIRMATION]).list()
            if (!anticipationPartnerAcquisitionList) throw new Exception("Não há nenhuma antecipação com esse identificador")

            VortxManager vortxManager = new VortxManager()
            String batchExternalIdentifier = vortxManager.transmitAcquisitionBatch(anticipationPartnerAcquisitionList)

            for (ReceivableAnticipationPartnerAcquisition anticipationPartnerAcquisition : anticipationPartnerAcquisitionList) {
                anticipationPartnerAcquisition.batchExternalIdentifier = batchExternalIdentifier
                anticipationPartnerAcquisition.save(failOnError: true)
            }

            vortxResult = true
        }, [logErrorMessage: "Vortx - Erro no envio de aquisição. anticipationPartnerAcquisitionIdList: ${anticipationPartnerAcquisitionIdList}"])

        if (vortxResult) return

        Utils.withNewTransactionAndRollbackOnError({
            List<ReceivableAnticipationPartnerAcquisition> anticipationPartnerAcquisitionList = ReceivableAnticipationPartnerAcquisition.getAll(anticipationPartnerAcquisitionIdList)

            for (ReceivableAnticipationPartnerAcquisition anticipationPartnerAcquisition : anticipationPartnerAcquisitionList) {
                anticipationPartnerAcquisition.status = ReceivableAnticipationPartnerAcquisitionStatus.ERROR
                anticipationPartnerAcquisition.save(failOnError: true)
            }
        })
    }

    public void sendYesterdayPartnerAcquistionDocumentList() {
        final Date lastExecutionDate = CustomDateUtils.setTime(new Date(), ReceivableAnticipationPartnerAcquisition.FINISH_HOUR_TO_EXECUTE_SEND_DOCUMENT, 0, 0)
        final Boolean isLastExecution = new Date() >= lastExecutionDate
        final Integer defaultQueryLimit = 500

        List<Long> partnerAcquisitionIdList = ReceivableAnticipationPartnerAcquisition.query([
            column: "id",
            "confirmedDate[le]": new Date().clearTime(),
            partner: ReceivableAnticipationPartner.VORTX,
            documentStatus: ReceivableAnticipationPartnerDocumentStatus.AWAITING_SEND
        ]).list(max: isLastExecution ? null : defaultQueryLimit)

        if (!partnerAcquisitionIdList) return

        sendAcquisitionDocumentByPartnerAcquisitionIdList(partnerAcquisitionIdList)
    }

    public void sendAcquisitionDocumentByPartnerAcquisitionIdList(List<Long> partnerAcquisitionIdList) {
        for (Long acquisitionId : partnerAcquisitionIdList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                sendAcquisitionDocument(acquisitionId)
            }, [
                logErrorMessage: "ReceivableAnticipationVortxService.sendAcquisitionDocument >> Erro no envio de lastro da aquisição [${acquisitionId}]",
                onError: { hasError = true }
            ])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    ReceivableAnticipationPartnerAcquisition anticipationPartnerAcquisition = ReceivableAnticipationPartnerAcquisition.get(acquisitionId)
                    anticipationPartnerAcquisition.documentStatus = ReceivableAnticipationPartnerDocumentStatus.ERROR
                    anticipationPartnerAcquisition.save(failOnError: false)
                }, [logErrorMessage: "ReceivableAnticipationVortxService.sendAcquisitionDocument >> Falha ao marcar o documentStatus da aquisição [${acquisitionId}] como erro"])
            }
        }
    }

    public void confirm(List<ReceivableAnticipationPartnerAcquisition> anticipationAcquisitionList, BigDecimal creditValue) {
        if (!anticipationAcquisitionList.collect { it.status }.contains(ReceivableAnticipationPartnerAcquisitionStatus.AWAITING_PARTNER_CONFIRMATION)) {
            throw new Exception("Essa antecipação via Vortx não está esperando confirmação.")
        }

        if (creditValue != anticipationAcquisitionList*.getValueWithPartnerFee().sum()) throw new Exception("O valor de confirmação é diferente do valor antecipado!")

        VortxManager vortxManager = new VortxManager()
        vortxManager.transmitAcquisitionConfirmationBatch(anticipationAcquisitionList)

        Date confirmedDate = new Date()
        anticipationAcquisitionList.each {
            it.status = ReceivableAnticipationPartnerAcquisitionStatus.AWAITING_PARTNER_CREDIT
            it.confirmedDate = confirmedDate
            it.save(failOnError: true)
        }
    }

    public void reject(List<Long> partnerAcquisitionIdList, String reason) {
        Utils.forEachWithFlushSession(partnerAcquisitionIdList, 50, { Long partnerAcquisitionId ->
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipationPartnerAcquisition partnerAcquisition = ReceivableAnticipationPartnerAcquisition.get(partnerAcquisitionId)
                partnerAcquisition.status = ReceivableAnticipationPartnerAcquisitionStatus.REJECTED
                partnerAcquisition.rejectReason = reason
                partnerAcquisition.save(failOnError: true)
            }, [logErrorMessage: "Vortx >> Erro ao rejeitar antecipação [${partnerAcquisitionId}]"])
        })

        AsaasLogger.warn("Vortx - Cessão reprovada pelo FIDC. AnticipationAcquisitionListId: ${partnerAcquisitionIdList}")
    }

    public void credit(String batchExternalIdentifier, List<Long> partnerAcquisitionIdList) {
        BigDecimal transferValue = 0

        Utils.forEachWithFlushSession(partnerAcquisitionIdList, 50, { Long partnerAcquisitionId ->
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipationPartnerAcquisition partnerAcquisition = ReceivableAnticipationPartnerAcquisition.get(partnerAcquisitionId)
                if (!partnerAcquisition.status.isAwaitingPartnerCredit()) {
                    if (!partnerAcquisition.status.isCredited() && !partnerAcquisition.status.isDebited()) throw new Exception("Essa antecipação via Vortx não está esperando crédito.")
                    return
                }

                receivableAnticipationService.credit(partnerAcquisition.receivableAnticipation)

                receivableAnticipationPartnerStatementService.saveAnticipationCredit(partnerAcquisition)
                transferValue += partnerAcquisition.getValueWithPartnerFee()
            }, [logErrorMessage: "Vortx >> Erro ao creditar antecipação [${partnerAcquisitionId}]"])
        })

        if (transferValue <= 0) return
        receivableAnticipationPartnerStatementService.saveExternalTransfer(transferValue, batchExternalIdentifier)
    }

    public void creditInBatchByOcean(String batchExternalIdentifier, List<Long> anticipationIdList) {
        Utils.withNewTransactionAndRollbackOnError({
            BigDecimal transferValue = 0

            for (Long anticipationId : anticipationIdList) {
                ReceivableAnticipationPartnerAcquisition partnerAcquisition = ReceivableAnticipationPartnerAcquisition.query(["receivableAnticipationId": anticipationId]).get()
                if (!partnerAcquisition) throw new RuntimeException("Não foi possível encontrar a aquisição. [anticipationId: ${anticipationId}]")
                if (!partnerAcquisition.status.isAwaitingPartnerCredit()) continue

                partnerAcquisition.batchExternalIdentifier = batchExternalIdentifier
                partnerAcquisition.save(failOnError: true)

                receivableAnticipationService.credit(partnerAcquisition.receivableAnticipation)
                receivableAnticipationPartnerStatementService.saveAnticipationCredit(partnerAcquisition)

                transferValue += partnerAcquisition.getValueWithPartnerFee()
            }

            if (transferValue <= 0) return
            receivableAnticipationPartnerStatementService.saveExternalTransfer(transferValue, batchExternalIdentifier)
        }, [logErrorMessage: "ReceivableAnticipationVortxService.creditInBatchByOcean >> Erro ao processar antecipações da requisição. [anticipationIdList: ${anticipationIdList}]"])
    }

    public void notifyDailyBalanceRequired() {
        List<ReceivableAnticipationPartnerAcquisition> partnerAcquisitionList = ReceivableAnticipationPartnerAcquisition.query(["confirmedDate[ge]": new Date().clearTime(), partner: ReceivableAnticipationPartner.VORTX]).list()
        messageService.sendVortxDailyBalanceRequired(partnerAcquisitionList)
    }

    public void processPendingWebhooks() {
        List<Long> pendingWebhookRequestIdList = WebhookRequest.pending([column: "id", requestProvider: WebhookRequestProvider.VORTX]).list(max: 50)

        for (Long webhookRequestId : pendingWebhookRequestIdList) {
            processWebhook(webhookRequestId)
        }
    }

    public void sendAcquisitionDocument(Long acquisitionId) {
        ReceivableAnticipationPartnerAcquisition anticipationPartnerAcquisition = ReceivableAnticipationPartnerAcquisition.get(acquisitionId)
        if (anticipationPartnerAcquisition.receivableAnticipation.billingType.isBoletoOrPix()) {
            VortxManager vortxManager = new VortxManager()
            vortxManager.transmitAcquisitionDocumentBatch(anticipationPartnerAcquisition.receivableAnticipation)
        } else {
            for (ReceivableAnticipationPartnerAcquisitionItem partnerAcquisitionItem : anticipationPartnerAcquisition.getPartnerAcquisitionItemList()) {
                VortxManager vortxManager = new VortxManager()
                vortxManager.transmitAcquisitionCreditCardDocument(partnerAcquisitionItem)
            }
        }
        anticipationPartnerAcquisition.documentStatus = ReceivableAnticipationPartnerDocumentStatus.SENT
        anticipationPartnerAcquisition.save(failOnError: true)
    }

    private void sendRegistrationPartnerByOcean(ReceivableAnticipationPartnerConfig anticipationPartnerConfig) {
        List<CustomerDocumentAdapter> customerDocumentAdapterList = getCustomerDocumentAdapterList(anticipationPartnerConfig)
        CreateCustomerAdapter createCustomerAdapter = new CreateCustomerAdapter(anticipationPartnerConfig)
        CustomerAdapter customerAdapter = oceanManagerService.saveCustomer(createCustomerAdapter, customerDocumentAdapterList)
        RegistrationPartnerAdapter registrationPartnerAdapter = customerAdapter.registrationPartnerList.find { it.partner.isFidc1() }
        if (!registrationPartnerAdapter) throw new RuntimeException("Não foi possível registrar o parceiro via Ocean")

        anticipationPartnerConfig.vortxStatus = registrationPartnerAdapter.status.convertToReceivableAnticipationPartnerConfigStatus()
        anticipationPartnerConfig.save(failOnError: true)
    }

    private List<CustomerDocumentAdapter> getCustomerDocumentAdapterList(ReceivableAnticipationPartnerConfig anticipationPartnerConfig) {
        List<CustomerDocumentAdapter> customerDocumentList = []

        for (ReceivableAnticipationPartnerConfigDocument partnerConfigDocument : anticipationPartnerConfig.partnerConfigDocuments) {
            Long fileId = partnerConfigDocument.externalFileId ?: partnerConfigDocument.file.id
            FileAdapter fileAdapter = customerDocumentFileProxyService.buildFile(anticipationPartnerConfig.customer.id, fileId)
            customerDocumentList.add(new CustomerDocumentAdapter(partnerConfigDocument, fileAdapter))
        }

        return customerDocumentList
    }

    private void sendRegistrationPartnerByVortx(ReceivableAnticipationPartnerConfig anticipationPartnerConfig) {
        VortxManager vortxManager = new VortxManager()
        Map registrationResponse = vortxManager.transmitRegistrationBatch(anticipationPartnerConfig)

        if (registrationResponse.reprocessTransmit) return

        if (registrationResponse.disablePartner) {
            receivableAnticipationPartnerConfigService.setAsDisabled(anticipationPartnerConfig)
        } else if (anticipationPartnerConfig.partnerConfigDocuments) {
            Map responseMap = vortxManager.transmitAssignorDocument(anticipationPartnerConfig)
            if (responseMap.retryTransmitAssignorDocument) {
                anticipationPartnerConfig.vortxStatus = ReceivableAnticipationPartnerConfigStatus.PENDING
                anticipationPartnerConfig.partnerRegisterAttempts = anticipationPartnerConfig.partnerRegisterAttempts != null ? anticipationPartnerConfig.partnerRegisterAttempts + 1 : 1
                anticipationPartnerConfig.save(failOnError: true)
            } else {
                receivableAnticipationPartnerConfigService.setAsApproved(anticipationPartnerConfig)
            }
        } else {
            receivableAnticipationPartnerConfigService.setAsApproved(anticipationPartnerConfig)
        }
    }

    private void processWebhook(Long webhookRequestId) {
        Boolean processResult

        Utils.withNewTransactionAndRollbackOnError({
            WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)
            VortxWebhook vortxWebhook = VortxWebhookParser.parse(webhookRequest.requestBody)

            if (vortxWebhook.status == VortxWebhookStatus.IGNORED) {
                webhookRequestService.setAsIgnored(webhookRequest)
                processResult = true
                return
            }

            if (vortxWebhook.type == VortxWebhookType.ACQUISITION_BATCH) {
                List<Long> partnerAcquisitionIdList = ReceivableAnticipationPartnerAcquisition.query([column: "id", batchExternalIdentifier: vortxWebhook.externalIdentifier, partner: ReceivableAnticipationPartner.VORTX]).list()

                if (!partnerAcquisitionIdList) throw new Exception("Não foi encontrado nenhuma antecipação para este webhook")

                if (vortxWebhook.status == VortxWebhookStatus.BATCH_ERROR) {
                    reject(partnerAcquisitionIdList, vortxWebhook.errorList.toString())
                } else if (vortxWebhook.status == VortxWebhookStatus.ACQUISITION_CREDIT) {
                    credit(vortxWebhook.externalIdentifier, partnerAcquisitionIdList)
                } else {
                    throw new Exception("Situação de webhook não suportada")
                }
            } else if (vortxWebhook.type == VortxWebhookType.ACQUISITION_CONFIRM) {
                List<ReceivableAnticipationPartnerAcquisition> anticipationAcquisitionList = ReceivableAnticipationPartnerAcquisition.query([batchExternalIdentifier: vortxWebhook.externalIdentifier, partner: ReceivableAnticipationPartner.VORTX]).list()

                confirm(anticipationAcquisitionList, vortxWebhook.value)
            } else if (vortxWebhook.type.isCustomerApproval()) {
                if (!vortxWebhook.status.isCustomerRegistrationApproved()) throw new Exception("Cliente não aprovado.")
            } else {
                throw new Exception("Tipo de webhook não suportado")
            }

            webhookRequestService.setAsProcessed(webhookRequest)

            processResult = true
        }, [onError: { Exception exception -> AsaasLogger.error("Vortx - Erro no processamento de webhook - webhookRequestId: ${webhookRequestId}", exception) }])

        if (processResult) return

        Utils.withNewTransactionAndRollbackOnError({
            WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)

            webhookRequestService.setAsError(webhookRequest)
        })
    }
}
