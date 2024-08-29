
package com.asaas.service.paymentcampaign

import com.asaas.domain.paymentcampaign.PaymentCampaign
import com.asaas.domain.paymentcampaign.PaymentCampaignStockControl
import com.asaas.paymentcampaign.PaymentCampaignStockControlRepository
import com.asaas.redis.RedissonProxy
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.redisson.api.RSemaphore

@Transactional
class PaymentCampaignStockControlService {

    def paymentCampaignService

    public Long saveWithNewTransaction(Long paymentCampaignId) {
        Long paymentCampaignStockControlId
        Utils.withNewTransactionAndRollbackOnError({
            PaymentCampaignStockControl paymentCampaignStockControl = new PaymentCampaignStockControl()
            paymentCampaignStockControl.paymentCampaignId = paymentCampaignId
            paymentCampaignStockControl.save(failOnError: true)

            paymentCampaignStockControlId = paymentCampaignStockControl.id
        })

        return paymentCampaignStockControlId
    }

    public void processNotDeletedPaymentCampaignStockControl() {
        Date startDateToProcessNotDeleted = CustomDateUtils.sumMinutes(new Date(), -1)
        List<Map> paymentCampaignStockControlMapInfo = PaymentCampaignStockControlRepository.query(["dateCreated[lt]": startDateToProcessNotDeleted]).column(["id", "paymentCampaignId"]).list()
        if (!paymentCampaignStockControlMapInfo) return
        Set<Long> paymentCampaignIdList = paymentCampaignStockControlMapInfo.collect { Utils.toLong(it.paymentCampaignId) }.toSet()
        List<Long> paymentCampaignStockControlIdList = paymentCampaignStockControlMapInfo.collect { Utils.toLong(it.id) }

        for (Long paymentCampaignId : paymentCampaignIdList) {
            PaymentCampaign paymentCampaign = PaymentCampaign.get(paymentCampaignId)
            RSemaphore paymentCampaignSemaphore = RedissonProxy.instance.getClient().getSemaphore("${PaymentCampaignService.PAYMENT_CAMPAIGN_AVAILABLE_QUANTITY_SEMAPHORE_PREFIX}:${paymentCampaign.id}")
            if (paymentCampaignSemaphore.isExists()) paymentCampaignSemaphore.delete()

            paymentCampaignService.buildPaymentCampaignSemaphore(paymentCampaign)
        }

        for (Long paymentCampaignStockControlId : paymentCampaignStockControlIdList) {
            PaymentCampaignStockControl.load(paymentCampaignStockControlId).delete()
        }
    }

    public void delete(Long paymentCampaignStockControlId) {
        PaymentCampaignStockControl.executeUpdate("delete from PaymentCampaignStockControl where id = :id", [id: paymentCampaignStockControlId])
    }
}
