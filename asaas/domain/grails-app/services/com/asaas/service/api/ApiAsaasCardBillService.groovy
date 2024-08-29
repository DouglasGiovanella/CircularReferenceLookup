package com.asaas.service.api

import com.asaas.api.ApiAsaasCardBillParser
import com.asaas.api.ApiCriticalActionParser
import com.asaas.api.ApiPaymentParser
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascardbillpayment.AsaasCardBillPaymentRequest
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.integration.bifrost.adapter.asaascardbill.AsaasCardBillAdapter
import com.asaas.integration.bifrost.adapter.asaascardbill.AsaasCardBillListAdapter
import com.asaas.integration.bifrost.adapter.cardbillitem.AsaasCardBillItemListAdapter
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiAsaasCardBillService extends ApiBaseService {

	def apiResponseBuilderService
    def asaasCardBillService
    def asaasCardBillPaymentService
    def asaasCardBillPaymentRequestService

    public Map find(Map params) {
        Customer customer = getProviderInstance(params)
        AsaasCard asaasCard = AsaasCard.find(params.asaasCardId, customer.id)

        Map fields = ApiAsaasCardBillParser.parseFindRequestParams(params)
        AsaasCardBillAdapter billAdapter = asaasCardBillService.findCardBill(asaasCard, fields)
        if (!billAdapter) {
            return apiResponseBuilderService.buildNotFoundItem()
        }

        return apiResponseBuilderService.buildSuccess(ApiAsaasCardBillParser.buildResponseItem(billAdapter))
    }

    public Map list(Map params) {
        Customer customer = getProviderInstance(params)
        AsaasCard asaasCard = AsaasCard.find(params.asaasCardId, customer.id)

        Map filters = ApiAsaasCardBillParser.parseListFilters(params) + [sort: "referenceMonth"]

        AsaasCardBillListAdapter billListAdapter = asaasCardBillService.listCardBills(asaasCard.id, customer.id, filters)
        List<Map> billItems = billListAdapter.cardBillList.collect { ApiAsaasCardBillParser.buildResponseItem(it) }

        return apiResponseBuilderService.buildList(billItems, getOffset(params), getLimit(params), billListAdapter.totalCount)
    }

    public Map listItems(Map params) {
        Customer customer = getProviderInstance(params)
        AsaasCard asaasCard = AsaasCard.find(params.asaasCardId, customer.id)

        Map filters = ApiAsaasCardBillParser.parseListItemFilters(params) + [order: "desc", sort: "referenceDate"]

        AsaasCardBillItemListAdapter billItemList = asaasCardBillService.listCardBillItems(asaasCard.id, customer.id, filters)
        List<Map> billItems = billItemList.cardBillItemList.collect { ApiAsaasCardBillParser.buildCardBillItemResponseItem(it) }

        return apiResponseBuilderService.buildList(billItems, getOffset(params), getLimit(params), billItemList.totalCount)
    }

    public Map requestPaymentToken(Map params) {
        Customer customer = getProviderInstance(params)
        AsaasCard asaasCard = AsaasCard.find(params.asaasCardId, customer.id)

        CriticalActionGroup criticalActionGroup = asaasCardBillPaymentService.savePayBillCriticalActionGroup(asaasCard)
        if (criticalActionGroup.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(criticalActionGroup)
        }

        return apiResponseBuilderService.buildSuccess(ApiCriticalActionParser.buildGroupResponseItem(criticalActionGroup))
    }

    public Map savePayment(Map params) {
        Map fields = ApiAsaasCardBillParser.parseSavePaymentRequestParams(params)

        Customer customer = getProviderInstance(params)
        AsaasCard asaasCard = AsaasCard.find(fields.asaasCardId, customer.id)

        Map response = [:]

        if (fields.paymentMethod.isPix()) {
            AsaasCardBillPaymentRequest asaasCardBillPaymentRequest = asaasCardBillPaymentRequestService.savePaymentRequest(asaasCard, fields.value, fields.paymentMethod)
            Map pixQrCodeInfo = asaasCardBillPaymentRequestService.getPaymentMethodInfo(asaasCardBillPaymentRequest)

            response.pixQrCode = ApiPaymentParser.buildPixQrCodeResponseItem(pixQrCodeInfo)
        } else {
            asaasCardBillPaymentService.saveManualDebit(asaasCard, fields.value, fields.groupId, fields.token)
        }

        return apiResponseBuilderService.buildSuccess(response)
    }

    public Map download(Map params) {
        AsaasCard asaasCard = AsaasCard.find(params.asaasCardId, params.provider)
        Map cardBillFileMap = asaasCardBillService.buildCardBillFile(asaasCard, Utils.toLong(params.billId))

        return apiResponseBuilderService.buildFile(cardBillFileMap.fileBytes, cardBillFileMap.fileName)
    }
}
