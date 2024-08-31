package com.asaas.service.originrequesterinfo

import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiMobileUtils
import com.asaas.domain.bill.Bill
import com.asaas.domain.bill.BillOriginRequesterInfo
import com.asaas.domain.creditbureaureport.CreditBureauReport
import com.asaas.domain.creditbureaureport.CreditBureauReportOriginRequesterInfo
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.invoice.InvoiceOriginRequesterInfo
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.payment.PaymentDunningOriginRequesterInfo
import com.asaas.domain.payment.PaymentOriginRequesterInfo
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationOriginRequesterInfo
import com.asaas.domain.receivableanticipationsimulation.ReceivableAnticipationSimulationBatch
import com.asaas.domain.receivableanticipationsimulation.ReceivableAnticipationSimulationBatchOriginRequesterInfo
import com.asaas.domain.transfer.Transfer
import com.asaas.domain.transfer.TransferOriginRequester
import com.asaas.originrequesterinfo.BaseOriginRequesterInfoEntity
import com.asaas.originrequesterinfo.OriginChannel
import com.asaas.originrequesterinfo.EventOriginType
import com.asaas.user.UserUtils
import com.asaas.utils.RequestUtils
import com.asaas.utils.UserKnownDeviceUtils
import grails.transaction.Transactional

@Transactional
class OriginRequesterInfoService {

    public BaseOriginRequesterInfoEntity save(referenceDomain) {
        BaseOriginRequesterInfoEntity originRequesterInfoDomainInstance = getOriginRequesterInstanceByDomain(referenceDomain)

        originRequesterInfoDomainInstance.user = UserUtils.getCurrentUser()
        originRequesterInfoDomainInstance.remoteIp = RequestUtils.getRemoteIp()
        originRequesterInfoDomainInstance.eventOrigin = getEventOrigin()
        if (originRequesterInfoDomainInstance.user) originRequesterInfoDomainInstance.device = UserKnownDeviceUtils.getCurrentDevice(originRequesterInfoDomainInstance.user.id)

        return originRequesterInfoDomainInstance.save(failOnError: true)
    }

    public EventOriginType getEventOrigin() {
        if (ApiMobileUtils.isAndroidAppRequest()) return EventOriginType.MOBILE_ANDROID
        if (ApiMobileUtils.isIOSAppRequest()) return EventOriginType.MOBILE_IOS
        if (UserUtils.actorIsApiKey()) return EventOriginType.API
        if (UserUtils.actorIsSystem()) return EventOriginType.SYSTEM

        return EventOriginType.WEB
    }

    public OriginChannel getOriginChannel() {
        return OriginChannel.convert(ApiBaseParser.getRequestOrigin().toString())
    }

    private BaseOriginRequesterInfoEntity getOriginRequesterInstanceByDomain(referenceDomain) {
        if (referenceDomain instanceof Bill) {
            BillOriginRequesterInfo billOriginRequesterInfo = new BillOriginRequesterInfo()
            billOriginRequesterInfo.bill = referenceDomain
            return billOriginRequesterInfo
        }

        if (referenceDomain instanceof Invoice) {
            InvoiceOriginRequesterInfo invoiceOriginRequesterInfo = new InvoiceOriginRequesterInfo()
            invoiceOriginRequesterInfo.invoice = referenceDomain
            return invoiceOriginRequesterInfo
        }

        if (referenceDomain instanceof ReceivableAnticipation) {
            ReceivableAnticipationOriginRequesterInfo receivableAnticipationOriginRequesterInfo = new ReceivableAnticipationOriginRequesterInfo()
            receivableAnticipationOriginRequesterInfo.receivableAnticipation = referenceDomain
            return receivableAnticipationOriginRequesterInfo
        }

        if (referenceDomain instanceof ReceivableAnticipationSimulationBatch) {
            ReceivableAnticipationSimulationBatchOriginRequesterInfo receivableAnticipationSimulationBatchOriginRequesterInfo = new ReceivableAnticipationSimulationBatchOriginRequesterInfo()
            receivableAnticipationSimulationBatchOriginRequesterInfo.receivableAnticipationSimulationBatch = referenceDomain
            return receivableAnticipationSimulationBatchOriginRequesterInfo
        }

        if (referenceDomain instanceof Transfer) {
            TransferOriginRequester transferOriginRequester = new TransferOriginRequester()
            transferOriginRequester.transfer = referenceDomain
            return transferOriginRequester
        }

        if (referenceDomain instanceof CreditBureauReport) {
            CreditBureauReportOriginRequesterInfo creditBureauReportOriginRequesterInfo = new CreditBureauReportOriginRequesterInfo()
            creditBureauReportOriginRequesterInfo.creditBureauReport = referenceDomain
            return creditBureauReportOriginRequesterInfo
        }

        if (referenceDomain instanceof PaymentDunning) {
            PaymentDunningOriginRequesterInfo paymentDunningOriginRequesterInfo = new PaymentDunningOriginRequesterInfo()
            paymentDunningOriginRequesterInfo.paymentDunning = referenceDomain
            return paymentDunningOriginRequesterInfo
        }

        if (referenceDomain instanceof Payment) {
            PaymentOriginRequesterInfo paymentOriginRequesterInfo = new PaymentOriginRequesterInfo()
            paymentOriginRequesterInfo.payment = referenceDomain
            paymentOriginRequesterInfo.originChannel = getOriginChannel()
            return paymentOriginRequesterInfo
        }

        throw new RuntimeException("Não há implementação de OriginRequesterInfo para o domain ${referenceDomain.class.name}")
    }

}
