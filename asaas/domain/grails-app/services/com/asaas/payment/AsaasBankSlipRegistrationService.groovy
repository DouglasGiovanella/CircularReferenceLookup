package com.asaas.payment

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.AsyncActionType
import com.asaas.bankslip.adapter.BankSlipPayerInfoAdapter
import com.asaas.boleto.BoletoRegistrationStatus
import com.asaas.boleto.asaas.AsaasBankSlipWriteOffType
import com.asaas.boleto.batchfile.BoletoAction
import com.asaas.boleto.batchfile.BoletoBatchFileStatus
import com.asaas.customer.CustomerBankSlipBeneficiaryStatus
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.bankslip.BankSlipOnlineRegistrationResponse
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBankSlipBeneficiary
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentConfirmRequest
import com.asaas.integration.jdnpc.utils.JdNpcUtils
import com.asaas.log.AsaasLogger
import com.asaas.status.Status
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class AsaasBankSlipRegistrationService {

    private static final Integer TOLERANCE_SECONDS_TO_UPDATE_STATUS = -3
    def asyncActionService
    def bankSlipOnlineRegistrationResponseService
    def boletoBatchFileItemService
    def customerBankSlipBeneficiaryService
    def jdNpcBankSlipManagerService
    def paymentUpdateService

    public BankSlipOnlineRegistrationResponse doRegistration(BoletoBatchFileItem boletoBatchFileItem, Boolean asyncRegistration) {
        Customer customer = boletoBatchFileItem.payment.provider
        BankSlipOnlineRegistrationResponse bankSlipOnlineRegistrationResponse

        if (customer.cpfCnpj) {
            if (asyncRegistration) {
                boletoBatchFileItem.payment.lock()
            }
            bankSlipOnlineRegistrationResponse = selectBeneficiaryAndRegister(customer, boletoBatchFileItem)
        } else {
            bankSlipOnlineRegistrationResponse = bankSlipOnlineRegistrationResponseService.save(boletoBatchFileItem.id, [errorCode: "SOAP-ENV:-660", errorMessage: "Cliente sem CPF/CNPJ", registrationStatus: BoletoRegistrationStatus.FAILED])
        }

        if (bankSlipOnlineRegistrationResponse.registrationStatus) {
            if (bankSlipOnlineRegistrationResponse.registrationStatus.failed() && keepPendingRegisterOnFailure(bankSlipOnlineRegistrationResponse.errorCode)) {
                return bankSlipOnlineRegistrationResponse
            } else {
                if (boletoBatchFileItem.action.isCreate()) boletoBatchFileItem.payment.updateRegistrationStatus(bankSlipOnlineRegistrationResponse.registrationStatus)
            }
            boletoBatchFileItemService.setItemAsSent(boletoBatchFileItem.id)
        }

        return bankSlipOnlineRegistrationResponse
    }

    public void processBankSlipWaitingRegisterConfirmation() {
        if (JdNpcUtils.isUnavailableApiSchedule()) return

        final Integer pendingAsaasBankSlipLimit = 1500

        List<Long> asyncActionIdList = AsyncAction.oldestPending([column: "id", "type": AsyncActionType.ASAAS_BANKSLIP_WAITING_REGISTER_CONFIRMATION, "dateCreated[lt]": CustomDateUtils.sumSeconds(new Date(), AsaasBankSlipRegistrationService.TOLERANCE_SECONDS_TO_UPDATE_STATUS)]).list(max: pendingAsaasBankSlipLimit)

        ThreadUtils.processWithThreadsOnDemand(asyncActionIdList, 100, { List<Long> idList ->
            processBankSlipRegisterStatusUpdateIdList(idList)
        })
    }

    public void processBankSlipWaitingRegisterRemovalConfirmation() {
        if (JdNpcUtils.isUnavailableApiSchedule()) return

        final Integer pendingAsaasBankSlipRegisterRemovalLimit = 800

        List<Long> asyncActionIdList = AsyncAction.oldestPending([column: "id", "type": AsyncActionType.ASAAS_BANKSLIP_WAITING_REGISTER_REMOVAL_CONFIRMATION, "dateCreated[lt]": CustomDateUtils.sumSeconds(new Date(), AsaasBankSlipRegistrationService.TOLERANCE_SECONDS_TO_UPDATE_STATUS)]).list(max: pendingAsaasBankSlipRegisterRemovalLimit)

        ThreadUtils.processWithThreadsOnDemand(asyncActionIdList, 100, { List<Long> idList ->
            for (Long asyncActionId in idList) {
                Boolean hasError = false
                Map asyncActionData = AsyncAction.get(asyncActionId).getDataAsMap()

                Utils.withNewTransactionAndRollbackOnError({
                    BoletoBatchFileItem boletoBatchFileItem = BoletoBatchFileItem.get(asyncActionData.boletoBatchFileItemId)

                    updateBankSlipRegisterRemovalStatus(boletoBatchFileItem, asyncActionData.barCode, asyncActionData.externalIdentifier, asyncActionData.asyncActionId)
                }, [logErrorMessage: "AsaasBankSlipRegistrationService.processBankSlipWaitingRegisterRemovalConfirmation() -> Erro ao processar AsyncAction [${asyncActionData.asyncActionId}]", onError: { hasError = true }])

                if (hasError) asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
            }
        })
    }

    public void processPendingCancellations() {
        if (JdNpcUtils.isUnavailableApiSchedule()) return

        final Integer flushEvery = 100
        final Integer itemPerThread = 320
        final Integer maxItems = 4800

        List<Long> pendingItemIdList = BoletoBatchFileItem.pendingDeleteIdsForOnlineRegistration(["boletoBankId": Payment.ASAAS_ONLINE_BOLETO_BANK_ID]).list(max: maxItems)

        if (!pendingItemIdList) return

        AsaasLogger.info("AsaasBankSlipRegistrationService.processPendingCancellations >> Cancelando ${pendingItemIdList.size()} registros de boleto.")

        ThreadUtils.processWithThreadsOnDemand(pendingItemIdList, itemPerThread, false,{ List<Long> idList ->
            Utils.forEachWithFlushSession(idList, flushEvery, { Long pendingItemId ->
                Utils.withNewTransactionAndRollbackOnError({
                    BoletoBatchFileItem pendingDeleteItem = BoletoBatchFileItem.get(pendingItemId)
                    processPendingDeleteItem(pendingDeleteItem)
                }, [logErrorMessage: "AsaasBankSlipRegistrationService.processPendingCancellations >>> Falha ao processar baixa por cancelamento para BoletoBatchFileItem: [${pendingItemId}]"])
            })
        })
    }

    public BankSlipPayerInfoAdapter getPayerInfo(String nossoNumero, Date paymentDate) {
        List<BankSlipPayerInfoAdapter> bankSlipPayerInfoAdapterList =  jdNpcBankSlipManagerService.getBankSlipPayerInfo(nossoNumero, paymentDate)

        return bankSlipPayerInfoAdapterList ? bankSlipPayerInfoAdapterList.last() : null
    }

    private void processBankSlipRegisterStatusUpdateIdList(List<Long> idList) {
        for (Long asyncActionId in idList) {
            Map asyncActionDataMap = AsyncAction.get(asyncActionId).getDataAsMap()

            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                BoletoBatchFileItem boletoBatchFileItem = BoletoBatchFileItem.get(asyncActionDataMap.boletoBatchFileItemId)
                updateBankSlipRegistrationStatus(boletoBatchFileItem, asyncActionDataMap.barCode, asyncActionDataMap.digitableLine, asyncActionDataMap.asyncActionId)
            }, [
                ignoreStackTrace: true,
                onError: { Exception exception ->
                if (Utils.isLock(exception)) return

                AsaasLogger.error("AsaasBankSlipRegistrationService.processBankSlipWaitingRegisterConfirmation() >>> Erro ao processar AsyncAction [${asyncActionDataMap.asyncActionId}]", exception)
                hasError = true
            }])

            if (hasError) asyncActionService.setAsErrorWithNewTransaction(asyncActionDataMap.asyncActionId)
        }
    }

    private BankSlipOnlineRegistrationResponse updateBankSlipRegistrationStatus(BoletoBatchFileItem boletoBatchFileItem, String barCode, String digitableLine, Long asyncActionId) {
        Map requestStatusResponseMap = jdNpcBankSlipManagerService.getBankSlipRegistrationStatusResponseMap(barCode, digitableLine)

        BankSlipOnlineRegistrationResponse bankSlipOnlineRegistrationResponse = bankSlipOnlineRegistrationResponseService.save(boletoBatchFileItem.id, requestStatusResponseMap)

        if (!bankSlipOnlineRegistrationResponse.registrationStatus || bankSlipOnlineRegistrationResponse.registrationStatus.waitingRegistration()) {
            doAsyncActionWaitingProcess(asyncActionId, boletoBatchFileItem.id, barCode, bankSlipOnlineRegistrationResponse.linhaDigitavel, null, AsyncActionType.ASAAS_BANKSLIP_WAITING_REGISTER_CONFIRMATION)
        } else {
            if (bankSlipOnlineRegistrationResponse.registrationStatus.failed()) {
                AsaasLogger.warn("AsaasBankSlipRegistrationService.updateBankSlipRegistrationStatus >>> Erro ao atualizar status de registro: [boletoBatchFileItemId: ${boletoBatchFileItem.id}, ErrorCode: ${bankSlipOnlineRegistrationResponse.errorCode}, ErrorMessage: ${bankSlipOnlineRegistrationResponse.errorMessage}, responseMap: ${requestStatusResponseMap}] ")
            }
            if (asyncActionId) asyncActionService.delete(asyncActionId)
        }

        boletoBatchFileItem.payment.updateRegistrationStatus(bankSlipOnlineRegistrationResponse.registrationStatus)

        if (bankSlipOnlineRegistrationResponse.registrationStatus.successful()) {
            updatePendingBoletoBatchFileItemUpdate(boletoBatchFileItem)
        } else if (bankSlipOnlineRegistrationResponse.registrationStatus.failed() && requestStatusResponseMap.mustCreateNewRegister) {
            AsaasLogger.warn("AsaasBankSlipRegistrationService.updateBankSlipRegistrationStatus >>> Fallback de erro de registro não atualizavel: [boletoBatchFileItemId: ${boletoBatchFileItem.id}, responseMap: ${requestStatusResponseMap}] ")
            paymentUpdateService.deleteAndRegisterNewBoleto(boletoBatchFileItem.payment)
        }

        return bankSlipOnlineRegistrationResponse
    }

    private void updatePendingBoletoBatchFileItemUpdate(BoletoBatchFileItem boletoBatchFileItem) {
        try {
            BoletoBatchFileItem updatedItem = BoletoBatchFileItem.query(payment: boletoBatchFileItem.payment, status: BoletoBatchFileStatus.PENDING, action: BoletoAction.UPDATE, readyForTransmission: false, sort: "id", order: "desc").get()

            if (updatedItem) {
                try {
                    updatedItem.readyForTransmission = true
                    updatedItem.save(failOnError: true)
                } catch (Exception exception) {
                    AsaasLogger.error("AsaasBankSlipRegistrationService.updateBankSlipRegistrationStatus >>> Erro ao atualizar atualização pendente com readyForTransmission = true. [id: ${updatedItem.id}]", exception)
                }

                cancelPendingObsoleteUpdateItems(updatedItem)
            }
        } catch (Exception exception) {
            if (Utils.isLock(exception)) return

            AsaasLogger.error("AsaasBankSlipRegistrationService.updateBankSlipRegistrationStatus >>> Erro ao atualizar status de registro [boletoBatchFileItemId: ${boletoBatchFileItem.id}]", exception)
            throw exception
        }
    }

    private BankSlipOnlineRegistrationResponse updateBankSlipRegisterRemovalStatus(BoletoBatchFileItem boletoBatchFileItem, String barCode, String externalIdentifier, Long asyncActionId) {
        Map requestStatusResponseMap = jdNpcBankSlipManagerService.getBankSlipWriteOffStatusResponseMap(barCode, externalIdentifier)

        BankSlipOnlineRegistrationResponse bankSlipOnlineRegistrationResponse = bankSlipOnlineRegistrationResponseService.save(boletoBatchFileItem.id, requestStatusResponseMap)

        if (bankSlipOnlineRegistrationResponse?.registrationStatus?.waitingRegistration()) {
            doAsyncActionWaitingProcess(asyncActionId, boletoBatchFileItem.id, barCode, null, externalIdentifier, AsyncActionType.ASAAS_BANKSLIP_WAITING_REGISTER_REMOVAL_CONFIRMATION)
        } else {
            if (!bankSlipOnlineRegistrationResponse.registrationStatus || bankSlipOnlineRegistrationResponse.registrationStatus.failed()) {
                AsaasLogger.warn("AsaasBankSlipRegistrationService.updateBankSlipRegisterRemovalStatus >>> Erro ao realizar baixa efetiva. [externalIdentifier: ${externalIdentifier}, boletoBatchFileItemId: ${boletoBatchFileItem.id}, barCode: ${barCode}, errorCode: ${bankSlipOnlineRegistrationResponse.errorCode}, errorMessage: ${bankSlipOnlineRegistrationResponse.errorMessage}]")
            }
            if (asyncActionId) asyncActionService.delete(asyncActionId)
        }

        boletoBatchFileItemService.setItemAsSent(boletoBatchFileItem.id)

        return bankSlipOnlineRegistrationResponse
    }

    private void processPendingDeleteItem(BoletoBatchFileItem pendingDeleteItem) {
        if (JdNpcUtils.isUnavailableApiSchedule()) return

        BoletoBatchFileItem lastSentBoletoBatchFileItem = BoletoBatchFileItem.query([paymentId: pendingDeleteItem.paymentId,
                                                                                     "action[in]": [BoletoAction.CREATE, BoletoAction.UPDATE],
                                                                                     status: BoletoBatchFileStatus.SENT,
                                                                                     nossoNumero: pendingDeleteItem.nossoNumero,
                                                                                     sort: "id",
                                                                                     order: "desc"]).get()
        if (!lastSentBoletoBatchFileItem) {
            boletoBatchFileItemService.setItemAsCancelled(pendingDeleteItem)
            return
        }

        if (mustCancelDeletedItem(pendingDeleteItem.payment)) {
            boletoBatchFileItemService.setItemAsCancelled(pendingDeleteItem)
            return
        }

        BankSlipOnlineRegistrationResponse originOnlineRegistrationResponse = BankSlipOnlineRegistrationResponse.query([boletoBatchFileItemId: lastSentBoletoBatchFileItem.id, sort: "id", order: "desc"]).get()
        if (!originOnlineRegistrationResponse) {
            boletoBatchFileItemService.setItemAsCancelled(pendingDeleteItem)
            return
        }

        if (!originOnlineRegistrationResponse.externalIdentifier) {
            Map referenceMap = jdNpcBankSlipManagerService.getRegisterReferences(originOnlineRegistrationResponse.barCode)
            if (!referenceMap.externalIdentifier) {
                AsaasLogger.error("AsaasBankSlipRegistrationService.processPendingDeleteItem >> Falha ao obter referência de registro. Sem retorno [BoletoBatchFileItem: ${pendingDeleteItem.id}, barCode: ${originOnlineRegistrationResponse.barCode}]")
                boletoBatchFileItemService.setItemAsSent(pendingDeleteItem.id)
                return
            }

            originOnlineRegistrationResponse.externalIdentifier = referenceMap.externalIdentifier
            originOnlineRegistrationResponse.registrationReferenceId = referenceMap.registrationReferenceId
            originOnlineRegistrationResponse.save(failOnError: true)
        }

        Map onlineRegisterRemovalResponseMap = jdNpcBankSlipManagerService.doWriteOffRequest(originOnlineRegistrationResponse.externalIdentifier, originOnlineRegistrationResponse.barCode, getWriteOffType(lastSentBoletoBatchFileItem.payment), [:])

        BankSlipOnlineRegistrationResponse onlineRegisterRemovalResponse = bankSlipOnlineRegistrationResponseService.save(pendingDeleteItem.id, onlineRegisterRemovalResponseMap)

        if (!onlineRegisterRemovalResponse.registrationStatus) {
            AsaasLogger.warn("AsaasBankSlipRegistrationService.processPendingDeleteItem >> Erro ao realizar baixa efetiva. Sem retorno : BoletoBatchFileItem [${pendingDeleteItem.id}] - Error code [${onlineRegisterRemovalResponse?.errorCode}]")
            return
        }

        if (onlineRegisterRemovalResponse.registrationStatus.failed()) {
            AsaasLogger.warn("AsaasBankSlipRegistrationService.processPendingDeleteItem >> Failed cancellation [boletoBatchFileItemId: ${pendingDeleteItem.id}, responseMap: ${onlineRegisterRemovalResponseMap}]")
        } else {
            onlineRegisterRemovalResponse = updateBankSlipRegisterRemovalStatus(pendingDeleteItem, originOnlineRegistrationResponse.barCode, originOnlineRegistrationResponse.externalIdentifier, null)
        }

        boletoBatchFileItemService.setItemAsSent(pendingDeleteItem.id)
    }

    private Boolean keepPendingRegisterOnFailure(String errorCode) {
        /**
         * EDDA0770 - Cliente Beneficiário Final inapto para o Participante Destinatário Administrado Requisitante da alteração do Boleto de Pagamento
         * EDDA0873 - Sacador avalista inapto para o participante destinatário administrado requisitante da inclusão do boleto de pagamento
         * EDDA0433 - Cliente beneficiário original inapto para o participante destinatário administrado requisitante da inclusão do boleto de pagamento
         * EDDA0429 -CNPJ ou CPF do Cliente Beneficiário Final é inválido
         * SOAP-ENV:-660 - CNPJ ou CPF não informado
         * SOAP-ENV:-722 - Situação do Título não Permite Alterações
         * EDDA0412 - Identificador do sacador avalista é obrigatório para o tipo identificação do sacador avalista informado
         */
        if (["EDDA0770", "EDDA0873", "EDDA0433", "EDDA0429", "SOAP-ENV:-660", "SOAP-ENV:-722", "EDDA0412", "SOAP-ENV:-385"].contains(errorCode))  return false

        return true
    }

    private Boolean mustCancelDeletedItem(Payment payment) {
        if (payment.isReceivedInCash()) return false

        if (!payment.billingType.isBoleto()) return false

        if (!payment.isConfirmedOrReceived()) return false

        Boolean isPaidInternally = PaymentConfirmRequest.query([exists: true, payment: payment, paidInternally: true, status: Status.SUCCESS]).get().asBoolean()
        if (isPaidInternally) return false

        return true
    }

    private BankSlipOnlineRegistrationResponse selectBeneficiaryAndRegister(Customer customer, BoletoBatchFileItem boletoBatchFileItem) {
        Boolean hasApprovedBeneficiary = CustomerBankSlipBeneficiary.query([customerId: customer.id, boletoBankId: Payment.ASAAS_ONLINE_BOLETO_BANK_ID, "status": CustomerBankSlipBeneficiaryStatus.APPROVED, exists: true]).get().asBoolean()
        Boolean useAsaasAsBeneficiary = false

        if (!hasApprovedBeneficiary) {
            customerBankSlipBeneficiaryService.createAsyncBeneficiaryRegistrationIfNecessary(Payment.ASAAS_ONLINE_BOLETO_BANK_ID, customer)
            useAsaasAsBeneficiary = true
        }

        Map registerResponseMap = jdNpcBankSlipManagerService.doRegistrationRequest(boletoBatchFileItem, useAsaasAsBeneficiary)
        if (registerResponseMap.registrationStatus) {
            boletoBatchFileItem.payment.updateRegistrationStatus(registerResponseMap.registrationStatus)
            if (registerResponseMap.registrationStatus.waitingRegistration())  asyncActionService.saveAsaasBankSlipWaitingRegisterConfirmation(boletoBatchFileItem.id, registerResponseMap.barCode, registerResponseMap.digitableLine)
        }

        return bankSlipOnlineRegistrationResponseService.save(boletoBatchFileItem.id, registerResponseMap)
    }

    private void doAsyncActionWaitingProcess(Long asyncActionId, Long boletoBatchFileItemId, String barCode, String linhaDigitavel, String externalIdentifier, AsyncActionType asyncActionType) {
        if (asyncActionId) {
            Map asyncActionData = AsyncAction.read(asyncActionId).getDataAsMap()

            AsyncActionStatus asyncActionStatus = asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId).status
            if (asyncActionStatus.isCancelled() && asyncActionData.attempt > asyncActionType.maxAttempts) {
                AsaasLogger.warn("AsaasBankSlipRegistrationService.registerAsyncActionProcessWhenWaiting >>> Processamento assíncrono foi cancelado por ultrapassar o máximo de tentativas. [externalIdentifier: ${externalIdentifier}, boletoBatchFileItemId: ${boletoBatchFileItemId}, asyncActionId: ${asyncActionId}]")
            }
        } else {
            if (asyncActionType == AsyncActionType.ASAAS_BANKSLIP_WAITING_REGISTER_CONFIRMATION) {
                asyncActionService.saveAsaasBankSlipWaitingRegisterConfirmation(boletoBatchFileItemId, barCode, linhaDigitavel)
            } else {
                asyncActionService.saveAsaasBankSlipWaitingRegisterRemovalConfirmation(boletoBatchFileItemId, barCode, externalIdentifier)
            }
        }
    }

    private void cancelPendingObsoleteUpdateItems(BoletoBatchFileItem lastUpdatedItem) {
        List<BoletoBatchFileItem> obsoleteUpdateItemList = BoletoBatchFileItem.query(payment: lastUpdatedItem.payment, status: BoletoBatchFileStatus.PENDING, action: BoletoAction.UPDATE, readyForTransmission: false, "id[le]":  lastUpdatedItem.id).list()

        for (BoletoBatchFileItem boletoBatchFileItem : obsoleteUpdateItemList) {
            boletoBatchFileItemService.setItemAsCancelled(boletoBatchFileItem)
        }
    }

    private AsaasBankSlipWriteOffType getWriteOffType(Payment payment) {
        if (payment.status.isReceived()) {
            return AsaasBankSlipWriteOffType.INTEGRAL_BY_RECIPIENT_INSTITUTION
        }

        if (payment.status.isReceivedInCash()) {
            return AsaasBankSlipWriteOffType.INTEGRAL_BY_RECIPIENT_INSTITUTION
        }

        return AsaasBankSlipWriteOffType.TRASFEROR_REQUEST
    }
}
