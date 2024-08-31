package com.asaas.service.customerbenefit

import com.asaas.benefit.enums.BenefitStatus
import com.asaas.benefit.repository.BenefitItemRepository
import com.asaas.benefit.repository.BenefitRepository
import com.asaas.customer.customerbenefit.adapter.CreateCustomerBenefitAdapter
import com.asaas.domain.benefit.Benefit
import com.asaas.domain.benefit.BenefitItem
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.customerbenefit.CustomerBenefitPromotionalCodeImport
import com.asaas.domain.promotionalcode.PromotionalCode
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.hibernate.criterion.CriteriaSpecification

@Transactional
class CustomerBenefitPromotionalCodeImportService {

    private static final Long MAX_ITEMS_TO_ANALYSE_TO_MIGRATE = 1000

    def customerBenefitService
    def grailsApplication

    public Integer createCustomerBenefitFromPromotionalCode() {
        Long firstIdToAnalyze = getLastPromotionalCodeAnalyzedId()
        if (!firstIdToAnalyze) firstIdToAnalyze = 0

        Long lastIdToAnalyze = firstIdToAnalyze + MAX_ITEMS_TO_ANALYSE_TO_MIGRATE
        Integer rowsAffected = migratePromotionalCode(firstIdToAnalyze, lastIdToAnalyze)

        save(firstIdToAnalyze, lastIdToAnalyze, rowsAffected)

        return rowsAffected
    }

    private Integer migratePromotionalCode(Long firstIdToAnalyze, Long lastIdToAnalyze) {
        final List<String> promotionalGroupNamesList = [
            grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedFirstNaturalPerson.name,
            grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedSecondNaturalPerson.name,
            grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedOverTwoNaturalPerson.name,
            grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedFirstLegalPerson.name,
            grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedSecondLegalPerson.name,
            grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedOverTwoLegalPerson.name,
            grailsApplication.config.asaas.referral.promotionalCodeGroup.name,
            grailsApplication.config.asaas.referral.campaigns.goldTicket,
            grailsApplication.config.asaas.assistanceRS.promotionalCodeGroup.name,
            grailsApplication.config.asaas.comeBackToAsaas.promotionalCodeGroup.name,
            grailsApplication.config.asaas.entryPromotion.promotionalCodeGroup.name
        ]

        Date migrationLimitDate = Date.parse("yyyy-MM-dd", "2024-08-23")

        List<Map> promotionalCodeDataList = PromotionalCode.createCriteria().list(max: MAX_ITEMS_TO_ANALYSE_TO_MIGRATE) {
            createAlias("promotionalCodeGroup", "promotionalCodeGroup")
            createAlias("customer", "customer")

            'in'('promotionalCodeGroup.name', promotionalGroupNamesList)
            between('id', firstIdToAnalyze, lastIdToAnalyze)
            isNotNull('customer.id')
            lt('dateCreated', migrationLimitDate)

            projections {
                property ("id", "id")
                property ("customer.id", "customerId")
            }

            resultTransformer(CriteriaSpecification.ALIAS_TO_ENTITY_MAP)
        }

        Integer rowsAffected = 0
        final Integer flushEvery = 50
        Utils.forEachWithFlushSession(promotionalCodeDataList, flushEvery, { Map promotionalCodeData ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(promotionalCodeData.customerId)

                PromotionalCode promotionalCode = PromotionalCode.read(promotionalCodeData.id)
                createCustomerBenefit(customer, promotionalCode)

                rowsAffected++
            }, [
                onError: { Exception exception ->
                    AsaasLogger.error("CustomerBenefitPromotionalCodeImportService.migratePromotionalCode >> Falha ao migrar CustomerBenefit para o PromotionalCode: ${promotionalCodeData.id}", exception)
                }
            ])
        })

        return rowsAffected
    }

    private void createCustomerBenefit(Customer customer, PromotionalCode promotionalCode) {
        String benefitName = Benefit.getBenefitNameBasedOnPromotionalCodeGroupName(promotionalCode.promotionalCodeGroup.name)
        Benefit benefit = BenefitRepository.query(name: benefitName).get()

        Boolean isFreePaymentAmountUsed = benefit.type.isFreePayment() && promotionalCode.freePaymentAmount <= promotionalCode.freePaymentsUsed
        Boolean isDiscountValueUsed = benefit.type.isPromotionalCredit() && promotionalCode.discountValue <= promotionalCode.discountValueUsed
        Boolean isEndDateExpired = promotionalCode.endDate && promotionalCode.endDate < new Date()
        Boolean isBenefitExpired = isEndDateExpired || isFreePaymentAmountUsed || isDiscountValueUsed

        BigDecimal benefitValue = benefit.type.isPromotionalCredit() ? promotionalCode.discountValue : promotionalCode.freePaymentAmount

        Map params = [
            customer: customer,
            benefit: benefit,
            activationDate: promotionalCode.dateCreated,
            expirationDate: promotionalCode.endDate ?: null,
            status: isBenefitExpired ? BenefitStatus.EXPIRED : BenefitStatus.ACTIVE,
            customerBenefitItemsList: buildCustomerBenefitItemsList(benefit, benefitValue)
        ]

        CreateCustomerBenefitAdapter adapter = CreateCustomerBenefitAdapter.build(params)
        customerBenefitService.save(adapter)
    }

    private List<Map> buildCustomerBenefitItemsList(Benefit benefit, BigDecimal value) {
        BenefitItem benefitItem = BenefitItemRepository.query(benefitId: benefit.id).get()
        List<Map> customerBenefitItemsList = [
            [
                benefitItem: benefitItem,
                value: value ?: benefitItem.defaultValue
            ]
        ]

        return customerBenefitItemsList
    }

    private void save(Long firstAnalyzedId, Long lastAnalyzedId, Integer rowsAffected) {
        CustomerBenefitPromotionalCodeImport productPromotionImport = new CustomerBenefitPromotionalCodeImport()
        productPromotionImport.firstAnalyzedId = firstAnalyzedId
        productPromotionImport.lastAnalyzedId = lastAnalyzedId
        productPromotionImport.rowsAffected = rowsAffected
        productPromotionImport.save(failOnError: true)
    }

    private Long getLastPromotionalCodeAnalyzedId() {
        Long lastAnalyzedIdList = CustomerBenefitPromotionalCodeImport.createCriteria().get() {
            projections {
                max("lastAnalyzedId")
            }
        }

        return lastAnalyzedIdList
    }
}
