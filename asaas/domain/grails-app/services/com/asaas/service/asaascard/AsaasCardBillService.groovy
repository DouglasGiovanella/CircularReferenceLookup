package com.asaas.service.asaascard

import com.asaas.converter.HtmlToPdfConverter
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.adapter.asaascardbill.AsaasCardBillAdapter
import com.asaas.integration.bifrost.adapter.asaascardbill.AsaasCardBillDetailedInfoAdapter
import com.asaas.integration.bifrost.adapter.asaascardbill.AsaasCardBillListAdapter
import com.asaas.integration.bifrost.adapter.cardbillitem.AsaasCardBillItemListAdapter
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class AsaasCardBillService {

    def bifrostCardBillManagerService
    def groovyPageRenderer

    public Map buildCardBillFile(AsaasCard asaasCard, Long billId) {
        AsaasCardBillAdapter billAdapter = getCardBill(asaasCard, [billId: billId])

        if (!billAdapter) throw new RuntimeException("Fatura não encontrada [asaasCardId: ${asaasCard.publicId} / billId: ${billId}].")
        if (!billAdapter.canBeExported) throw new BusinessException("Somente pode ser feito download de faturas fechadas ou pagas.")

        AsaasCardBillDetailedInfoAdapter asaasCardBillDetailedInfoAdapter = bifrostCardBillManagerService.getDetails(asaasCard, billId)

        String htmlString = groovyPageRenderer.render(template: "/asaasCard/templates/bill/billPdf", model: [asaasCard: asaasCard, customer: asaasCard.customer, asaasCardBillDetailedInfoAdapter: asaasCardBillDetailedInfoAdapter]).decodeHTML()

        byte[] fileBytes = HtmlToPdfConverter.convert(htmlString)
        String fileName = "Asaas_${CustomDateUtils.fromDate(billAdapter.dueDate, CustomDateUtils.DATABASE_DATE_FORMAT)}.pdf"

        Map cardBillFileMap = [:]
        cardBillFileMap.fileBytes = fileBytes
        cardBillFileMap.fileName = fileName

        return cardBillFileMap
    }

    public AsaasCardBillAdapter getCardBill(AsaasCard asaasCard, Map filter) {
        return bifrostCardBillManagerService.get(asaasCard, filter)
    }

    public AsaasCardBillListAdapter listCardBills(Long asaasCardId, Long customerId, Map filter) {
        AsaasCard asaasCard = AsaasCard.find(asaasCardId, customerId)

        return bifrostCardBillManagerService.listCardBills(asaasCard, filter)
    }

    public AsaasCardBillAdapter findCardBill(AsaasCard asaasCard, Map filter) {
        return bifrostCardBillManagerService.find(asaasCard, filter)
    }

    public AsaasCardBillItemListAdapter listCardBillItems(Long asaasCardId, Long customerId, Map filter) {
        AsaasCard asaasCard = AsaasCard.find(asaasCardId, customerId)

        return bifrostCardBillManagerService.listItems(asaasCard, filter)
    }
}
