package com.asaas.service.customer.customerbenefit

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.benefit.enums.BenefitStatus
import com.asaas.benefit.enums.BenefitType
import com.asaas.benefit.repository.BenefitRepository
import com.asaas.customer.customerbenefit.adapter.CreateCustomerBenefitAdapter
import com.asaas.customer.customerbenefit.adapter.CreateCustomerBenefitItemAdapter
import com.asaas.customer.customerbenefit.repository.CustomerBenefitRepository
import com.asaas.domain.benefit.Benefit
import com.asaas.domain.customer.customerbenefit.CustomerBenefit
import com.asaas.domain.customer.customerbenefit.CustomerBenefitItem
import com.asaas.domain.promotionalcode.PromotionalCode
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class CustomerBenefitService {

    public void save(CreateCustomerBenefitAdapter adapter) {
        CustomerBenefit customerBenefit = new CustomerBenefit()
        customerBenefit.customer = adapter.customer
        customerBenefit.benefit = adapter.benefit
        customerBenefit.activationDate = adapter.activationDate
        customerBenefit.expirationDate = adapter.expirationDate
        customerBenefit.status = adapter.status
        customerBenefit.save(failOnError: true)

        adapter.customerBenefitItems.each { CreateCustomerBenefitItemAdapter customerBenefitItemAdapter ->
            CustomerBenefitItem customerBenefitItem = new CustomerBenefitItem()
            customerBenefitItem.customerBenefit = customerBenefit
            customerBenefitItem.benefitItem = customerBenefitItemAdapter.benefitItem
            customerBenefitItem.value = customerBenefitItemAdapter.value
            customerBenefitItem.save(failOnError: true)
        }
    }

    public void expireCustomerBenefitsIfNecessary() {
        List<Long> customerBenefitIdsList = CustomerBenefitRepository
            .query([status: BenefitStatus.ACTIVE, "expirationDate[lt]": new Date()])
            .column("id")
            .list()

        final Integer FLUSH_EVERY = 50
        Utils.forEachWithFlushSession(customerBenefitIdsList, FLUSH_EVERY, { Long customerBenefitId ->
            Utils.withNewTransactionAndRollbackOnError({
                CustomerBenefit customerBenefit = CustomerBenefit.get(customerBenefitId)
                customerBenefit.status = BenefitStatus.EXPIRED
                customerBenefit.save(failOnError: true)
            }, [
                onError: { Exception exception ->
                    AsaasLogger.error("CustomerBenefitService.expireCustomerBenefitsIfNecessary >> Erro ao expirar CustomerBenefit: ${customerBenefitId}", exception)
                }
            ])
        })
    }

    public void updateStatusToUsedIfNecessary(PromotionalCode promotionalCode) {
        String promotionalCodeGroupName = promotionalCode.promotionalCodeGroup?.name
        if (shouldNotSetStatusToUsed(promotionalCodeGroupName)) return

        String benefitName = Benefit.getBenefitNameBasedOnPromotionalCodeGroupName(promotionalCodeGroupName)
        Long benefitId = BenefitRepository.query([name: benefitName]).column("id").get()
        CustomerBenefit customerBenefit = CustomerBenefitRepository.query([customerId: promotionalCode.customer.id, benefitId: benefitId, status: BenefitStatus.ACTIVE]).get()
        if (!customerBenefit) return

        BenefitType benefitType = customerBenefit.benefit.type
        switch (benefitType) {
            case BenefitType.FREE_PAYMENT:
                Boolean freePaymentAmountUsed = promotionalCode.freePaymentsUsed >= promotionalCode.freePaymentAmount
                if (freePaymentAmountUsed) updateStatusToUsed(customerBenefit)
                break
            case BenefitType.PROMOTIONAL_CREDIT:
                Boolean discountValueUsed = promotionalCode.discountValueUsed >= promotionalCode.discountValue
                if (discountValueUsed) updateStatusToUsed(customerBenefit)
                break
            default:
                throw new NotImplementedException("CustomerBenefitService.updateStatusToUsedIfNecessary >> Tipo de benefício não implementado: ${customerBenefit.benefit.type} para o customerId: ${customerBenefit.customer.id}")
        }
    }

    private void updateStatusToUsed(CustomerBenefit customerBenefit) {
        customerBenefit.status = BenefitStatus.USED
        customerBenefit.save(failOnError: true)
    }

    private Boolean shouldNotSetStatusToUsed(String promotionalCodeGroupName) {
        List promotionsToIgnore = [
            AsaasApplicationHolder.config.asaas.doublePromotion.promotionalCodeGroup.name
        ]

        return promotionsToIgnore.contains(promotionalCodeGroupName)
    }
}
