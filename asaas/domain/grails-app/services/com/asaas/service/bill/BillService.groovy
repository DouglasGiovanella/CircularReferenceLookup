package com.asaas.service.bill

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.authorizationrequest.AuthorizationRequestActionType
import com.asaas.authorizationrequest.AuthorizationRequestType
import com.asaas.bill.BillStatus
import com.asaas.bill.BillStatusReason
import com.asaas.bill.BillUtils
import com.asaas.boleto.BoletoUtils
import com.asaas.checkout.CheckoutValidator
import com.asaas.checkoutRiskAnalysis.CheckoutRiskAnalysisReasonObject
import com.asaas.checkoutRiskAnalysis.adapter.CheckoutRiskAnalysisInfoAdapter
import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.bank.Bank
import com.asaas.domain.bill.Bill
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.linhadigitavel.InvalidLinhaDigitavelException
import com.asaas.linhadigitavel.LinhaDigitavelInfo
import com.asaas.log.AsaasLogger
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class BillService {

    def asaasMoneyService
    def asaasMoneyTransactionInfoService
    def asaasSegmentioService
    def asyncActionService
    def authorizationRequestService
    def billAsaasPaymentService
    def billBankSlipRegistrationInfoService
    def billPayService
    def checkoutNotificationService
    def checkoutRiskAnalysisService
    def checkoutRiskAnalysisRequestService
    def customerAlertNotificationService
    def customerExternalAuthorizationRequestCreateService
    def customerMessageService
    def fileService
    def financialTransactionService
    def grailsApplication
    def lastCheckoutInfoService
    def messageService
    def originRequesterInfoService
    def promotionalCodeService
    def pushNotificationRequestBillService
    def transactionReceiptService

    public Bill save(Customer customer, Map params) {
        lastCheckoutInfoService.save(customer)

        Map parsedParams = parseSaveParams(customer, params)

        LinhaDigitavelInfo linhaDigitavelInfo = LinhaDigitavelInfo.getInstance(parsedParams.linhaDigitavel)
        linhaDigitavelInfo.ignoreCelcoinConsultForLargeValues = true
        linhaDigitavelInfo.build()

        Map linhaDigitavelDataMap = BoletoUtils.buildLinhaDigitavelDataMap(linhaDigitavelInfo.linhaDigitavel)
        Payment asaasPaymentBankSlip = billPayService.findPaymentAsaasWithBankSlipInfo(linhaDigitavelDataMap.bankCode, linhaDigitavelDataMap.covenant, linhaDigitavelDataMap.covenantDigit, linhaDigitavelDataMap.agency, linhaDigitavelDataMap.nossoNumero)
        Boolean isAsaasBankSlip = asaasPaymentBankSlip ? true : false

        Bill validatedBill = validateSave(customer, linhaDigitavelInfo, parsedParams, asaasPaymentBankSlip)
        if (validatedBill.hasErrors()) {
            return validatedBill
        }

        Date estimatedPaymentDate = parsedParams.scheduleDate
        if (!isAsaasBankSlip) CustomDateUtils.setDateForNextBusinessDayIfHoliday(estimatedPaymentDate)

        Bill bill = new Bill()
        bill.linhaDigitavel = parsedParams.linhaDigitavel
        bill.scheduleDate = estimatedPaymentDate
        bill.bank = Bank.get(parsedParams.bankId)
        bill.customer = customer
        bill.dueDate = parsedParams.dueDate
        bill.description = parsedParams.description
        bill.discount = parsedParams.discount
        bill.value = parsedParams.value
        bill.fee = parsedParams.fee
        bill.interest = parsedParams.interest
        bill.fine = parsedParams.fine
        bill.status = BillStatus.SCHEDULED
        bill.publicId = UUID.randomUUID()
        bill.isUtility = linhaDigitavelInfo.isUtilityBill()
        if (bill.isUtility) bill.companyName = linhaDigitavelInfo.companyName
        bill.asaasBankSlip = isAsaasBankSlip
        bill.valueDebited = false
        bill.externalReference = parsedParams.externalReference

        bill.save(flush: true)

        if (bill.hasErrors()) return bill
        originRequesterInfoService.save(bill)

        if (parsedParams.tempFileId) {
            AsaasFile file = fileService.saveFileFromTemporary(customer, parsedParams.tempFileId)
            bill.file = file
            bill.save(failOnError: true)
        }

        if (isAsaasBankSlip) billAsaasPaymentService.save(bill, asaasPaymentBankSlip)

        if (asaasMoneyService.isAsaasMoneyRequest()) asaasMoneyTransactionInfoService.save(customer, bill, parsedParams)

        promotionalCodeService.consumeFeeDiscountPromotionalCode(bill)

        validateCheckoutValue(bill)
        if (bill.scheduleDate < CustomDateUtils.tomorrow() && !bill.status.isWaitingRiskAuthorization()) {
            BusinessValidation processedScheduledStatus = processScheduledStatus(bill)
            if (!processedScheduledStatus.isValid()) throw new BusinessException(processedScheduledStatus.getFirstErrorMessage())
        }

        transactionReceiptService.saveBillScheduled(bill)

        if (parsedParams.notifyThirdPartyOnConfirmation) {
            Map checkoutNotificationParams = [
                name: parsedParams.thirdPartyName,
                phone: parsedParams.thirdPartyPhone,
                email: parsedParams.thirdPartyEmail,
                message: parsedParams.message
            ]
            checkoutNotificationService.saveConfig(bill, checkoutNotificationParams)
        }

        if (!bill.isUtility) {
            billBankSlipRegistrationInfoService.save(bill, linhaDigitavelInfo)
        }

        pushNotificationRequestBillService.save(PushNotificationRequestEvent.BILL_CREATED, bill)

        AuthorizationRequestType authorizationRequestType = authorizationRequestService.findAuthorizationRequestType(bill.customer, AuthorizationRequestActionType.BILL_SAVE)

        if (authorizationRequestType.isCriticalAction()) {
            if (asaasMoneyService.isAsaasMoneyRequest()) {
                if (!params.authorizationData) throw new BusinessException("É obrigatório informar os dados para autorização da ação crítica")

                validateToken(customer, parsedParams.linhaDigitavel, parsedParams.originalValue, params.authorizationData)
            } else {
                bill.awaitingCriticalActionAuthorization = true
                CriticalAction.saveBillInsert(bill)
            }
        } else if (authorizationRequestType.isExternalAuthorization()) {
            bill.status = BillStatus.AWAITING_EXTERNAL_AUTHORIZATION
            bill.save(failOnError: true)
            customerExternalAuthorizationRequestCreateService.saveForBill(bill)
        }

        saveCheckoutRiskAnalysisRequestIfNecessary(bill)

        if (canPayBill(bill)) {
            Bill billPaid = payIfPossible(bill)

            if (billPaid.hasErrors()) {
                if (BillUtils.isOverdue(bill.dueDate)) {
                    throw new BusinessException(DomainUtils.getFirstValidationMessage(billPaid))
                } else {
                    DomainUtils.removeErrorsFromErrorCodeList(bill, ["bill.payWithGateway.paid.error"])
                }
            }
        }

        asaasSegmentioService.track(customer.id, "Logged :: Pagamentos :: Solicitação realizada", [providerEmail: customer.email,
                                                                                                   linhaDigitavel: bill.linhaDigitavel,
                                                                                                   value: bill.value,
                                                                                                   discount: bill.discount,
                                                                                                   interest: bill.interest,
                                                                                                   fine: bill.fine,
                                                                                                   dueDate: bill.dueDate,
                                                                                                   bank: bill.bank?.name,
                                                                                                   scheduleDate: bill.scheduleDate])

        return bill
    }

    public CriticalActionGroup requestAuthorizationToken(Customer customer, String identificationField, BigDecimal value) {
        String hash = buildCriticalActionHash(customer, identificationField, value)
        String authorizationMessage = "o código para autorizar o pagamento de conta no valor de R\$ ${value} é"
        return AsaasApplicationHolder.grailsApplication.mainContext.criticalActionService.saveAndSendSynchronous(customer, CriticalActionType.BILL_INSERT, hash, authorizationMessage)
    }

    public String buildCriticalActionHash(Customer customer, String identificationField, BigDecimal value) {
        String operation = ""
        operation += customer.id.toString()
        operation += BigDecimalUtils.roundDown(value * -1).toString()
        operation += identificationField

        if (!operation) throw new RuntimeException("Operação não suportada!")
        return operation.encodeAsMD5()
    }

    public void onExternalAuthorizationApproved(Bill bill) {
        if (!bill.status.isAwaitingExternalAuthorization()) {
            throw new RuntimeException("BillService.onExternalAuthorizationApproved > Pagamento de conta [${bill.id}] não está aguardando autorização externa.")
        }

        saveCheckoutRiskAnalysisRequestIfNecessary(bill)
        if (!canPayBill(bill)) return

        savePayBillAsyncAction(bill)
    }

    public void onExternalAuthorizationRefused(Bill bill) {
        if (!bill.status.isAwaitingExternalAuthorization()) {
            throw new RuntimeException("BillService.onExternalAuthorizationRefused > Pagamento de conta [${bill.id}] não está aguardando autorização externa.")
        }

        executeCancellation(bill, true)
    }

    public void onCriticalActionInsertAuthorization(CriticalAction action) {
        action.bill.awaitingCriticalActionAuthorization = false
        action.bill.save(failOnError: true)

        saveCheckoutRiskAnalysisRequestIfNecessary(action.bill)
        if (!canPayBill(action.bill)) return

        if (!action.bill.valueDebited && action.bill.scheduleDate < CustomDateUtils.tomorrow()) {
            BusinessValidation businessValidation = processScheduledStatus(action.bill)

            if (!businessValidation.isValid()) return
        }

        asyncActionService.savePayBill(action.bill.id)
    }

    public void onCriticalActionInsertCancellation(CriticalAction action) {
        executeCancellation(action.bill, true)
    }

    public void onCriticalActionDeleteAuthorization(CriticalAction action) {
        executeCancellation(action.bill, true)
    }

    public Bill cancel(Customer customer, id) {
        Bill bill = Bill.find(id, customer)

        validateCancel(bill)

        if (bill.hasErrors()) return bill

        Boolean criticalActionConfigBill = CustomerCriticalActionConfig.query([column: "bill", customerId: customer.id]).get()
        if (criticalActionConfigBill && !bill.awaitingCriticalActionAuthorization) {
            CriticalAction.saveBillDelete(bill)
            return bill
        } else {
            CriticalAction.deleteNotAuthorized(bill)
            executeCancellation(bill, true)
            return bill
        }
    }

    public Bill cancel(Bill bill, String reason) {
        if (bill.status.isCancelled()) throw new Exception("O pagamento desta conta já está cancelado [${bill.id}].")

        if (!reason) return DomainUtils.addError(bill, Utils.getMessageProperty("bill.cancel.error.validation.reason"))

        bill.cancellationReason = reason

        CriticalAction.deleteNotAuthorized(bill)
        return executeCancellation(bill, false)
    }

    public void processCustomerConfirmedFraudBillAsyncCancellation() {
        final Integer limit = 500
        final Integer flushEvery = 10
        List<Map> asyncActionList = asyncActionService.listCancelCustomerConfirmedFraudBill(limit)

        Utils.forEachWithFlushSession(asyncActionList, flushEvery, { Map asyncAction ->
            Utils.withNewTransactionAndRollbackOnError({
                cancelBillForCustomerWithConfirmedFraud(Utils.toLong(asyncAction.customerId))

                asyncActionService.delete(Utils.toLong(asyncAction.asyncActionId))
            }, [logErrorMessage: "BillService.processCustomerConfirmedFraudBillAsyncCancellation >> Ocorreu um erro ao cancelar pague contas. [asyncActionId: ${asyncAction.asyncActionId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(Utils.toLong(asyncAction.asyncActionId)) }
            ])
        })
    }

    public Bill executeCancellation(Bill bill, Boolean validate) {
        if (validate) {
            validateCancel(bill)
            if (bill.hasErrors()) throw new RuntimeException("Não é possível cancelar o pagamento da conta [${bill.id}].")
        }

        bill.status = BillStatus.CANCELLED
        bill.statusReason = null
        bill.awaitingCriticalActionAuthorization = false

        refundFinancialTransaction(bill)

        bill.save(failOnError: true, flush: true)

        asaasMoneyTransactionInfoService.refundCheckoutIfNecessary(bill)

        pushNotificationRequestBillService.save(PushNotificationRequestEvent.BILL_CANCELLED, bill)

        return bill
    }

    public Bill refund(Bill bill) {
        if (!bill.canBeRefundable()) throw new BusinessException("Este pagamento de conta não pode ser estornado.")

        financialTransactionService.refundBillPayment(bill)

        bill.status = BillStatus.REFUNDED
        bill.statusReason = null
        bill.save(failOnError: true, flush: true)

        asaasMoneyTransactionInfoService.refundCheckoutIfNecessary(bill)

        pushNotificationRequestBillService.save(PushNotificationRequestEvent.BILL_REFUNDED, bill)
        customerAlertNotificationService.notifyBillRefunded(bill)
        messageService.sendBillRefundToCustomer(bill)

        return bill
    }

    public Bill validateSave(Customer customer, LinhaDigitavelInfo linhaDigitavelInfo, Map params, Payment asaasBankSlip) {
        Boolean isAsaasBankSlip = asaasBankSlip.asBoolean()
        Bill bill = new Bill()

        if (!params.dueDate) {
            return DomainUtils.addFieldError(bill, "dueDate", "invalid", [:])
        }

        if (isAsaasBankSlip && !asaasBankSlip.canConfirm()) {
            return DomainUtils.addError(bill, Utils.getMessageProperty("bill.payWithGateway.billAlreadyProcessing"))
        }

        BusinessValidation checkoutBusinessValidation = validateCheckout(customer)
        if (!checkoutBusinessValidation.isValid()) DomainUtils.copyAllErrorsFromBusinessValidation(checkoutBusinessValidation, bill)

        if (!customer.billPaymentEnabled()) {
            DomainUtils.addError(bill, "Você não possui pagamento de contas habilitados, entre em contato com seu gerente.")
        }

        if (linhaDigitavelInfo.hasErrors()) return DomainUtils.addError(bill, linhaDigitavelInfo.errors[0].getMessage())

        BigDecimal billValueDifference = isAsaasBankSlip ? (params.value - asaasBankSlip.calculateCurrentPaymentValue()).abs() : 0
        if (isAsaasBankSlip && billValueDifference > Payment.MAX_INTEREST_TOLERANCE_VALUE) DomainUtils.addError(bill, Utils.getMessageProperty("bill.pay.valueDifferentFromRegistered"))

        BusinessValidation canBePaidValidation = validateIfCanBePaid(params.dueDate, asaasBankSlip)
        if (!canBePaidValidation.isValid()) DomainUtils.addError(bill, canBePaidValidation.getFirstErrorMessage())

        Boolean isOverdue = BillUtils.isOverdue(params.dueDate)

        if (isOverdue) {
            if (params.scheduleDate.clearTime() <= CustomDateUtils.getYesterday() || params.scheduleDate.clearTime() >= CustomDateUtils.tomorrow()) {
                DomainUtils.addError(bill, "Contas vencidas não podem ser agendadas.")
            }
        } else {
            Date dueDateToValidate = BillUtils.getDueDateWithHolidayTolerance(params.dueDate)
            if (params.scheduleDate.clearTime() > dueDateToValidate.clearTime()) {
                DomainUtils.addError(bill, "A data de agendamento é maior que a data de vencimento")
            }
        }

        if ((linhaDigitavelInfo.originalValue && linhaDigitavelInfo.originalValue != params.originalValue) ||
            (linhaDigitavelInfo.dueDate && linhaDigitavelInfo.dueDate.clearTime() != params.dueDate) ||
            linhaDigitavelInfo.bank && linhaDigitavelInfo.bank.id != params.bankId) {
            DomainUtils.addError(bill, "Os dados de pagamento são inválidos.")
        }

        if (linhaDigitavelInfo.isUtilityBill() && params.discount) {
            DomainUtils.addError(bill, "Nao é possivel aplicar desconto em contas de consumo e impostos.")
        }

        if (params.value <= 0) {
            DomainUtils.addError(bill, "A conta precisa ter valor maior que zero")
        }

        //Different credit card invoices have same "linha digitável" and they don't have due date and value in "linha digitável", that's why this kind of bill can have duplicate "linha digitável" in database.
        if (((!linhaDigitavelInfo.isUtilityBill() && linhaDigitavelInfo.dueDate && linhaDigitavelInfo.value) || linhaDigitavelInfo.isUtilityBill()) && Bill.linhaDigitavelAlreadyExists(params.linhaDigitavel, customer)) {
            DomainUtils.addError(bill, "Esta conta já foi paga")
        }

        Map minimumScheduleDateMap = calculateMinimumScheduleDate(isAsaasBankSlip)
        if (params.scheduleDate.clearTime().before(minimumScheduleDateMap.minimumScheduleDate.clearTime())) {
            if (params.scheduleDate.clearTime() < new Date().clearTime()) {
                DomainUtils.addError(bill, "A data de agendamento não pode ser inferior à hoje")
            } else {
                if (!isAsaasBankSlip) DomainUtils.addError(bill, "A conta somente pode ser agendada com o vencimento a partir do próximo dia útil")
            }
        }

        if (Bill.notAllowedCompanyCodesForUtilityBillPayment.contains(linhaDigitavelInfo.companyCode)) {
            DomainUtils.addError(bill, "O pagamento não pode ser agendado, pois a empresa/órgão não está disponivel para pagamento.")
        }

        if (params.thirdPartyEmail && Boolean.valueOf(params.notifyThirdPartyOnConfirmation) && !Utils.emailIsValid(params.thirdPartyEmail)) {
            DomainUtils.addError(bill, "O e-mail informado é inválido.")
        }

        if (params.thirdPartyPhone && Boolean.valueOf(params.notifyThirdPartyOnConfirmation) && !PhoneNumberUtils.validateMobilePhone(params.thirdPartyPhone)) {
            DomainUtils.addError(bill, "O celular informado é inválido.")
        }

        return bill
    }

    public BusinessValidation validateIfCanBePaid(Date dueDate, Payment asaasBankSlip) {
        Boolean isOverdue = BillUtils.isOverdue(dueDate)
        if (isOverdue) return validateIfOverdueBillCanBePaid(asaasBankSlip)

        BusinessValidation businessValidation = new BusinessValidation()

        if (!asaasBankSlip) {
            Map minimumScheduleDateMap = calculateMinimumScheduleDate(false)

            Date dueDateToValidate = BillUtils.getDueDateWithHolidayTolerance(dueDate)

            if (dueDateToValidate && dueDateToValidate.clearTime() < minimumScheduleDateMap.minimumScheduleDate.clearTime()) {
                List parameters = [minimumScheduleDateMap.mininumDaysToSchedule]

                if (minimumScheduleDateMap.mininumDaysToSchedule > 1) {
                    parameters.add("dias")

                    if (minimumScheduleDateMap.consideringBusinessDays) parameters.add("úteis")
                } else {
                    parameters.add("dia")

                    if (minimumScheduleDateMap.consideringBusinessDays) parameters.add("útil")
                }

                businessValidation.addError("bill.scheduled.exceedsLimit", parameters)
            }
        }

        return businessValidation
    }

    public void notifyScheduledForToday() {
        List<Bill> billList = Bill.query([ignoreCustomer: true, status: BillStatus.PENDING, "scheduleDate[le]": new Date().clearTime(), awaitingCriticalActionAuthorization: false]).list()
        if (!billList) return

        messageService.sendScheduledBillsForToday(billList)
    }

    public Map parseSaveParams(Customer customer, Map params) {
        Map parsedParams = [:]

        parsedParams.linhaDigitavel = Utils.removeNonNumeric(params.linhaDigitavel)
        parsedParams.scheduleDate = params.scheduleDate
        parsedParams.dueDate = params.dueDate?.clearTime()
        parsedParams.discount = Utils.toBigDecimal(params.discount ?: 0)
        parsedParams.interest = Utils.toBigDecimal(params.interest ?: 0)
        parsedParams.fine = Utils.toBigDecimal(params.fine ?: 0)
        parsedParams.originalValue = Utils.toBigDecimal(params.value ?: 0)
        parsedParams.value = parsedParams.originalValue + parsedParams.interest + parsedParams.fine - parsedParams.discount
        parsedParams.bankId = params.bankId ? Long.valueOf(params.bankId) : null
        parsedParams.tempFileId = params.tempFileId ? params.tempFileId as Long : params.tempFileId
        parsedParams.fee = calculateFee(customer, params.linhaDigitavel)
        parsedParams.valueToConsume = parsedParams.value + parsedParams.fee
        parsedParams.description = params.description
        parsedParams.notifyThirdPartyOnConfirmation = Boolean.valueOf(params.notifyThirdPartyOnConfirmation)
        parsedParams.thirdPartyName = params.thirdPartyName
        parsedParams.thirdPartyPhone = params.thirdPartyPhone
        parsedParams.thirdPartyEmail = params.thirdPartyEmail
        parsedParams.message = params.message
        parsedParams.externalReference = params.externalReference

        if (asaasMoneyService.isAsaasMoneyRequest()) {
            if (params.containsKey("backingPayment")) parsedParams.backingPaymentId = params.backingPayment
            if (params.containsKey("backingInstallment")) parsedParams.backingInstallmentId = params.backingInstallment
            if (params.containsKey("asaasMoneyPaymentFinancingFeeValue")) parsedParams.asaasMoneyPaymentFinancingFeeValue = Utils.toBigDecimal(params.asaasMoneyPaymentFinancingFeeValue)
            if (params.containsKey("asaasMoneyPaymentAnticipationFeeValue")) parsedParams.asaasMoneyPaymentAnticipationFeeValue = Utils.toBigDecimal(params.asaasMoneyPaymentAnticipationFeeValue)
            if (params.containsKey("asaasMoneyDiscountValue")) parsedParams.asaasMoneyDiscountValue = Utils.toBigDecimal(params.asaasMoneyDiscountValue)
            if (params.containsKey("asaasMoneyCashbackValue")) parsedParams.asaasMoneyCashbackValue = Utils.toBigDecimal(params.asaasMoneyCashbackValue)
        }

        return parsedParams
    }

    public Map getAndValidateLinhaDigitavelInfo(String linhaDigitavel, Date dueDate) {
        LinhaDigitavelInfo linhaDigitavelInfo = LinhaDigitavelInfo.getInstance(linhaDigitavel)
        linhaDigitavelInfo.build()
        if (linhaDigitavelInfo.hasErrors()) {
            throw new InvalidLinhaDigitavelException(linhaDigitavelInfo.errors[0].code)
        }

        if (dueDate) linhaDigitavelInfo.dueDate = dueDate

        Map linhaDigitavelDataMap = BoletoUtils.buildLinhaDigitavelDataMap(linhaDigitavelInfo.linhaDigitavel)
        Payment asaasPaymentBankSlip = billPayService.findPaymentAsaasWithBankSlipInfo(linhaDigitavelDataMap.bankCode, linhaDigitavelDataMap.covenant, linhaDigitavelDataMap.covenantDigit, linhaDigitavelDataMap.agency, linhaDigitavelDataMap.nossoNumero)
        Boolean isAsaasBankSlip = asaasPaymentBankSlip ? true : false

        BusinessValidation canBePaidValidation = validateIfCanBePaid(linhaDigitavelInfo.dueDate, asaasPaymentBankSlip)
        if (!canBePaidValidation.isValid()) throw new BusinessException(canBePaidValidation.getFirstErrorMessage())

        Boolean isOverdue = BillUtils.isOverdue(linhaDigitavelInfo.dueDate)

        Map minimumScheduleDateMap = calculateMinimumScheduleDate(isAsaasBankSlip)

        return [linhaDigitavelInfo: linhaDigitavelInfo, minimumScheduleDateMap: minimumScheduleDateMap, isOverdue: isOverdue]
    }

    public Bill setAsFailed(Bill bill, BillStatusReason billStatusReason) {
        if (bill.status == BillStatus.FAILED) return bill

        bill.status = BillStatus.FAILED

        bill.statusReason = billStatusReason

        bill.save(failOnError: true)

        refundFinancialTransaction(bill)

        asaasMoneyTransactionInfoService.refundCheckoutIfNecessary(bill)

        customerAlertNotificationService.notifyBillPaymentFailed(bill)

        pushNotificationRequestBillService.save(PushNotificationRequestEvent.BILL_FAILED, bill)

        return bill
    }

    public Bill setAsPending(Bill bill) {
        bill.status = BillStatus.PENDING
        bill.statusReason = null
        bill.save(failOnError: true)

        pushNotificationRequestBillService.save(PushNotificationRequestEvent.BILL_PENDING, bill)

        return bill
    }

    public Bill setAsScheduled(Bill bill) {
        bill.status = BillStatus.SCHEDULED
        bill.statusReason = null
        bill.save(failOnError: true)

        return bill
    }

    public List<Bill> cancelWhileBalanceIsNegative(Customer customer) {
        List<Bill> cancelledBillList = []

        if (FinancialTransaction.getCustomerBalance(customer) >= 0) return cancelledBillList

        List<Long> billListId = Bill.cancellable([column: 'id', customer: customer, sort: 'scheduleDate', order: 'desc']).list()

        for (Long billId : billListId) {
            Bill bill = Bill.get(billId)

            if (bill.todayIsNotValidDateToCancel()) continue

            bill = cancel(customer, bill.id)

            if (bill.hasErrors()) throw new RuntimeException("Não é possível cancelar o pagamento do título [${billId}]")

            cancelledBillList.add(bill)

            if (FinancialTransaction.getCustomerBalance(customer) >= 0) return cancelledBillList
        }

        return cancelledBillList
    }

    public BusinessValidation validateIfOverdueBillCanBePaid(Payment asaasBankSlip) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (asaasBankSlip) {
            if (!asaasBankSlip.canBePaidAfterDueDate()) businessValidation.addError("bill.payWithGateway.cannotBePaidAfterDueDate")
        } else {
            Map minimumScheduleDateMap = calculateMinimumScheduleDate(asaasBankSlip.asBoolean())
            if (new Date().clearTime() < minimumScheduleDateMap.minimumScheduleDate.clearTime()) businessValidation.addError("bill.overdue.limitHour", [Bill.DEFAULT_LIMIT_HOUR_TO_EXECUTE_BILL_ON_SAME_DAY])
        }

        return businessValidation
    }

    public void payPendingScheduledBill() {
        List<Long> billIdList = Bill.query([ignoreCustomer: true, scheduleDate: new Date().clearTime(), status: BillStatus.PENDING, awaitingCriticalActionAuthorization: false, forPaymentOnGateway: true, column: "id"]).list()

        final Integer flushEvery = 50
        final Integer numberOfThreads = 2
        Utils.processWithThreads(billIdList, numberOfThreads, { List<Long> billIdListFromThread ->
            Utils.forEachWithFlushSession(billIdListFromThread, flushEvery, { Long billId ->
                Utils.withNewTransactionAndRollbackOnError ({
                    AsaasLogger.info("BillService.payPendingScheduledBill >>> Processando pagamento da conta [${billId}]")

                    Bill bill = Bill.get(billId)
                    payIfPossible(bill)
                }, [logErrorMessage: "BillService.payPendingScheduledBill >>> Erro ao salvar o pague contas [id: ${billId}]."] )
            })
        })
    }

    public Boolean processScheduledBill() {
        final Integer limitOfPendingItemsToProcess = 1000
        List<Long> billIdList = Bill.query([ignoreCustomer: true, scheduleDate: new Date().clearTime(), status: BillStatus.SCHEDULED, awaitingCriticalActionAuthorization: false, column: "id"]).list(max: limitOfPendingItemsToProcess)

        Utils.forEachWithFlushSession(billIdList, 50, { Long billId ->
            Boolean mustSetAsFailOnBusinessError = false

            Utils.withNewTransactionAndRollbackOnError ({
                Bill bill = Bill.get(billId)

                processScheduledStatus(bill)
            },
                [
                    logErrorMessage: "BillService.processScheduledBill >>> Erro ao alterar a status do pague contas [id: ${billId}].",
                    onError: { Exception exception ->
                        if (exception instanceof BusinessException) {
                            mustSetAsFailOnBusinessError = true
                        } else {
                            AsaasLogger.error("BillService.processScheduledBill >>> Erro não tratado ao descontar pague contas do saldo do cliente [billId: ${billId}]", exception)
                        }
                    }
                ]
            )

            if (mustSetAsFailOnBusinessError) setAsFailedWithNewTransaction(billId)
        } )

        return !billIdList
    }

    public void setAsFailedWithNewTransaction(Long billId) {
        Utils.withNewTransactionAndRollbackOnError ({
            Bill bill = Bill.get(billId)

            setAsFailed(bill, null)
        }, [logErrorMessage: "BillService.setAsFailedWithNewTransaction >>> Erro ao alterar a status do pague contas para falha [id: ${billId}]."] )
    }

    public BusinessValidation processScheduledStatus(Bill bill) {
        asaasMoneyTransactionInfoService.setAsPaidIfNecessary(bill)

        BusinessValidation businessValidation = validateIfCanBeChangeForTheNextStatusAfterTheScheduledStatus(bill)
        if (!businessValidation.isValid()) {
            BillStatusReason reason
            if (businessValidation.asaasErrors.first().code == "bill.denied.insufficient.balance") reason = BillStatusReason.CUSTOMER_WITHOUT_BALANCE

            setAsFailed(bill, reason)
            return businessValidation
        }


        bill.valueDebited = true
        bill.status = BillStatus.PENDING
        bill.statusReason = null
        bill.save(failOnError: true)

        financialTransactionService.saveBillPayment(bill, null)

        asaasMoneyTransactionInfoService.creditAsaasMoneyCashbackIfNecessary(bill)

        return businessValidation
    }

    public void payBillOnAsyncActionQueue() {
        Integer maxItems = 35

        List<Map> payBillList = asyncActionService.listPayBill(maxItems)

        Utils.forEachWithFlushSession(payBillList, 50, { Map asyncActionDataMap ->
            Boolean hasError = false

            Utils.withNewTransactionAndRollbackOnError ( {
                Bill bill = Bill.get(Utils.toLong(asyncActionDataMap.billId))
                payIfPossible(bill)
                asyncActionService.delete(asyncActionDataMap.asyncActionId)
            },
                [
                    logErrorMessage: "BillService.payBillOnAsyncActionQueue >>> Erro ao processar o pagamento da conta [billId: ${asyncActionDataMap.billId}].",
                    onError: { hasError = true }
                ]
            )

            if (hasError) asyncActionService.setAsErrorWithNewTransaction(asyncActionDataMap.asyncActionId)
        })
    }

    public Bill payIfPossible(Bill bill) {
        if (bill.status.isWaitingRiskAuthorization() || bill.status.isAwaitingCheckoutRiskAnalysisRequest()) return bill

        bill = billPayService.payBillIfPossible(bill)

        if (bill.hasErrors()) bill = setAsFailedIfNecessary(bill)

        return bill
    }

    public void onCheckoutRiskAnalysisRequestApproved(Long billId) {
        Bill bill = Bill.get(billId)

        if (!canUpdateBillAfterRiskAnalysis(bill)) throw new RuntimeException("BillService.onCheckoutRiskAnalysisRequestApproved > Não é possível atualizar o pague contas")

        savePayBillAsyncAction(bill)
    }

    public void onCheckoutRiskAnalysisRequestDenied(Long billId) {
        Bill bill = Bill.get(billId)

        if (!canUpdateBillAfterRiskAnalysis(bill)) throw new RuntimeException("BillService.onCheckoutRiskAnalysisRequestDenied > Não é possível atualizar o pague contas")

        cancel(bill, "O pagamento desta conta foi cancelado por motivos de segurança")

        customerAlertNotificationService.notifyBillCancelled(bill)
        messageService.sendBillCancelToCustomer(bill)
    }

    private void cancelBillForCustomerWithConfirmedFraud(Long customerId) {
        List<Bill> cancellableBillList = Bill.cancellable([customerId: customerId]).list()

        final Integer flushEvery = 10
        Utils.forEachWithFlushSession(cancellableBillList, flushEvery, { Bill bill ->
            cancel(bill, "O pagamento desta conta foi cancelado por suspeita de fraude")
        })
    }

    private void savePayBillAsyncAction(Bill bill) {
        if (bill.valueDebited) {
            bill.status = BillStatus.PENDING
        } else if (bill.scheduleDate < CustomDateUtils.tomorrow()) {
            BusinessValidation businessValidation = processScheduledStatus(bill)
            if (!businessValidation.isValid()) return
        } else {
            bill.status = BillStatus.SCHEDULED
        }

        bill.save(failOnError: true)
        asyncActionService.savePayBill(bill.id)
    }

    private void validateToken(Customer customer, String identificationField, BigDecimal value, Map params) {
        BusinessValidation businessValidation = AsaasApplicationHolder.grailsApplication.mainContext.criticalActionService.authorizeSynchronous(customer, Utils.toLong(params.groupId), params.token, CriticalActionType.BILL_INSERT, null, buildCriticalActionHash(customer, identificationField, value))
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())
    }

    private BusinessValidation validateIfCanBeChangeForTheNextStatusAfterTheScheduledStatus(Bill bill) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!bill.valueDebited) {
            if (!bill.customer.hasSufficientBalance(bill.getValueToConsume())) {
                BigDecimal customerBalance = FinancialTransaction.getCustomerBalance(bill.customer)
                businessValidation.addError("bill.denied.insufficient.balance", [bill.value, bill.fee, FormUtils.formatCurrencyWithMonetarySymbol(customerBalance)])

                return businessValidation
            }
        }

        return businessValidation
    }

    private BusinessValidation validateCheckout(Customer customer) {
        CheckoutValidator checkoutValidator = new CheckoutValidator(customer)
        checkoutValidator.isBillOrAsaasCard = true

        List<AsaasError> asaasErrorList = checkoutValidator.validate()

        for (asaasError in asaasErrorList) {
            asaasError.code = "bill.${asaasError.code}"
        }

        BusinessValidation businessValidation = new BusinessValidation()
        businessValidation.addErrors(asaasErrorList)

        return businessValidation
    }

    private void validateCheckoutValue(Bill bill) {
        CheckoutValidator checkoutValidator = new CheckoutValidator(bill.customer)
        checkoutValidator.isBillOrAsaasCard = true
        checkoutValidator.fee = bill.fee
        checkoutValidator.bypassSufficientBalance = true

        List<AsaasError> asaasErrorList = checkoutValidator.validate(bill.getValueToConsume())

        if (asaasErrorList) throw new BusinessException(Utils.getMessageProperty("bill." + asaasErrorList.first().code, asaasErrorList.first().arguments))
    }

    private void validateCancel(Bill bill) {
        String cancellationDenialReason = bill.getCancellationDenialReason()

        if (cancellationDenialReason) {
            DomainUtils.addError(bill, cancellationDenialReason)
        }
    }

    private BigDecimal calculateFee(Customer customer, String linhaDigitavel) {
        BigDecimal calculatedFee = Bill.getOriginalFee(linhaDigitavel)

        BigDecimal promotionalCodeCreditValue = promotionalCodeService.getAllAvailableFeeDiscountValue(customer)
        if (promotionalCodeCreditValue <= 0) return calculatedFee

        if (calculatedFee <= promotionalCodeCreditValue) {
            calculatedFee = 0
        } else {
            calculatedFee -= promotionalCodeCreditValue
        }

        return calculatedFee
    }

    private void refundFinancialTransaction(Bill bill) {
        if (!bill.status.isFailed() && !bill.status.isCancelled()) throw new BusinessException("Somente pague contas cancelados ou com falha podem retornar o valor do saldo.")

        if (bill.valueDebited) financialTransactionService.cancelBillPayment(bill, null)
    }

    private Map calculateMinimumScheduleDate(Boolean isAsaasBankSlip) {
        Map response = [listOfReason: [], consideringBusinessDays: true]

        if (isAsaasBankSlip) {
            response.mininumDaysToSchedule = 0
            response.minimumScheduleDate = new Date().clearTime()
        } else {
            Integer estimatedDays = 0

            Boolean canBeEffectivateToday = (CustomDateUtils.getInstanceOfCalendar().get(Calendar.HOUR_OF_DAY) < Bill.getLimitHourToExecuteBillToday())
            if (!canBeEffectivateToday) estimatedDays = 1

            response.mininumDaysToSchedule = estimatedDays
            response.minimumScheduleDate = CustomDateUtils.addBusinessDays(new Date(), estimatedDays).clearTime()
        }

        return response
    }

    private Bill setAsFailedIfNecessary(Bill bill) {
        Boolean isOverdue = BillUtils.isOverdue(bill.dueDate)

        BillStatusReason billStatusReason = BillStatusReason.getStatusReasonByErrorCode(bill)

        Boolean shouldSetAsFailed = shouldSetAsFailed(billStatusReason)

        if (shouldSetAsFailed || isOverdue) {
            bill = setAsFailed(bill, billStatusReason)
            customerMessageService.sendBillPaymentFailedAlert(bill)
        }

        return bill
    }

    private Boolean shouldSetAsFailed(BillStatusReason billStatusReason) {
        if (!billStatusReason) return false
        if (billStatusReason.isAssignorNotFound()) return false
        if (billStatusReason.isCommunicationError()) return false
        if (billStatusReason.isForbiddenValue()) return false
        if (billStatusReason.isLimitExceededCelcoin()) {
            messageService.sendAlert(grailsApplication.config.asaas.mail.liquidacaoFinanceira, "Sem saldo suficiente na Celcoin para realizar o pagamento", "")
            return false
        }
        return true
    }

    private void saveCheckoutRiskAnalysisRequestIfNecessary(Bill bill) {
        if (!canPayBill(bill)) return

        CheckoutRiskAnalysisInfoAdapter checkoutInfoAdapter = new CheckoutRiskAnalysisInfoAdapter(bill)
        Boolean isSuspectedOfFraud = checkoutRiskAnalysisService.checkIfCheckoutIsSuspectedOfFraud(CheckoutRiskAnalysisReasonObject.BILL, checkoutInfoAdapter)
        if (!isSuspectedOfFraud) return

        bill.status = BillStatus.AWAITING_CHECKOUT_RISK_ANALYSIS_REQUEST
        bill.save(failOnError: true)

        checkoutRiskAnalysisRequestService.save(bill.customer, bill)
    }

    private Boolean canPayBill(Bill bill) {
        if (bill.status.isAwaitingExternalAuthorization()) return false
        if (bill.status.isWaitingRiskAuthorization()) return false
        if (bill.status.isAwaitingCheckoutRiskAnalysisRequest()) return false
        if (bill.awaitingCriticalActionAuthorization) return false

        return true
    }

    private Boolean canUpdateBillAfterRiskAnalysis(Bill bill) {
        if (bill.status.isCancelled()) return false
        if (!bill.status.isAwaitingCheckoutRiskAnalysisRequest()) {
            throw new BusinessException("BillService.onCheckoutRiskAnalysisRequestApproved > O pague contas: [${bill.id}] não está aguardando análise de saque.")
        }

        return true
    }
}
