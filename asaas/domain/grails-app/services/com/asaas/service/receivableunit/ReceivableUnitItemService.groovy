package com.asaas.service.receivableunit

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.cerc.CercBankAccount
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationItem
import com.asaas.domain.receivableunit.AnticipatedReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.exception.BusinessException
import com.asaas.integration.cerc.enums.ReceivableRegistrationNonPaymentReason
import com.asaas.log.AsaasLogger

import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.receivableunit.PaymentArrangement
import com.asaas.receivableunit.ReceivableUnitItemCalculator
import com.asaas.receivableunit.ReceivableUnitItemStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ReceivableUnitItemService {

    def asyncActionService
    def anticipatedReceivableUnitService
    def cercBankAccountService
    def cercFidcContractualEffectService
    def receivableRegistrationEventQueueService
    def receivableUnitService
    def paymentArrangementService

    public void processPendingCreateAnticipatedReceivableUnit() {
        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.CREATE_ANTICIPATED_RECEIVABLE_UNIT, 500)
        for (Map asyncActionData : asyncActionDataList) {

            Utils.withNewTransactionAndRollbackOnError({
                linkItemsToAnticipatedReceivableUnit(ReceivableAnticipation.get(asyncActionData.anticipationId))

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [ignoreStackTrace: true,
                onError: { Exception exception ->

                Utils.withNewTransactionAndRollbackOnError({
                    AsyncActionStatus asyncActionStatus = asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId).status
                    if (asyncActionStatus.isCancelled()) {
                        AsaasLogger.error("ReceivableUnitItemService.processPendingCreateAnticipatedReceivableUnit >> Processo de criação de pós-contratada foi cancelado por atingir o número máximo de tentativas. [AsyncActionId: ${asyncActionData.asyncActionId}]", exception)
                    }
                }, [logErrorMessage: "ReceivableUnitItemService.processPendingCreateAnticipatedReceivableUnit >> Erro na verificação de possibilidade de nova tentativa da asyncAction [AsyncActionId: ${asyncActionData.asyncActionId}]"])
            }])
        }
    }

    public ReceivableUnitItem save(Payment payment) {
        CercBankAccount bankAccount = cercBankAccountService.saveForAsaasAccount(payment.provider)

        BigDecimal paymentNetValue = ReceivableUnitItemCalculator.calculateNetValue(payment)
        if (paymentNetValue <= 0) {
            AsaasLogger.info("ReceivableUnitItemService.save >> Item para cobrança [${payment.id}] não foi criado pois não possui valor líquido")
            return null
        }

        return save(payment.creditDate,
                    payment.value,
                    paymentNetValue,
                    payment.provider.cpfCnpj,
                    paymentArrangementService.getPaymentArrangement(payment),
                    bankAccount,
                    [payment: payment])
    }

    public ReceivableUnitItem save(Date estimatedCreditDate, BigDecimal value, BigDecimal netValue, String customerCpfCnpj, PaymentArrangement paymentArrangement, CercBankAccount bankAccount, Map additionalParams) {
        ReceivableUnitItem validatedDomain = validateSave(additionalParams, netValue, paymentArrangement)
        if (validatedDomain.hasErrors()) throw new ValidationException("Falha ao salvar recebível [paymentId: ${additionalParams?.payment?.id}, contractualEffectId: ${additionalParams?.contractualEffect?.id}]", validatedDomain.errors)

        ReceivableUnitItem receivableUnitItem = new ReceivableUnitItem()
        receivableUnitItem.estimatedCreditDate = estimatedCreditDate
        receivableUnitItem.value = value
        receivableUnitItem.netValue = netValue
        receivableUnitItem.paymentArrangement = paymentArrangement
        receivableUnitItem.bankAccount = bankAccount
        receivableUnitItem.customerCpfCnpj = customerCpfCnpj
        receivableUnitItem.holderCpfCnpj = additionalParams.holderCpfCnpj ?: customerCpfCnpj
        receivableUnitItem.payment = additionalParams.payment
        receivableUnitItem.contractualEffect = additionalParams.contractualEffect
        receivableUnitItem.status = ReceivableUnitItemStatus.PENDING
        receivableUnitItem.save(failOnError: true, flush: true)

        return receivableUnitItem
    }

    public void setAsAwaitingSettlement(Payment payment) {
        ReceivableUnitItem receivableUnitItem = ReceivableUnitItem.query([paymentId: payment.id, "contractualEffect[isNull]": true]).get()
        if (!receivableUnitItem) {
            AsaasLogger.info("ReceivableUnitItemService.setAsAwaitingSettlement >> UR item não existe para a cobrança [${payment.id}]")
            return
        }

        asyncActionService.saveSettleReceivableUnitItem(receivableUnitItem.id)
    }

    public void settleIfPossible(ReceivableUnitItem item) {
        if (item.status.isSettled()) return

        if (item.payment?.creditDate) {
            settle(item)
            return
        }

        Boolean canFinishWithoutNonPaymentReason = item.receivableUnit.operationType.isFinish() && !item.netValue
        if (canFinishWithoutNonPaymentReason) {
            settle(item)
        } else {
            setAsSettlementDenied(item, ReceivableRegistrationNonPaymentReason.OTHER)
        }
    }

    public void setAsSettlementDenied(ReceivableUnitItem item, ReceivableRegistrationNonPaymentReason reason) {
        if (!reason) throw new BusinessException("É obrigatório informar o motivo de não pagamento")

        item.nonPaymentReason = reason
        item.creditDate = item.payment?.creditDate ?: new Date()
        item.status = ReceivableUnitItemStatus.SETTLEMENT_DENIED
        item.save(failOnError: true, flush: true)

        receivableUnitService.consolidate(item.receivableUnit)
        if (item.receivableUnit != item.originalReceivableUnit) receivableUnitService.consolidate(item.originalReceivableUnit)
    }

    public void delete(ReceivableUnitItem item) {
        if (item.status.isInSettlementProcess()) throw new RuntimeException("O recebível [${item.id}] não pode ser excluído pois já está ou já passou pela fase de liquidação. Status: [${item.status.toString()}]")

        item.deleted = true
        setAsPending(item)
    }

    public void processChargebackReversed(Long paymentId) {
        Boolean originalItemStillExists = ReceivableUnitItem.query([exists: true, paymentId: paymentId, "contractualEffect[isNull]": true]).get().asBoolean()
        if (originalItemStillExists) return

        save(Payment.read(paymentId))
    }

    public void changeItemsCpfCnpj(String oldCpfCnpj, String newCpfCnpj) {
        Map search = [:]
        search.column = "id"
        search.receivableUnitCustomerCpfCnpj = oldCpfCnpj
        search."contractualEffect[isNull]" = true
        search."status[in]" = ReceivableUnitItemStatus.listNotSettledStatuses()
        search.disableSort = true

        Long customerId = Customer.query([column: "id", cpfCnpj: newCpfCnpj]).get()

        List<Long> itemIdList = ReceivableUnitItem.query(search).list()
        for (Long itemId : itemIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CercBankAccount newCercBankAccount = cercBankAccountService.saveForAsaasAccount(Customer.read(customerId))

                ReceivableUnitItem item = ReceivableUnitItem.get(itemId)
                item.customerCpfCnpj = newCpfCnpj
                item.bankAccount = newCercBankAccount

                changeItemHolder(item, newCpfCnpj)
            }, [logErrorMessage: "ReceivableUnitItemService.changeItemsCpfCnpj >> Falha ao trocar o CPF/CNPJ do item [${itemId}]"])
        }
    }

    public void process(ReceivableUnitItem item) {
        linkItemToReceivableUnit(item)

        if (!item.status.isInSettlementProcess()) item.status = ReceivableUnitItemStatus.PROCESSED
        item.save(failOnError: true, flush: true)

        receivableUnitService.consolidate(item.receivableUnit)
        if (item.receivableUnit != item.originalReceivableUnit) receivableUnitService.consolidate(item.originalReceivableUnit)
    }

    public void refreshPaymentItem(ReceivableUnitItem paymentItem) {
        if (paymentItem.status.isInSettlementProcess()) return

        BigDecimal netValue = ReceivableUnitItemCalculator.calculateNetValue(paymentItem.payment)
        if (netValue) {
            paymentItem.netValue = netValue
            setAsPending(paymentItem)
        } else {
            delete(paymentItem)
        }

        cercFidcContractualEffectService.refreshGuaranteeIfPossible(paymentItem)
    }

    public void setAsReadyToScheduleSettlement(ReceivableUnitItem item) {
        item.status = ReceivableUnitItemStatus.READY_TO_SCHEDULE_SETTLEMENT
        item.save(failOnError: true)
    }

    public void setAsError(ReceivableUnitItem item) {
        item.status = ReceivableUnitItemStatus.ERROR
        item.save(failOnError: true, flush: true)
    }

    public ReceivableUnitItem refreshContractualEffectItem(ReceivableUnitItem contractualEffectItem) {
        if (contractualEffectItem.status.isInSettlementProcess()) return null

        contractualEffectItem.value = contractualEffectItem.contractualEffect.value
        contractualEffectItem.netValue = contractualEffectItem.contractualEffect.value
        contractualEffectItem.bankAccount = contractualEffectItem.contractualEffect.bankAccount
        if (contractualEffectItem.isDirty()) contractualEffectItem.save(failOnError: true)

        return contractualEffectItem
    }

    public void processRecreateReceivableUnitItem() {
        final Integer maxItemsPerExecution = 500
        List<Map> eventDataList = receivableRegistrationEventQueueService.listPendingEventData(ReceivableRegistrationEventQueueType.RECREATE_RECEIVABLE_UNIT_ITEM, null, null, maxItemsPerExecution)

        for (Map eventData : eventDataList) {
            Boolean hasError = false

            Utils.withNewTransactionAndRollbackOnError({
                ReceivableUnitItem previousItem = ReceivableUnitItem.query([paymentId: eventData.paymentId, "contractualEffect[isNull]": true]).get()

                if (previousItem && !previousItem.status.isInSettlementProcess()) {
                    delete(previousItem)
                    ReceivableUnitItem newItem = save(previousItem.payment)

                    cercFidcContractualEffectService.refreshGuaranteeIfPossible(newItem)
                }

                receivableRegistrationEventQueueService.delete(eventData.eventQueueId)
            }, [logErrorMessage: "ReceivableUnitItemService.processRecreateReceivableUnitItem --> Falha ao processar evento [${eventData.eventQueueId}]",
                onError: { hasError = true }])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    receivableRegistrationEventQueueService.setAsError(eventData.eventQueueId)
                }, [logErrorMessage: "ReceivableUnitItemService.processRecreateReceivableUnitItem --> Falha ao marcar evento como ERROR [${eventData.eventQueueId}]"])
            }
        }
    }

    public void processRefund(ReceivableUnitItem item) {
        if (item.status.isInSettlementProcess()) throw new BusinessException("O recebível [${item.id}] já está ou já passou pela fase de liquidação.")

        refreshPaymentItem(item)
    }

    public void settle(ReceivableUnitItem item) {
        Date creditDate = item.payment?.creditDate ?: new Date()

        if (item.anticipatedReceivableUnit) creditDate = item.anticipatedReceivableUnit.anticipationCreditDate

        item.creditDate = creditDate
        item.status = ReceivableUnitItemStatus.SETTLED
        item.save(failOnError: true, flush: true)

        if (!item.anticipatedReceivableUnit) receivableUnitService.settleIfPossible(item.receivableUnit)
    }

    private ReceivableUnitItem getAffectedReceivableUnitItem(Payment payment) {
        ReceivableUnitItem receivableUnitItem = ReceivableUnitItem.query([paymentId: payment.id, "contractualEffect[isNull]": true]).get()
        if (!receivableUnitItem) receivableUnitItem = save(payment)

        return receivableUnitItem
    }

    private void changeItemHolder(ReceivableUnitItem item, String holderCpfCnpj) {
        item.holderCpfCnpj = holderCpfCnpj
        item.receivableUnit = null

        process(item)
    }

    private ReceivableUnitItem validateSave(Map additionalParams, BigDecimal netValue, PaymentArrangement paymentArrangement) {
        ReceivableUnitItem validatedDomain = new ReceivableUnitItem()

        if (additionalParams.payment) {
            if (netValue <= 0 && !additionalParams.shouldIgnore) DomainUtils.addError(validatedDomain, "Cobrança ${additionalParams.payment.id} tem o valor líquido zerado")

            if (!ReceivableUnitItem.ALLOWED_BILLING_TYPE_LIST.contains(additionalParams.payment.billingType)) DomainUtils.addError(validatedDomain, "Cobrança ${additionalParams.payment.id} não foi recebida em cartão de crédito ou débito")

            Boolean itemExistsAlready = ReceivableUnitItem.query([exists: true, paymentId: additionalParams.payment.id]).get().asBoolean()
            if (itemExistsAlready) DomainUtils.addError(validatedDomain, "Cobrança ${additionalParams.payment.id} já cadastrada como recebível")

            final Integer necessaryBusinessDaysToScheduleSettlement = 2
            Integer businessDaysUntilCreditDate = CustomDateUtils.calculateDifferenceInBusinessDays(new Date().clearTime(), additionalParams.payment.creditDate)
            if (businessDaysUntilCreditDate < necessaryBusinessDaysToScheduleSettlement) DomainUtils.addError(validatedDomain, "Cobrança ${additionalParams.payment.id} não pode ser registrada com menos de ${necessaryBusinessDaysToScheduleSettlement} dias úteis para a data de crédito")
        }

        if (!paymentArrangement) DomainUtils.addError(validatedDomain, "O arranjo de pagamento é obrigatório")

        return validatedDomain
    }

    private void setAsPending(ReceivableUnitItem item) {
        item.status = ReceivableUnitItemStatus.PENDING
        if (item.isDirty()) item.save(failOnError: true, flush: true)
    }

    private void linkItemToReceivableUnit(ReceivableUnitItem item) {
        if (item.receivableUnit) return

        ReceivableUnit receivableUnit = receivableUnitService.saveIfNecessary(item)
        item.receivableUnit = receivableUnit
        if (!item.originalReceivableUnit) item.originalReceivableUnit = receivableUnit
    }

    private void linkItemToAnticipation(ReceivableUnitItem item, ReceivableAnticipation anticipation, ReceivableAnticipationItem anticipationItem) {
        ReceivableUnitItem validatedDomain = validateSaveAnticipation(item, anticipation)
        if (validatedDomain.hasErrors()) throw new ValidationException("ReceivableUnitItemService.linkItemToAnticipation >> Falha ao associar item [${item.id}] à antecipação [${anticipation.id}]", validatedDomain.errors)

        item.anticipation = anticipation
        item.anticipationItem = anticipationItem
        item.anticipatedNetValue = ReceivableUnitItemCalculator.calculateAnticipatedNetValue(item)

        linkItemToAnticipatedReceivableUnit(item)
        process(item)
        anticipatedReceivableUnitService.consolidate(item.anticipatedReceivableUnit)
    }

    private void linkItemToAnticipatedReceivableUnit(ReceivableUnitItem item) {
        AnticipatedReceivableUnit anticipatedReceivableUnit = anticipatedReceivableUnitService.save(item.customerCpfCnpj, item.paymentArrangement, item.estimatedCreditDate, item.anticipation.getApprovedDate(), item.holderCpfCnpj)
        if (anticipatedReceivableUnit.hasErrors()) throw new ValidationException("ReceivableUnitItemService.linkItemToAnticipatedReceivableUnit >> Falha ao associar item [${item.id}] a uma unidade de recebíveis antecipadas", anticipatedReceivableUnit.errors)

        item.anticipatedReceivableUnit = anticipatedReceivableUnit
    }

    private ReceivableUnitItem validateSaveAnticipation(ReceivableUnitItem item, ReceivableAnticipation anticipation) {
        ReceivableUnitItem validatedDomain = new ReceivableUnitItem()

        Boolean anticipationExistsAlready = ReceivableUnitItem.query([exists: true,
                                                                      anticipationId: anticipation.id,
                                                                      customerCpfCnpj: item.customerCpfCnpj,
                                                                      holderCpfCnpj: item.holderCpfCnpj,
                                                                      paymentId: item.payment.id]).get().asBoolean()
        if (anticipationExistsAlready) DomainUtils.addError(validatedDomain, "Antecipação já criada para a cobrança ${item.id}")

        if (anticipation.isVortxAcquisition()) DomainUtils.addError(validatedDomain, "Só é possível atrelar o item a uma antecipação que foi realizada pelo Asaas ou Ocean")

        return validatedDomain
    }

    private void linkItemsToAnticipatedReceivableUnit(ReceivableAnticipation anticipation) {
        if (anticipation.isVortxAcquisition()) throw new BusinessException("A antecipação [${anticipation.id}] não pode ser linkada a uma pós-contratada pois não foi antecipada via ASAAS ou OCEAN")

        if (anticipation.payment) {
            ReceivableUnitItem item = getAffectedReceivableUnitItem(anticipation.payment)
            if (!item) return

            process(item)
            linkItemToAnticipation(item, anticipation, null)
        } else if (anticipation.installment) {
            for (ReceivableAnticipationItem anticipationItem : anticipation.items) {
                ReceivableUnitItem item = getAffectedReceivableUnitItem(anticipationItem.payment)
                if (!item) return

                process(item)
                linkItemToAnticipation(item, anticipation, anticipationItem)
            }
        }
    }
}
