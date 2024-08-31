package com.asaas.service.api.pix.bacen

import com.asaas.api.ApiBaseParser
import com.asaas.api.pix.bacen.ApiBacenPixParser
import com.asaas.api.pix.bacen.ApiBacenPixPaymentParser
import com.asaas.api.pix.bacen.ApiBacenPixResponseBuilder
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.exception.api.ApiBacenPixViolationException
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.service.api.ApiBaseService
import com.asaas.service.api.ApiResponseBuilder
import com.asaas.utils.DomainUtils

import grails.gorm.PagedResultList
import grails.transaction.Transactional

@Transactional
class ApiBacenPixPaymentService extends ApiBaseService {

    def customerAccountService
    def paymentUpdateService
    def paymentService

    public Map save(Map params) {
        validateImplementedResources(params)
        Map parsedParams = ApiBacenPixPaymentParser.parseSaveParams(params)

        parsedParams.customerAccount = saveCustomerAccountIfNotExists(parsedParams.customerAccount)

        Payment payment = paymentService.save(parsedParams, true, true)
        if (payment.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(payment))

        return ApiBacenPixResponseBuilder.buildCreated(ApiBacenPixPaymentParser.buildResponseItem(payment))
    }

    public Map get(String txId) {
        if (!PixUtils.isValidPaymentConciliationIdentifier(txId)) throw new ApiBacenPixViolationException("Necessário informar um identificador de conciliação válido.", "txId", txId)

        Payment payment = Payment.find(txId, getProvider(params))

        if (!payment) return ApiResponseBuilder.buildNotFound()
        return ApiResponseBuilder.buildSuccess(ApiBacenPixPaymentParser.buildShowResponseItem(payment))
    }

    public Map list(Map params) {
        validateListingImplementedFilters(params)
        Map parsedParams = ApiBacenPixPaymentParser.parseListingFilters(params) + [customerId: getProvider(params)]

        Integer offset = ApiBacenPixParser.getOffset(params)
        Integer max = ApiBacenPixParser.getMax(params)
        final Integer customQueryTimeoutInSeconds = 30

        PagedResultList paymentList = Payment.query(parsedParams)
            .list(max: max, offset: offset, readOnly: true, timeout: customQueryTimeoutInSeconds)

        Map responseData = ApiBacenPixPaymentParser.buildResponseItemList(paymentList)
        return ApiBacenPixResponseBuilder.buildSuccessList(
            ApiBacenPixPaymentParser.ALLOWED_FILTERS,
            params,
            responseData,
            max,
            offset,
            paymentList.totalCount
        )
    }

    public Map update(Map params) {
        validateUpdate(params)
        Map parsedFields = ApiBacenPixPaymentParser.parseUpdateParams(params)

        Payment payment = Payment.find(params.txId, getProvider(params))

        payment = paymentUpdateService.update(parsedFields + [payment: payment])
        if (payment.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(payment))

        return ApiResponseBuilder.buildSuccess(ApiBacenPixPaymentParser.buildResponseItem(payment))
    }

    private CustomerAccount saveCustomerAccountIfNotExists(Map customerAccountMap) {
        CustomerAccount customerAccount = CustomerAccount.query([customerId: ApiBaseParser.getProviderId(), cpfCnpj: customerAccountMap.cpfCnpj, readOnly: true]).get()
        if (!customerAccount) {
            customerAccount = customerAccountService.save(ApiBaseParser.getProviderInstance(), null, customerAccountMap)
            if (customerAccount.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(customerAccount))
            customerAccountService.createNotificationsForCustomerAccount(customerAccount)
        }

        return customerAccount
    }

    private void validateImplementedResources(Map params) {
        if (ApiBaseParser.containsKeyAndInstanceOf(params, "calendario", Map)) {
            if (params.calendario.containsKey("validadeAposVencimento")) {
                throw new IllegalArgumentException("O campo 'calendario.validadeAposVencimento' não é suportado.")
            }
        }

        if (params.containsKey("loc")) throw new IllegalArgumentException("O campo 'loc' não é suportado.")
        if (params.containsKey("txid")) throw new IllegalArgumentException("O campo 'txid' não é suportado.")
        if (ApiBaseParser.containsKeyAndInstanceOf(params, "valor", Map)) {
            if (ApiBaseParser.containsKeyAndInstanceOf(params, "juros", Map)) {
                Map interest = params.valor.juros
                final Integer interestMonthlyPeriodModality = 3
                if (interest.modalidade != interestMonthlyPeriodModality) {
                    throw new IllegalArgumentException("O campo 'valor.juros' não é suportado.")
                }
            }

            if (params.valor.containsKey("abaixamento")) throw new IllegalArgumentException("O campo 'valor.abaixamento' não é suportado.")

            if (ApiBaseParser.containsKeyAndInstanceOf(params, "desconto", Map)) {
                Map discount = params.valor.desconto
                List<Integer> allowedDiscountTypes = [1, 2]
                if (!allowedDiscountTypes.contains(discount.modalidade)) {
                    throw new IllegalArgumentException("O campo 'valor.desconto.modalidade' não permite a configuração informada.")
                }

                final Integer maxAllowedDiscountConfig = 1
                if (discount.descontoDataFixa && discount.descontoDataFixa.size() > maxAllowedDiscountConfig) {
                    throw new IllegalArgumentException("O campo 'valor.desconto.descontoDataFixa' não permite mais de um item.")
                }
            }
        }
    }

    private void validateUpdate(Map params) {
        if (!PixUtils.isValidPaymentConciliationIdentifier(params.txId)) throw new ApiBacenPixViolationException("Necessário informar um identificador de conciliação válido.", "txId", params.txId)
        if (params.containsKey("loc")) throw new IllegalArgumentException("O campo 'loc' não é suportado.")
    }

    private void validateListingImplementedFilters(Map params) {
        if (params.containsKey("revisao")) throw new IllegalArgumentException("O campo 'revisao' não é suportado.")
    }
}
