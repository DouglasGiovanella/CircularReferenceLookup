package com.asaas.service.api.pix

import com.asaas.api.ApiAsaasErrorParser
import com.asaas.api.ApiBankAccountInfoParser
import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiCriticalActionParser
import com.asaas.api.ApiMobileUtils
import com.asaas.api.knownrequestpattern.adapter.CustomerKnownApiRequestPatternAdapter
import com.asaas.api.pix.ApiPixTransactionParser
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.domain.transfer.Transfer
import com.asaas.integration.pix.parser.PixParser
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixCheckoutValidator
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.adapter.qrcode.QrCodeAdapter
import com.asaas.pix.vo.transaction.PixAddressKeyDebitVO
import com.asaas.pix.vo.transaction.PixManualDebitVO
import com.asaas.pix.vo.transaction.PixQrCodeDebitVO
import com.asaas.service.api.ApiBaseService
import com.asaas.utils.RequestUtils
import com.asaas.validation.AsaasError
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class ApiPixTransactionService extends ApiBaseService {

    def asaasMoneyService
    def customerKnownApiRequestPatternService
    def customerKnownApiTransferRequestService
    def apiResponseBuilderService
    def pixCnab750TransactionReportFileService
    def pixDebitService
    def pixQrCodeService
    def pixTransactionExportService
    def pixTransactionService

    public Map find(Map params) {
        PixTransaction pixTransaction = PixTransaction.find(params.id, getProviderInstance(params))

        return apiResponseBuilderService.buildSuccess(ApiPixTransactionParser.buildResponseItem(pixTransaction))
    }

    public Map list(Map params) {
        Map filters = ApiPixTransactionParser.parseListingFilters(params)

        if (filters."dateCreated[ge]" || filters."dateCreated[le]") {
            filters.sortList = [
                [sort: "dateCreated", order: "desc"],
                [sort: "id", order: "desc"]
            ]
        }

        final Integer customQueryTimeoutInSeconds = 60
        List<PixTransaction> pixTransactionsList = PixTransaction.query(filters + [customerId: getProvider(params)]).list(max: getLimit(params), offset: getOffset(params), readOnly: true, timeout: customQueryTimeoutInSeconds)
        List<Map> pixTransactions = pixTransactionsList.collect { ApiPixTransactionParser.buildResponseItem(it) }

        return apiResponseBuilderService.buildList(pixTransactions, getLimit(params), getOffset(params), pixTransactionsList.totalCount)
    }

    public Map save(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiPixTransactionParser.parseRequestParams(params)

        CustomerKnownApiRequestPatternAdapter apiRequestPatternAdapter
        if (!ApiMobileUtils.isMobileAppRequest()) {
            apiRequestPatternAdapter = customerKnownApiRequestPatternService.buildPattern(params, RequestUtils.getHeaders(), [])
        }

        if (!fields.qrCode && !ApiMobileUtils.isMobileAppRequest()) {
            AsaasLogger.warn("ApiPixTransactionService.save >> Recebido requisição sem dados do QrCode ${customer.id}")
        }

        PixTransaction pixTransaction

        if (fields.externalAccount) {
            pixTransaction = pixDebitService.saveManualDebit(customer, new PixManualDebitVO(customer, fields), fields.authorizationData)
        } else if (fields.addressKey) {
            pixTransaction = pixDebitService.saveAddressKeyDebit(customer, new PixAddressKeyDebitVO(fields, customer), fields.authorizationData)
        } else if (fields.qrCode) {
            QrCodeAdapter qrCodeAdapter = pixQrCodeService.decode(fields.qrCode.payload, customer)
            if (!qrCodeAdapter) return apiResponseBuilderService.buildNotFoundItem()

            if (asaasMoneyService.isAsaasMoneyRequest()) {
                fields.bypassAddressKeyValidation = true
            }

            BigDecimal cashValue = PixParser.parseCashValue(qrCodeAdapter, fields.value, fields.changeValue)
            pixTransaction = pixDebitService.saveQrCodeDebit(customer, new PixQrCodeDebitVO(customer, fields, qrCodeAdapter, cashValue), fields.authorizationData)
        }

        if (pixTransaction.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(pixTransaction)
        }

        if (apiRequestPatternAdapter) {
            Transfer transfer = Transfer.query([customer: customer, pixTransaction: pixTransaction, readOnly: true]).get()
            customerKnownApiTransferRequestService.save(transfer, apiRequestPatternAdapter)
        }

        return apiResponseBuilderService.buildSuccess(ApiPixTransactionParser.buildResponseItem(pixTransaction, [buildCriticalAction: true]))
    }

    public Map requestToken(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiPixTransactionParser.parseRequestParams(params)

        CriticalActionGroup criticalActionGroup
        if (fields.externalAccount) {
            criticalActionGroup = pixDebitService.requestManualDebitAuthorizationToken(customer,  new PixManualDebitVO(customer, fields))
        } else if (fields.addressKey) {
            criticalActionGroup = pixDebitService.requestAddressKeyDebitAuthorizationToken(customer, new PixAddressKeyDebitVO(fields, customer))
        } else if (fields.qrCode) {
            QrCodeAdapter qrCodeAdapter = pixQrCodeService.decode(fields.qrCode.payload, customer)

            if (ApiMobileUtils.isMobileAppRequest() && ApiMobileUtils.getApplicationType().isMoney()) {
                fields.bypassAddressKeyValidation = true
            }

            if (!qrCodeAdapter) return apiResponseBuilderService.buildNotFoundItem()

            BigDecimal cashValue = PixParser.parseCashValue(qrCodeAdapter, fields.value, fields.changeValue)
            criticalActionGroup = pixDebitService.requestQrCodeDebitAuthorizationToken(customer, new PixQrCodeDebitVO(customer, fields, qrCodeAdapter, cashValue))
        }

        if (criticalActionGroup.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(criticalActionGroup)
        }

        return apiResponseBuilderService.buildSuccess(ApiCriticalActionParser.buildGroupResponseItem(criticalActionGroup))
    }

    public Map listScheduledTransactionsNotEffectivated(Map params) {
        Integer limit = getLimit(params)
        Integer offset = getOffset(params)

        List<PixTransaction> scheduledPixTransactionList = PixTransaction.scheduledTransactionsNotEffectivated([customer: getProviderInstance(params), sort: "id"]).list(max: limit, offset: offset, readOnly: true)
        List<Map> responseList = scheduledPixTransactionList.collect { ApiPixTransactionParser.buildResponseItem(it) }

        return apiResponseBuilderService.buildList(responseList, limit, offset, scheduledPixTransactionList.size())
    }

    public Map cancelScheduledTransaction(Map params) {
        PixTransaction pixTransaction = pixTransactionService.cancel(params.id, getProviderInstance(params))

        if (pixTransaction.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(pixTransaction)
        }

        return apiResponseBuilderService.buildSuccess(ApiPixTransactionParser.buildResponseItem(pixTransaction))
    }

    public Map validateSchedule(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiPixTransactionParser.parseValidateScheduleParams(params)

        Map responseItem = [:]
        responseItem.valueExceedsScheduleDayCheckoutLimit = buildValueExceedsScheduleDayCheckoutLimit(customer, fields)

        return apiResponseBuilderService.buildSuccess(responseItem)
    }

    public Map listSavedBankAccounts(Map params) {
        Customer customer = getProviderInstance(params)
        Integer limit = getLimit(params)
        Integer offset = getOffset(params)

        Map filters = ApiPixTransactionParser.parseSavedBankAccountsFilters(params)

        List<BankAccountInfo> bankAccountInfoList = BankAccountInfo.approvedBankAccountInfoWithPaymentServiceProviderLinked(customer, filters).list(readonly: true, max: limit, offset: offset)

        List<Map> responseItems = bankAccountInfoList.collect { bankAccount -> ApiBankAccountInfoParser.buildResponseItem(bankAccount, [buildBankAccountInfoCheckoutLimit: filters.buildBankAccountInfoCheckoutLimit, allowsUnmaskedBankAccount: true]) }

        return apiResponseBuilderService.buildList(responseItems, limit, offset, bankAccountInfoList.totalCount)
    }

    public Map saveAsyncExportToCnab750(Map params) {
        Customer customer = getProviderInstance(params)

        pixCnab750TransactionReportFileService.save(customer, ApiBaseParser.parseDate(params.date))

        return apiResponseBuilderService.buildSuccess([success: true, message: "Você receberá um email com o arquivo CNAB 750 com base na data solicitada"])
    }

    public Map exportDailyReport(Map params) {
        Date date = ApiBaseParser.parseDate(params.date.toString())
        PixTransactionStatus status = PixTransactionStatus.convert(params.status)

        AsaasFile asaasFile = pixTransactionExportService.exportDailyReport(getProviderInstance(params), date, status)
        return apiResponseBuilderService.buildFile(asaasFile.getFileBytes(), asaasFile.originalName)
    }

    private List<Map> buildValueExceedsScheduleDayCheckoutLimit(Customer customer, Map fields) {
        List<Map> valueExceedsScheduleDayCheckoutLimit
        if (fields.bankAccountInfoId) {
            BankAccountInfo bankAccountInfo = BankAccountInfo.query([id: fields.bankAccountInfoId, customer: customer, readOnly: true]).get()
            PixManualDebitVO manualDebitVo = new PixManualDebitVO(bankAccountInfo, fields.value, [scheduledDate: fields.scheduledDate])

            AsaasError asaasError = PixCheckoutValidator.validateValue(customer, manualDebitVo)
            if (asaasError) {
                valueExceedsScheduleDayCheckoutLimit = [ApiAsaasErrorParser.buildResponseItem(asaasError)]
            }
        } else if (fields.qrCodePayload) {
            QrCodeAdapter qrCodeAdapter = pixQrCodeService.decode(fields.qrCodePayload, customer)
            BigDecimal cashValue = PixParser.parseCashValue(qrCodeAdapter, fields.value, null)
            PixQrCodeDebitVO qrCodeDebitVO = new PixQrCodeDebitVO(customer, fields, qrCodeAdapter, cashValue)

            AsaasError asaasError = PixCheckoutValidator.validateValue(customer, qrCodeDebitVO)
            if (asaasError) {
                valueExceedsScheduleDayCheckoutLimit = [ApiAsaasErrorParser.buildResponseItem(asaasError)]
            }
        } else if (fields.addressKeyType && fields.pixKey) {
            PixAddressKeyDebitVO addressKeyDebitVO = new PixAddressKeyDebitVO(fields, customer)

            AsaasError asaasError = PixCheckoutValidator.validateValue(customer, addressKeyDebitVO)
            if (asaasError) {
                valueExceedsScheduleDayCheckoutLimit = [ApiAsaasErrorParser.buildResponseItem(asaasError)]
            }
        } else {
            valueExceedsScheduleDayCheckoutLimit = buildValueExceedsScheduleDayCheckoutLimit(customer, fields.value, fields.scheduledDate)
        }

        return valueExceedsScheduleDayCheckoutLimit
    }

    @Deprecated
    private List<Map> buildValueExceedsScheduleDayCheckoutLimit(Customer customer, BigDecimal value, Date scheduledDate) {
        BusinessValidation validatedBusiness = PixTransactionCheckoutLimit.validateIfValueExceedsScheduleDayCheckoutLimit(customer, value, scheduledDate)
        return ApiAsaasErrorParser.buildResponseList(validatedBusiness)
    }
}
