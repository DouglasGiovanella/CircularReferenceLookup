package com.asaas.service.plan

import com.asaas.cadoc.retailAndChannels.adapters.CadocRetailAndChannelsFillerResponseAdapter
import com.asaas.cadoc.retailAndChannels.enums.CadocAccessChannel
import com.asaas.cadoc.retailAndChannels.enums.CadocOtherNonFinancialProducts
import com.asaas.cadoc.retailAndChannels.enums.CadocRetailAndChannelsFillerType
import com.asaas.cadoc.retailAndChannels.enums.CadocTransactionProduct
import com.asaas.domain.cadoc.CadocRetailAndChannelsFillerControl
import com.asaas.domain.customerplan.CustomerPlan
import com.asaas.domain.payment.Payment
import com.asaas.domain.plan.Plan
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class PlanCadocRetailAndChannelsService {

    def asaasBacenCadocRetailAndChannelsManagerService
    def retailAndChannelsManagerService

    public void sendCustomerPlanInfo() {
        CadocRetailAndChannelsFillerControl fillerControl = CadocRetailAndChannelsFillerControl.query([className: CustomerPlan.getSimpleName(), fillerType: CadocRetailAndChannelsFillerType.CUSTOMER_PLAN]).get()
        Date baseStartDate = fillerControl ? CustomDateUtils.sumDays(fillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()
        Date baseEndDate = CustomDateUtils.getLastDayOfMonth(baseStartDate)

        if (baseEndDate >= new Date().clearTime()) return

        List<Long> planId = Plan.getCustomerPaymentPlanId()
        Map queryParameters = [:]
        queryParameters."planId[in]" = planId
        queryParameters.status = PaymentStatus.RECEIVED
        queryParameters."creditDate[ge]" = baseStartDate.clearTime()
        queryParameters."creditDate[le]" = CustomDateUtils.setTimeToEndOfDay(baseEndDate)
        Map customerPlanInfo = Payment.sumValueAndCount(queryParameters).get()

        Map cadocTransactionData = [
            product: CadocTransactionProduct.OTHERS_NON_FINANCIAL,
            accessChannel: CadocAccessChannel.INTERNET_BANKING,
            otherNonFinancialProducts: CadocOtherNonFinancialProducts.CUSTOMER_PLAN,
            sumValue: customerPlanInfo.sumValue,
            sumQuantity: customerPlanInfo.sumQuantity
        ]
        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createTransaction(baseStartDate, cadocTransactionData)
        retailAndChannelsManagerService.createTransaction(baseStartDate, cadocTransactionData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendCustomerPlanInfo >> Erro ao enviar dados de planos. [errorDescription: ${responseAdapter.errorDescription}]")
            return
        }

        if (!fillerControl) {
            fillerControl = new CadocRetailAndChannelsFillerControl()
            fillerControl.className = CustomerPlan.getSimpleName()
            fillerControl.fillerType = CadocRetailAndChannelsFillerType.CUSTOMER_PLAN
        }

        fillerControl.baseDateSynchronized = baseEndDate
        fillerControl.save(failOnError: true)
    }
}
