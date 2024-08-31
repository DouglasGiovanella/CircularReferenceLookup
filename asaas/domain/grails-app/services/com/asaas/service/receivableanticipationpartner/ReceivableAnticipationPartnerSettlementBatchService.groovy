package com.asaas.service.receivableanticipationpartner

import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementBatch
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementBatchItem
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementItem
import com.asaas.domain.user.User
import com.asaas.log.AsaasLogger
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementBatchStatus
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementItemStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException
import org.hibernate.StaleObjectStateException
import org.springframework.orm.hibernate3.HibernateOptimisticLockingFailureException

@Transactional
class ReceivableAnticipationPartnerSettlementBatchService {

    def financialStatementReceivableAnticipationService
    def receivableAnticipationPartnerStatementService
    def asyncActionService
    def messageService

    public void createBatchAndNotifyForPendingReceivableAnticipationPartnerSettlement() {
        for (Map asyncActionData in asyncActionService.listPendingReceivableAnticipationPartnerSettlementBatch()) {
            String errorMessage

            Utils.withNewTransactionAndRollbackOnError({
                User user = User.read(asyncActionData.userId)
                ReceivableAnticipationPartnerSettlementBatch settlementBatch = saveForPendingReceivableAnticipationPartnerSettlement(user)
                if (settlementBatch.hasErrors()) throw new ValidationException("Erro ao gerar a remessa", settlementBatch.errors)

                asyncActionService.setAsDone(asyncActionData.asyncActionId)
            },  [
                    onError: { Exception exception ->
                        if (exception instanceof HibernateOptimisticLockingFailureException || exception instanceof StaleObjectStateException) {
                            AsaasLogger.warn("ReceivableAnticipationPartnerSettlementBatchService.createBatchAndNotifyForPendingReceivableAnticipationPartnerSettlement >> Lock ao processar item [${asyncActionData.asyncActionId}]")
                            return
                        }

                        if (exception instanceof ValidationException) {
                            errorMessage = DomainUtils.getValidationMessagesAsString(exception.errors)
                        } else {
                            errorMessage = "Erro interno ao gerar a remessa"
                        }
                        AsaasLogger.error("ReceivableAnticipationPartnerSettlementBatchService.createBatchAndNotifyForPendingReceivableAnticipationPartnerSettlement >> Erro ao processar item [${asyncActionData.asyncActionId}] e não haverá retry")
                        asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
                    }
                ]
            )

            messageService.notifyReceivableAnticipationPartnerSettlementBatchCreationResult(errorMessage)
        }
    }

    public ReceivableAnticipationPartnerSettlementBatch saveForPendingReceivableAnticipationPartnerSettlement(User user) {
        ReceivableAnticipationPartnerSettlementBatch validatedBatch = validateSave()
        if (validatedBatch.hasErrors()) return validatedBatch

        List<Long> settlementItemIdList = ReceivableAnticipationPartnerSettlementItem.vortxItemReadyForBatch([column: "id"]).list()
        return save(settlementItemIdList, user)
	}

    public ReceivableAnticipationPartnerSettlementBatch save(List<Long> settlementItemIdList, User user) {
        ReceivableAnticipationPartnerSettlementBatch settlementBatch = new ReceivableAnticipationPartnerSettlementBatch([creator: user])
        settlementBatch.awaitingSendSettlementItems = false
        settlementBatch.save(failOnError: true)

        saveItems(settlementBatch, settlementItemIdList)

        receivableAnticipationPartnerStatementService.saveAnticipationSettlement(settlementItemIdList)

        return settlementBatch
    }

    private void saveItems(ReceivableAnticipationPartnerSettlementBatch batch, List<Long> settlementItemIdList) {
        Utils.forEachWithFlushSession(settlementItemIdList, 50, { Long settlementItemId ->
            ReceivableAnticipationPartnerSettlementItem settlementItem = ReceivableAnticipationPartnerSettlementItem.get(settlementItemId)
            settlementItem.status = settlementItem.canBeTransmitted() ? ReceivableAnticipationPartnerSettlementItemStatus.AWAITING_SEND_BY_API : ReceivableAnticipationPartnerSettlementItemStatus.DONT_SEND_BY_API
            settlementItem.paid = true
            settlementItem.save(flush: true, failOnError: true)

            if (settlementItem.partnerSettlement.unpaidValueToPartner) {
                settlementItem.partnerSettlement.unpaidValueToPartner -= settlementItem.value
                settlementItem.partnerSettlement.save(failOnError: true)
            }

            ReceivableAnticipationPartnerSettlementBatchItem batchItem = new ReceivableAnticipationPartnerSettlementBatchItem()
            batchItem.receivableAnticipationPartnerSettlementItem = settlementItem
            batchItem.batch = batch
            batchItem.save(failOnError: true)
            if (settlementItem.status.isAwaitingSendByApi() && !batch.awaitingSendSettlementItems) {
                batch.awaitingSendSettlementItems = true
                batch.save(failOnError: true)
            }
        })
    }

    public void confirm(Long batchId) {
        ReceivableAnticipationPartnerSettlementBatch batch = ReceivableAnticipationPartnerSettlementBatch.get(batchId)

        if (batch.isDone()) throw new Exception("Esta remessa já foi confirmada")

        batch.status = ReceivableAnticipationPartnerSettlementBatchStatus.DONE
        batch.transmissionDate = new Date()
        batch.save(failOnError: true)

        financialStatementReceivableAnticipationService.createForReceivableAnticipationPartnerSettlementBatch(batchId)
    }

    private ReceivableAnticipationPartnerSettlementBatch validateSave() {
        ReceivableAnticipationPartnerSettlementBatch validatedBatch = new ReceivableAnticipationPartnerSettlementBatch()

        Boolean hasBatchCreatedToday = ReceivableAnticipationPartnerSettlementBatch.query([exists: true, "dateCreated[ge]": new Date().clearTime(), "dateCreated[le]": CustomDateUtils.setTimeToEndOfDay(new Date())]).get().asBoolean()
        if (hasBatchCreatedToday) {
            DomainUtils.addError(validatedBatch, "Não é possível gerar mais de uma remessa para o mesmo dia.")
            return validatedBatch
        }

        Boolean hasSettlementItemList = ReceivableAnticipationPartnerSettlementItem.vortxItemReadyForBatch([exists: true]).get().asBoolean()
        if (!hasSettlementItemList) {
            DomainUtils.addError(validatedBatch, "Não há nenhuma liquidação aguardando a geração de remessa.")
            return validatedBatch
        }

        return validatedBatch
    }
}
