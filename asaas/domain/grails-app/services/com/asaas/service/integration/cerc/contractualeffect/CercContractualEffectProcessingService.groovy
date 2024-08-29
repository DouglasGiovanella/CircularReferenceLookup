package com.asaas.service.integration.cerc.contractualeffect

import com.asaas.domain.auditlog.AuditLogEvent
import com.asaas.domain.integration.cerc.CercContractualEffect
import com.asaas.domain.receivableregistration.ReceivableRegistrationEventQueue
import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.integration.cerc.adapter.contractualeffect.ContractualEffectAdapter
import com.asaas.integration.cerc.enums.CercProcessingStatus
import com.asaas.integration.cerc.enums.webhook.CercEffectType
import com.asaas.log.AsaasLogger
import com.asaas.namedqueries.SqlOrder
import com.asaas.paymentinfo.PaymentNonAnticipableReason
import com.asaas.receivableanticipation.validator.ReceivableAnticipationNonAnticipableReasonVO
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.receivableregistration.contractualeffect.ContractualEffectCalculator
import com.asaas.receivableunit.PaymentArrangement
import com.asaas.receivableunit.ReceivableUnitItemStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CercContractualEffectProcessingService {

    def cercContractService
    def cercContractualEffectService
    def cercManagerService
    def receivableAnticipationValidationService
    def receivableRegistrationEventQueueService
    def receivableUnitService
    def receivableUnitItemService

    public void processCreateOrUpdateContractualEffectEventQueue() {
        final Integer maxNumberOfGroupIdPerExecution = 800
        List<Long> receivableRegistrationEventQueueForDeleteIdList = Collections.synchronizedList(new ArrayList<Long>())
        List<String> groupIdList = ReceivableRegistrationEventQueue.oldestPending([distinct: "groupId", disableSort: true, type: ReceivableRegistrationEventQueueType.CREATE_OR_UPDATE_CONTRACTUAL_EFFECT]).list(max: maxNumberOfGroupIdPerExecution)

        final Integer maxEventsPerTask = 100
        final Integer maxTasks = 8

        Utils.processWithThreads(groupIdList, maxTasks, { List<String> subGroupIdList ->
            Utils.withNewTransactionAndRollbackOnError {
                SqlOrder sqlOrder = new SqlOrder("group_id, id ASC")
                List<Map> eventDataList = receivableRegistrationEventQueueService.listPendingEventData(ReceivableRegistrationEventQueueType.CREATE_OR_UPDATE_CONTRACTUAL_EFFECT, subGroupIdList, sqlOrder, maxEventsPerTask)

                Utils.forEachWithFlushSession(eventDataList, 25, { Map eventData ->
                    try {
                        ContractualEffectAdapter contractualEffectAdapter = new ContractualEffectAdapter(eventData)
                        cercContractualEffectService.save(contractualEffectAdapter)

                        receivableRegistrationEventQueueForDeleteIdList.add(eventData.eventQueueId)
                    } catch (Exception error) {
                        if (Utils.isLock(error)) {
                            AsaasLogger.warn("CercContractualEffectProcessingService.processCreateOrUpdateContractualEffectEventQueue >> Ocorreu um lock ao processar evento [${eventData.eventQueueId}]", error)
                            return
                        }

                        AsaasLogger.error("CercContractualEffectProcessingService.processCreateOrUpdateContractualEffectEventQueue >> Falha ao processar evento [${eventData.eventQueueId}]", error)

                        Utils.withNewTransactionAndRollbackOnError({
                            receivableRegistrationEventQueueService.setAsError(eventData.eventQueueId)
                            receivableRegistrationEventQueueForDeleteIdList.remove(eventData.eventQueueId)
                        }, [logErrorMessage: "CercContractualEffectProcessingService.processCreateOrUpdateContractualEffectEventQueue >> Falha ao marcar evento como ERROR [${eventData.eventQueueId}]"])
                    }
                })
            }
        })

        try {
            receivableRegistrationEventQueueService.deleteBatch(receivableRegistrationEventQueueForDeleteIdList)
        } catch (Exception error) {
            AsaasLogger.error("CercContractualEffectProcessingService.processCreateOrUpdateContractualEffectEventQueue >> Falha ao deletar o ReceivableRegistrationEventQueue [${receivableRegistrationEventQueueForDeleteIdList}]")
        }
    }

    public void processRefreshContractualEffectsCompromisedValueWithAffectedReceivableUnit() {
        final Integer maxNumberOfGroupIdPerExecution = 400
        List<Long> receivableRegistrationEventQueueForDeleteIdList = Collections.synchronizedList(new ArrayList<Long>())
        List<String> groupIdList = ReceivableRegistrationEventQueue.oldestPending([distinct: "groupId", disableSort: true, type: ReceivableRegistrationEventQueueType.REFRESH_CONTRACTUAL_EFFECTS_COMPROMISED_VALUE_WITH_AFFECTED_RECEIVABLE_UNIT]).list(max: maxNumberOfGroupIdPerExecution)
        if (!groupIdList) return

        final Integer maxEventsPerTask = 100
        final Integer maxTasks = 4

        Utils.processWithThreads(groupIdList, maxTasks, { List<String> subGroupIdList ->
            Utils.withNewTransactionAndRollbackOnError {
                SqlOrder sqlOrder = new SqlOrder("group_id, id ASC")
                List<Map> eventDataList = receivableRegistrationEventQueueService.listPendingEventData(ReceivableRegistrationEventQueueType.REFRESH_CONTRACTUAL_EFFECTS_COMPROMISED_VALUE_WITH_AFFECTED_RECEIVABLE_UNIT, subGroupIdList, sqlOrder, maxEventsPerTask)

                Utils.forEachWithFlushSession(eventDataList, 25, { Map eventData ->
                    try {
                        refreshCompromisedValueIfNecessary(ReceivableUnit.read(eventData.receivableUnitId))
                        receivableRegistrationEventQueueForDeleteIdList.add(eventData.eventQueueId)
                    } catch (Exception exception) {
                        Utils.withNewTransactionAndRollbackOnError({
                            if (Utils.isLock(exception)) {
                                AsaasLogger.warn("CercContractualEffectProcessingService.processRefreshContractualEffectsCompromisedValueWithAffectedReceivableUnit >> Ocorreu um lock ao tentar atualizar o valor comprometido de efeito de contrato, eventId: [${eventData.eventQueueId}]", exception)
                                return
                            }
                            AsaasLogger.error("CercContractualEffectProcessingService.processRefreshContractualEffectsCompromisedValueWithAffectedReceivableUnit >> Falha ao processar evento [${eventData.eventQueueId}]", exception)

                            receivableRegistrationEventQueueService.setAsError(eventData.eventQueueId)
                            receivableRegistrationEventQueueForDeleteIdList.remove(eventData.eventQueueId)
                        }, [logErrorMessage: "CercContractualEffectProcessingService.processRefreshContractualEffectsCompromisedValueWithAffectedReceivableUnit >> Falha ao marcar evento como ERROR [${eventData.eventQueueId}]"])
                    }
                })
            }
        })

        try {
            receivableRegistrationEventQueueService.deleteBatch(receivableRegistrationEventQueueForDeleteIdList)
        } catch (Exception exception) {
            AsaasLogger.error("CercContractualEffectProcessingService.processRefreshAffectedReceivableUnitContractualEffect >> Falha ao deletar o ReceivableRegistrationEventQueue [${receivableRegistrationEventQueueForDeleteIdList}]", exception)
        }
    }

    public Boolean updateAsNotProcessedForNotConstituted(Date endDate) {
        final Integer maxEffectsPerExecution = 1000

        Map search = [:]
        search.column = "id"
        search."disableSort" = true
        search."affectedReceivableUnitId[isNull]" = true
        search."dueDate[le]" = endDate
        search."status[in]" = CercProcessingStatus.listActiveStatuses()

        List<Long> notConstitutedContractualEffectIdList = CercContractualEffect.query(search).list(max: maxEffectsPerExecution)

        final Integer maxItemsPerThread = 250
        final Integer flushEvery = 50
        ThreadUtils.processWithThreadsOnDemand(notConstitutedContractualEffectIdList, maxItemsPerThread, { List<Long> contractualEffectIdList ->
            Utils.forEachWithFlushSession(contractualEffectIdList, flushEvery, { Long effectId ->
                Utils.withNewTransactionAndRollbackOnError({
                    CercContractualEffect contractualEffect = CercContractualEffect.get(effectId)
                    setAsNotProcessed(contractualEffect)
                }, [logErrorMessage: "CercContractualEffectProcessingService.updateAsNotProcessedForNotConstituted >> Falha ao marcar contrato sem UR constituída [${effectId}] como não processado"])
            })
        })

        return notConstitutedContractualEffectIdList.size() == maxEffectsPerExecution
    }

    public void setAsNotProcessedForOverdueWithoutSettlement() {
        Map search = [:]
        search.column = "id"
        search.disableSort = true
        search.withAffectedReceivableUnitInactivateAndOverdue = true
        search."affectedReceivableUnitId[isNotNull]" = true
        search."estimatedCreditDate[le]" = new Date()
        search."status[in]" = CercProcessingStatus.listActiveStatuses()

        List<Long> idList = CercContractualEffect.query(search).list()
        for (Long id : idList) {
            Utils.withNewTransactionAndRollbackOnError({
                CercContractualEffect contractualEffect = CercContractualEffect.get(id)
                setAsNotProcessed(contractualEffect)
            }, [logErrorMessage: "CercContractualEffectProcessingService.setAsNotProcessedForOverdueWithoutSettlement >> Falha ao marcar contrato como não processado [${id}]"])
        }
    }

    public void setAsNotProcessed(CercContractualEffect contractualEffect) {
        contractualEffect.status = CercProcessingStatus.NOT_PROCESSED

        if (contractualEffect.compromisedValue == 0.0) {
            contractualEffect.value = 0.0
            contractualEffect.save(failOnError: true, flush: true)
            cercContractService.consolidate(contractualEffect.contract)
        } else {
            contractualEffect.save(failOnError: true, flush: true)
        }

        deleteNotSettledContractualEffectItem(contractualEffect, [:])

        if (contractualEffect.affectedReceivableUnitId) cercContractualEffectService.setAllContractsAsPending(contractualEffect.affectedReceivableUnitId)
    }

    public void processPendingItems() {
        final Integer maxItemsPerCycle = 100
        List<Map> eventDataList = receivableRegistrationEventQueueService.listPendingEventData(ReceivableRegistrationEventQueueType.PROCESS_CONTRACTUAL_EFFECTS_WITH_AFFECTED_RECEIVABLE_UNIT, null, null, maxItemsPerCycle)
        if (!eventDataList) return

        List<Long> eventIdList = eventDataList.collect { it.eventQueueId }
        receivableRegistrationEventQueueService.updateBatchStatusAsProcessing(eventIdList)

        Utils.forEachWithFlushSession(eventDataList, 25, { Map eventData ->
            Boolean hasError = false

            Utils.withNewTransactionAndRollbackOnError({
                processActiveContracts(eventData.receivableUnitId)
                receivableRegistrationEventQueueService.delete(eventData.eventQueueId)
            }, [
                ignoreStackTrace: true,
                onError: { Exception exception ->
                    if (Utils.isLock(exception)) {
                        AsaasLogger.warn("CercContractualEffectProcessingService.processPendingItems >> Ocorreu um lock ao processar o evento [${eventData.eventQueueId}]", exception)
                        Utils.withNewTransactionAndRollbackOnError({
                            receivableRegistrationEventQueueService.updateAsPending(eventData.eventQueueId)
                        }, [logErrorMessage: "CercContractualEffectProcessingService.processPendingItems >> Falha ao atualizar evento como pendente [${eventData.eventQueueId}]"])
                        return
                    }

                    AsaasLogger.error("CercContractualEffectProcessingService.processPendingItems >> Falha ao processar o evento [${eventData.eventQueueId}]", exception)
                    hasError = true
                }
            ])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    cercContractualEffectService.setAllContractsAsError(eventData.receivableUnitId)
                    receivableRegistrationEventQueueService.setAsError(eventData.eventQueueId)
                }, [logErrorMessage: "CercContractualEffectProcessingService.processPendingItems >> Falha ao atualizar evento como erro [${eventData.eventQueueId}]"])
            }
        })
    }

    public void processActiveContracts(Long receivableUnitId) {
        List<CercContractualEffect> contractualEffectActiveList = cercContractualEffectService.listActiveContractsForReceivableUnit(receivableUnitId)
        for (CercContractualEffect contractualEffectActive : contractualEffectActiveList) {
            prepare(contractualEffectActive)
        }

        List<CercContractualEffect> contractualEffectPreparedList = cercContractualEffectService.listActiveContractsForReceivableUnit(receivableUnitId)
        for (CercContractualEffect contractualEffectPrepared : contractualEffectPreparedList) {
            process(contractualEffectPrepared)
        }
    }

    public void processIfPossible(CercContractualEffect contractualEffect) {
        prepare(contractualEffect)

        if (contractualEffect.status.isActive()) process(contractualEffect)
    }

    public Date getLastCompromisedValueUpdateDate(CercContractualEffect contractualEffect) {
        Map searchCompromisedValueUpdateDate = [
            column: "dateCreated",
            className: CercContractualEffect.simpleName,
            persistedObjectId: contractualEffect.id,
            eventName: "UPDATE",
            propertyName: "compromisedValue",
            sort: "id",
            order: "desc"
        ]

        return AuditLogEvent.query(searchCompromisedValueUpdateDate).get()
    }

    public void refreshCompromisedValueIfNecessary(ReceivableUnit receivableUnit) {
        Map response = cercManagerService.queryReceivableUnitContractualEffects(receivableUnit)

        Map contractualEffectData = [:]
        if (response.agendas) {
            contractualEffectData.customerCpfCnpj = Utils.removeNonNumeric(response.documentoUsuarioFinalRecebedor.toString())
            contractualEffectData.holderCpfCnpj = Utils.removeNonNumeric(response.agendas[0].unidadesRecebiveis[0].documentoTitular.toString())
            contractualEffectData.paymentArrangement = PaymentArrangement.valueOf(response.agendas[0].codigoArranjoPagamento.toString())
            contractualEffectData.estimatedCreditDate = CustomDateUtils.fromStringDatabaseDateFormat(response.agendas[0].unidadesRecebiveis[0].dataLiquidacao.toString())
        }

        for (Map agendas : response.agendas) {
            for (Map unidadeDeRecebivel : agendas.unidadesRecebiveis) {
                for (Map efeitoDeContrato : unidadeDeRecebivel.efeitosContrato) {
                    CercContractualEffect contractualEffect = CercContractualEffect.query([
                        customerCpfCnpj: contractualEffectData.customerCpfCnpj,
                        holderCpfCnpj: contractualEffectData.holderCpfCnpj,
                        paymentArrangement: contractualEffectData.paymentArrangement,
                        estimatedCreditDate: contractualEffectData.estimatedCreditDate,
                        protocol: efeitoDeContrato.protocolo
                    ]).get()
                    if (!contractualEffect) {
                        if (efeitoDeContrato.valorComprometido > BigDecimal.ZERO) {
                            AsaasLogger.info("CercContractualEffectProcessingService.refreshCompromisedValueIfNecessary -> Não foi encontrado efeito de contrato, protocolo recebido: [${efeitoDeContrato.protocolo}], valor comprometido: [${efeitoDeContrato.valorComprometido}], UR: [${receivableUnit.id}]")
                            String groupId = contractualEffectData.encodeAsMD5()
                            contractualEffectData.contractualEffect = efeitoDeContrato
                            receivableRegistrationEventQueueService.saveIfHasNoEventPendingWithSameGroupIdAndHash(ReceivableRegistrationEventQueueType.CREATE_OR_UPDATE_CONTRACTUAL_EFFECT, contractualEffectData, groupId)
                        }
                        continue
                    }

                    BigDecimal newCompromisedValue = Utils.toBigDecimal(efeitoDeContrato.valorComprometido)
                    if (contractualEffect.compromisedValue != newCompromisedValue) AsaasLogger.info("CercContractualEffectProcessingService.refreshCompromisedValueIfNecessary -> Valor comprometido alterado de ${contractualEffect.compromisedValue} para ${newCompromisedValue}, UR [${contractualEffect.affectedReceivableUnitId}]")

                    contractualEffect.compromisedValue = newCompromisedValue
                    cercContractualEffectService.setAsPending(contractualEffect)
                }
            }
        }

        Map eventData = [receivableUnitId: receivableUnit.id]
        receivableRegistrationEventQueueService.saveIfHasNoEventPendingWithSameGroupId(ReceivableRegistrationEventQueueType.PROCESS_CONTRACTUAL_EFFECTS_WITH_AFFECTED_RECEIVABLE_UNIT, eventData, eventData.encodeAsMD5())
    }

    private void prepare(CercContractualEffect contractualEffect) {
        if (!contractualEffect.hasCompromisedValue()) {
            setAsNotProcessed(contractualEffect)
        }
    }

    private void process(CercContractualEffect contractualEffect) {
        if (!contractualEffect.affectedReceivableUnit) return

        if (contractualEffect.isReprocess()) {
            deleteReceivableUnitItemNotSettledBeforeCompromisedValueUpdate(contractualEffect)
        }

        contractualEffect.value = ContractualEffectCalculator.calculateAffordableValue(contractualEffect)

        if (contractualEffect.affectedReceivableUnit.operationType.isFinish()) {
            cercContractualEffectService.setAsFinished(contractualEffect)
            deleteNotSettledContractualEffectItem(contractualEffect, [:])
            cercContractService.consolidate(contractualEffect.contract)
            return
        }

        setAffectedPaymentsAsNotAnticipable(contractualEffect.affectedReceivableUnit.id, contractualEffect.beneficiaryCpfCnpj)

        ReceivableUnitItem contractItem = saveUnitItemIfNecessary(contractualEffect)
        if (CercEffectType.listHolderChangeType().contains(contractualEffect.effectType)) {
            contractItem.originalReceivableUnit = contractualEffect.affectedReceivableUnit
            contractItem.save(failOnError: true)

            contractualEffect.receivableUnit = receivableUnitService.saveIfNecessary(contractItem)
        }
        receivableUnitItemService.process(contractItem)

        contractualEffect.save(failOnError: true)

        cercContractualEffectService.setAsProcessed(contractualEffect)

        cercContractService.consolidate(contractualEffect.contract)
    }

    private void setAffectedPaymentsAsNotAnticipable(Long receivableUnitId, String beneficiaryCpfCnpj) {
        List<Long> affectedPaymentIdList = ReceivableUnitItem.query([column: "payment.id",
                                                                     receivableUnitId: receivableUnitId,
                                                                     "payment[isNotNull]": true]).list()
        if (affectedPaymentIdList) receivableAnticipationValidationService.setAsNotAnticipableAndSchedulable(affectedPaymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.HAS_CERC_CONTRACTUAL_EFFECT, [beneficiaryCpfCnpj]))
    }

    private ReceivableUnitItem saveUnitItemIfNecessary(CercContractualEffect contractualEffect) {
        ReceivableUnitItem contractualEffectItem = ReceivableUnitItem.notSettled([contractualEffectId: contractualEffect.id]).get()
        if (contractualEffectItem) return receivableUnitItemService.refreshContractualEffectItem(contractualEffectItem)

        Map additionalParams = [contractualEffect: contractualEffect]

        if (CercEffectType.listHolderChangeType().contains(contractualEffect.effectType)) additionalParams.holderCpfCnpj = contractualEffect.beneficiaryCpfCnpj

        contractualEffectItem = receivableUnitItemService.save(
            contractualEffect.estimatedCreditDate,
            contractualEffect.value,
            contractualEffect.value,
            contractualEffect.customerCpfCnpj,
            contractualEffect.paymentArrangement,
            contractualEffect.bankAccount,
            additionalParams
        )

        return contractualEffectItem
    }

    private void deleteReceivableUnitItemNotSettledBeforeCompromisedValueUpdate(CercContractualEffect contractualEffect) {
        Date compromisedValueUpdateDate = getLastCompromisedValueUpdateDate(contractualEffect)
        if (!compromisedValueUpdateDate) return

        deleteNotSettledContractualEffectItem(contractualEffect, ["dateCreated[lt]": compromisedValueUpdateDate])
    }

    private void deleteNotSettledContractualEffectItem(CercContractualEffect contractualEffect, Map search) {
        List<ReceivableUnitItem> contractualEffectItemList = ReceivableUnitItem.query(search + [
            contractualEffectId: contractualEffect.id,
            "status[in]": ReceivableUnitItemStatus.listNotSettledStatuses()
        ]).list()

        for (ReceivableUnitItem contractualEffectItem : contractualEffectItemList) {
            receivableUnitItemService.delete(contractualEffectItem)
        }
    }
}
