package com.asaas.service.payment

import com.asaas.bankslip.worker.PaymentBankSlipWorkerConfigVO
import com.asaas.bankslip.worker.PaymentBankSlipWorkerItemVO
import com.asaas.billinginfo.BillingType
import com.asaas.boleto.asaas.AsaasBoletoReturnFileParser
import com.asaas.domain.bank.Bank
import com.asaas.domain.bankslip.PaymentBankSlipInfo
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.boleto.BoletoReturnFile
import com.asaas.domain.holiday.Holiday
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentConfirmRequest
import com.asaas.domain.payment.PaymentConfirmRequestGroup
import com.asaas.domain.payment.PaymentHistory
import com.asaas.domain.subscriptionpayment.SubscriptionPayment
import com.asaas.exception.BusinessException
import com.asaas.exception.PaymentAlreadyConfirmedException
import com.asaas.log.AsaasLogger
import com.asaas.status.Status
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PaymentConfirmRequestService {

    def boletoBatchFileItemService
    def customerAccountReceiverBankService
    def customerMessageService
    def financialStatementPaymentService
    def messageService
    def paymentConfirmService
    def paymentHistoryService
    def paymentService
    def paymentSettlementScheduleService

    public List<PaymentBankSlipWorkerItemVO> listPendingByStatus(PaymentBankSlipWorkerConfigVO bankSlipWorkerConfigVO, List<Long> paymentConfirmRequestIdListProcessing, Integer maxItems) {
        final Integer groupLimit = 5

        Date settlementCycleDateLimit
        if (bankSlipWorkerConfigVO.useSettlementCycleDateFilter) {
            settlementCycleDateLimit = new Date()
        }

        List<Long> paymentConfirmRequestGroupIdList = listPendingGroupsCreatedToday(bankSlipWorkerConfigVO.boletoBankIdList, bankSlipWorkerConfigVO.paymentConfirmRequestStatus, settlementCycleDateLimit, groupLimit)

        if (!paymentConfirmRequestGroupIdList) return []

        Map searchParams = [status: bankSlipWorkerConfigVO.paymentConfirmRequestStatus, "groupId[in]": paymentConfirmRequestGroupIdList, column: "id", disableSort: true]

        if (paymentConfirmRequestIdListProcessing) {
            searchParams."id[notIn]" = paymentConfirmRequestIdListProcessing
        }

        List<Long> paymentConfirmRequestIdList = PaymentConfirmRequest.query(searchParams).list([max: maxItems])
        List<PaymentBankSlipWorkerItemVO> itemList = []
        paymentConfirmRequestIdList.collate(bankSlipWorkerConfigVO.maxItemsPerThread).each { itemList.add(new PaymentBankSlipWorkerItemVO(it, paymentConfirmRequestGroupIdList)) }

        return itemList
    }

    public void approvePendingGroupsCreatedToday(List<Long> boletoBankIdList) {
        List<Long> paymentConfirmRequestGroupWithSuccessItemsIdList = listPendingGroupsCreatedToday(boletoBankIdList, Status.SUCCESS, null, null)

        if (!paymentConfirmRequestGroupWithSuccessItemsIdList) return

        approveGroupList(paymentConfirmRequestGroupWithSuccessItemsIdList)
    }

    public Boolean approveGroupCreatedToday(List<Long> boletoBankIdList, Integer maxItems) {
        final Integer groupLimit = 5

        List<Long> paymentConfirmRequestGroupIdList = listPendingGroupsCreatedToday(boletoBankIdList, Status.PENDING, null, groupLimit)

        if (!paymentConfirmRequestGroupIdList) return false

        for (Long paymentConfirmRequestGroupId : paymentConfirmRequestGroupIdList) {
            approveGroupItems(paymentConfirmRequestGroupId, maxItems)
        }

        return true
    }

    public void confirmApprovedGroups(List<Long> boletoBankIdList, Integer maxItems) {
        final Integer groupLimit = 5

        List<Long> paymentConfirmRequestGroupIdList = listPendingGroupsCreatedToday(boletoBankIdList, Status.APPROVED, null, groupLimit)

        if (!paymentConfirmRequestGroupIdList) return

        for (Long paymentConfirmRequestGroupId : paymentConfirmRequestGroupIdList) {
            confirmApprovedItemsWithNewTransaction(paymentConfirmRequestGroupId, maxItems)
        }

        approveGroupList(paymentConfirmRequestGroupIdList)
    }

    public void approveGroupWithNewTransaction(Long paymentConfirmRequestGroupId) throws Exception {
        PaymentConfirmRequest.withNewTransaction {
            approveGroup(paymentConfirmRequestGroupId)
        }

        processPendingItemsWithNewTransaction(paymentConfirmRequestGroupId)
    }

    public List<Long> listPaymentIdByNossoNumeroAndBoletoBank(String nossoNumero, BoletoBank boletoBank) {
        List<Long> paymentIdList = []
        paymentIdList += PaymentBankSlipInfo.query([column: "payment.id", nossoNumero: nossoNumero, "payment.boletoBank": boletoBank]).list()

        Map defaultPaymentFilter = [column: "id", nossoNumero: nossoNumero, deleted: true, disableSort: true]

        Boolean isSantanderBoletoBank = !boletoBank || boletoBank?.id == Payment.SANTANDER_BOLETO_BANK_ID
        if (isSantanderBoletoBank) {
            paymentIdList += Payment.query(defaultPaymentFilter + [isSantanderBankSlip: true]).list()
            paymentIdList += PaymentHistory.listPaymentSantanderBankSlip(nossoNumero)
            return paymentIdList
        }

        paymentIdList += Payment.query(defaultPaymentFilter + [boletoBank: boletoBank]).list()
        paymentIdList += PaymentHistory.listPaymentIdByNossoNumeroAndBoletoBank(nossoNumero, boletoBank)

        return paymentIdList
    }

    public Payment processDuplicatedPayment(Long id, Boolean bypassValueValidation) {
        Long newPaymentConfirmRequestId

        Map response = generateDuplicatedPayment(id, bypassValueValidation)
        newPaymentConfirmRequestId = response.paymentConfirmRequest.id

        PaymentConfirmRequest paymentConfirmRequest = PaymentConfirmRequest.get(newPaymentConfirmRequestId)

        financialStatementPaymentService.saveForConfirmedPayments(paymentConfirmRequest.paymentBank, [paymentConfirmRequest.payment.id])

        return paymentConfirmRequest.payment
    }

    public PaymentConfirmRequest processDuplicatedPaymentWithNewTransaction(Long id, Boolean bypassValueValidation) {
        Long newPaymentConfirmRequestId

        Utils.withNewTransactionAndRollbackOnError({
            Map response = generateDuplicatedPayment(id, bypassValueValidation)
            newPaymentConfirmRequestId = response.paymentConfirmRequest.id
        }, [onError: { Exception e -> throw e }])

        PaymentConfirmRequest paymentConfirmRequest = PaymentConfirmRequest.get(newPaymentConfirmRequestId)

        financialStatementPaymentService.saveForConfirmedPaymentsWithNewTransaction(paymentConfirmRequest.paymentBank, [paymentConfirmRequest.payment.id])

        return paymentConfirmRequest
    }

    public void processItemWithError(Long id, Map options) {
        PaymentConfirmRequest.withNewTransaction { transaction ->
            try {
                PaymentConfirmRequest paymentConfirmRequest = PaymentConfirmRequest.get(id)
                if (!(paymentConfirmRequest.status == Status.ERROR)) throw new RuntimeException("PaymentConfirmRequest [${paymentConfirmRequest.id}] já foi processado.")

                processRequest(paymentConfirmRequest, options)
            } catch (Exception exception) {
                transaction.setRollbackOnly()
                AsaasLogger.error("PaymentConfirmRequestService.processItemWithError >>> Ocorreu um erro ao processar item com erro", exception)
                throw exception
            }
        }

        PaymentConfirmRequest.withTransaction {
            PaymentConfirmRequest paymentConfirmRequest = PaymentConfirmRequest.get(id)
            financialStatementPaymentService.saveForConfirmedPaymentsWithNewTransaction(paymentConfirmRequest.paymentBank, [paymentConfirmRequest.payment.id])
        }
    }

    public Long processPaymentWithCreditDateError(Long id) {
        Long paymentConfirmRequestGroupId

        PaymentConfirmRequest.withNewTransaction { transaction ->
            try {
                PaymentConfirmRequest paymentConfirmRequest = PaymentConfirmRequest.get(id)
                if (!(paymentConfirmRequest.status == Status.ERROR)) throw new RuntimeException("PaymentConfirmRequest [${paymentConfirmRequest.id}] já foi processado.")

                processRequest(paymentConfirmRequest, [bypassCreditDateValidation: true])

                if (!paymentConfirmRequest.creditDate) {
                    paymentConfirmRequest.creditDate = new Date().clearTime()
                    paymentConfirmRequest.save(flush: true, failOnError: true)
                }

                paymentConfirmRequestGroupId = paymentConfirmRequest.group.id
            } catch (Exception exception) {
                transaction.setRollbackOnly()
                AsaasLogger.error("PaymentConfirmRequestService.processPaymentWithCreditDateError >>> Ocorreu um erro ao processar pagamento com erro na data de credito", exception)
                throw exception
            }
        }

        PaymentConfirmRequest.withTransaction {
            PaymentConfirmRequest paymentConfirmRequest = PaymentConfirmRequest.get(id)
            financialStatementPaymentService.saveForConfirmedPaymentsWithNewTransaction(paymentConfirmRequest.paymentBank, [paymentConfirmRequest.payment.id])
        }

        return paymentConfirmRequestGroupId
    }

    public void processPendingItemsWithNewTransaction(Long groupId) {
        List<Long> pendingRequestIdList = []

        PaymentConfirmRequest.withNewTransaction {
            pendingRequestIdList = listPendingRequestIds(groupId)
        }

        PaymentConfirmRequestGroup paymentConfirmRequestGroup = PaymentConfirmRequestGroup.read(groupId)
        processItemsWithNewTransaction(pendingRequestIdList, paymentConfirmRequestGroup.paymentBank, false, false)
    }

    public Map receive(BillingType billingType, Bank bank, List<Map> paymentInfoList) {
        Map response = saveGroupAndPaymentConfirmRequestList(billingType, bank, paymentInfoList, null, null)

        approveGroup(response.groupId)
        processPendingItems(response.groupId)

        return response
    }

    public void receiveBillPayment(Long paymentId, String nossoNumero, BigDecimal value, Bank bank) {
        Map paymentInfo = [:]

        paymentInfo.id = paymentId
        paymentInfo.nossoNumero = nossoNumero
        paymentInfo.value = value
        paymentInfo.date = new Date()
        paymentInfo.creditDate = new Date()
        paymentInfo.paidInternally = true

        receive(BillingType.BOLETO, bank, [paymentInfo])

        Payment payment = Payment.get(paymentId)
        boletoBatchFileItemService.delete(payment)
    }

    public void saveFinancialTransactionForGroup(Long groupId) {
        PaymentConfirmRequestGroup paymentConfirmRequestGroup = PaymentConfirmRequestGroup.get(groupId)
        if (!paymentConfirmRequestGroup.approvedDate) return

        List<Long> paymentIdListToCreateFinancialStatements = PaymentConfirmRequest.query([groupId: groupId, column: "payment.id", status: Status.SUCCESS, duplicatedPayment: false]).list()
        if (!paymentIdListToCreateFinancialStatements) return

        financialStatementPaymentService.saveForConfirmedPaymentsWithNewTransaction(paymentConfirmRequestGroup.paymentBank, paymentIdListToCreateFinancialStatements)
    }

    public PaymentConfirmRequestGroup saveGroup(Bank paymentBank, Long boletoReturnFileId) {
        validateGroup(paymentBank, boletoReturnFileId)

        PaymentConfirmRequestGroup group = new PaymentConfirmRequestGroup()
        group.paymentBank = paymentBank
        if (boletoReturnFileId) {
            group.boletoReturnFile = BoletoReturnFile.read(boletoReturnFileId)
            group.settlementStartDate = getSettlementDate(paymentBank, group.boletoReturnFile.fileName)
        }

        return group.save(flush: true, failOnError: true)
    }

    public void saveList(BillingType billingType, Bank bank, BoletoBank boletoBank, List<Map> paymentInfoList, Long boletoReturnFileId) {
        Map response = saveListWithNewTransaction(billingType, bank, paymentInfoList, boletoBank, boletoReturnFileId)

        PaymentConfirmRequest.withNewTransaction {
            if (response.success) {
                messageService.sendPaymentConfirmRequests(PaymentConfirmRequestGroup.get(response.groupId))
            }
        }
    }

    public void processItemsWithNewTransaction(List<Long> requestIdList, Bank bank, Boolean bypassFinancialStamentCreation, Boolean confirmOnly) {
        List<Long> paymentIdListToCreateFinancialStatements = []

        Utils.forEachWithFlushSession(requestIdList, 50, { Long id ->
            Map result = [:]
            Boolean lockingExceptionOccurred = false

            Utils.withNewTransactionAndRollbackOnError({
                PaymentConfirmRequest paymentConfirmRequest = PaymentConfirmRequest.get(id)
                paymentConfirmRequest.confirmOnly = confirmOnly
                result = processItem(paymentConfirmRequest)

                if (result.status.isSuccess() && result.billingType?.isBoleto()) {
                    customerAccountReceiverBankService.save(paymentConfirmRequest.payment.customerAccount.cpfCnpj, paymentConfirmRequest.receiverBankCode)

                    if (!bypassFinancialStamentCreation) paymentIdListToCreateFinancialStatements.add(result.paymentId)
                }
            }, [onError: { Exception exception ->
                if (Utils.isLock(exception)) {
                    lockingExceptionOccurred = true
                    return
                }

                if (exception instanceof PaymentAlreadyConfirmedException) {
                    result.message = "Cobrança já recebida."
                    result.duplicatedPayment = true
                } else {
                    result.message = "Exception: ${exception.message}"
                    result.duplicatedPayment = false
                }

                result.status = Status.ERROR

                PaymentConfirmRequest paymentConfirmRequest = PaymentConfirmRequest.get(id)
                if (!bankSlipAlreadyConfirmed(paymentConfirmRequest)) {
                    AsaasLogger.error("PaymentConfirmRequestService.processItemsWithNewTransaction >> Erro ao confirmar boleto [paymentConfirmRequest: ${id}]", exception)
                }
            }, ignoreStackTrace: true])

            if (result.status?.isIgnored()) return

            if (!lockingExceptionOccurred && !result.status?.isSuccess()) {
                Utils.withNewTransactionAndRollbackOnError({
                    saveErrorMessage(id, result.message, result.duplicatedPayment)
                })
            }
        })

        if (paymentIdListToCreateFinancialStatements) {
            financialStatementPaymentService.saveForConfirmedPaymentsWithNewTransaction(bank, paymentIdListToCreateFinancialStatements)
        }
    }

    private Date getSettlementDate(Bank paymentBank, String fileName) {
        if (paymentBank.code != SupportedBank.ASAAS.code()) return new Date().clearTime()

        return AsaasBoletoReturnFileParser.getSettlementDate(fileName)
    }

    private void approveGroupList(List<Long> paymentConfirmRequestGroupIdList) {
        for (Long paymentConfirmRequestGroupId : paymentConfirmRequestGroupIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                Boolean existsApprovedOrPendingPaymentConfirmRequest = PaymentConfirmRequest.query([groupId: paymentConfirmRequestGroupId, "status[in]": [Status.APPROVED, Status.PENDING], exists: true, disableSort: true]).get().asBoolean()
                if (!existsApprovedOrPendingPaymentConfirmRequest) {
                    approveGroup(paymentConfirmRequestGroupId)
                }
            }, [logErrorMessage: "PaymentConfirmRequestService.approveGroupsIfPossible() -> Erro ao processar aprovação do grupo [${paymentConfirmRequestGroupId}]"])
        }
    }

    private void approveGroup(Long paymentConfirmRequestGroupId) {
        PaymentConfirmRequestGroup group = PaymentConfirmRequestGroup.get(paymentConfirmRequestGroupId)

        checkIfGroupCanBeApproved(group)

        group.approvedDate = new Date()
        group.status = Status.APPROVED
        group.save(flush: true, failOnError: true)
    }

    private void approveGroupItems(Long paymentConfirmRequestGroupId, Integer maxItems) {
        final Integer numberOfItemsPerThread = 1000

        List<Long> pendingPaymentConfirmRequestIdList = PaymentConfirmRequest.query([status: Status.PENDING, groupId: paymentConfirmRequestGroupId, column: "id", disableSort: true]).list([max: maxItems])

        if (!pendingPaymentConfirmRequestIdList.size()) return

        ThreadUtils.processWithThreadsOnDemand(pendingPaymentConfirmRequestIdList, numberOfItemsPerThread, { List<Long> items ->
            processGroupItemsApproval(items)
        })
        AsaasLogger.info("PaymentConfirmRequestService.approveGroupItems >> Quantidade Total: ${pendingPaymentConfirmRequestIdList?.size()}" )
    }

    private Boolean bankSlipAlreadyConfirmed(PaymentConfirmRequest paymentConfirmRequest) {
        return (paymentConfirmRequest.billingType.isBoleto() && paymentConfirmRequest.payment && !paymentConfirmRequest.payment.canConfirm())
    }

    private void checkIfGroupCanBeApproved(PaymentConfirmRequestGroup group) throws Exception {
        if (!group) throw new RuntimeException("PaymentConfirmRequestGroup not found")

        if (group.status != Status.AWAITING_APPROVAL) throw new RuntimeException("PaymentConfirmRequest has already been approved")
    }

    private void confirmApprovedItemsWithNewTransaction(Long groupId, Integer maxItems) {
        final Integer numberOfItemsPerThread = 250

        List<Long> approvedRequestIdList = PaymentConfirmRequest.query([status: Status.APPROVED, groupId: groupId, column: "id", disableSort: true]).list([max: maxItems])

        ThreadUtils.processWithThreadsOnDemand(approvedRequestIdList, numberOfItemsPerThread, { List<Long> paymentConfirmRequestIdList ->
            processItemsWithNewTransaction(paymentConfirmRequestIdList, null, true, false)
        })
    }

    private Map generateDuplicatedPayment(Long id, Boolean bypassValueValidation) {
        PaymentConfirmRequest originalPaymentConfirmRequest = PaymentConfirmRequest.get(id)

        String originalPaymentConfirmRequestBankCode = originalPaymentConfirmRequest.payment.boletoBank?.bank?.code

        if (!originalPaymentConfirmRequest.duplicatedPayment || !(originalPaymentConfirmRequest.status == Status.ERROR)) throw new BusinessException("PaymentConfirmRequest [${originalPaymentConfirmRequest.id}] não é duplicado ou já foi processado.")
        if (!bypassValueValidation && (originalPaymentConfirmRequest.payment.value != originalPaymentConfirmRequest.value)) {
            throw new RuntimeException("PaymentConfirmRequest [${originalPaymentConfirmRequest.id}] não possui o mesmo valor da cobrança duplicada.")
        }
        if (!originalPaymentConfirmRequest.creditDate && originalPaymentConfirmRequestBankCode == SupportedBank.SAFRA.code()) {
            throw new RuntimeException("PaymentConfirmRequest [${originalPaymentConfirmRequest.id}] não possui data de crédito.")
        }
        if (originalPaymentConfirmRequest.creditDate && originalPaymentConfirmRequest.creditDate > new Date().clearTime() && originalPaymentConfirmRequestBankCode == SupportedBank.ASAAS.code()) {
            throw new RuntimeException("PaymentConfirmRequest [${originalPaymentConfirmRequest.id}] só pode ser processada no próximo dia.")
        }

        Payment duplicatedPayment = originalPaymentConfirmRequest.payment

        Map params = [customerAccount: duplicatedPayment.customerAccount, billingType: originalPaymentConfirmRequest.billingType, dueDate: duplicatedPayment.dueDate, value: originalPaymentConfirmRequest.value, duplicatedPayment: duplicatedPayment, automaticRoutine: true, interest: [value: 0], fine: [value: 0], boletoBank: duplicatedPayment.boletoBank]

        Payment payment = paymentService.save(params, true, false)

        PaymentConfirmRequestGroup group = saveGroup(originalPaymentConfirmRequest.paymentBank, null)
        group.status = Status.APPROVED

        PaymentConfirmRequest paymentConfirmRequest = new PaymentConfirmRequest()
        paymentConfirmRequest.group = group
        paymentConfirmRequest.payment = payment
        paymentConfirmRequest.nossoNumero = payment.nossoNumero
        paymentConfirmRequest.billingType = payment.billingType
        paymentConfirmRequest.value = payment.value
        paymentConfirmRequest.paymentDate = originalPaymentConfirmRequest.paymentDate

        Date today = new Date().clearTime()
        paymentConfirmRequest.creditDate = (originalPaymentConfirmRequest.creditDate <= today) ? originalPaymentConfirmRequest.creditDate : today

        paymentConfirmRequest.paymentBank = originalPaymentConfirmRequest.paymentBank
        paymentConfirmRequest.boletoBank = payment.boletoBank
        paymentConfirmRequest.status = Status.PENDING

        paymentConfirmRequest.save(flush: true, failOnError: true)

        if (duplicatedPayment.getSubscription()) {
            SubscriptionPayment subscriptionPayment = new SubscriptionPayment(subscription: duplicatedPayment.getSubscription(), payment: payment)
            subscriptionPayment.save(flush: true, failOnError: true)
        }

        processRequest(paymentConfirmRequest)

        originalPaymentConfirmRequest.status = Status.SUCCESS
        originalPaymentConfirmRequest.message = "Pagamento duplicado, compensado através da cobrança ${paymentConfirmRequest.payment.getInvoiceNumber()}."

        customerMessageService.notifyDuplicatedPaymentReceived(duplicatedPayment, payment)

        return [paymentConfirmRequest: paymentConfirmRequest, duplicatedPayment: duplicatedPayment]
    }

    private List<Long> listPayment(PaymentConfirmRequest paymentConfirmRequest) {
        if (paymentConfirmRequest.payment) {
            return [paymentConfirmRequest.paymentId]
        } else if ((paymentConfirmRequest.billingType in [BillingType.DEPOSIT, BillingType.TRANSFER])) {
            return listPaymentIdByNossoNumero(paymentConfirmRequest.nossoNumero)
        } else {
            return listPaymentIdByNossoNumeroAndBoletoBank(paymentConfirmRequest.nossoNumero, paymentConfirmRequest.boletoBank)
        }
    }

    private List<Long> listPaymentIdByNossoNumero(String nossoNumero) {
        List<Long> paymentIdList = Payment.query([column: "id", nossoNumero: nossoNumero, deleted: true]).list()

        paymentIdList += PaymentHistory.query([column: "payment.id", nossoNumero: nossoNumero]).list()

        paymentIdList += PaymentBankSlipInfo.query([column: "payment.id", nossoNumero: nossoNumero]).list()

        return paymentIdList
    }

    private List<Long> listPendingRequestIds(Long groupId) {
        return PaymentConfirmRequest.executeQuery("select id from PaymentConfirmRequest where status = :status and group.id = :groupId order by id", [status: Status.PENDING, groupId: groupId])
    }

    private void processGroupItemsApproval(List<Long> pendingPaymentConfirmRequestIdList) {
        final Integer flushEvery = 200
        final Integer batchSize = 200

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(pendingPaymentConfirmRequestIdList, batchSize, flushEvery, { Long paymentConfirmRequestId ->
            PaymentConfirmRequest paymentConfirmRequest = PaymentConfirmRequest.get(paymentConfirmRequestId)

            List<Long> paymentIdList = listPayment(paymentConfirmRequest)
            if (!validateBeforeConfirming(paymentConfirmRequest, paymentIdList, [bypassCreditDateValidation: false])) return

            Long firstPaymentId = paymentIdList.first()
            paymentConfirmRequest.payment = Payment.load(firstPaymentId)
            paymentConfirmRequest.status = Status.APPROVED

            paymentConfirmRequest.save(failOnError: true)

            if (paymentConfirmRequest.boletoBank?.isAsaasBoletoBank()) paymentSettlementScheduleService.saveFromPaymentConfirmRequest(paymentConfirmRequest)
        }, [logErrorMessage: "PaymentConfirmRequestService.processGroupItemsApproval >> Falha ao processar aprovação de paymentConfurmRequest em lote"])
    }

    private Map processItem(PaymentConfirmRequest paymentConfirmRequest) {
        Map result = [status: Status.SUCCESS, message: null, paymentId: null, billingType: null]

        if (![Status.PENDING, Status.APPROVED].contains(paymentConfirmRequest.status)) {
            throw new RuntimeException("PaymentConfirmRequestService.processItem: o item de confirmação não está com status pendente ou aprovado: PaymentConfirmRequest [${paymentConfirmRequest.id}]")
        }

        processRequest(paymentConfirmRequest)

        if (paymentConfirmRequest.status.isError()) {
            result.status = Status.IGNORED
            return result
        }

        result.paymentId = paymentConfirmRequest.payment?.id
        result.billingType = paymentConfirmRequest.payment?.billingType

        return result
    }

    private void processPendingItems(Long groupId) {
        List<Long> pendingRequestIdList = listPendingRequestIds(groupId)

        List<Long> confirmedPaymentIdList = []

        for (Long id in pendingRequestIdList) {
            try {
                PaymentConfirmRequest paymentConfirmRequest = PaymentConfirmRequest.get(id)
                Map result = processItem(paymentConfirmRequest)

                if (result.status == Status.SUCCESS) {
                    confirmedPaymentIdList.add(result.paymentId)
                } else if (result.status.isIgnored()) {
                    continue
                } else {
                    throw new RuntimeException(result.message)
                }
            } catch (Exception exception) {
                throw new RuntimeException(exception.getMessage())
            }
        }

        PaymentConfirmRequestGroup paymentConfirmRequestGroup = PaymentConfirmRequestGroup.get(groupId)
        financialStatementPaymentService.saveForConfirmedPayments(paymentConfirmRequestGroup.paymentBank, confirmedPaymentIdList)
    }

    private void processRequest(PaymentConfirmRequest paymentConfirmRequest) {
        processRequest(paymentConfirmRequest, [bypassCreditDateValidation: false])
    }

    private void processRequest(PaymentConfirmRequest paymentConfirmRequest, Map options) {
        Payment payment = paymentConfirmRequest.payment

        if (!paymentConfirmRequest.payment) {
            List paymentIdList = listPayment(paymentConfirmRequest)
            if (!validateBeforeConfirming(paymentConfirmRequest, paymentIdList, options)) return
            payment = Payment.get(paymentIdList.first())
        }

        if (paymentConfirmRequest.boletoBank && payment.boletoBank?.id != paymentConfirmRequest.boletoBank?.id) {
            paymentHistoryService.save(payment, payment.nossoNumero, payment.boletoBank.id)

            payment.nossoNumero = paymentConfirmRequest.nossoNumero
            payment.boletoBank = paymentConfirmRequest.boletoBank
            payment.save(failOnError: true)
        }

        paymentConfirmRequest.payment = paymentConfirmService.confirmPayment(payment, paymentConfirmRequest.value, paymentConfirmRequest.paymentDate, paymentConfirmRequest.billingType)
        paymentConfirmRequest.message = "Cobrança recebida com sucesso"
        paymentConfirmRequest.status = Status.SUCCESS

        paymentConfirmRequest.save(flush: true, failOnError: true)
    }

    private void saveErrorMessage(Long paymentConfirmRequestId, String message, Boolean duplicatedPayment) {
        PaymentConfirmRequest paymentConfirmRequest = PaymentConfirmRequest.get(paymentConfirmRequestId)

        if (![Status.PENDING, Status.APPROVED].contains(paymentConfirmRequest.status)) {
            throw new RuntimeException("PaymentConfirmRequestService.saveErrorMessage: o item de confirmação não está com o status pendente ou aprovado: PaymentConfirmRequest [${paymentConfirmRequest.id}]")
        }

        paymentConfirmRequest.status = Status.ERROR
        paymentConfirmRequest.message = message
        paymentConfirmRequest.duplicatedPayment = duplicatedPayment
        paymentConfirmRequest.save(flush: true, failOnError: true)
    }

    private Map saveGroupAndPaymentConfirmRequestList(BillingType billingType, Bank bank, List<Map> paymentInfoList, BoletoBank boletoBank, Long boletoReturnFileId) {
        Integer totalBoletoCount = 0
        BigDecimal totalBoletoValue = 0
        Long groupId

        PaymentConfirmRequestGroup group = saveGroup(bank, boletoReturnFileId)
        groupId = group.id

        Utils.forEachWithFlushNewSession(paymentInfoList, 100, { Map paymentInfo ->
            BigDecimal value = paymentInfo.value instanceof String ? BigDecimalUtils.fromFormattedString(paymentInfo.value) : Utils.toBigDecimal(paymentInfo.value)
            Date paymentDate = paymentInfo.date instanceof String ? CustomDateUtils.fromString(paymentInfo.date) : paymentInfo.date
            Payment payment = paymentInfo.id ? Payment.get(paymentInfo.id) : null

            totalBoletoCount++
            totalBoletoValue += value

            PaymentConfirmRequest paymentConfirmRequest = new PaymentConfirmRequest(nossoNumero: paymentInfo.nossoNumero, billingType: billingType, value: value, paymentDate: paymentDate, paymentBank: bank, payment: payment, boletoBank: boletoBank)
            paymentConfirmRequest.group = group
            paymentConfirmRequest.receiverBankCode = paymentInfo.receiverBankCode
            paymentConfirmRequest.receiverAgency = paymentInfo.receiverAgency

            if (paymentInfo.containsKey("creditDate") && paymentInfo.creditDate instanceof Date) {
                paymentConfirmRequest.creditDate = paymentInfo.creditDate
            }

            paymentConfirmRequest.paidInternally = paymentInfo.paidInternally
            paymentConfirmRequest.save(flush: true, failOnError: true)
        })

        return [success: true, totalBoletoCount: totalBoletoCount, totalBoletoValue: totalBoletoValue, groupId: groupId]
    }

    private Map saveListWithNewTransaction(BillingType billingType, Bank bank, List<Map> paymentInfoList, BoletoBank boletoBank, Long boletoReturnFileId) {
        Integer totalBoletoCount = 0
        BigDecimal totalBoletoValue = 0
        Boolean success = false
        Long groupId

        Payment.withNewTransaction { transaction ->
            try {
                Map saveResultMap = saveGroupAndPaymentConfirmRequestList(billingType, bank, paymentInfoList, boletoBank, boletoReturnFileId)
                totalBoletoCount = saveResultMap.totalBoletoCount
                totalBoletoValue = saveResultMap.totalBoletoValue
                success = saveResultMap.success
                groupId = saveResultMap.groupId
            } catch (Exception exception) {
                AsaasLogger.error("PaymentConfirmRequestService.saveListWithNewTransaction >>> Ocorreu um erro ao salvar lista de pagamentos")
                transaction.setRollbackOnly()
                success = false
            }
        }

        return [success: success, totalBoletoCount: totalBoletoCount, totalBoletoValue: totalBoletoValue, groupId: groupId]
    }

	private Boolean validateBeforeConfirming(PaymentConfirmRequest paymentConfirmRequest, List<Long> paymentIdList, Map options) {
        if (![Status.PENDING, Status.APPROVED].contains(paymentConfirmRequest.status)) {
            throw new RuntimeException("PaymentConfirmRequestService.validateBeforeConfirming: o item de confirmação não está com o status pendente ou aprovado: PaymentConfirmRequest [${paymentConfirmRequest.id}]")
        }

		if (paymentIdList.size() == 0) {
            paymentConfirmRequest.status = Status.ERROR
            paymentConfirmRequest.message = "Cobrança não encontrada."
            paymentConfirmRequest.save(flush: true, failOnError: true)
            return false
        } else if (paymentIdList.size() > 1) {
            paymentConfirmRequest.status = Status.ERROR
            paymentConfirmRequest.message = "Encontrada mais de uma cobrança com o número ${paymentConfirmRequest.nossoNumero}."
            paymentConfirmRequest.save(flush: true, failOnError: true)
            return false
        }

        Payment payment = Payment.read(paymentIdList.first())

		if (paymentConfirmRequest.value == 0) {
			paymentConfirmRequest.status = Status.ERROR
			paymentConfirmRequest.message = "Valor pago igual a zero."
			paymentConfirmRequest.payment = payment
			paymentConfirmRequest.save(flush: true, failOnError: true)
			return false
		}

		if (payment.isReceivingProcessInitiated() || payment.isRefunded()) {
			paymentConfirmRequest.status = Status.ERROR
			paymentConfirmRequest.message = "Cobrança já recebida."
			paymentConfirmRequest.duplicatedPayment = true
			paymentConfirmRequest.payment = payment
			paymentConfirmRequest.save(flush: true, failOnError: true)
			return false
		}

        if ([SupportedBank.SMARTBANK.code(), SupportedBank.ASAAS.code()].contains(paymentConfirmRequest.paymentBank?.code)) options.bypassCreditDateValidation = true

		if (!options.bypassCreditDateValidation && !paymentConfirmRequest.creditDate && payment.boletoBank?.bank?.code == SupportedBank.SAFRA.code()) {
			paymentConfirmRequest.status = Status.ERROR
			paymentConfirmRequest.message = "${Utils.getMessageProperty('paymentConfirmRequest.creditDate.isNull')}"
 			paymentConfirmRequest.payment = payment
 			paymentConfirmRequest.save(flush: true, failOnError: true)
 			return false
		}

        if (!options.bypassCreditDateValidation && paymentConfirmRequest.creditDate && paymentConfirmRequest.creditDate > new Date() && !Holiday.isHoliday(new Date())) {
                paymentConfirmRequest.status = Status.ERROR
                paymentConfirmRequest.message = "${Utils.getMessageProperty('paymentConfirmRequest.creditDate.greaterThanToday', [CustomDateUtils.formatDate(paymentConfirmRequest.creditDate)])}"
                paymentConfirmRequest.payment = payment
                paymentConfirmRequest.save(flush: true, failOnError: true)
                return false
        }

		return true
	}

    private void validateGroup(Bank paymentBank, Long boletoReturnFileId) {
        if (!boletoReturnFileId) return

        Boolean existGroupWithReturnFile = PaymentConfirmRequestGroup.query([boletoReturnFileId: boletoReturnFileId, paymentBank: paymentBank, exists: true]).get().asBoolean()
        if (existGroupWithReturnFile) {
            AsaasLogger.error("PaymentConfirmRequestService.validateGroup >>> Já existe grupo de confirmação para o arquivo bancário [boletoReturnFileId: ${boletoReturnFileId}]")
            throw new RuntimeException("Já existe grupo de confirmação para o arquivo bancário")
        }
    }

    private List<Long> listPendingGroupsCreatedToday(List<Long> boletoBankIdList, Status groupStatus, Date settlementCycleDateLimit, Integer limit) {
        List<Long> bankIdList = BoletoBank.query(["column": "bank.id", "id[in]": boletoBankIdList, disableSort: true, "distinct": "bank.id"]).list(readOnly: true)

        Date lastBusinessDayBeforeToday = CustomDateUtils.subtractBusinessDays(new Date().clearTime(), 1)

        Map searchParams = [column: "id", "itemStatus[exists]": groupStatus, "paymentBankId[in]": bankIdList, status: Status.AWAITING_APPROVAL, "dateCreated[ge]": lastBusinessDayBeforeToday, disableSort: true]

        if (settlementCycleDateLimit) searchParams += ["settlementStartDate[le]": settlementCycleDateLimit]

        Map listParams = [:]
        if (limit) {
            listParams = [max: limit]
        }

        return PaymentConfirmRequestGroup.query(searchParams).list(listParams)
    }
}

