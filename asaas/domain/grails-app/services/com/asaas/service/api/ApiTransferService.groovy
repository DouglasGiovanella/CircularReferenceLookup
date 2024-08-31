package com.asaas.service.api

import com.asaas.api.ApiBankAccountInfoParser
import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiIncidentReportParser
import com.asaas.api.ApiMobileUtils
import com.asaas.api.ApiPaymentParser
import com.asaas.api.ApiTransferParser
import com.asaas.api.knownrequestpattern.adapter.CustomerKnownApiRequestPatternAdapter
import com.asaas.bankaccountinfo.BaseBankAccount
import com.asaas.checkout.CheckoutValidator
import com.asaas.checkout.CustomerCheckoutFee
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.bankaccountinfo.BankAccountInfoPixKey
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.internaltransfer.InternalTransfer
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.payment.ReceivedPaymentBatchFileItem
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimit
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.domain.transfer.Transfer
import com.asaas.domain.transfer.TransferType
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixCheckoutValidator
import com.asaas.pix.PixTransactionOriginType
import com.asaas.pix.vo.transaction.PixAddressKeyDebitVO
import com.asaas.pix.vo.transaction.PixManualDebitVO
import com.asaas.transfer.TransferUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.RequestUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError

import grails.transaction.Transactional

@Transactional
class ApiTransferService extends ApiBaseService {

    def customerKnownApiRequestPatternService
    def customerKnownApiTransferRequestService
    def apiResponseBuilderService
    def bankAccountInfoUpdateRequestService
    def bankAccountTransferService
    def createTransferService
    def creditTransferRequestService
    def customerCheckoutLimitService
    def internalTransferService
    def pixDebitService

    public Map find(Map params) {
        Customer customer = getProviderInstance(params)

        Transfer transfer
        if (Utils.toLong(params.id)) {
            transfer = Transfer.query([customer: customer, creditTransferRequestId: Utils.toLong(params.id)]).get()
        } else {
            transfer = Transfer.findByPublicId(customer, params.id.toString())
        }

        if (!transfer) return apiResponseBuilderService.buildNotFoundItem()

        return ApiTransferParser.buildResponseItem(transfer, [buildCriticalAction: true])
    }

    public Map list(Map params) {
        Customer customer = getProviderInstance(params)
        Map fields = ApiTransferParser.parseFilters(params)

        List<Transfer> transferList = Transfer.query(fields + [customer: customer, excludePixTransactionCreditRefund: true]).list(max: getLimit(params), offset: getOffset(params), readOnly: true)

        List<Map> extraData = []

        if (ApiMobileUtils.isMobileAppRequest()) {
            CheckoutValidator checkoutValidator = new CheckoutValidator(customer)
            checkoutValidator.isTransfer = true

            List<Map> denialReasons = checkoutValidator.validate().collect { [code: it.code, description: Utils.getMessageProperty("transfer." + it.code, it.arguments)] }
            if (denialReasons) {
                extraData << [denialReasons: denialReasons]
            } else {
                Map customerInvoiceInfoMap = Invoice.buildNextInvoiceWithFeeNotChargedMap(customer)
                if (customerInvoiceInfoMap) extraData << [customerInvoiceInfoMap: customerInvoiceInfoMap]
            }

            extraData << [balance: FinancialTransaction.getCustomerBalance(customer)]
        }

        List<Map> responseList = transferList.collect { ApiTransferParser.buildResponseItem(it, [:]) }

        return apiResponseBuilderService.buildList(responseList, getLimit(params), getOffset(params), transferList.totalCount, extraData)
    }

    public Map save(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiTransferParser.parseSaveParams(customer, params)

        TransferType transferType = resolveTransferType(customer, fields)

        if (transferType.isInternal()) {
            InternalTransfer internalTransfer = internalTransferService.save(customer, fields.destinationWalletPublicId, fields.value)

            if (internalTransfer.hasErrors()) {
                return apiResponseBuilderService.buildErrorList(internalTransfer)
            }

            Transfer transfer = Transfer.query([customer: customer, internalTransfer: internalTransfer]).get()
            return apiResponseBuilderService.buildSuccess(ApiTransferParser.buildResponseItem(transfer, [:]))
        }

        if (transferType.isPix()) {
            CustomerKnownApiRequestPatternAdapter apiRequestPatternAdapter

            if (!ApiMobileUtils.isMobileAppRequest()) {
                apiRequestPatternAdapter = customerKnownApiRequestPatternService.buildPattern(params, RequestUtils.getHeaders(), [fields.addressKey?.type, transferType])
                fields.customerKnownApiRequestPatternAdapter = apiRequestPatternAdapter
            }

            PixTransaction pixTransaction

            if (fields.containsKey("addressKey")) {
                pixTransaction = pixDebitService.saveAddressKeyDebit(customer, new PixAddressKeyDebitVO(fields, customer), [:])
            } else {
                if (fields.bankAccountInfo) {
                    BankAccountInfoPixKey bankAccountInfoPixKey = (fields.bankAccountInfo as BankAccountInfo).getPixKey()

                    if (bankAccountInfoPixKey) {
                        pixTransaction = pixDebitService.saveAddressKeyDebit(customer, new PixAddressKeyDebitVO(bankAccountInfoPixKey, Utils.toBigDecimal(fields.value), fields), [:])
                    } else {
                        pixTransaction = pixDebitService.saveManualDebit(customer, new PixManualDebitVO(customer, fields), [:])
                    }
                } else {
                    pixTransaction = pixDebitService.saveManualDebit(customer, new PixManualDebitVO(customer, fields), [:])
                }
            }

            if (pixTransaction.hasErrors()) return apiResponseBuilderService.buildErrorList(pixTransaction)

            Transfer transfer = Transfer.query([customer: customer, pixTransaction: pixTransaction]).get()

            if (apiRequestPatternAdapter) customerKnownApiTransferRequestService.save(transfer, apiRequestPatternAdapter)

            return apiResponseBuilderService.buildSuccess(ApiTransferParser.buildResponseItem(transfer, [buildCriticalAction: true]))
        }

        Long bankAccountInfoId = buildBankAccountInfoId(customer, fields)
        if (!bankAccountInfoId) throw new BusinessException("Informe a conta de destino que deseja efetuar a transferência.")

        if (fields.scheduledDate) AsaasLogger.info("ApiTransferService.save() -> Agendamento TED via API. [customerId: ${customer.id}, scheduledDate: ${fields.scheduledDate}]")
        CreditTransferRequest creditTransferRequest = creditTransferRequestService.save(customer, bankAccountInfoId, fields.value, fields)
        if (creditTransferRequest.hasErrors()) return apiResponseBuilderService.buildErrorList(creditTransferRequest)

        Transfer transfer = Transfer.query([customer: customer, creditTransferRequest: creditTransferRequest]).get()

        return apiResponseBuilderService.buildSuccess(ApiTransferParser.buildResponseItem(transfer, [buildCriticalAction: true]))
    }

    public Map index(Map params) {
        Customer customer = getProviderInstance(params)

        Map responseMap = [:]
        responseMap.pixAvailability = ApiIncidentReportParser.buildPixAvailability()
        responseMap.accountBalance = FinancialTransaction.getCustomerBalance(customer)
        responseMap.accountRemainingDailyCheckoutValue = customerCheckoutLimitService.getAvailableDailyCheckout(customer)
        responseMap.shouldChargeDebitFeeForTransfers = CustomerCheckoutFee.shouldChargePixDebitFeeForTransfer(customer)

        Map pixCheckoutLimit = [:]
        pixCheckoutLimit.maxDaytimeLimitPerTransaction = PixTransactionCheckoutLimit.calculateDaytimeLimitPerTransaction(customer)
        pixCheckoutLimit.maxNightlyLimitPerTransaction = PixTransactionCheckoutLimit.calculateNightlyLimitPerTransaction(customer)
        pixCheckoutLimit.remainingDaytimeLimit = PixTransactionCheckoutLimit.calculateCurrentDayRemainingDaytimeCheckoutLimit(customer)
        pixCheckoutLimit.remainingNightlyLimit = PixTransactionCheckoutLimit.calculateCurrentDayRemainingNightlyCheckoutLimit(customer)

        responseMap.pixCheckoutLimit = pixCheckoutLimit

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    public Map getSummary(Map params) {
        Customer customer = getProviderInstance(params)

        Map responseMap = [:]

        BigDecimal value = Utils.toBigDecimal(params.value)
        BankAccountInfo bankAccountInfo = BankAccountInfo.find(Long.valueOf(params.bankAccountInfoId), customer.id)
        Date scheduledDate = ApiBaseParser.parseDate(params.scheduleDate)

        Map transferTypeAndReason

        if (params.operationType) {
            transferTypeAndReason = [transferType: TransferType.convert(params.operationType)]
        } else {
            transferTypeAndReason = createTransferService.resolveTransferType(bankAccountInfo, value)
        }

        Transfer validatedTransfer = createTransferService.validate(customer, bankAccountInfo.id, value, transferTypeAndReason.transferType, scheduledDate)
        if (validatedTransfer.hasErrors()) return apiResponseBuilderService.buildErrorList(validatedTransfer)

        Integer estimatedDaysForDebit
        Date estimatedTransferDate
        Boolean isScheduledTransfer = TransferUtils.isScheduledTransfer(scheduledDate)
        if (isScheduledTransfer) {
            estimatedDaysForDebit = createTransferService.getEstimatedDaysForScheduledDebit(scheduledDate)
            estimatedTransferDate = scheduledDate
        } else {
            scheduledDate = null
            estimatedDaysForDebit = createTransferService.getEstimatedDaysForDebit(transferTypeAndReason.transferType)
            estimatedTransferDate = createTransferService.calculateEstimatedDebitDate(new Date(), estimatedDaysForDebit)
        }

        Map transferFeeMap = createTransferService.calculateTransferFeeAndReason(customer, bankAccountInfo, value, transferTypeAndReason.transferType, scheduledDate)
        responseMap.value = value
        responseMap.fee = transferFeeMap.fee
        responseMap.feeReason = transferFeeMap.reason?.toString()
        responseMap.estimatedDate = ApiBaseParser.formatDate(estimatedTransferDate)
        responseMap.isScheduled = isScheduledTransfer
        responseMap.scheduledDate = ApiBaseParser.formatDate(scheduledDate)
        responseMap.bankAccount = ApiBankAccountInfoParser.buildResponseItem(bankAccountInfo)
        responseMap.operationType = transferTypeAndReason.transferType.toString()
        responseMap.operationTypeReason = transferTypeAndReason.reason

        responseMap.canCreateUnlimitedTransfersWithoutFee = CustomerCheckoutFee.canCreateUnlimitedTransfersWithoutFee(customer, transferTypeAndReason.transferType)
        if (!responseMap.canCreateUnlimitedTransfersWithoutFee) {
            responseMap.remainingTransfersWithoutFee = CustomerCheckoutFee.getRemainingTransfersWithoutFee(customer, transferTypeAndReason.transferType)
        } else {
            responseMap.remainingTransfersWithoutFee = null
        }

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    public Map getPayments(Map params) {
        Customer customer = getProviderInstance(params)
        Integer limit = getLimit(params)
        Integer offset = getOffset(params)

        Map search = ApiTransferParser.parseCreditTransferRequestParams(customer, params)

        if (!search.receivedPaymentBatchFileCreditTransferRequestId) return apiResponseBuilderService.buildNotFoundItem()

        List<ReceivedPaymentBatchFileItem> batchFileItemList = ReceivedPaymentBatchFileItem.query(search + [creditTransferRequestProvider: customer]).list(max: limit, offset: offset, readOnly: true)
        List<Map> responseList = batchFileItemList.collect { ApiPaymentParser.buildResponseItem(it.payment, [:]) }

        return apiResponseBuilderService.buildList(responseList, limit, offset, batchFileItemList.totalCount)
    }

    public Map cancel(Map params) {
        Customer customer = getProviderInstance(params)

        Transfer transfer

        if (Utils.toLong(params.id)) {
            transfer = Transfer.query([customer: customer, creditTransferRequestId: Utils.toLong(params.id)]).get()
        } else {
            transfer = Transfer.findByPublicId(customer, params.id.toString())
        }

        Transfer cancelledTransfer = bankAccountTransferService.cancel(transfer)
        if (cancelledTransfer.hasErrors()) return apiResponseBuilderService.buildErrorList(cancelledTransfer)

        return apiResponseBuilderService.buildSuccess(ApiTransferParser.buildResponseItem(cancelledTransfer, [buildCriticalAction: true]))
    }

    private Long buildBankAccountInfoId(Customer customer, Map parsedFields) {
        if (parsedFields.bankAccountInfo) return parsedFields.bankAccountInfo.id

        if (parsedFields.saveBankAccountParams) {
            BankAccountInfo bankAccountInfo = saveBankAccountInfo(customer, parsedFields.saveBankAccountParams)
            return bankAccountInfo.id
        }

        if (ApiBaseParser.getApiVersion() < 3 || parsedFields.mainBankAccount) {
            return BankAccountInfo.findMainAccount(customer.id)?.id
        }

        return null
    }

    private BankAccountInfo saveBankAccountInfo(Customer customer, Map bankAccount) {
        if (!bankAccount.containsKey("thirdPartyAccount") && bankAccountInfoUpdateRequestService.shouldBankAccountInfoBeThirdPartyAccount(customer, bankAccount.cpfCnpj)) {
            bankAccount.thirdPartyAccount = true
        }

        BaseBankAccount baseBankAccount = bankAccountInfoUpdateRequestService.save(customer.id, bankAccount)
        if (baseBankAccount.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(baseBankAccount))

        if (baseBankAccount.status.isAwaitingActionAuthorization()) {
            String authorizationDeviceTypeDescription = AuthorizationDevice.findCurrentTypeDescription(customer)
            throw new BusinessException("Não é possível solicitar transferência para uma conta bancária aguardando autorização via ${authorizationDeviceTypeDescription}.")
        }

        if (baseBankAccount.status.isAwaitingApproval()) {
            throw new BusinessException("Não é possível solicitar transferência sem ter a conta principal aprovada.")
        }

        if (baseBankAccount instanceof BankAccountInfo) {
            return baseBankAccount
        }

        return BankAccountInfo.query([cpfCnpj: baseBankAccount.cpfCnpj, customer: baseBankAccount.customer, agency: baseBankAccount.agency, account: baseBankAccount.account, accountDigit: baseBankAccount.accountDigit]).get()
    }

    private TransferType resolveTransferType(Customer customer, Map parsedFields) {
        if (parsedFields.transferType) return parsedFields.transferType
        if (ApiMobileUtils.isMobileAppRequest()) return TransferType.TED
        if (parsedFields.destinationWalletPublicId) return TransferType.INTERNAL
        if (canTransferWithPix(customer, parsedFields)) return TransferType.PIX

        return TransferType.TED
    }

    private Boolean canTransferWithPix(Customer customer, Map parsedFields) {
        if (parsedFields.containsKey("addressKey")) return true

        Boolean bankAccountHasIspb = parsedFields.externalAccountVo?.ispb?.trim()?.asBoolean()
        if (bankAccountHasIspb) {
            CustomerCriticalActionConfig customerCriticalActionConfig = CustomerCriticalActionConfig.query([customerId: customer.id]).get()
            Boolean criticalActionAllowsPix = !customerCriticalActionConfig || customerCriticalActionConfig.hasPixCriticalActionConfigEqualsToTransfer()
            if (criticalActionAllowsPix) {
                Boolean toSameOwnership = parsedFields.externalAccountVo.cpfCnpj == customer.cpfCnpj

                PixTransactionBankAccountInfoCheckoutLimit bankAccountInfoCheckoutLimit
                if (parsedFields.bankAccountInfo) bankAccountInfoCheckoutLimit = PixTransactionBankAccountInfoCheckoutLimit.query([customer: customer, bankAccountInfoId: parsedFields.bankAccountInfo.id]).get()

                AsaasError checkoutError
                if (bankAccountInfoCheckoutLimit) {
                    checkoutError = PixCheckoutValidator.validateCheckoutWithBankAccountInfoCheckoutLimit(customer, parsedFields.value, PixTransactionOriginType.MANUAL, parsedFields.scheduleDate, false, toSameOwnership, bankAccountInfoCheckoutLimit)
                } else {
                    checkoutError = PixCheckoutValidator.validateCheckoutLimit(customer, parsedFields.value, PixTransactionOriginType.MANUAL, parsedFields.scheduleDate, false, toSameOwnership)
                }

                if (!checkoutError) return true
            }
        }

        return false
    }
}
