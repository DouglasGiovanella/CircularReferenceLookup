package com.asaas.service.paymentdunning.creditbureau

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.bankslip.PaymentBankSlipInfo
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.paymentdunning.PaymentDunningCustomerAccountInfo
import com.asaas.domain.paymentdunning.creditbureau.CreditBureauDunningBatch
import com.asaas.domain.paymentdunning.creditbureau.CreditBureauDunningBatchItem
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.payment.PaymentBuilder
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningBatchItemStatus
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningBatchItemType
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningBatchStatus
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningBatchType
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CreditBureauDunningBatchService {

    def asyncActionService
    def creditBureauDunningBatchItemService
    def creditBureauDunningBatchTransmitionService
    def creditBureauDunningService
    def revenueServiceRegisterService

    public void createAndTransmit() {
        List<Long> pendingCreationItemIdList = getPendingCreationItemIdList()
        if (pendingCreationItemIdList) {
            Long creationBatchId = saveWithNewTransaction(CreditBureauDunningBatchType.CREATION)
            processCreationItems(creationBatchId, pendingCreationItemIdList)

            creditBureauDunningBatchTransmitionService.transmit(creationBatchId)
        }

        sleep(30000)

        List<Long> pendingRemovalItemIdList = getPendingRemovalItemIdList()
        if (pendingRemovalItemIdList) {
            Long removalBatchId = saveWithNewTransaction(CreditBureauDunningBatchType.REMOVAL)
            processRemovalItems(removalBatchId, pendingRemovalItemIdList)

            creditBureauDunningBatchTransmitionService.transmit(removalBatchId)
        }
    }

    private List<Long> getPendingCreationItemIdList() {
        return CreditBureauDunningBatchItem.query([column: "id", "creditBureauDunningBatch[isNull]": true, type: CreditBureauDunningBatchItemType.CREATION, status: CreditBureauDunningBatchItemStatus.PENDING]).list()
    }

    private Long saveWithNewTransaction(CreditBureauDunningBatchType type) {
        Long creditBureauDunningBatchId

        Utils.withNewTransactionAndRollbackOnError({
            CreditBureauDunningBatch creditBureauDunningBatch = new CreditBureauDunningBatch()
            creditBureauDunningBatch.status = CreditBureauDunningBatchStatus.PENDING
            creditBureauDunningBatch.type = type
            creditBureauDunningBatch.save(failOnError: true)

            creditBureauDunningBatchId = creditBureauDunningBatch.id
        }, [logErrorMessage: "CreditBureauDunningBatchService >> Erro na criação da remessa de [${type}]"])

        return creditBureauDunningBatchId
    }

    private void processCreationItems(Long creationBatchId, List<Long> pendingCreationItemIdList) {
        for (Long itemId in pendingCreationItemIdList) {
            Boolean isItemProcessed

            Utils.withNewTransactionAndRollbackOnError({
                CreditBureauDunningBatchItem item = CreditBureauDunningBatchItem.get(itemId)

                saveAndRegisterNewBankSlipIfNecessary(item)
                saveRevenueServiceRegisterIfNecessary(item.paymentDunning)

                item.creditBureauDunningBatch = CreditBureauDunningBatch.get(creationBatchId)
                item.save(failOnError: true)

                isItemProcessed = true
            }, [logErrorMessage: "CreditBureauDunningBatchService >> Erro ao salvar e registrar novo boleto para o item [${itemId}]"])

            if (isItemProcessed) continue

            Utils.withNewTransactionAndRollbackOnError({
                CreditBureauDunningBatchItem item = CreditBureauDunningBatchItem.get(itemId)
                creditBureauDunningBatchItemService.setAsError(item)
            }, [logErrorMessage: "CreditBureauDunningBatchService >> Erro ao atualizar status do item [${itemId}]"])
        }
    }

    private void saveAndRegisterNewBankSlipIfNecessary(CreditBureauDunningBatchItem item) {
        Boolean hasPaymentBankSlipInfo = PaymentBankSlipInfo.query([exists: true, payment: item.paymentDunning.payment]).get()
        if (hasPaymentBankSlipInfo) return

        if (!item.paymentDunning.payment.boletoBank) {
            item.paymentDunning.payment.boletoBank = PaymentBuilder.selectBoletoBank(item.paymentDunning.customer, item.paymentDunning.payment.customerAccount)
            item.paymentDunning.payment.save(failOnError: true, flush: true)
        }

        if (PaymentDunning.INVALID_BOLETO_BANK_FOR_CREDIT_BUREAU.contains(item.paymentDunning.payment.boletoBank.id)) {
            item.paymentDunning.payment.boletoBank = BoletoBank.get(Payment.BRADESCO_ONLINE_BOLETO_BANK_ID)
            item.paymentDunning.payment.save(failOnError: true, flush: true)
        }

        final Integer maxDaysToPartnerDeliverBankSlipForCustomerAccount = 20
        creditBureauDunningService.saveAndRegister(item.paymentDunning.payment, maxDaysToPartnerDeliverBankSlipForCustomerAccount)
    }

    private void saveRevenueServiceRegisterIfNecessary(PaymentDunning paymentDunning) {
        PaymentDunningCustomerAccountInfo customerAccountInfo = PaymentDunningCustomerAccountInfo.query([paymentDunning: paymentDunning]).get()
        if (!CpfCnpjUtils.isCnpj(customerAccountInfo.cpfCnpj)) return

        RevenueServiceRegister revenueServiceRegister = revenueServiceRegisterService.findLegalPerson(customerAccountInfo.cpfCnpj)
        if (revenueServiceRegister.hasErrors()) throw new Exception("CreditBureauDunningBatchService >> Não foi possível consultar dados do devedor na receita. [paymentDunningId: ${paymentDunning.id}, customerAccountInfoId: ${customerAccountInfo.id}] Motivo: ${Utils.getMessageProperty(revenueServiceRegister.errors.allErrors[0])}")
    }

    private List<Long> getPendingRemovalItemIdList() {
        return CreditBureauDunningBatchItem.query([column: "id", "creditBureauDunningBatch[isNull]": true, type: CreditBureauDunningBatchItemType.REMOVAL, status: CreditBureauDunningBatchItemStatus.PENDING]).list()
    }

    private void processRemovalItems(Long removalBatchId, List<Long> pendingRemovalItemIdList) {
        for (Long itemId in pendingRemovalItemIdList) {
            Boolean isItemProcessed

            Utils.withNewTransactionAndRollbackOnError({
                CreditBureauDunningBatchItem item = CreditBureauDunningBatchItem.get(itemId)
                item.creditBureauDunningBatch = CreditBureauDunningBatch.get(removalBatchId)
                item.save(failOnError: true)

                isItemProcessed = true
            }, [logErrorMessage: "CreditBureauDunningBatchService >> Não foi possível criar a remessa de cancelamento para Negativação via Serasa"])

            if (isItemProcessed) continue

            Utils.withNewTransactionAndRollbackOnError({
                CreditBureauDunningBatchItem item = CreditBureauDunningBatchItem.get(itemId)
                creditBureauDunningBatchItemService.setAsError(item)
            }, [logErrorMessage: "CreditBureauDunningBatchService >> Erro ao atualizar status do item [${itemId}]"])
        }
    }

    private Boolean hasBatchNotTransmitted(List<Long> batchIdList) {
        return CreditBureauDunningBatch.query([exists: true, "id[notIn]": batchIdList, "status[ne]": CreditBureauDunningBatchStatus.TRANSMITTED]).get()
    }
}
