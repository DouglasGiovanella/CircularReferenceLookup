package com.asaas.service.customerbenefit

import com.asaas.benefit.enums.BenefitStatus
import com.asaas.customer.customerbenefit.adapter.CreateCustomerBenefitAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.customerbenefit.CustomerBenefitProductPromotionImport
import com.asaas.domain.productpromotion.ProductPromotion
import com.asaas.log.AsaasLogger
import com.asaas.product.ProductName
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.hibernate.criterion.CriteriaSpecification

@Transactional
class CustomerBenefitProductPromotionImportService {

    private static final Long MAX_ITEMS_TO_ANALYSE_TO_MIGRATE = 1000

    def customerBenefitService
    def productPromotionService

    public Integer createCustomerBenefitFromProductPromotion() {
        Long firstIdToAnalyze = getLastProductPromotionAnalyzedId()
        if (!firstIdToAnalyze) firstIdToAnalyze = 0

        Long lastIdToAnalyze = firstIdToAnalyze + MAX_ITEMS_TO_ANALYSE_TO_MIGRATE
        Integer rowsAffected = migrateProductPromotion(firstIdToAnalyze, lastIdToAnalyze)

        save(firstIdToAnalyze, lastIdToAnalyze, rowsAffected)

        return rowsAffected
    }

    public Integer migrateProductPromotion(Long firstIdToAnalyze, Long lastIdToAnalyze) {
        final List<ProductName> productNames = [
            ProductName.ANTICIPATION_PROMOTIONAL_BANK_SLIP_TAXES,
            ProductName.ANTICIPATION_PROMOTIONAL_TAXES,
            ProductName.ANTICIPATION_PROMOTIONAL_TAXES_SECOND_VERSION,
            ProductName.DOUBLE_PROMOTION,
            ProductName.DOUBLE_PROMOTION_V2
        ]

        List<Map> productPromotionDataList = ProductPromotion.createCriteria().list(max: MAX_ITEMS_TO_ANALYSE_TO_MIGRATE) {
            'in'('productName', productNames)
            between('id', firstIdToAnalyze, lastIdToAnalyze)

            projections {
                property ("id", "id")
                property ("customer.id", "customerId")
                property ("productName", "productName")
                property ("dateCreated", "dateCreated")
            }

            resultTransformer(CriteriaSpecification.ALIAS_TO_ENTITY_MAP)
        }

        Integer rowsAffected = 0
        Utils.forEachWithFlushSession(productPromotionDataList, 50, { Map promotionInfo ->
            Utils.withNewTransactionAndRollbackOnError({
                Map promotionData = productPromotionService.getPromotionData(promotionInfo.productName, promotionInfo.customerId)

                Customer customer = Customer.read(promotionInfo.customerId)
                Date expirationDate = CustomDateUtils.sumDays(promotionInfo.dateCreated, promotionData.daysToExpire)
                Map params = [
                    customer: customer,
                    benefit: promotionData.benefit,
                    activationDate: promotionInfo.dateCreated,
                    expirationDate: expirationDate,
                    status: expirationDate > new Date() ? BenefitStatus.ACTIVE : BenefitStatus.EXPIRED,
                    customerBenefitItemsList: promotionData.customerBenefitItemsList
                ]

                CreateCustomerBenefitAdapter adapter = CreateCustomerBenefitAdapter.build(params)
                customerBenefitService.save(adapter)

                rowsAffected++
            }, [
                onError: { Exception exception ->
                    AsaasLogger.error("CustomerBenefitImportService.migrateProductPromotion >> Falha ao migrar promoção de produto para o ProductPromotion: ${promotionInfo.id}", exception)
                }
            ])
        })

        return rowsAffected
    }

    private void save(Long firstAnalyzedId, Long lastAnalyzedId, Integer rowsAffected) {
        CustomerBenefitProductPromotionImport productPromotionImport = new CustomerBenefitProductPromotionImport()
        productPromotionImport.firstAnalyzedId = firstAnalyzedId
        productPromotionImport.lastAnalyzedId = lastAnalyzedId
        productPromotionImport.rowsAffected = rowsAffected
        productPromotionImport.save(failOnError: true)
    }

    private Long getLastProductPromotionAnalyzedId() {
        Long lastAnalyzedIdList = CustomerBenefitProductPromotionImport.createCriteria().get() {
            projections {
                max("lastAnalyzedId")
            }
        }

        return lastAnalyzedIdList
    }
}
