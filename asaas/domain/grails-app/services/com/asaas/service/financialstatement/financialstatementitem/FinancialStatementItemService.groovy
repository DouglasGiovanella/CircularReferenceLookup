package com.asaas.service.financialstatement.financialstatementitem

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.asaascard.AsaasCardRecharge
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.bankdeposit.BankDeposit
import com.asaas.domain.bill.Bill
import com.asaas.domain.bill.BillAsaasPayment
import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.creditcard.CreditCardAcquirerOperation
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdebtappropriation.CustomerDebtAppropriation
import com.asaas.domain.customerdebtappropriation.CustomerLossProvision
import com.asaas.domain.debit.Debit
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialstatement.FinancialStatementItem
import com.asaas.domain.financialstatement.FinancialStatementItemOriginInfo
import com.asaas.domain.financialstatement.FinancialStatementItemPayment
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.integration.cerc.contractualeffect.CercContractualEffectSettlement
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunningAccountability
import com.asaas.domain.paymentdunning.creditbureau.conciliation.CreditBureauDunningConciliation
import com.asaas.domain.postalservice.PaymentPostalServiceBatch
import com.asaas.domain.promotionalcode.PromotionalCodeUse
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementBatch
import com.asaas.domain.refundrequest.RefundRequest
import com.asaas.exception.BusinessException
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialstatementitem.FinancialStatementItemBuilder
import com.asaas.financialstatementitempayment.FinancialStatementItemPaymentOrigin
import com.asaas.log.AsaasLogger
import com.asaas.pagination.SequencedResultList
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DatabaseBatchUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementItemService {

    def asyncActionService
    def bifrostPrepaidCardService
    def dataSource
    def financialStatementItemOriginInfoService
    def financialStatementUpdaterService
    def generateFinancialStatementItemAsyncActionService

    public void saveItemsWithThreads(List<Long> financialTransactionIdList, Long financialStatementId) {
        Boolean hasError = false
        final Integer minItemsPerThread = 15000
        final Integer collateSize = 2500

        ThreadUtils.processWithThreadsOnDemand(financialTransactionIdList, minItemsPerThread, true, { List<Long> currentFinancialTransactionIdList ->
            Utils.withNewTransactionAndRollbackOnError({
                FinancialStatement financialStatement = FinancialStatement.load(financialStatementId)

                List<FinancialTransaction> financialTransactionList = []
                for (List<Long> currentFinancialTransactionIdPartialList : currentFinancialTransactionIdList.collate(collateSize)) {
                    financialTransactionList += FinancialTransaction.query(["id[in]": currentFinancialTransactionIdPartialList]).list(readOnly: true)
                }

                Utils.forEachWithFlushSession(financialTransactionList, minItemsPerThread, { FinancialTransaction currentFinancialTransaction ->
                    save(financialStatement, currentFinancialTransaction)
                })
            }, [
                logErrorMessage: "FinancialStatementItemService.saveItemsWithThreads() -> Falha ao salvar itens de um lançamento financeiro [financialStatementId: ${financialStatementId}]",
                onError: { Exception e ->
                    Utils.withNewTransactionAndRollbackOnError({
                        hasError = true
                        financialStatementUpdaterService.setToError(financialStatementId)
                    }, [
                        logErrorMessage: "FinancialStatementItemService.saveItemsWithThreads() -> Falha ao enviar para ERRO um lançamento financeiro [financialStatementId: ${financialStatementId}]"
                    ])
                }
            ])
        })

        if (hasError) throw new RuntimeException("FinancialStatementItemService.saveItemsWithThreads() -> Falha ao lançar itens de um lançamento financeiro [financialStatementId: ${financialStatementId}]")

        Utils.withNewTransactionAndRollbackOnError({
            financialStatementUpdaterService.setToAwaitingCalculateFinancialStatementValue(financialStatementId)
        })
    }

    public SequencedResultList<Map> list(Map params, Integer limit, Integer offset) {
        Boolean hasIdFilter = params.financialStatementId || params.paymentId
        if (!hasIdFilter) validatePeriod(params)

        List<FinancialStatementItem> financialStatementItemList

        if (params.paymentId) {
            Map searchForPaymentIdFilter = [paymentId: params.paymentId, column: "financialStatementItem"]
            if (params.financialStatementId) searchForPaymentIdFilter.financialStatementId = params.financialStatementId
            financialStatementItemList = FinancialStatementItemPayment.query(searchForPaymentIdFilter).list(readonly: true, max: limit + 1, offset: offset)
        } else {
            Map search = [ order: "asc" ]
            if (params."statementDate[ge]") search."statementDate[ge]" = params."statementDate[ge]"
            if (params."statementDate[le]") search."statementDate[le]" = params."statementDate[le]"
            if (params.financialStatementId) search.financialStatementId = params.financialStatementId
            if (params."financialStatementBankAccountId[in]") search."financialStatementBankAccountId[in]" = params."financialStatementBankAccountId[in]"
            financialStatementItemList = FinancialStatementItem.query(search).list(readonly: true, max: limit + 1, offset: offset)
        }

        SequencedResultList<Map> financialStatementItemResultList

        if (financialStatementItemList) {
            financialStatementItemResultList = buildFinancialStatementItemList(financialStatementItemList, params, limit, offset)
        } else {
            financialStatementItemResultList = listPrepaidCardFinancialStatementItem(params, limit, offset)
        }

        return financialStatementItemResultList
    }

    public void processValueAndOriginInfoPendingItems() {
        final Integer maxItemsPerCycle = 8000
        final Integer minItemsPerThread = 1600

        Long totalItems = 0
        List<Long> financialStatementItemIdList = []
        List<Long> asyncActionIdToDeleteList = []
        Long lastAsyncAction

        while (totalItems < maxItemsPerCycle) {
            Map asyncActionQueryParams = [type: AsyncActionType.BUILD_VALUE_AND_ORIGIN_INFO_FOR_FINANCIAL_STATEMENT_ITEM]
            if (lastAsyncAction) asyncActionQueryParams."id[gt]" = lastAsyncAction

            List<AsyncAction> asyncActionList = AsyncAction.oldestPending(asyncActionQueryParams).list(max: 50)
            if (!asyncActionList) break

            List<Map> pendingAsyncActionDataList = asyncActionList.collect { it.getDataAsMap() }
            lastAsyncAction = asyncActionList.last().id

            for (Map pendingAsyncActionData : pendingAsyncActionDataList) {
                if (pendingAsyncActionData.financialStatementItemId) {
                    if (!financialStatementItemIdList.contains(pendingAsyncActionData.financialStatementItemId)) financialStatementItemIdList += pendingAsyncActionData.financialStatementItemId
                    asyncActionIdToDeleteList += pendingAsyncActionData.asyncActionId
                } else {
                    Long limit = (maxItemsPerCycle - totalItems)
                    Map queryParams = [:]
                    queryParams.column = "id"
                    queryParams.disableSort = true
                    queryParams."financialStatementOriginInfo[notExists]" = true
                    queryParams.limit = limit
                    queryParams.financialStatementId = pendingAsyncActionData.financialStatementId

                    List<Long> idList = FinancialStatementItem.query(queryParams).list()
                    financialStatementItemIdList += idList

                    Boolean hasMore = idList.size() == limit
                    if (!hasMore) asyncActionIdToDeleteList += pendingAsyncActionData.asyncActionId
                }

                totalItems = financialStatementItemIdList.size()
                if (totalItems >= maxItemsPerCycle) break
            }
        }

        if (!financialStatementItemIdList) return

        ThreadUtils.processWithThreadsOnDemand(financialStatementItemIdList, minItemsPerThread, { List<Long> fullThreadIdList ->
            List<Long> itemWithErrorIdList = []

            for (List<Long> idList : fullThreadIdList.collate(50)) {
                Utils.withNewTransactionAndRollbackOnError({
                    List<FinancialStatementItemOriginInfo> financialStatementItemOriginInfoListToSave = []
                    for (Long financialStatementItemId : idList) {
                        FinancialStatementItem financialStatementItem = FinancialStatementItem.get(financialStatementItemId)
                        if (!financialStatementItem) {
                            AsaasLogger.info("FinancialStatementItemService.processValueAndOriginInfoPendingItems >> FinancialStatementItem [${financialStatementItemId}] não localizado")
                            continue
                        }

                        financialStatementItem.originDescription = buildOriginDescription(financialStatementItem)
                        if (financialStatementItem.value == null) financialStatementItem.value = FinancialStatementItemBuilder.buildValue(financialStatementItem)
                        financialStatementItem.save(failOnError: true)

                        Map valuesToInsertOriginInfo = financialStatementItemOriginInfoService.buildValuesToInsertOriginInfo(financialStatementItem)
                        Payment payment = valuesToInsertOriginInfo.payment
                        Customer customer = valuesToInsertOriginInfo.customer
                        FinancialStatementItemPaymentOrigin financialStatementItemPaymentOrigin = valuesToInsertOriginInfo.financialStatementItemPaymentOrigin

                        if (financialStatementItemPaymentOrigin) saveFinancialStatementItemPayment(financialStatementItem, payment, financialStatementItemPaymentOrigin)

                        FinancialStatementItemOriginInfo financialStatementItemOriginInfo = FinancialStatementItemOriginInfo.query([financialStatementItemId: financialStatementItemId]).get()
                        if (financialStatementItemOriginInfo) {
                            financialStatementItemOriginInfoService.update(financialStatementItemOriginInfo, payment, customer)
                        } else {
                            FinancialStatementItemOriginInfo financialStatementItemOriginInfoToSave = financialStatementItemOriginInfoService.buildFinancialStatementItemOriginInfo(financialStatementItem, payment, customer)
                            financialStatementItemOriginInfoToSave.discard()
                            financialStatementItemOriginInfoListToSave.push(financialStatementItemOriginInfoToSave)
                        }
                    }

                    financialStatementItemOriginInfoService.insertOriginInfoInBatch(financialStatementItemOriginInfoListToSave)
                }, [logErrorMessage: "FinancialStatementItemService.processValueAndOriginInfoPendingItems() >> Erro ao processar fsi [${idList}]",
                    logLockAsWarning: true,
                    onError: { itemWithErrorIdList += idList }])
            }

            Utils.forEachWithFlushSession(itemWithErrorIdList, 100, { Long financialStatementItemId ->
                asyncActionService.save(AsyncActionType.BUILD_VALUE_AND_ORIGIN_INFO_FOR_FINANCIAL_STATEMENT_ITEM,
                    null,
                    [financialStatementItemId: financialStatementItemId, isRetry: true],
                    [allowDuplicatePendingWithSameParameters: true])
            })
        })

        if (asyncActionIdToDeleteList) asyncActionService.deleteList(asyncActionIdToDeleteList)
    }

    public FinancialStatementItem save(FinancialStatement financialStatement, Object domainInstance) {
        FinancialStatementItem financialStatementItem = new FinancialStatementItem(financialStatement: financialStatement)

        switch (domainInstance) {
            case CreditTransferRequest:
                financialStatementItem.transfer = domainInstance
                break
            case PromotionalCodeUse:
                financialStatementItem.promotionalCodeUse = domainInstance
                break
            case Payment:
                financialStatementItem.payment = domainInstance
                break
            case Bill:
                financialStatementItem.bill = domainInstance
                break
            case PaymentPostalServiceBatch:
                financialStatementItem.paymentPostalServiceBatch = domainInstance
                break
            case ReceivableAnticipation:
                financialStatementItem.receivableAnticipation = domainInstance
                break
            case Debit:
                financialStatementItem.debit = domainInstance
                break
            case Invoice:
                financialStatementItem.invoice = domainInstance
                break
            case AsaasCardRecharge:
                financialStatementItem.asaasCardRecharge = domainInstance
                break
            case Chargeback:
                financialStatementItem.chargeback = domainInstance
                break
            case RefundRequest:
                financialStatementItem.refundRequest = domainInstance
                break
            case FinancialTransaction:
                FinancialTransaction financialTransaction = domainInstance

                financialStatementItem.financialTransaction = financialTransaction
                if (financialStatement.financialStatementType.isAsyncProcessSaveItems()) financialStatementItem.value = financialTransaction.value.abs()

                break
            case PaymentDunningAccountability:
                financialStatementItem.paymentDunningAccountability = domainInstance
                break
            case BankDeposit:
                financialStatementItem.bankDeposit = domainInstance
                break
            case ReceivableAnticipationPartnerSettlementBatch:
                financialStatementItem.receivableAnticipationPartnerSettlementBatch = domainInstance
                break
            case CreditCardAcquirerOperation:
                financialStatementItem.creditCardAcquirerOperation = domainInstance
                break
            case CreditBureauDunningConciliation:
                financialStatementItem.creditBureauDunningConciliation = domainInstance
                break
            case CercContractualEffectSettlement:
                financialStatementItem.contractualEffectSettlement = domainInstance
                break
            case CustomerDebtAppropriation:
                financialStatementItem.customerDebtAppropriation = domainInstance
                break
            case CustomerLossProvision:
                financialStatementItem.customerLossProvision = domainInstance
                break
            case Map:
                if (financialStatement.financialStatementType.isAsyncProcessSaveItems()) {
                    financialStatementItem.value = domainInstance.value
                    financialStatementItem.originDescription = domainInstance.originDescription
                }
                break
        }

        financialStatementItem.save(failOnError: true)
        return financialStatementItem
    }

    public saveInBatch(FinancialStatement financialStatement, List<Object> domainObjectList) {
        final Integer maxItemsPerBatch = 500
        for (List domainObjectPartialList : domainObjectList.collate(maxItemsPerBatch)) {
            List<Map> financialStatementItemInfoList = domainObjectPartialList.collect { getFinancialStatementItemInfoForDomainObject(it, financialStatement) }
            DatabaseBatchUtils.insertInBatch(dataSource, "financial_statement_item", financialStatementItemInfoList)
        }
    }

    public void saveAsyncAction(Object domainObject, FinancialStatementType financialStatementType, Date statementDate, Long bankId) {
        generateFinancialStatementItemAsyncActionService.save(domainObject, financialStatementType, statementDate, bankId)
    }

    private SequencedResultList<Map> listPrepaidCardFinancialStatementItem(Map params, Integer limit, Integer offset) {
        Map search = [order: "asc"]
        if (params."statementDate[ge]") search."statementDate[ge]" = params."statementDate[ge]"
        if (params."statementDate[le]") search."statementDate[le]" = params."statementDate[le]"
        if (params."financialStatementId") search."financialStatementId" = params."financialStatementId"
        if (params."financialStatementType[in]") {
            if (params."financialStatementType[in]" instanceof String) {
                search.financialStatementType = params."financialStatementType[in]"
            } else {
                search."financialStatementType[in]" = params."financialStatementType[in]"
            }
        }
        List<FinancialStatement> financialStatementList = FinancialStatement.query(search).list(max: limit + 1, offset: offset)
        Boolean hasPreviousPage = offset > 0
        Boolean hasNextPage = financialStatementList.size() > limit

        if (hasNextPage) financialStatementList.removeAt(limit)

        List<Map> prepaidFinancialStatementResult = []

        for (FinancialStatement financialStatement : financialStatementList) {
            if (financialStatement.financialStatementType.canSynchronizeWithoutItem()) {
                List<Map> financialStatementItemList = bifrostPrepaidCardService.listPrepaidFinancialStatementItems(financialStatement.id, limit, offset)
                prepaidFinancialStatementResult += buildPrepaidCardFinancialStatementItemList(financialStatement, financialStatementItemList)
            }
        }

        return new SequencedResultList(prepaidFinancialStatementResult, limit, offset, hasPreviousPage, hasNextPage)
    }

    private void validatePeriod(Map params) {
        final Integer daysLimitToFilter = 30

        Date startDate = CustomDateUtils.fromString(params."statementDate[ge]")
        Date endDate = CustomDateUtils.fromString(params."statementDate[le]")

        if (!startDate || !endDate) {
            throw new BusinessException("É obrigatório informar o período inicial e final, com limite de ${daysLimitToFilter} dias")
        }

        if (CustomDateUtils.calculateDifferenceInDays(startDate, endDate) > daysLimitToFilter) {
            throw new BusinessException("O período máximo para filtro é de ${daysLimitToFilter} dias")
        }
    }

    private SequencedResultList<Map> buildFinancialStatementItemList(List<FinancialStatementItem> financialStatementItemList, Map params, Integer max, Integer offset) {
        Boolean hasPreviousPage = offset > 0
        Boolean hasNextPage = financialStatementItemList.size() > max

        if (hasNextPage) financialStatementItemList.removeAt(max)

        List<Map> result = []

        for (FinancialStatementItem statementItem : financialStatementItemList) {
            Customer customer = getRelatedCustomer(statementItem)
            FinancialStatement financialStatement = statementItem.financialStatement
            result.add([
                financialStatementId: statementItem.financialStatement.id,
                financialStatementCategoryLabel: financialStatement.financialStatementType.getLabel(),
                statementDate: financialStatement.statementDate,
                originDescription: statementItem.originDescription,
                url: FinancialStatementItemBuilder.buildLinkByInstance(statementItem.getRelatedTo(), params),
                value: statementItem.value,
                customerId: customer?.id,
                customerName: customer?.getProviderName()
            ])
        }

        return new SequencedResultList<Map>(result, max, offset, hasPreviousPage, hasNextPage)
    }

    private List<Map> buildPrepaidCardFinancialStatementItemList(FinancialStatement financialStatement, List<Map> financialStatementItemList) {
        List<Map> result = financialStatementItemList.collect { it ->
            [
                financialStatementId: financialStatement.id,
                financialStatementCategoryLabel: financialStatement.financialStatementType.getLabel(),
                statementDate: financialStatement.statementDate,
                originDescription: "Transação referente ao cartão pré-pago",
                url: AsaasApplicationHolder.applicationContext.grailsLinkGenerator.link(controller: "asaasCardTransactionAdmin", action: "show", params: [asaasCardId: it.asaasCardId, externalId: it.transactionId]),
                value: financialStatement.financialStatementType.isExpense() ? BigDecimalUtils.negate(it.value) : BigDecimalUtils.abs(it.value),
                customerId: it.customerId,
                customerName: it.customerName
            ]
        }

        return result
    }

    private Customer getRelatedCustomer(FinancialStatementItem financialStatementItem) {
        Customer customer = null
        Boolean isInternalBillPaymentCredit = financialStatementItem.financialStatement.financialStatementType == FinancialStatementType.INTERNAL_BILL_PAYMENT_CREDIT
        if (isInternalBillPaymentCredit) {
            Map search = [:]
            search.bill = financialStatementItem.financialTransaction.bill
            search.column = "payment.provider"
            customer = BillAsaasPayment.query(search).get()
        } else {
            customer = FinancialStatementItemBuilder.getRelatedCustomer(financialStatementItem)
        }

        return customer
    }

    private String buildOriginDescription(financialStatementItem) {
        String originDescription = FinancialStatementItemBuilder.buildOriginDescription(financialStatementItem, null, null)
        final Integer originDescriptionMaxSize = 255

        return Utils.truncateString(originDescription, originDescriptionMaxSize)
    }

    private void saveFinancialStatementItemPayment(FinancialStatementItem item, Payment payment, FinancialStatementItemPaymentOrigin financialStatementItemPaymentOrigin) {
        FinancialStatementItemPayment financialStatementItemPayment = new FinancialStatementItemPayment()
        financialStatementItemPayment.financialStatementItem = item
        financialStatementItemPayment.payment = payment
        financialStatementItemPayment.originDomain = financialStatementItemPaymentOrigin
        financialStatementItemPayment.save(failOnError: true)
    }

    private Map getFinancialStatementItemInfoForDomainObject(domainInstance, FinancialStatement financialStatement) {
        BigDecimal itemValue = null
        String originDescription = null

        if (domainInstance instanceof Map) {
            itemValue = domainInstance.value
            originDescription = domainInstance.originDescription
        } else if (domainInstance instanceof FinancialTransaction && financialStatement.financialStatementType.isAsyncProcessSaveItems()) {
            itemValue = domainInstance.value
        }

        return [
            "version": "0",
            "date_created": new Date(),
            "last_updated": new Date(),
            "deleted": 0,
            "financial_statement_id": financialStatement.id,
            "value": itemValue,
            "origin_description": originDescription,
            "bill_id": domainInstance instanceof Bill ? domainInstance.id : null,
            "payment_id": domainInstance instanceof Payment ? domainInstance.id : null,
            "payment_postal_service_batch_id": domainInstance instanceof PaymentPostalServiceBatch ? domainInstance.id : null,
            "promotional_code_use_id": domainInstance instanceof PromotionalCodeUse ? domainInstance.id : null,
            "receivable_anticipation_id": domainInstance instanceof ReceivableAnticipation ? domainInstance.id : null,
            "transfer_id": domainInstance instanceof CreditTransferRequest ? domainInstance.id : null,
            "debit_id": domainInstance instanceof Debit ? domainInstance.id : null,
            "invoice_id": domainInstance instanceof Invoice ? domainInstance.id : null,
            "asaas_card_recharge_id": domainInstance instanceof AsaasCardRecharge ? domainInstance.id : null,
            "refund_request_id": domainInstance instanceof RefundRequest ? domainInstance.id : null,
            "chargeback_id": domainInstance instanceof Chargeback ? domainInstance.id : null,
            "financial_transaction_id": domainInstance instanceof FinancialTransaction ? domainInstance.id : null,
            "payment_dunning_accountability_id": domainInstance instanceof PaymentDunningAccountability ? domainInstance.id : null,
            "bank_deposit_id": domainInstance instanceof BankDeposit ? domainInstance.id : null,
            "receivable_anticipation_partner_settlement_batch_id": domainInstance instanceof ReceivableAnticipationPartnerSettlementBatch ? domainInstance.id : null,
            "credit_card_acquirer_operation_id": domainInstance instanceof CreditCardAcquirerOperation ? domainInstance.id : null,
            "credit_bureau_dunning_conciliation_id": domainInstance instanceof CreditBureauDunningConciliation ? domainInstance.id : null,
            "contractual_effect_settlement_id": domainInstance instanceof CercContractualEffectSettlement ? domainInstance.id : null,
            "customer_loss_provision_id": domainInstance instanceof CustomerLossProvision ? domainInstance.id : null,
            "customer_debt_appropriation_id": domainInstance instanceof CustomerDebtAppropriation ? domainInstance.id : null
        ]
    }
}
