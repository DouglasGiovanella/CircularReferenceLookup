package com.asaas.service.api.asaasmoney

import com.asaas.api.ApiBillParser
import com.asaas.api.ApiCreditCardParser
import com.asaas.api.ApiPaymentParser
import com.asaas.api.asaasmoney.ApiAsaasMoneyParser
import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.companypartnerquery.CompanyPartnerQuery
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.integration.heimdall.enums.revenueserviceregister.RevenueServiceRegisterCacheLevel
import com.asaas.integration.pix.parser.PixParser
import com.asaas.linhadigitavel.InvalidLinhaDigitavelException
import com.asaas.pix.adapter.qrcode.QrCodeAdapter
import com.asaas.pix.vo.transaction.PixQrCodeDebitVO
import com.asaas.service.api.ApiBaseService
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class ApiAsaasMoneyService extends ApiBaseService {

    def apiResponseBuilderService
    def billService
    def criticalActionService
    def paymentService
    def pixDebitService
    def pixQrCodeService
    def revenueServiceRegisterService

    public Map getCustomer(Map params) {
        Customer customer = getProviderInstance(params)
        return apiResponseBuilderService.buildSuccess(ApiAsaasMoneyParser.buildCustomerResponse(customer))
    }

    public Map listCustomerRelatedCpfs(Map params) {
        Customer customer = getProviderInstance(params)
        List<Map> responseList = []
        if (customer.cpfCnpj && (customer.personType || customer.companyType)) {
            if (customer.personType?.isFisica() || customer.companyType?.isMEI()) {
                responseList.add(ApiAsaasMoneyParser.buildCustomerRelatedCpfResponseFromCustomer(customer))
            } else {
                RevenueServiceRegister revenueServiceRegister = revenueServiceRegisterService.findLegalPerson(customer.cpfCnpj, RevenueServiceRegisterCacheLevel.HIGH)
                List<CompanyPartnerQuery> companyPartnerQueryList = revenueServiceRegister.buildCompanyPartnerList()
                responseList = companyPartnerQueryList.collect { CompanyPartnerQuery companyPartnerQuery -> ApiAsaasMoneyParser.buildCustomerRelatedCpfResponse(companyPartnerQuery) }
            }
        }

        return apiResponseBuilderService.buildList(responseList, responseList.size(), 0, responseList.size())
    }

    public Map payWithCreditCard(params) {
        try {
            Customer customer = getProviderInstance(params)

            Payment payment = Payment.queryByPayerCpfCnpj(customer.cpfCnpj, [publicId: params.paymentId]).get()

            Map fields = ApiPaymentParser.parseRequestParams(params)
            fields.creditCardTransactionOriginInfo = ApiCreditCardParser.parseRequestCreditCardTransactionOriginInfoParams(params)
            fields.creditCardTransactionOriginInfo.asaasMoneyPayerCustomer = customer

            paymentService.processCreditCardPayment(payment, fields)

            if (payment.hasErrors()) {
                return apiResponseBuilderService.buildErrorList(payment)
            }

            return apiResponseBuilderService.buildSuccess(ApiPaymentParser.buildResponseItem(payment, [:]))
        } catch (Exception e) {
            return apiResponseBuilderService.buildErrorFrom("failed", e.message)
        }
    }

    public Map verifyPixQrCodeDebitCriticalAction(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiAsaasMoneyParser.parsePixParams(params)
        fields.bypassAddressKeyValidation = true

        QrCodeAdapter qrCodeAdapter = pixQrCodeService.decode(fields.qrCode.payload, customer)
        if (!qrCodeAdapter) return apiResponseBuilderService.buildNotFoundItem()

        BigDecimal cashValue = PixParser.parseCashValue(qrCodeAdapter, fields.value, fields.changeValue)

        String hash = pixDebitService.buildCriticalActionHash(customer, new PixQrCodeDebitVO(customer, fields, qrCodeAdapter, cashValue))

        BusinessValidation businessValidation = criticalActionService.verifySynchronous(customer, Long.valueOf(params.groupId), params.token, CriticalActionType.PIX_SAVE_QR_CODE_DEBIT, hash)
        if (!businessValidation.isValid()) return apiResponseBuilderService.buildErrorFromCode(businessValidation.getFirstErrorCode())

        return apiResponseBuilderService.buildSuccess([success: true])
    }

    public Map verifyBillInsertCriticalAction(Map params) {
        Customer customer = getProviderInstance(params)

        try {
            Map linhaDigitavelMap = billService.getAndValidateLinhaDigitavelInfo(params.identificationField ?: params.linhaDigitavel, null)
            params = ApiBillParser.parseSaveParams(params, linhaDigitavelMap)
        } catch (InvalidLinhaDigitavelException e) {
            return apiResponseBuilderService.buildErrorFrom("unknow.error", e.message)
        }

        String hash = billService.buildCriticalActionHash(customer, params.linhaDigitavel, params.value)

        BusinessValidation businessValidation = criticalActionService.verifySynchronous(customer, Long.valueOf(params.groupId), params.token, CriticalActionType.BILL_INSERT, hash)
        if (!businessValidation.isValid()) return apiResponseBuilderService.buildErrorFromCode(businessValidation.getFirstErrorCode())

        return apiResponseBuilderService.buildSuccess([success: true])
    }
}
