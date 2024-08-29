package com.asaas.service.api.pix

import com.asaas.api.pix.ApiPixQrCodeParser
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.pix.adapter.qrcode.PixQrCodeTransactionListAdapter
import com.asaas.pix.adapter.qrcode.QrCodeAdapter
import com.asaas.service.api.ApiBaseService

import grails.transaction.Transactional

@Transactional
class ApiPixQrCodeService extends ApiBaseService {

    def apiResponseBuilderService
    def pixQrCodeService

    public Map decode(Map params) {
        Customer customer = getProviderInstance(params)
        QrCodeAdapter qrCodeAdapter = pixQrCodeService.decode(params.payload.toString(), customer)

        if (!qrCodeAdapter) return apiResponseBuilderService.buildNotFoundItem()

        return apiResponseBuilderService.buildSuccess(ApiPixQrCodeParser.buildDecodeResponseItem(qrCodeAdapter))
    }

    public Map save(Map params) {
        Map fields = ApiPixQrCodeParser.parseSaveParams(params)

        Map qrCode = pixQrCodeService.createStaticQrCode(getProviderInstance(params), fields)

        if (!qrCode.success) {
            return apiResponseBuilderService.buildErrorFrom("unknown_error", qrCode.errorMessage)
        }

        return apiResponseBuilderService.buildSuccess(ApiPixQrCodeParser.buildResponseItem(qrCode))
    }

    public Map delete(Map params) {
        pixQrCodeService.delete(getProviderInstance(params), params.conciliationIdentifier.toString())

        return apiResponseBuilderService.buildDeleted(params.conciliationIdentifier.toString())
    }

    public Map listTransactions(Map params) {
        if (!params.id) return apiResponseBuilderService.buildErrorFrom("not_informed_id", "O Id do QR Code deve ser informado.")

        Customer customer = getProviderInstance(params)
        PixQrCodeTransactionListAdapter transactionListAdapter = pixQrCodeService.listTransactions(customer, params.id, getOffset(params), getLimit(params))
        Boolean allowUnmaskedCpfCnpj = CustomerParameter.getValue(customer.id, CustomerParameterName.ALLOW_UNMASKED_CPF_CNPJ_ON_API)

        return apiResponseBuilderService.buildSuccess(ApiPixQrCodeParser.buildTransactionResponseList(transactionListAdapter, [allowUnmaskedCpfCnpj: allowUnmaskedCpfCnpj]))
    }
}
