package com.asaas.service.receivableanticipation

import com.asaas.billinginfo.BillingType
import com.asaas.billinginfo.ChargeType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerreceivableanticipationconfig.CustomerAutomaticReceivableAnticipationConfig
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.file.TemporaryFile
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.domain.paymentinfo.PaymentAnticipableInfo
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationLimitRecalculationInfo
import com.asaas.domain.receivableanticipation.ReceivableAnticipationOriginRequesterInfoMethod
import com.asaas.domain.receivableanticipationsimulation.ReceivableAnticipationSimulationBatch
import com.asaas.domain.receivableanticipationsimulation.ReceivableAnticipationSimulationBatchItem
import com.asaas.domain.receivableanticipationsimulation.ReceivableAnticipationSimulationBatchItemFile
import com.asaas.domain.receivableanticipationsimulation.ReceivableAnticipationSimulationBatchOriginRequesterInfo
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.originrequesterinfo.EventOriginType
import com.asaas.payment.PaymentStatus
import com.asaas.paymentinfo.PaymentAnticipableInfoStatus
import com.asaas.receivableanticipation.ReceivableAnticipationCalculator
import com.asaas.receivableanticipation.ReceivableAnticipationDocumentVO
import com.asaas.receivableanticipation.ReceivableAnticipationFinancialInfoVO
import com.asaas.receivableanticipation.ReceivableAnticipationSchedulingValidator
import com.asaas.receivableanticipation.adapter.CreateReceivableAnticipationAdapter
import com.asaas.receivableanticipationcompromisedvalue.ReceivableAnticipationCompromisedValueCache
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.receivableanticipationsimulationbatch.ReceivableAnticipationSimulationBatchItemStatus
import com.asaas.receivableanticipationsimulationbatch.ReceivableAnticipationSimulationBatchOriginType
import com.asaas.receivableanticipationsimulationbatch.ReceivableAnticipationSimulationBatchPrioritization
import com.asaas.receivableanticipationsimulationbatch.ReceivableAnticipationSimulationBatchStatus
import com.asaas.utils.AbTestUtils
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.apache.commons.lang.NotImplementedException

@Transactional
class ReceivableAnticipationBatchService {

    def customerAlertNotificationService
    def customerReceivableAnticipationConfigService
    def originRequesterInfoService
    def receivableAnticipationFinancialInfoService
    def receivableAnticipationService
    def receivableAnticipationPartnerConfigService
    def receivableAnticipationValidationCacheService

    public Map proceedWithSimulationBatch(Customer customer, Long simulationId) {
        final Integer maxItemsThatCanBeSavedSynchronous = 30

        Boolean hasAnySimulationBatchReadyToSave = ReceivableAnticipationSimulationBatch.readyToSave([exists: true, customer: customer]).get().asBoolean()
        if (hasAnySimulationBatchReadyToSave) throw new BusinessException("Já existe uma solicitação de simulação em processo")

        Map response = [:]
        ReceivableAnticipationSimulationBatch simulation = ReceivableAnticipationSimulationBatch.find(customer, simulationId)

        if (simulation.quantity < maxItemsThatCanBeSavedSynchronous) {
            List<Long> itemIdList = ReceivableAnticipationSimulationBatchItem.pending([column: "id", simulationBatch: simulation, sort: "dueDate", order: "asc"]).list()

            EventOriginType batchOriginRequesterInfoEventOrigin = ReceivableAnticipationSimulationBatchOriginRequesterInfo.query([column: "eventOrigin", simulationBatchId: simulationId]).get()

            Map result = saveAnticipationsFromSimulationItemList(itemIdList, batchOriginRequesterInfoEventOrigin)

            simulation.status = ReceivableAnticipationSimulationBatchStatus.PROCESSED
            simulation.save(failOnError: true)

            response.processedSimulation = true
            response.successCount = result.successCount
            response.failCount = result.failCount
        } else {
            simulation.status = ReceivableAnticipationSimulationBatchStatus.READY_TO_SAVE
            simulation.save(failOnError: true)

            response.itemsToSave = simulation.quantity
        }

        return response
    }

    public ReceivableAnticipationSimulationBatch simulateBatchRequest(Customer customer, BigDecimal maxValueToAnticipate, Map params) {
        List<ReceivableAnticipationSimulationBatchItem> simulationItemList = getReceivableAnticipationSimulationBatchItemList(customer, maxValueToAnticipate, params)

        ReceivableAnticipationSimulationBatch receivableAnticipationSimulationBatch = saveSimulationBatch(customer, params.billingType, ReceivableAnticipationSimulationBatchOriginType.MANUAL)
        setSimulationBatchAsPendingAndRecalculate(receivableAnticipationSimulationBatch, simulationItemList)

        return receivableAnticipationSimulationBatch
    }

    public ReceivableAnticipationSimulationBatchItem getFirstPaymentOrInstallmentAvailableForAnticipation(Customer customer, Map params) {
        params.sort = "dueDate"
        params.order = "asc"

        Payment payment = PaymentAnticipableInfo.query(params + getDefaultParamsToFindDetachedAnticipablePayment(customer) + ["dueDate[le]": ReceivableAnticipation.getLimitDateToAnticipate(customer).clearTime(), column: "payment"]).get()

        if (!payment) payment = PaymentAnticipableInfo.query(params + getDefaultParamsToFindDetachedAnticipablePayment(customer) + [billingType: BillingType.MUNDIPAGG_CIELO, column: "payment"]).get()
        if (payment) {
            BigDecimal valueToBeAnticipated = receivableAnticipationService.calculateReceivableAnticipationValue(payment)

            ReceivableAnticipationPartner receivableAnticipationPartner = receivableAnticipationPartnerConfigService.getReceivableAnticipationPartner(customer, ChargeType.DETACHED, payment.customerAccount.cpfCnpj, payment.billingType, valueToBeAnticipated)

            ReceivableAnticipationFinancialInfoVO receivableAnticipationFinancialInfoVO = new ReceivableAnticipationFinancialInfoVO()
            receivableAnticipationFinancialInfoVO.build(payment, false)

            Map financialInfo = receivableAnticipationFinancialInfoService.buildFinancialInfo(customer, receivableAnticipationFinancialInfoVO, receivableAnticipationPartner)

            Map processedPayment = [payment: payment, financialInfo: financialInfo]

            return saveReceivableAnticipationSimulationItem(processedPayment)
        }

        Installment installment = PaymentAnticipableInfo.query(params + getDefaultParamsToFindAnticipableCreditCardInstallment(customer) + [column: "installment"]).get()
        if (installment) {
            List<Payment> confirmedPaymentList = installment.getConfirmedPayments()
            if (!confirmedPaymentList) throw new BusinessException("Não foi possível antecipar as cobranças selecionadas")
            BigDecimal valueToBeAnticipated = receivableAnticipationService.calculateReceivableAnticipationValue(confirmedPaymentList)
            ReceivableAnticipationPartner receivableAnticipationPartner = receivableAnticipationPartnerConfigService.getReceivableAnticipationPartner(customer, ChargeType.INSTALLMENT, installment.customerAccount.cpfCnpj, installment.billingType, valueToBeAnticipated)

            ReceivableAnticipationFinancialInfoVO receivableAnticipationFinancialInfoVO = new ReceivableAnticipationFinancialInfoVO()
            receivableAnticipationFinancialInfoVO.build(installment, false, confirmedPaymentList)

            Map financialInfo = receivableAnticipationFinancialInfoService.buildFinancialInfo(customer, receivableAnticipationFinancialInfoVO, receivableAnticipationPartner)

            Map processedInstallment = [installment: installment, financialInfo: financialInfo]

            return saveReceivableAnticipationSimulationItem(processedInstallment)
        }
    }

    public List<Long> listPaymentsAvailableForAnticipation(Customer customer, BigDecimal maxValueToAnticipate, Map params) {
        if (!customer.canAnticipate()) throw new BusinessException(Utils.getMessageProperty("receivableAnticipationValidationClosures.customerInvalidStatus"))

        if (!params.billingType || params.billingType.isBoleto()) {
            if (!customer.canAnticipateBoleto()) throw new BusinessException(Utils.getMessageProperty("receivableAnticipationValidationClosures.isBoletoAndCustomerIsLegalPerson"))
        }

        BigDecimal anticipationLimitForBankSlip = customerReceivableAnticipationConfigService.calculateBankSlipAndPixAvailableLimit(customer)
        BigDecimal paymentMinimumValue = Payment.getMinimumBankSlipAndPixValue(customer)
        List<Long> availablePaymentIdList = []
        Boolean findPaymentsAvailableForAnticipation = true
        Integer queryMaxPayments = 1000
        Integer queryOffset = 0
        BigDecimal valueSelected = 0.0

        while (findPaymentsAvailableForAnticipation) {
            if (anticipationLimitForBankSlip < paymentMinimumValue) params = params + [billingType: BillingType.MUNDIPAGG_CIELO]

            List<Map> paymentAnticipableInfoList = getAnticipablePaymentList(customer, params, maxValueToAnticipate, queryMaxPayments, queryOffset)
            if (!paymentAnticipableInfoList) break

            queryOffset = queryOffset + queryMaxPayments

            BigDecimal minValueFound = paymentAnticipableInfoList*.value.min()

            if (minValueFound > maxValueToAnticipate) return

            for (Map paymentAnticipableInfo : paymentAnticipableInfoList) {
                if (availablePaymentIdList.contains(paymentAnticipableInfo."payment.id")) continue
                if (paymentAnticipableInfo.value > maxValueToAnticipate) continue
                if (ReceivableAnticipationSchedulingValidator.paymentIsScheduled(
                    paymentAnticipableInfo.billingType,
                    paymentAnticipableInfo."payment.status",
                    paymentAnticipableInfo."payment.confirmedDate",
                    paymentAnticipableInfo.dueDate,
                    null,
                    customer)
                ) continue

                if (paymentAnticipableInfo.billingType.isBoleto()) {
                    if (anticipationLimitForBankSlip < paymentAnticipableInfo.value) continue
                    anticipationLimitForBankSlip = anticipationLimitForBankSlip - paymentAnticipableInfo.value
                }

                valueSelected += paymentAnticipableInfo.value
                availablePaymentIdList.add(paymentAnticipableInfo."payment.id")
                maxValueToAnticipate = maxValueToAnticipate - paymentAnticipableInfo.value
                queryOffset = 0

                if (maxValueToAnticipate < minValueFound) break
            }
            if (maxValueToAnticipate < paymentMinimumValue) break
        }

        return availablePaymentIdList
    }

    public Map getSimulationBatchReadyToSaveInfo(Customer customer) {
        ReceivableAnticipationSimulationBatch simulationBatchReadyToSave = ReceivableAnticipationSimulationBatch.readyToSave([customer: customer]).get()

        if (!simulationBatchReadyToSave) return [:]

        return [simulationBatchReadyToSave: simulationBatchReadyToSave,
                processedSimulationItemsCount: ReceivableAnticipationSimulationBatchItem.query([simulationBatch: simulationBatchReadyToSave,
                                                                                                "status[in]": [ReceivableAnticipationSimulationBatchItemStatus.PROCESSED, ReceivableAnticipationSimulationBatchItemStatus.ERROR]]).count()]
    }

    public Map getProcessedSimulationBatchInfo(Customer customer, Long simulationBatchId) {
        ReceivableAnticipationSimulationBatch simulationBatch = ReceivableAnticipationSimulationBatch.query([customer: customer, status: ReceivableAnticipationSimulationBatchStatus.PROCESSED, id: simulationBatchId]).get()
        Integer errorCount = ReceivableAnticipationSimulationBatchItem.query([simulationBatch: simulationBatch, status: ReceivableAnticipationSimulationBatchItemStatus.ERROR]).count()
        Integer successCount = ReceivableAnticipationSimulationBatchItem.query([simulationBatch: simulationBatch, status: ReceivableAnticipationSimulationBatchItemStatus.PROCESSED]).count()

        if (!simulationBatch) return [:]

        return [errorCount: errorCount,
                successCount: successCount,
                simulationBatchId: simulationBatchId]
    }

    public void proceedWithManualSimulationBatchsReadyToSave() {
        proceedWithSimulationBatchsReadyToSave(ReceivableAnticipationSimulationBatchOriginType.MANUAL)
    }

    public void proceedWithAutomaticSimulationBatchsReadyToSave() {
        proceedWithSimulationBatchsReadyToSave(ReceivableAnticipationSimulationBatchOriginType.AUTOMATIC)
    }

    public void proceedBatchsReadyToSave(List<Long> simulationBatchIdList, Integer maxThreads, Integer maxItemsPerThread) {
        if (!simulationBatchIdList) return

        Utils.processWithThreads(simulationBatchIdList, maxThreads, { Long simulationBatchId ->
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipationSimulationBatch simulation = ReceivableAnticipationSimulationBatch.get(simulationBatchId)
                if (!simulation) throw new BusinessException("Não foi possível encontrar o batch id ${simulationBatchId}")

                List<Long> simulationItemIdList = ReceivableAnticipationSimulationBatchItem.pending([column: "id", simulationBatch: simulation, sort: "dueDate", order: "asc"]).list(max: maxItemsPerThread)
                if (!simulationItemIdList) {
                    setSimulationBatchAsProcessedAndSendNotification(simulation)
                    return
                }

                EventOriginType batchOriginRequesterInfoEventOrigin = ReceivableAnticipationSimulationBatchOriginRequesterInfo.query([column: "eventOrigin", simulationBatchId: simulationBatchId]).get()

                saveAnticipationsFromSimulationItemList(simulationItemIdList, batchOriginRequesterInfoEventOrigin)

                simulation.lastProcessedDate = new Date()
                simulation.save(failOnError: true)
            }, [logErrorMessage: "ReceivableAnticipationBatchService.proceedWithSimulationBatchsReadyToSave >> Erro ao salvar antecipação do simulador [simulationBatchId: ${simulationBatchId}]"])
        })
    }

    public Map saveAnticipationsFromSimulationItemList(List<Long> simulationItemIdList, EventOriginType batchOriginRequesterInfoEventOrigin) {
        Integer successCount = 0
        Integer failCount = 0

        Utils.forEachWithFlushNewSession(simulationItemIdList, 50, { Long itemId ->
            Boolean isItemProcessed = false

            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipationSimulationBatchItem item = ReceivableAnticipationSimulationBatchItem.get(itemId)
                List<ReceivableAnticipationDocumentVO> receivableAnticipationDocumentVOList = getReceivableAnticipationDocumentVOListFromSimulationItem(item)
                ReceivableAnticipationOriginRequesterInfoMethod method = item.simulationBatch.originType.isAutomatic() ? ReceivableAnticipationOriginRequesterInfoMethod.BATCH_AUTOMATIC : ReceivableAnticipationOriginRequesterInfoMethod.BATCH_MANUAL

                CreateReceivableAnticipationAdapter createReceivableAnticipationAdapter = CreateReceivableAnticipationAdapter.buildWithSimulationBatch(item.installment, item.payment, receivableAnticipationDocumentVOList, method, batchOriginRequesterInfoEventOrigin)
                ReceivableAnticipation receivableAnticipation = receivableAnticipationService.save(createReceivableAnticipationAdapter)
                if (receivableAnticipation.hasErrors()) return

                item.receivableAnticipation = receivableAnticipation
                item.status = ReceivableAnticipationSimulationBatchItemStatus.PROCESSED
                item.save(failOnError: true)

                isItemProcessed = true
                successCount++
            }, [
                ignoreStackTrace: true,
                onError: { Exception exception ->
                    if (Utils.isLock(exception)) {
                        AsaasLogger.warn("saveAnticipationsFromSimulationItemList >> A antecipação do item [${itemId}] sofreu um lock", exception)
                        return
                    }

                    if (exception instanceof BusinessException) {
                        AsaasLogger.warn("saveAnticipationsFromSimulationItemList >> A antecipação do item [${itemId}] não pode ser solicitada", exception)
                        return
                    }

                    AsaasLogger.error("saveAnticipationsFromSimulationItemList >> Erro ao salvar uma antecipação do item [${itemId}]", exception)
                }
            ])

            if (isItemProcessed) return

            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipationSimulationBatchItem item = ReceivableAnticipationSimulationBatchItem.get(itemId)
                item.status = ReceivableAnticipationSimulationBatchItemStatus.ERROR
                item.save(failOnError: true)
                failCount++
            }, [logErrorMessage: "saveAnticipationsFromSimulationItemList >> Erro ao atualizar status do item [${itemId}]"])
        })

        return [successCount: successCount, failCount: failCount]
    }

    public Map buildAnticipationTotalValues(Customer customer) {
        CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
        Map anticipationTotalValues

        if (customerReceivableAnticipationConfig.hasCreditCardPercentage()) {
            anticipationTotalValues = buildPercentageTotalValues(customer)
        } else {
            anticipationTotalValues = buildBankSlipAnticipationTotalValues(customer) + buildCreditCardAnticipationTotalValues(customer)
            if (!anticipationTotalValues) return [:]

            anticipationTotalValues.totalBalanceAvailable = anticipationTotalValues.boletoAnticipableValue + anticipationTotalValues.creditCardAnticipableValue
        }

        if (AbTestUtils.hasPixAnticipation(customer)) {
            anticipationTotalValues += buildPixAnticipationTotalValues(customer)
            anticipationTotalValues.totalBalanceAvailable += anticipationTotalValues.pixAnticipableValue
        }

        return anticipationTotalValues
    }

    public Map buildBankSlipAnticipationTotalValues(Customer customer) {
        Map anticipationValues = getBankSlipValueAvailableForAnticipation(customer)
        if (!anticipationValues) return [:]

        return [
            boletoAnticipableValue: BigDecimalUtils.min(anticipationValues.totalValueAvailableForDetachedBankSlipPayments, anticipationValues.availableLimitForBankSlip),
            boletoBalanceAvailable: anticipationValues.totalValueAvailableForDetachedBankSlipPayments,
            boletoLimitAvailable: anticipationValues.availableLimitForBankSlip
        ]
    }

    public Map buildPixAnticipationTotalValues(Customer customer) {
        Map anticipationValues = getPixValueAvailableForAnticipation(customer)
        if (!anticipationValues) return [:]

        return [
            pixAnticipableValue: BigDecimalUtils.min(anticipationValues.totalValueAvailableForDetachedPixPayments, anticipationValues.availableLimitForPix),
            pixBalanceAvailable: anticipationValues.totalValueAvailableForDetachedPixPayments,
            pixLimitAvailable: anticipationValues.availableLimitForPix
        ]
    }

    public Map buildCreditCardAnticipationTotalValues(Customer customer) {
        Map anticipationValues = getCreditCardValueAvailableForAnticipation(customer)
        if (!anticipationValues) return [:]

        BigDecimal creditCardBalanceAvailable = anticipationValues.totalValueAvailableForCreditCardInstallment + anticipationValues.totalValueAvailableForDetachedCreditCardPayments

        Map anticipationTotalValues = [
            creditCardAnticipableValue: BigDecimalUtils.min(creditCardBalanceAvailable, anticipationValues.availableLimitForCreditCard),
            creditCardBalanceAvailable: creditCardBalanceAvailable,
            creditCardLimitAvailable: anticipationValues.availableLimitForCreditCard
        ]

        return anticipationTotalValues
    }

    public void updateSimulationItemFileList(Customer customer, Long simulationItemId, List<Long> addedFileList, List<Long> removedFileList) {
        for (Long fileId in addedFileList) {
            saveSimulationItemFile(customer, simulationItemId, fileId)
        }

        for (Long fileId in removedFileList) {
            deleteSimulationItemFile(customer, simulationItemId, fileId)
        }
    }

    public Map loadSimulationList(Long simulationId, Customer customer, Integer max, Integer offset) {
        ReceivableAnticipationSimulationBatch simulation = ReceivableAnticipationSimulationBatch.find(customer, simulationId)
        List<ReceivableAnticipationSimulationBatchItem> detachedSimulationItemList = []
        List<ReceivableAnticipationSimulationBatchItem> installmentSimulationItemList = []

        if (simulation.billingType?.isCreditCard()) {
            List<ReceivableAnticipationSimulationBatchItem> batchItemList = ReceivableAnticipationSimulationBatchItem.query(getPrioritizationParameters(simulation.prioritization) + [simulationBatch: simulation]).list(max: max, offset: offset)
            detachedSimulationItemList = batchItemList.findAll { it.payment }
            installmentSimulationItemList = batchItemList.findAll { it.installment }
         } else {
            detachedSimulationItemList = simulation.getDetachedSimulationItemList(getPrioritizationParameters(simulation.prioritization))
            installmentSimulationItemList = simulation.getInstallmentSimulationItemList(getPrioritizationParameters(simulation.prioritization))
         }

        Boolean hasPixAnticipation = AbTestUtils.hasPixAnticipation(customer)
        Boolean anticipationBatchHasAnyBoletoOrPix = detachedSimulationItemList.any { it.billingType.isBoleto() || (hasPixAnticipation && it.billingType.isPix()) }

        return [installmentSimulationList: installmentSimulationItemList,
                detachedSimulationList: detachedSimulationItemList,
                anticipationBatchHasAnyBoletoOrPix: anticipationBatchHasAnyBoletoOrPix,
                valueWithoutPaymentFee: simulation.getValueWithoutPaymentFee(),
                simulation: simulation]
    }

    public void saveSimulationBatchToAutomaticAnticipationIfNecessary(Customer customer) {
        Boolean creditCardEnabledWithoutCache = CustomerReceivableAnticipationConfig.query([column: "creditCardEnabled", customerId: customer.id]).get().asBoolean()

        if (!creditCardEnabledWithoutCache) return

        Boolean hasConfigActivated = receivableAnticipationValidationCacheService.isAutomaticActivated(customer.id)
        if (!hasConfigActivated) return

        Boolean hasBatch = ReceivableAnticipationSimulationBatch.awaitingItemsCreationToAutomaticAnticipation([exists: true, "batchReadyToSave[notExists]": false, customer: customer]).get().asBoolean()
        if (hasBatch) return

        saveSimulationBatch(customer, BillingType.MUNDIPAGG_CIELO, ReceivableAnticipationSimulationBatchOriginType.AUTOMATIC)
    }

    public void processAwaitingItemsCreationToManualAnticipation(BillingType billingType) {
        final Integer maxBatchList = 8
        final Integer numberOfThreads = 8

        Map batchSearch = [column: "id", sort: "id", order: "asc"]
        List<Long> simulationBatchIdList = ReceivableAnticipationSimulationBatch.awaitingItemsCreationToManualAnticipation(batchSearch, billingType).list(max: maxBatchList)

        Utils.processWithThreads(simulationBatchIdList, numberOfThreads, { List<Long> idList ->
            for (Long simulationBatchId : idList) {
                Utils.withNewTransactionAndRollbackOnError({
                    ReceivableAnticipationSimulationBatch simulationBatch = ReceivableAnticipationSimulationBatch.get(simulationBatchId)

                    Map search = getPrioritizationParameters(simulationBatch.prioritization)
                    search.billingType = simulationBatch.billingType

                    List<ReceivableAnticipationSimulationBatchItem> simulationItemList = getReceivableAnticipationSimulationBatchItemList(simulationBatch.customer, simulationBatch.requestedValue, search)

                    if (!simulationItemList) {
                        setSimulationBatchAsCancelled(simulationBatch)
                        return
                    }

                    setSimulationBatchAsPendingAndRecalculate(simulationBatch, simulationItemList)
                }, [onError: { setSimulationBatchAsErrorWithNewTransaction(simulationBatchId) }, logErrorMessage: "ReceivableAnticipationBatchService.processAwaitingItemsCreationToManualAnticipation >> Erro ao processar batch ${simulationBatchId} para antecipação manual"])
            }
        })
    }

    public void processAwaitingItemsCreationToAutomaticAnticipation() {
        final Integer maxBatchList = 5

        List<Long> simulationBatchIdList = ReceivableAnticipationSimulationBatch.awaitingItemsCreationToAutomaticAnticipation([column: "id", "batchReadyToSave[notExists]": true, sort: "id", order: "asc"]).list(maxBatchList)

        for (Long simulationBatchId in simulationBatchIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipationSimulationBatch simulationBatch = ReceivableAnticipationSimulationBatch.get(simulationBatchId)
                if (!simulationBatch) throw new RuntimeException("ReceivableAnticipationBatchService.processSimulationBatchToAutomaticAntecipation >> Não foi possível encontrar o batch para antecipação automática [${simulationBatchId}]")

                CustomerAutomaticReceivableAnticipationConfig config = CustomerAutomaticReceivableAnticipationConfig.query([customerId: simulationBatch.customer.id]).get()
                if (!config.active) {
                    setSimulationBatchAsCancelled(simulationBatch)
                    AsaasLogger.info("ReceivableAnticipationBatchService.processSimulationBatchToAutomaticAntecipation >> Cliente ${config.customer.id} inativou a antecipação automática em ${config.lastDeactivationDate}")
                    return
                }

                Map valuesToAnticipation = buildCreditCardAnticipationTotalValues(simulationBatch.customer)
                Map search = getSearchParamsAutomaticAnticipation()
                List<ReceivableAnticipationSimulationBatchItem> simulationItemList = getReceivableAnticipationSimulationBatchItemList(simulationBatch.customer, valuesToAnticipation.creditCardAnticipableValue, search)

                if (!simulationItemList) {
                    setSimulationBatchAsCancelled(simulationBatch)
                    AsaasLogger.info("ReceivableAnticipationBatchService.processSimulationBatchToAutomaticAntecipation >> Nenhuma cobrança foi encontrada [customerId: ${config.customer.id}]")
                    return
                }

                setSimulationBatchAsReadyToSaveAndRecalculate(simulationBatch, simulationItemList)
            }, [onError: { setSimulationBatchAsErrorWithNewTransaction(simulationBatchId) }, logErrorMessage: "ReceivableAnticipationBatchService.processSimulationBatchToAutomaticAntecipation >> Erro ao processar batch para antecipação automática [simulationBatchId: ${simulationBatchId}]"])
        }
    }

    public List<ReceivableAnticipationSimulationBatchItem> getReceivableAnticipationSimulationBatchItemList(Customer customer, BigDecimal maxValueToAnticipate, Map params) {
        List<ReceivableAnticipationSimulationBatchItem> simulationItemList = Collections.synchronizedList(new ArrayList<ReceivableAnticipationSimulationBatchItem>())

        if (!params.billingType && !customer.canAnticipateBoleto()) params.billingType = BillingType.MUNDIPAGG_CIELO

        if (maxValueToAnticipate > 0) {
            List<Long> paymentIdList = listPaymentsAvailableForAnticipation(customer, maxValueToAnticipate, params)

            if (paymentIdList) {
                final Integer minItemsPerThread = 250
                Integer itemsPerThreadIfListSizeExceedLimitOfThreads = Math.ceil(paymentIdList.size() / ThreadUtils.DEFAULT_LIMIT_OF_THREADS).toInteger()
                Integer itemsPerThread = Math.max(minItemsPerThread, itemsPerThreadIfListSizeExceedLimitOfThreads)

                ThreadUtils.processWithThreadsOnDemand(paymentIdList, itemsPerThread, { List<Long> paymentIdListFromThread ->
                    Payment.withTransaction {
                        simulationItemList.addAll(getProcessedPaymentList(paymentIdListFromThread, customer.id))
                    }
                })
            }
        }

        if (!simulationItemList) {
            ReceivableAnticipationSimulationBatchItem batchItem = getFirstPaymentOrInstallmentAvailableForAnticipation(customer, params)
            if (batchItem) simulationItemList.add(batchItem)
        }

        return simulationItemList
    }

    public ReceivableAnticipationSimulationBatch saveManualSimulationBatch(Customer customer, BillingType billingType, BigDecimal requestedValue, ReceivableAnticipationSimulationBatchPrioritization prioritization, ReceivableAnticipationSimulationBatchOriginType originType) {
        cancelAllAwaitingItemsCreationManualSimulationBatch(customer)

        ReceivableAnticipationSimulationBatch simulationBatch = saveSimulationBatch(customer, billingType, originType)
        simulationBatch.prioritization = prioritization
        simulationBatch.requestedValue = requestedValue
        simulationBatch.save(failOnError: true)

        return simulationBatch
    }

    private void proceedWithSimulationBatchsReadyToSave(ReceivableAnticipationSimulationBatchOriginType originType) {
        final Integer maxThreads = 5
        List<Long> simulationBatchIdList = ReceivableAnticipationSimulationBatch.readyToSave([column: "id",
                                                                                              "originType": originType,
                                                                                              sort: "lastProcessedDate",
                                                                                              order: "asc"]).list(max: maxThreads)
        if (!simulationBatchIdList) return

        proceedBatchsReadyToSave(simulationBatchIdList, maxThreads, 50)
    }

    private Map buildPercentageTotalValues(Customer customer) {
        CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)

        BigDecimal lastCreditCardTotalConfirmedValue = ReceivableAnticipationLimitRecalculationInfo.query([column: "lastCreditCardTotalConfirmedValue", "customerId": customer.id]).get()
        BigDecimal creditCardTotalValue = lastCreditCardTotalConfirmedValue ?: getSumValueOfCreditCard(customer)
        BigDecimal creditCardLimit = receivableAnticipationConfig.buildCreditCardAnticipationLimit()
        BigDecimal creditCardNotDebitedValue = ReceivableAnticipationCalculator.calculateNotDebitedValueForCreditCard(customer)
        BigDecimal creditCardNotAnticipableValue = 0.00
        BigDecimal creditCardBalanceAvailable = getSumValueOfCreditCardAnticipable(customer)
        BigDecimal creditCardLimitAvailable = BigDecimalUtils.roundDown(BigDecimalUtils.max(creditCardLimit - creditCardNotDebitedValue, 0.00))
        BigDecimal creditCardAnticipableValue = BigDecimalUtils.min(creditCardBalanceAvailable, creditCardLimitAvailable)

        BigDecimal bankSlipLimit = receivableAnticipationConfig.bankSlipAnticipationLimit ?: 0.00
        BigDecimal bankSlipNotDebitedValue = ReceivableAnticipationCompromisedValueCache.getCompromisedValueForBankSlipAndPix(customer)

        Map bankSlipAnticipationTotalValues = buildBankSlipAnticipationTotalValues(customer)

        BigDecimal totalBalanceAvailable = bankSlipAnticipationTotalValues.boletoAnticipableValue + creditCardAnticipableValue

        return [
            creditCardTotalValue: creditCardTotalValue,
            creditCardLimit: creditCardLimit,
            creditCardAnticipatedValue: creditCardNotDebitedValue,
            creditCardNotAnticipableValue: creditCardNotAnticipableValue,
            creditCardBalanceAvailable: creditCardBalanceAvailable,
            creditCardLimitAvailable: creditCardLimitAvailable,
            creditCardAnticipableValue: creditCardAnticipableValue,
            totalBalanceAvailable: totalBalanceAvailable,
            bankSlipLimit: bankSlipLimit,
            bankSlipAnticipatedValue: bankSlipNotDebitedValue,
        ] + bankSlipAnticipationTotalValues
    }

    private void cancelAllAwaitingItemsCreationManualSimulationBatch(Customer customer) {
        Map search = [customer: customer]
        List<ReceivableAnticipationSimulationBatch> batchList = []

        batchList.addAll(ReceivableAnticipationSimulationBatch.awaitingItemsCreationToManualAnticipation(search, BillingType.BOLETO).list())
        batchList.addAll(ReceivableAnticipationSimulationBatch.awaitingItemsCreationToManualAnticipation(search, BillingType.MUNDIPAGG_CIELO).list())
        batchList.addAll(ReceivableAnticipationSimulationBatch.awaitingItemsCreationToManualAnticipation(search, null).list())

        for (ReceivableAnticipationSimulationBatch batch : batchList) {
            setSimulationBatchAsCancelled(batch)
        }
    }

    private ReceivableAnticipationSimulationBatch saveSimulationBatch(Customer customer, BillingType billingType, ReceivableAnticipationSimulationBatchOriginType originType) {
        ReceivableAnticipationSimulationBatch simulationBatch = new ReceivableAnticipationSimulationBatch()
        simulationBatch.customer = customer
        simulationBatch.billingType = billingType
        simulationBatch.originType = originType
        simulationBatch.status = ReceivableAnticipationSimulationBatchStatus.AWAITING_ITEMS_CREATION
        simulationBatch.value = 0
        simulationBatch.netValue = 0
        simulationBatch.paymentFee = 0
        simulationBatch.anticipationFee = 0
        simulationBatch.quantity = 0
        simulationBatch.save(failOnError: true, flush: true)

        originRequesterInfoService.save(simulationBatch)

        return simulationBatch
    }

    private Map getPrioritizationParameters(ReceivableAnticipationSimulationBatchPrioritization prioritization) {
        if (prioritization.isDueDateAsc()) return [sort: "dueDate", order: "asc"]

        if (prioritization.isDueDateDesc()) return [sort: "dueDate", order: "desc"]

        throw new NotImplementedException("Priorização ${prioritization} não implementada")
    }

    private Map getBankSlipValueAvailableForAnticipation(Customer customer) {
        BigDecimal totalValueAvailableForDetachedBankSlipPayments = 0
        BigDecimal availableLimitForBankSlip = 0

        if (customer.canAnticipate()) {
            totalValueAvailableForDetachedBankSlipPayments = getSumValueOfDetachedAnticipablePayment(customer, BillingType.BOLETO)
            availableLimitForBankSlip = BigDecimalUtils.roundDown(customerReceivableAnticipationConfigService.calculateBankSlipAndPixAvailableLimit(customer))
        }

        return [
            totalValueAvailableForDetachedBankSlipPayments: totalValueAvailableForDetachedBankSlipPayments,
            availableLimitForBankSlip: availableLimitForBankSlip,
        ]
    }

    private Map getPixValueAvailableForAnticipation(Customer customer) {
        BigDecimal totalValueAvailableForDetachedPixPayments = 0
        BigDecimal availableLimitForPix = 0

        if (customer.canAnticipate()) {
            totalValueAvailableForDetachedPixPayments = getSumValueOfDetachedAnticipablePayment(customer, BillingType.PIX)
            availableLimitForPix = BigDecimalUtils.roundDown(customerReceivableAnticipationConfigService.calculateBankSlipAndPixAvailableLimit(customer))
        }

        return [
            totalValueAvailableForDetachedPixPayments: totalValueAvailableForDetachedPixPayments,
            availableLimitForPix: availableLimitForPix,
        ]
    }

    private Map getCreditCardValueAvailableForAnticipation(Customer customer) {
        BigDecimal availableLimitForCreditCard = 0
        BigDecimal totalValueAvailableForCreditCardInstallment = 0
        BigDecimal totalValueAvailableForDetachedCreditCardPayments = 0

        if (customer.canAnticipate()) {
            availableLimitForCreditCard = BigDecimalUtils.roundDown(customerReceivableAnticipationConfigService.calculateCreditCardAvailableLimit(customer))
            totalValueAvailableForCreditCardInstallment = getSumValueOfAnticipableCreditCardInstallment(customer)
            totalValueAvailableForDetachedCreditCardPayments = getSumValueOfDetachedAnticipablePayment(customer, BillingType.MUNDIPAGG_CIELO)
        }

        Map anticipationTotalValues = [
            availableLimitForCreditCard: availableLimitForCreditCard,
            totalValueAvailableForCreditCardInstallment: totalValueAvailableForCreditCardInstallment,
            totalValueAvailableForDetachedCreditCardPayments: totalValueAvailableForDetachedCreditCardPayments
        ]

        return anticipationTotalValues
    }

    private Map getSearchParamsAutomaticAnticipation() {
        Map search = [:]
        search.sort = "dueDate"
        search.order = "asc"
        search.billingType = BillingType.MUNDIPAGG_CIELO
        search.paymentStatus = PaymentStatus.CONFIRMED

        return search
    }

    private void saveSimulationItemFile(Customer customer, Long simulationItemId, Long temporaryFileId) {
        ReceivableAnticipationSimulationBatchItem simulationItem = ReceivableAnticipationSimulationBatchItem.query([id: simulationItemId, customer: customer]).get()
        TemporaryFile temporaryFile = TemporaryFile.get(temporaryFileId)
        Boolean isTemporaryFileAlreadySaved = ReceivableAnticipationSimulationBatchItemFile.query([simulationItem: simulationItem, temporaryFile: temporaryFile]).get().asBoolean()
        if (isTemporaryFileAlreadySaved) return

        ReceivableAnticipationSimulationBatchItemFile simulationItemFile = new ReceivableAnticipationSimulationBatchItemFile()
        simulationItemFile.temporaryFile = temporaryFile
        simulationItemFile.simulationItem = simulationItem

        simulationItemFile.save(failOnError: true)
    }

    private void deleteSimulationItemFile(Customer customer, Long simulationItemId, Long temporaryFileId) {
        ReceivableAnticipationSimulationBatchItem simulationItem = ReceivableAnticipationSimulationBatchItem.query([id: simulationItemId, customer: customer]).get()
        TemporaryFile temporaryFile = TemporaryFile.get(temporaryFileId)

        ReceivableAnticipationSimulationBatchItemFile simulationItemFile = ReceivableAnticipationSimulationBatchItemFile.query([simulationItem: simulationItem, temporaryFile: temporaryFile]).get()
        if (!simulationItemFile) return

        simulationItemFile.deleted = true
        simulationItemFile.save(failOnError: true)
    }

    private List<Map> getAnticipablePaymentList(Customer customer, Map params, BigDecimal maxValueToAnticipate, Integer max, Integer offset) {
        if (!params.sort) params.sort = "dueDate"
        if (!params.order) params.order = "asc"
        if (params.billingType?.isBoleto() || (AbTestUtils.hasPixAnticipation(customer) && params.billingType?.isPix())) {
            params."dueDate[le]" = ReceivableAnticipation.getLimitDateToAnticipate(customer).clearTime()
            params."paymentStatus" = PaymentStatus.PENDING
        } else {
            params."paymentStatus" = PaymentStatus.CONFIRMED
        }

        return PaymentAnticipableInfo.query(params + [
                columnList : ["payment.id", "value", "dueDate", "payment.status", "billingType", "payment.confirmedDate"],
                'value[le]': maxValueToAnticipate,
                customer   : customer,
                status: PaymentAnticipableInfoStatus.ANALYZED,
                anticipable: true,
                schedulable: false,
                anticipated: false
            ]).list(max: max, offset: offset)
    }

    private BigDecimal getSumValueOfAnticipableCreditCardInstallment(Customer customer) {
        BigDecimal totalValue = PaymentAnticipableInfo.sumValue(getDefaultParamsToFindAnticipableCreditCardInstallment(customer)).get()
        return BigDecimalUtils.roundHalfUp(totalValue)
    }

    private BigDecimal getSumValueOfDetachedAnticipablePayment(Customer customer, BillingType billingType) {
        Map params = [billingType: billingType]
        if (billingType.isBoleto() || (billingType.isBoletoOrPix() && AbTestUtils.hasPixAnticipation(customer)))
            params."dueDate[le]" = ReceivableAnticipation.getLimitDateToAnticipate(customer).clearTime()

        BigDecimal totalValue = PaymentAnticipableInfo.sumValue(getDefaultParamsToFindDetachedAnticipablePayment(customer) + params).get()
        return BigDecimalUtils.roundHalfUp(totalValue)
    }

    private Map getDefaultParamsToFindAnticipableCreditCardInstallment(Customer customer) {
        return [customer: customer,
                anticipable: true,
                anticipated: false,
                schedulable: false,
                status: PaymentAnticipableInfoStatus.ANALYZED,
                billingType: BillingType.MUNDIPAGG_CIELO,
                "installment[isNotNull]": true,
                paymentStatus: PaymentStatus.CONFIRMED]
    }

    private Map getDefaultParamsToFindDetachedAnticipablePayment(Customer customer) {
        return [customer: customer, anticipable: true, anticipated: false, schedulable: false, status: PaymentAnticipableInfoStatus.ANALYZED, isNotCreditCardInstallment: true]
    }

    private List<ReceivableAnticipationSimulationBatchItem> getProcessedPaymentList(List<Long> paymentIdList, Long customerId) {
        List<ReceivableAnticipationSimulationBatchItem> simulationItemList = []

        final Integer flushEvery = 50
        Utils.forEachWithFlushSession(paymentIdList, flushEvery, { Long paymentId ->
            try {
                Payment payment = Payment.read(paymentId)
                Customer customer = Customer.read(customerId)

                ReceivableAnticipationPartner receivableAnticipationPartner = receivableAnticipationPartnerConfigService.getReceivableAnticipationPartner(customer, ChargeType.DETACHED, payment.customerAccount.cpfCnpj, payment.billingType, payment.netValue)

                ReceivableAnticipationFinancialInfoVO receivableAnticipationFinancialInfoVO = new ReceivableAnticipationFinancialInfoVO()
                receivableAnticipationFinancialInfoVO.build(payment, false)

                Map financialInfo = receivableAnticipationFinancialInfoService.buildFinancialInfo(customer, receivableAnticipationFinancialInfoVO, receivableAnticipationPartner)

                Map processedPayment = [payment: payment, financialInfo: financialInfo]

                simulationItemList.add(saveReceivableAnticipationSimulationItem(processedPayment))
            } catch (Exception exception) {
                AsaasLogger.error("ReceivableAnticipationBatchService.getProcessedPaymentList >> Erro ao salvar batch Item simulation [paymentId: ${paymentId}]", exception)
            }
        })

        return simulationItemList
    }

    private ReceivableAnticipationSimulationBatchItem saveReceivableAnticipationSimulationItem(Map processedItem) {
        ReceivableAnticipationSimulationBatchItem simulationItem = new ReceivableAnticipationSimulationBatchItem()

        simulationItem.installment = processedItem.installment
        simulationItem.payment = processedItem.payment
        simulationItem.value = processedItem.financialInfo.totalValue
        simulationItem.paymentFee = (processedItem.financialInfo.paymentFee != null) ? processedItem.financialInfo.paymentFee : processedItem.financialInfo.installmentFee
        simulationItem.anticipationFee = processedItem.financialInfo.fee
        simulationItem.netValue = processedItem.financialInfo.netValue
        simulationItem.dueDate = processedItem.financialInfo.dueDate
        simulationItem.billingType = processedItem.payment?.billingType ?: processedItem.installment.billingType
        simulationItem.status = ReceivableAnticipationSimulationBatchItemStatus.PENDING
        simulationItem.discountValue = processedItem.financialInfo.discountValue

        simulationItem.save(failOnError: true)

        return simulationItem
    }

    private void recalculateSimulationBatch(ReceivableAnticipationSimulationBatch simulationBatch, List<ReceivableAnticipationSimulationBatchItem> simulationItemList) {
        if (!simulationItemList) return

        simulationBatch.quantity = simulationItemList.size()
        simulationBatch.value = simulationItemList.sum { it.value }
        simulationBatch.anticipationFee = simulationItemList.sum { it.anticipationFee }
        simulationBatch.netValue = simulationItemList.sum { it.netValue }
        simulationBatch.paymentFee = simulationItemList.sum { it.paymentFee }
        simulationBatch.discountValue = simulationItemList.sum { it.discountValue }
        simulationBatch.save(failOnError: true, flush: true)

        ReceivableAnticipationSimulationBatchItem.executeUpdate("update ReceivableAnticipationSimulationBatchItem set simulationBatch = :simulationBatch, lastUpdated = :lastUpdated where id in (:idList)", [simulationBatch: simulationBatch, lastUpdated: new Date(), idList: simulationItemList.collect { it.id }])
    }

    private void setSimulationBatchAsProcessedAndSendNotification(ReceivableAnticipationSimulationBatch simulationBatch) {
        simulationBatch.status = ReceivableAnticipationSimulationBatchStatus.PROCESSED
        simulationBatch.lastProcessedDate = new Date()
        simulationBatch.save(failOnError: true)

        customerAlertNotificationService.notifyAnticipationSimulationBatchProcessed(simulationBatch)
    }

    private void setSimulationBatchAsCancelled(ReceivableAnticipationSimulationBatch simulationBatch) {
        simulationBatch.status = ReceivableAnticipationSimulationBatchStatus.CANCELLED
        simulationBatch.lastProcessedDate = new Date()
        simulationBatch.save(failOnError: true)
    }

    private void setSimulationBatchAsPendingAndRecalculate(ReceivableAnticipationSimulationBatch simulationBatch, List<ReceivableAnticipationSimulationBatchItem> simulationItemList) {
        simulationBatch.status = ReceivableAnticipationSimulationBatchStatus.PENDING
        simulationBatch.lastProcessedDate = new Date()
        recalculateSimulationBatch(simulationBatch, simulationItemList)
    }

    private void setSimulationBatchAsReadyToSaveAndRecalculate(ReceivableAnticipationSimulationBatch simulationBatch, List<ReceivableAnticipationSimulationBatchItem> simulationItemList) {
        simulationBatch.status = ReceivableAnticipationSimulationBatchStatus.READY_TO_SAVE
        simulationBatch.lastProcessedDate = new Date()
        recalculateSimulationBatch(simulationBatch, simulationItemList)
    }

    private setSimulationBatchAsErrorWithNewTransaction(Long simulationBatchId) {
        Utils.withNewTransactionAndRollbackOnError({
            ReceivableAnticipationSimulationBatch simulationBatch = ReceivableAnticipationSimulationBatch.get(simulationBatchId)
            simulationBatch.status = ReceivableAnticipationSimulationBatchStatus.ERROR
            simulationBatch.lastProcessedDate = new Date()
            simulationBatch.save(failOnError: true)
        }, [logErrorMessage: "ReceivableAnticipationBatchService.setSimulationBatchAsErrorWithNewTransaction >> Falha ao marcar batch [${simulationBatchId}] com o status de error"])
    }

    private List<ReceivableAnticipationDocumentVO> getReceivableAnticipationDocumentVOListFromSimulationItem(ReceivableAnticipationSimulationBatchItem item) {
        List<ReceivableAnticipationDocumentVO> listOfReceivableAnticipationDocumentVO = []
        List<ReceivableAnticipationSimulationBatchItemFile> itemFileList = item.getSimulationItemFileList()

        for (ReceivableAnticipationSimulationBatchItemFile file in itemFileList) {
            ReceivableAnticipationDocumentVO receivableAnticipationDocumentVO = new ReceivableAnticipationDocumentVO(file.temporaryFile.id)
            listOfReceivableAnticipationDocumentVO.add(receivableAnticipationDocumentVO)
        }

        return listOfReceivableAnticipationDocumentVO
    }

    private BigDecimal getSumValueOfCreditCard(Customer customer) {
        Map search = [
            customer: customer,
            billingType: BillingType.MUNDIPAGG_CIELO,
            status: PaymentStatus.CONFIRMED
        ]

        return Payment.sumValue(search).get()
    }

    private BigDecimal getSumValueOfCreditCardAnticipable(Customer customer) {
        Map search = [
            customer: customer,
            billingType: BillingType.MUNDIPAGG_CIELO,
            paymentStatus: PaymentStatus.CONFIRMED,
            status: PaymentAnticipableInfoStatus.ANALYZED,
            anticipable: true,
            schedulable: false,
            anticipated: false
        ]

        return PaymentAnticipableInfo.sumValue(search).get()
    }
}
