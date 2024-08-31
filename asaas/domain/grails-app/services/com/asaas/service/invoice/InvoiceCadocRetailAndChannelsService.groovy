package com.asaas.service.invoice

import com.asaas.cadoc.retailAndChannels.adapters.CadocRetailAndChannelsFillerResponseAdapter
import com.asaas.cadoc.retailAndChannels.enums.CadocAccessChannel
import com.asaas.cadoc.retailAndChannels.enums.CadocOtherNonFinancialProducts
import com.asaas.cadoc.retailAndChannels.enums.CadocRetailAndChannelsFillerType
import com.asaas.cadoc.retailAndChannels.enums.CadocTransactionProduct
import com.asaas.domain.cadoc.CadocRetailAndChannelsFillerControl
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.invoice.ProductInvoice
import com.asaas.invoice.InvoiceType
import com.asaas.log.AsaasLogger
import com.asaas.originrequesterinfo.EventOriginType
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.hibernate.SQLQuery

@Transactional
class InvoiceCadocRetailAndChannelsService {

    def asaasBacenCadocRetailAndChannelsManagerService
    def sessionFactory
    def retailAndChannelsManagerService

    public Boolean generateDataForInvoiceRequest() {
        Boolean sentWebData = sendInvoiceInfo(EventOriginType.WEB)
        Boolean sentIosData = sendInvoiceInfo(EventOriginType.MOBILE_IOS)
        Boolean sentAndroidData = sendInvoiceInfo(EventOriginType.MOBILE_ANDROID)

        return sentWebData || sentIosData || sentAndroidData
    }

    private Boolean sendInvoiceInfo(EventOriginType eventOrigin) {
        CadocRetailAndChannelsFillerControl fillerControl = CadocRetailAndChannelsFillerControl.query([className: Invoice.getSimpleName(), eventOrigin: eventOrigin, fillerType: CadocRetailAndChannelsFillerType.TRANSACTION]).get()
        Date baseDate = fillerControl ? CustomDateUtils.sumDays(fillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()

        if (CustomDateUtils.isSameDayOfYear(baseDate, new Date())) return false

        sendForInvoice(eventOrigin, baseDate)
        sendForProductInvoice(eventOrigin, baseDate)

        if (!fillerControl) {
            fillerControl = new CadocRetailAndChannelsFillerControl()
            fillerControl.className = Invoice.getSimpleName()
            fillerControl.fillerType = CadocRetailAndChannelsFillerType.TRANSACTION
            fillerControl.eventOrigin = eventOrigin
        }
        fillerControl.baseDateSynchronized = baseDate
        fillerControl.save(failOnError: true)
        return true
    }

    private void sendForInvoice(EventOriginType eventOrigin, Date baseDate) {
        StringBuilder sql = new StringBuilder()
            .append(" select count(this_.id) as sumQuantity ")
            .append(" from invoice this_ ")
            .append(" inner join invoice_origin_requester_info originRequester on this_.id = originRequester.invoice_id ")
            .append(" where this_.effective_date >= :effectiveDateStart ")
            .append(" and this_.effective_date <= :effectiveDateFinish ")
            .append(" and type IN (:typeList) ")
            .append(" and originRequester.event_origin = :eventOrigin ")
            .append(" and customer_id is not null ")
            .append(" and number is not null ")
            .append(" and this_.deleted = false ")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setParameterList("typeList", [InvoiceType.NFSE.toString(), InvoiceType.NFE.toString()])
        query.setTimestamp("effectiveDateStart", baseDate.clearTime())
        query.setTimestamp("effectiveDateFinish", CustomDateUtils.setTimeToEndOfDay(baseDate))
        query.setString("eventOrigin", eventOrigin.toString())

        Integer countQuery = query.uniqueResult()

        Map cadocTransactionData = [
            product                  : CadocTransactionProduct.OTHERS_NON_FINANCIAL,
            accessChannel            : CadocAccessChannel.parseFromEventOriginType(eventOrigin),
            otherNonFinancialProducts: CadocOtherNonFinancialProducts.INVOICE,
            sumQuantity              : countQuery
        ]
        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)
        retailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendForInvoice >> Erro ao enviar dados de transações da Invoice. [errorDescription: ${responseAdapter.errorDescription}]")
            throw new RuntimeException("Não foi possível processar dados de notas fiscais na data ${baseDate} com o evento de origem ${eventOrigin}.")
        }
    }

    private void sendForProductInvoice(EventOriginType eventOrigin, Date baseDate) {
        if (eventOrigin != EventOriginType.WEB) return

        Integer productInvoiceQuantity = ProductInvoice.query(["column": "id", "effectiveDate[ge]": baseDate.clearTime(), "effectiveDate[le]": CustomDateUtils.setTimeToEndOfDay(baseDate), disableSort: true]).count()

        Map cadocTransactionData = [
            product                  : CadocTransactionProduct.OTHERS_NON_FINANCIAL,
            accessChannel            : CadocAccessChannel.parseFromEventOriginType(eventOrigin),
            otherNonFinancialProducts: CadocOtherNonFinancialProducts.INVOICE,
            sumQuantity              : productInvoiceQuantity
        ]
        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)
        retailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendForProductInvoice >> Erro ao enviar dados de transações do productInvoice. [errorDescription: ${responseAdapter.errorDescription}]")
            throw new RuntimeException("Não foi possível processar dados de notas fiscais de produto na data ${baseDate} com o evento de origem ${eventOrigin}.")
        }
    }

}
