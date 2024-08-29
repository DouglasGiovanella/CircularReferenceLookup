package com.asaas.service.boleto

import com.asaas.asyncaction.AsyncActionType
import com.asaas.bankslip.worker.registration.PaymentBankSlipRegistrationWorkerConfigVO
import com.asaas.bankslip.worker.registration.PaymentBankSlipRegistrationWorkerItemVO
import com.asaas.boleto.BoletoRegistrationStatus
import com.asaas.boleto.batchfile.BoletoAction
import com.asaas.boleto.batchfile.BoletoBatchFileStatus
import com.asaas.boleto.RegisteredBoletoValidator
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.bankslip.PaymentBankSlipConfig
import com.asaas.domain.bankslip.PaymentBankSlipInfo
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.boleto.BoletoBatchFile
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.boleto.returnfile.BoletoReturnStatus
import com.asaas.domain.boleto.BoletoReturnFileItem
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class BoletoBatchFileItemService {

    def asyncActionService

    public BoletoBatchFileItem create(Payment payment) {
        if (BoletoBatchFileItem.existsItemNotSent(payment, BoletoAction.CREATE)) return

        BoletoBatchFileItem lastCreationItem = BoletoBatchFileItem.getLastCreationItem(payment)

        BoletoBatchFileItem boletoBatchFileItem = new BoletoBatchFileItem()
        boletoBatchFileItem.payment = payment
        boletoBatchFileItem.action = BoletoAction.CREATE
        boletoBatchFileItem.nossoNumero = payment.getCurrentBankSlipNossoNumero()
        boletoBatchFileItem.itemSequence = (lastCreationItem && lastCreationItem.itemSequence) ? lastCreationItem.itemSequence + 1 : 1
        boletoBatchFileItem.readyForTransmission = readyForTransmission(boletoBatchFileItem)
        boletoBatchFileItem.boletoBankId = payment.boletoBank.id
        boletoBatchFileItem.dueDate = payment.dueDate

        boletoBatchFileItem.save(failOnError: true)

        setPaymentRegistrationStatusOnCreate(payment)

        return boletoBatchFileItem
    }

    public void delete(Payment payment) {
        setPaymentRegistrationStatusOnDelete(payment)
        saveDeleteItem(payment, payment.nossoNumero)
    }

    public void delete(PaymentBankSlipInfo bankSlipInfo) {
        saveDeleteItem(bankSlipInfo.payment, bankSlipInfo.nossoNumero)
    }

    public void update(Payment payment) {
        saveUpdatedItem(payment, payment.nossoNumero)
    }

    public void setPaymentRegistrationStatusOnCreate(Payment payment) {
        if (payment.registrationStatus == BoletoRegistrationStatus.SUCCESSFUL) return

        payment.updateRegistrationStatus(BoletoRegistrationStatus.WAITING_REGISTRATION)
    }

    public void setPaymentRegistrationStatusOnDelete(Payment payment) {
        if (!BoletoBatchFileItem.sentCreateItem([payment: payment]).get()) {
            payment.registrationStatus = BoletoRegistrationStatus.NOT_REGISTERED
            if (!payment.avoidExplicitSave) payment.save(failOnError: true)
        } else {
            BoletoBatchFileItem lastSentCreateItem = BoletoBatchFileItem.sentCreateItem([payment: payment, nossoNumero: payment.nossoNumero, sort: "id", order: "desc"]).get()
            if (!lastSentCreateItem) return

            BoletoBatchFileItem lastSentDeleteItem = BoletoBatchFileItem.sentDeleteItem([payment: payment, nossoNumero: payment.nossoNumero, sort: "id", order: "desc"]).get()
            if (!lastSentDeleteItem) return

            if (lastSentDeleteItem.itemSequence >= lastSentCreateItem.itemSequence) {
                payment.registrationStatus = BoletoRegistrationStatus.NOT_REGISTERED
                if (!payment.avoidExplicitSave) payment.save(failOnError: true)
            }
        }
    }

	public void setItemsAsSent(BoletoBatchFile boletoBatchFile, List<BoletoBatchFileItem> boletoList) {
        if (boletoList.size() == 0) return

        BoletoBatchFileItem.executeUpdate("update BoletoBatchFileItem set status = :sent, boletoBatchFile = :boletoBatchFile, version = version + 1, lastUpdated = :now where id in (:boletoList)", [sent: BoletoBatchFileStatus.SENT, boletoBatchFile: boletoBatchFile, boletoList: boletoList.id, now: new Date()])
    }

    public void setItemAsSent(Long boletoBatchFileItemId) {
        BoletoBatchFileItem.executeUpdate("update BoletoBatchFileItem set status = :sent, version = version + 1, lastUpdated = :now where id = :id", [sent: BoletoBatchFileStatus.SENT, now: new Date(), id: boletoBatchFileItemId])
    }

    public void setItemAsSent(BoletoBatchFileItem boletoBatchFileItem) {
        boletoBatchFileItem.status = BoletoBatchFileStatus.SENT
        boletoBatchFileItem.save(failOnError: true)
    }

    public void deleteItems(List<BoletoBatchFileItem> boletoList) {
        BoletoBatchFileItem.executeUpdate("update BoletoBatchFileItem set deleted = true, version = version + 1, lastUpdated = :now where id in (:boletoList)", [boletoList: boletoList.id, now: new Date()])
    }

    public void removeItemNotSentIfExists(String nossoNumero, BoletoBank boletoBank) {
        BoletoBatchFileItem boletoBatchFileItem = BoletoBatchFileItem.findNotSentItem(nossoNumero, boletoBank)

        if (!boletoBatchFileItem) return

        removeItem(boletoBatchFileItem)
    }

    public void proccessInvalidItems(List<BoletoBatchFileItem> invalidItems) {
        if (!invalidItems) return

        deleteItems(invalidItems)

        for (BoletoBatchFileItem item : invalidItems) {
            item.payment.updateRegistrationStatus(BoletoRegistrationStatus.NOT_REGISTERED)
        }
    }

    public void deleteRegistrationIfRegistered(Installment installment) {
        for (Payment payment in installment.payments) {
            deleteRegistrationIfRegistered(payment)
        }
    }

    public void deleteRegistrationIfRegistered(Payment payment) {
		try {
			if (payment.registrationStatus in [BoletoRegistrationStatus.SUCCESSFUL, BoletoRegistrationStatus.WAITING_REGISTRATION]) {
                delete(payment)
            }
		} catch (Exception exception) {
            AsaasLogger.error("BoletoBatchFileItemService.deleteRegistrationIfRegistered >> Erro ao deletar registro de pagamento. [paymentId: ${payment.id}]", exception)
		}
	}

    public void deleteNotSentItemsOfFailedRegistration(Payment payment) {
        if (payment.registrationStatus != BoletoRegistrationStatus.FAILED) {
            return
        }

        List<BoletoBatchFileItem> notSentItemsOfFailedRegistration = BoletoBatchFileItem.findNotSentUpdateOrDeleteItems(payment)
        if (notSentItemsOfFailedRegistration.size() == 0) {
            return
        }

        deleteItems(notSentItemsOfFailedRegistration)
    }

    public void verifyCreateItemsWithoutReturn(BoletoBank boletoBank, Date boletoBatchFileDateCreated) {
		Boolean existsCreatedItensWithoutReturn = BoletoBatchFileItem.sentCreateItemsWithoutReturn([exists: true, "boletoBatchFile.boletoBank": boletoBank, "boletoBatchFile.dateCreated": boletoBatchFileDateCreated]).get().asBoolean()

        if (existsCreatedItensWithoutReturn) AsaasLogger.error("BoletoBatchFileItemService.verifyCreateItemsWithoutReturn : Existe item de registro de boleto criado sem retorno para o banco ${boletoBank.id} na data ${boletoBatchFileDateCreated}")
	}

    public void setPendingItemToReadyForTransmission(Payment payment) {
        BoletoBatchFileItem notSentCreateItem = BoletoBatchFileItem.itemNotSent(payment, BoletoAction.CREATE).get()

        if (!notSentCreateItem) return

        notSentCreateItem.dueDate = payment.dueDate
        notSentCreateItem.readyForTransmission = readyForTransmission(notSentCreateItem)
        notSentCreateItem.save(flush: true, failOnError: false)
    }

    public void updatePendingItemsToReadyForTransmission() {
        for (BoletoBank boletoBank in BoletoBank.query([enabled: true]).list()) {
            AsaasLogger.info("BoletoBatchFileItemService.updatePendingItemsToReadyForTransmission : BoletoBank [${boletoBank.id}] : iniciando atualização")

            List<Long> pendingItemIds

            pendingItemIds = BoletoBatchFileItem.listPendingCreateIdsNotReadyForTransmission(boletoBank, false)
            updateReadyForTransmission(pendingItemIds)

            pendingItemIds = BoletoBatchFileItem.listPendingCreateIdsNotReadyForTransmission(boletoBank, true)
            updateReadyForTransmission(pendingItemIds)

            pendingItemIds = BoletoBatchFileItem.listPendingCreateIdsNotReadyForTransmissionUsingCustomerParameter(boletoBank, pendingItemIds)
            updateReadyForTransmission(pendingItemIds)

            if (boletoBank.bank.code in [SupportedBank.BANCO_DO_BRASIL.code(), SupportedBank.SANTANDER.code()] && boletoBank.onlineRegistrationEnabled) {
                pendingItemIds = BoletoBatchFileItem.pendingDeleteWithoutCreateSentToday([column: "id", "payment.boletoBank": boletoBank, readyForTransmission: false]).list()
                updateReadyForTransmission(pendingItemIds)
            } else {
                pendingItemIds = BoletoBatchFileItem.listPendingDeleteIdsNotReadyForTransmission(boletoBank)
                updateReadyForTransmission(pendingItemIds)
            }
        }
    }

    public void processPaymentsWithConfigurationToDeleteToday() {
        List<PaymentBankSlipConfig> paymentBankSlipConfigList = PaymentBankSlipConfig.query([daysToAutomaticRegistrationCancellation: 0, dueDate: new Date().clearTime()]).list()

        for (PaymentBankSlipConfig paymentBankSlipConfig in paymentBankSlipConfigList) {
            delete(paymentBankSlipConfig.payment)
        }
    }

    public List<Long> listPendingValidCreateIdsForOnlineRegistration(Long boletoBankId, Integer pendingItemsLimit, List<Long> idListOnProcessing) {
        Map searchMap = [boletoBankId: boletoBankId]
        if (idListOnProcessing) searchMap."id[notIn]" = idListOnProcessing

        List<BoletoBatchFileItem> pendingItems = BoletoBatchFileItem.pendingCreateForOnlineRegistration(searchMap).list([max: pendingItemsLimit])

        List<Long> validPendingItemIds = []
        List<BoletoBatchFileItem> invalidPendingItems = []

        for (BoletoBatchFileItem pendingItem in pendingItems) {
            if (RegisteredBoletoValidator.validate(pendingItem.payment)) {
                validPendingItemIds.add(pendingItem.id)
            } else {
                invalidPendingItems.add(pendingItem)
            }
        }

        proccessInvalidItems(invalidPendingItems)

        return validPendingItemIds
    }

    public List<PaymentBankSlipRegistrationWorkerItemVO> listPendingValidCreateIdsForOnlineRegistrationWithWorker(PaymentBankSlipRegistrationWorkerConfigVO paymentBankSlipRegistrationWorkerConfigVO, Long boletoBankId, Integer pendingItemsLimit, List<Long> idListOnProcessing) {
        List<Long> pendingItemIds = listPendingValidCreateIdsForOnlineRegistration(boletoBankId, pendingItemsLimit, idListOnProcessing)

        List<PaymentBankSlipRegistrationWorkerItemVO> itemList = []
        pendingItemIds.collate(paymentBankSlipRegistrationWorkerConfigVO.maxItemsPerThread).each { itemList.add(new PaymentBankSlipRegistrationWorkerItemVO(it)) }

        return itemList
    }

    public List<Long> listPendingValidUpdateIdsForOnlineRegistration(BoletoBank boletoBank, Integer pendingItemsLimit) {
        final Integer toleranceSecondsForUpdateProcess = -30
        Map searchMap = [boletoBankId: boletoBank.id, "dateCreated[lt]": CustomDateUtils.sumSeconds(new Date(), toleranceSecondsForUpdateProcess)]

        List<BoletoBatchFileItem> pendingItems = BoletoBatchFileItem.pendingUpdateForOnlineRegistration(searchMap).list([max: pendingItemsLimit])

        List<Long> validPendingItemIds = []
        List<BoletoBatchFileItem> invalidPendingItems = []

        for (BoletoBatchFileItem pendingItem in pendingItems) {
            if (RegisteredBoletoValidator.validate(pendingItem.payment)) {
                validPendingItemIds.add(pendingItem.id)
            } else {
                invalidPendingItems.add(pendingItem)
            }
        }

        proccessInvalidItems(invalidPendingItems)

        return validPendingItemIds
    }

    public BoletoBatchFileItem saveRegistrationInfo(BoletoBatchFileItem boletoBatchFileItem, Map registrationInfo) {
        boletoBatchFileItem.value = registrationInfo.value
        boletoBatchFileItem.dueDate = registrationInfo.dueDate
        boletoBatchFileItem.cpfCnpj = registrationInfo.cpfCnpj
        boletoBatchFileItem.fineValue = registrationInfo.fineValue
        boletoBatchFileItem.finePercentage = registrationInfo.finePercentage
        boletoBatchFileItem.monthlyInterestPercentage = registrationInfo.monthlyInterestPercentage
        boletoBatchFileItem.discountValue = registrationInfo.discountValue
        boletoBatchFileItem.discountPercentage = registrationInfo.discountPercentage
        boletoBatchFileItem.discountLimitDate = registrationInfo.discountLimitDate

        return boletoBatchFileItem.save(failOnError: true, validate: false, deepValidate: false)
    }

    private void removeItemNotSent(Payment payment, BoletoAction action) {
        BoletoBatchFileItem boletoBatchFileItem = BoletoBatchFileItem.itemNotSent(payment, action).get()

        if (!boletoBatchFileItem) throw new Exception("BoletoBatchFileItem not found with Payment ${payment.id} and action ${action} on remove")

        removeItem(boletoBatchFileItem)
    }

    public void removeItem(BoletoBatchFileItem boletoBatchFileItem) {
        boletoBatchFileItem.deleted = true
        boletoBatchFileItem.save(flush: true, failOnError: true)
    }

    private Boolean readyForTransmission(BoletoBatchFileItem boletoBatchFileItem) {
        Calendar limitDate = CustomDateUtils.getInstanceOfCalendar()

        if (boletoBatchFileItem.payment.installment) {
            limitDate.add(Calendar.MONTH, BoletoBatchFileItem.DUEDATE_LIMIT_MONTHS_FOR_INSTALLMENT_REGISTRATION)
        } else {
            limitDate.add(Calendar.DAY_OF_MONTH, BoletoBatchFileItem.DUEDATE_LIMIT_DAYS_FOR_REGISTRATION)
        }

        if (boletoBatchFileItem.payment.getCurrentBankSlipDueDate() <= limitDate.getTime()) return true

        Integer customerMonthLimitForBankSlipRegistration = Utils.toInteger(CustomerParameter.getNumericValue(boletoBatchFileItem.payment.provider, CustomerParameterName.MONTH_LIMIT_FOR_BANK_SLIP_REGISTRATION))
        if (customerMonthLimitForBankSlipRegistration) {
            limitDate = CustomDateUtils.getInstanceOfCalendar()
            limitDate.add(Calendar.MONTH, customerMonthLimitForBankSlipRegistration)
            return boletoBatchFileItem.payment.getCurrentBankSlipDueDate() <= limitDate.getTime()
        }

        return false
    }

    private void saveDeleteItem(Payment payment, String nossoNumero) {
        if (BoletoBatchFileItem.existsDeletedCreateItem(payment, nossoNumero)) return

        if (BoletoBatchFileItem.existsItemByAction(payment, nossoNumero, BoletoAction.DELETE)) return

        if (BoletoReturnFileItem.existsPaidReturnItem(nossoNumero, payment.boletoBank)) return

        if (BoletoReturnFileItem.existsReturnItem(nossoNumero, payment.boletoBank, BoletoReturnStatus.DELETE_SUCCESSFUL)) return

        Boolean existsPendingItems = BoletoBatchFileItem.query([exists: true, payment: payment, "action[in]": [BoletoAction.CREATE, BoletoAction.UPDATE], status: BoletoBatchFileStatus.PENDING]).get().asBoolean()
        if (existsPendingItems) {
            Map asyncActionData = [paymentId: payment.id]
            AsyncActionType asyncActionType = AsyncActionType.DELETE_BOLETO_BATCH_FILE_ITEM

            if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return
            asyncActionService.save(asyncActionType, asyncActionData)
            return
        }

        BoletoBatchFileItem lastCreationItem = BoletoBatchFileItem.getLastCreationItem(payment, nossoNumero)

        if (!lastCreationItem) return

        BoletoBatchFileItem boletoBatchFileItem = new BoletoBatchFileItem()
        boletoBatchFileItem.payment = payment
        boletoBatchFileItem.action = BoletoAction.DELETE
        boletoBatchFileItem.nossoNumero = nossoNumero
        boletoBatchFileItem.itemSequence = lastCreationItem.itemSequence
        boletoBatchFileItem.readyForTransmission = true
        boletoBatchFileItem.boletoBankId = lastCreationItem.boletoBankId ?: payment.boletoBank.id
        boletoBatchFileItem.dueDate = payment.dueDate

        if (boletoBatchFileItem.boletoBankId == Payment.BB_BOLETO_BANK_ID && lastCreationItem.lastUpdated.clone().clearTime() == new Date().clearTime()) {
            boletoBatchFileItem.readyForTransmission = false
        }

        boletoBatchFileItem.save(failOnError: true)
    }

    private void saveUpdatedItem(Payment payment, String nossoNumero) {
        if (BoletoBatchFileItem.existsDeletedCreateItem(payment, nossoNumero)) return

        if (BoletoBatchFileItem.existsItemByAction(payment, nossoNumero, BoletoAction.DELETE)) return

        if (BoletoReturnFileItem.existsPaidReturnItem(nossoNumero, payment.boletoBank)) return

        if (!BoletoBatchFileItem.existsItemByAction(payment, nossoNumero, BoletoAction.CREATE)) return

        Boolean readyForTransmission = true

        Map updatedSearchParams = [payment: payment, "action[in]": [BoletoAction.CREATE, BoletoAction.UPDATE], sort: "id", order: "desc"]
        BoletoBatchFileItem lastItem = BoletoBatchFileItem.query(updatedSearchParams).get()
        if (lastItem?.status == BoletoBatchFileStatus.PENDING) readyForTransmission = false

        Map lastSentItemInfoMap = BoletoBatchFileItem.query([columnList: ["id", "itemSequence"], payment: payment, "action[in]": [BoletoAction.CREATE, BoletoAction.UPDATE], status: BoletoBatchFileStatus.SENT, sort: "id", order: "desc"]).get()

        BoletoBatchFileItem boletoBatchFileItem = new BoletoBatchFileItem()
        boletoBatchFileItem.itemSequence = lastSentItemInfoMap?.itemSequence
        boletoBatchFileItem.payment = payment
        boletoBatchFileItem.action = BoletoAction.UPDATE
        boletoBatchFileItem.nossoNumero = nossoNumero
        boletoBatchFileItem.readyForTransmission = readyForTransmission
        boletoBatchFileItem.boletoBankId = lastItem?.boletoBankId ?: payment.boletoBank.id
        boletoBatchFileItem.dueDate = payment.dueDate

        boletoBatchFileItem.save(failOnError: true)
    }

    public void setItemAsCancelled(BoletoBatchFileItem boletoBatchFileItem) {
        boletoBatchFileItem.status = BoletoBatchFileStatus.CANCELLED
        boletoBatchFileItem.save(flush: true, failOnError: true)
    }

    public void processPendingItemDeletion() {
        final Integer maximunNumberOfMigrations = 100
        List<AsyncAction> asyncActionList = AsyncAction.oldestPending([type: AsyncActionType.DELETE_BOLETO_BATCH_FILE_ITEM]).list(max: maximunNumberOfMigrations)

        for (AsyncAction asyncAction in asyncActionList) {
            Boolean hasError = false
            Map asyncActionData = asyncAction.getDataAsMap()

            Utils.withNewTransactionAndRollbackOnError({
                List<Long> boletoBatchFileItemIdList = []

                if (asyncActionData.boletoBatchFileItemId) boletoBatchFileItemIdList += Long.valueOf(asyncActionData.boletoBatchFileItemId)

                if (!boletoBatchFileItemIdList) {
                    boletoBatchFileItemIdList = BoletoBatchFileItem.query([column: "id", paymentId: Long.valueOf(asyncActionData.paymentId), "action[in]": [BoletoAction.CREATE, BoletoAction.UPDATE]]).list()
                }

                for (Long boletoBatchFileItemId : boletoBatchFileItemIdList) {
                    BoletoBatchFileItem boletoBatchFileItem = BoletoBatchFileItem.get(boletoBatchFileItemId)

                    if (!boletoBatchFileItem) continue

                    if (boletoBatchFileItem.status == BoletoBatchFileStatus.PENDING) {
                        boletoBatchFileItem.deleted = true
                        boletoBatchFileItem.save(flush: true, failOnError: true)
                    } else if (boletoBatchFileItem.status == BoletoBatchFileStatus.SENT) {
                        saveDeleteItem(boletoBatchFileItem.payment, boletoBatchFileItem.nossoNumero)
                    }
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [ignoreStackTrace: true,
                onError: { Exception exception ->
                    if (Utils.isLock(exception)) return
                    AsaasLogger.error("BoletoBatchFileItemService.processPendingItemDeletion >> Falha ao processar BoletoBatchFileItem delete asyncAction [${asyncActionData.asyncActionId}]", exception)
                    hasError = true
                }
            ])

            if (hasError) asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
        }
    }

    private void updateReadyForTransmission(List<Long> pendingItemIdList) {
        Utils.forEachWithFlushSession(pendingItemIdList, 100, { Long pendingItemId ->
            Utils.withNewTransactionAndRollbackOnError({
                BoletoBatchFileItem pendingItem = BoletoBatchFileItem.get(pendingItemId)

                pendingItem.readyForTransmission = true
                pendingItem.save(flush: true, failOnError: false)
            }, [logErrorMessage: "BoletoBatchFileItemService.updatePendingItemsToReadyForTransmission : Falha ao atualizar item [${pendingItemId}]"])
        })

        AsaasLogger.info("BoletoBatchFileItemService.updatePendingItemsToReadyForTransmission : ${pendingItemIdList.size()} items atualizados para registro de boleto")
    }
}
