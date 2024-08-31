package com.asaas.service.promotionalarea

import com.asaas.abtest.enums.AbTestPlatform
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.freepaymentconfig.FreePaymentMonthlyConfig
import com.asaas.domain.partnerapplication.CustomerPartnerApplication

import grails.transaction.Transactional

@Transactional
class PromotionalAreaService {

    def abTestService
    def grailsApplication
    def promotionalCodeService

    public void drawPromotionalAreaAdvantagesSectionRemovedAbTest(Customer customer, AbTestPlatform platform) {
        if (!abTestService.canDrawAbTestFollowingGrowthRules(customer)) return

        abTestService.chooseVariant(grailsApplication.config.asaas.abtests.promotionalArea.removeAdvantagesSection.name, customer, platform)
    }

    public Map getFreePaymentInformation(Customer customer) {
        Integer totalFreePayment = 0

        FreePaymentMonthlyConfig freePaymentMonthly = FreePaymentMonthlyConfig.valid([customer: customer]).get()
        List<Map> freePaymentList = promotionalCodeService.getValidFreePayments(customer.id)

        if (freePaymentMonthly?.freePaymentsRemaining) totalFreePayment += freePaymentMonthly.freePaymentsRemaining

        for (Map freePaymentItem : freePaymentList) {
            totalFreePayment += freePaymentItem.freePaymentRemaining
        }

        Map freePaymentInformation = [
            freePaymentList: freePaymentList,
            totalFreePayment: totalFreePayment,
            freePaymentMonthly: freePaymentMonthly
        ]

        return freePaymentInformation
    }

    public Boolean canViewNewPromotionalArea(Customer customer) {
        if (customer.accountOwner) return false

        Boolean isWhiteLabel = CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)
        if (isWhiteLabel) return false

        if (CustomerPartnerApplication.hasBradesco(customer.id)) return false

        return true
    }
}
