package com.asaas.service.api

import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiBillParser
import com.asaas.api.ApiCriticalActionParser
import com.asaas.api.ApiMobileUtils
import com.asaas.bill.BillUtils
import com.asaas.checkout.CheckoutValidator
import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.bill.Bill
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.TemporaryFile
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.invoice.Invoice
import com.asaas.exception.BusinessException
import com.asaas.linhadigitavel.InvalidLinhaDigitavelException
import com.asaas.linhadigitavel.LinhaDigitavelExtractor
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import grails.transaction.Transactional
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class ApiBillService extends ApiBaseService {

    def apiResponseBuilderService
    def billService
    def temporaryFileService

    def find(params) {
        Bill bill = Bill.find(params.id, getProviderInstance(params))
        return ApiBillParser.buildResponseItem(bill, [buildCriticalAction: ApiMobileUtils.isMobileAppRequest()])
    }

    def list(params) {
        Customer customer = getProviderInstance(params)

        Map searchMap = ApiBillParser.parseFilters(params)

        searchMap << [
            customer: customer,
            sortList: [
                [sort: "scheduleDate", order: "desc"],
                [sort: "id", order: "asc"]
            ]
        ]

        List<Bill> bills = Bill.query(searchMap).list(max: getLimit(params), offset: getOffset(params))

        List<Map> responseItems = []

        bills.each { bill ->
            responseItems << ApiBillParser.buildResponseItem(bill)
        }

        List<Map> extraData = []

        if (ApiMobileUtils.isMobileAppRequest()) {
            CheckoutValidator checkoutValidator = new CheckoutValidator(customer)
            checkoutValidator.isBillOrAsaasCard = true

            List<AsaasError> listOfAsaasError = checkoutValidator.validate()
            List<Map> denialReasons = listOfAsaasError.collect { [code: it.code, description: Utils.getMessageProperty("bill." + it.code, it.arguments)] }

            if (denialReasons) {
                extraData << [denialReasons: denialReasons]
            } else {
                Map customerInvoiceInfoMap = Invoice.buildNextInvoiceWithFeeNotChargedMap(customer)
                if (customerInvoiceInfoMap) extraData << [customerInvoiceInfoMap: customerInvoiceInfoMap]
            }

            extraData << [balance: FinancialTransaction.getCustomerBalance(customer)]
        }

        return apiResponseBuilderService.buildList(responseItems, getLimit(params), getOffset(params), bills.totalCount, extraData)
    }

    def save(params) {
        try {
            if (!params.identificationField && !params.linhaDigitavel) {
                return apiResponseBuilderService.buildError("required", "bill", "identificationField")
            }

            Map linhaDigitavelMap = billService.getAndValidateLinhaDigitavelInfo(params.identificationField ?: params.linhaDigitavel, null)
            params = ApiBillParser.parseSaveParams(params, linhaDigitavelMap)
        } catch (InvalidLinhaDigitavelException e) {
            return apiResponseBuilderService.buildErrorFrom("unknow.error", e.message)
        }

        Bill bill = billService.save(getProviderInstance(params), params)

        if (bill.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(bill)
        }

        return ApiBillParser.buildResponseItem(bill, [buildCriticalAction: ApiMobileUtils.isMobileAppRequest()])
    }

    def delete(params) {
        Customer customer = getProviderInstance(params)
        Bill bill = billService.cancel(customer, params.id)

        if (bill.hasErrors()) {
            String errorCode = bill.errors.allErrors.defaultMessage[0]
            return apiResponseBuilderService.buildErrorFrom(errorCode, Utils.getMessageProperty(errorCode))
        }

        Map responseMap = apiResponseBuilderService.buildDeleted(bill.id)
        responseMap.awaitingCriticalActionAuthorization = CriticalAction.pendingOrAwaitingAuthorization([customer: customer, object: bill, type: CriticalActionType.BILL_DELETE, exists: true]).get().asBoolean()
        responseMap << CriticalAction.getCriticalActionMapFromObjectAndAuthorizationDeviceCaseNecessary(customer, [bill: [id: bill.id]])

        return responseMap
    }

    def cancel(params) {
        Bill bill = billService.cancel(getProviderInstance(params), params.id)

        if (bill.hasErrors()) {
            String errorCode = bill.errors.allErrors.defaultMessage[0]
            return apiResponseBuilderService.buildErrorFrom(errorCode, Utils.getMessageProperty(errorCode))
        }

        return apiResponseBuilderService.buildSuccess(ApiBillParser.buildResponseItem(bill))
    }

    def simulate(params) {
        String linhaDigitavel
        Customer customer = getProviderInstance(params)

        if (!params.identificationField && !params.barCode && !params.bankSlipFile) {
            return apiResponseBuilderService.buildErrorFrom("bank_slip_not_informed", Utils.getMessageProperty("linhaDigitavel.error.barCodeOrFileNotInformed"))
        }

        if (params.identificationField) {
            linhaDigitavel = params.identificationField
        } else if (params.barCode) {
            LinhaDigitavelExtractor linhaDigitavelExtractor = new LinhaDigitavelExtractor(params.barCode)
            linhaDigitavelExtractor.extract()

            if (linhaDigitavelExtractor.hasErrors()) {
                return apiResponseBuilderService.buildErrorFromCode(linhaDigitavelExtractor.errors[0].code)
            }

            linhaDigitavel = linhaDigitavelExtractor.linhaDigitavel
        } else if (params.bankSlipFile) {
            Map linhaDigitavelMap = getLinhaDigitavelMapFromFile(params)
            if (!linhaDigitavelMap.success) {
                return apiResponseBuilderService.buildErrorFromCode(linhaDigitavelMap.errorCode)
            }

            linhaDigitavel = linhaDigitavelMap.linhaDigitavel
        }

        CheckoutValidator checkoutValidator = new CheckoutValidator(customer)
        checkoutValidator.isBillOrAsaasCard = true

        List<AsaasError> listOfAsaasError = checkoutValidator.validate()

        if (listOfAsaasError) {
            AsaasError asaasError = listOfAsaasError.first()
            return apiResponseBuilderService.buildErrorFrom(asaasError.code, Utils.getMessageProperty("bill." + asaasError.code, asaasError.arguments))
        }

        try {
            Map linhaDigitavelResponse = billService.getAndValidateLinhaDigitavelInfo(linhaDigitavel, null)
            return ApiBillParser.buildSimulationItem(linhaDigitavelResponse)
        } catch (InvalidLinhaDigitavelException e) {
            return apiResponseBuilderService.buildErrorFrom("unknow.error", e.message)
        }
    }

    public Map requestToken(Map params) {
        Customer customer = getProviderInstance(params)

        try {
            String linhaDigitavel = params.identificationField ?: params.linhaDigitavel

            if (!linhaDigitavel) {
                throw new BusinessException("É obrigatório informar o código de barras ou a linha digitável.")
            }

            Map linhaDigitavelMap = billService.getAndValidateLinhaDigitavelInfo(linhaDigitavel, null)
            params = ApiBillParser.parseSaveParams(params, linhaDigitavelMap)
        } catch (InvalidLinhaDigitavelException e) {
            return apiResponseBuilderService.buildErrorFrom("unknow.error", e.message)
        }

        CriticalActionGroup criticalActionGroup = billService.requestAuthorizationToken(customer, params.linhaDigitavel, params.value)

        if (criticalActionGroup.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(criticalActionGroup)
        }

        return apiResponseBuilderService.buildSuccess(ApiCriticalActionParser.buildGroupResponseItem(criticalActionGroup))
    }

    public Map validateDueDate(Map params) {
        Date dueDate = ApiBaseParser.parseDate(params.dueDate)

        return apiResponseBuilderService.buildSuccess([isOverdue: BillUtils.isOverdue(dueDate)])
    }

    private Map getLinhaDigitavelMapFromFile(Map params) {
        if (!(params.bankSlipFile instanceof CommonsMultipartFile)) {
            return [success: false, errorCode: "linhaDigitavelExtractor.itWasNotPossibleReadBarCode"]
        }

        CommonsMultipartFile bankSlipFile = params.bankSlipFile

        TemporaryFile boletoTempFile = temporaryFileService.save(getProviderInstance(params), bankSlipFile, false)

        LinhaDigitavelExtractor linhaDigitavelExtractor = new LinhaDigitavelExtractor(boletoTempFile)
        linhaDigitavelExtractor.extract()

        if (linhaDigitavelExtractor.hasErrors()) {
            return [success: false, errorCode: linhaDigitavelExtractor.errors[0].code]
        }

        return [success: true, linhaDigitavel: linhaDigitavelExtractor.linhaDigitavel]
    }
}
