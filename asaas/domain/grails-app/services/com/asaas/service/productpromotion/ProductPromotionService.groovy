package com.asaas.service.productpromotion

import com.asaas.asaascard.AsaasCardStatus
import com.asaas.asaascard.AsaasCardType
import com.asaas.asaascard.validator.AsaasCardCustomerRequirementsValidator
import com.asaas.benefit.enums.BenefitStatus
import com.asaas.benefit.repository.BenefitItemRepository
import com.asaas.benefit.repository.BenefitRepository
import com.asaas.customer.customerbenefit.adapter.CreateCustomerBenefitAdapter
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.benefit.Benefit
import com.asaas.domain.benefit.BenefitItem
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerStatistic
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.productpromotion.ProductPromotion
import com.asaas.domain.promotionalcode.PromotionalCode
import com.asaas.domain.promotionalcode.PromotionalCodeGroup
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.user.User
import com.asaas.product.ProductName
import com.asaas.productpromotion.BankSlipAnticipationPromotionalTaxesVO
import com.asaas.promotionalcode.PromotionalCodeType
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class ProductPromotionService {

    def customerBenefitService
    def customerInteractionService
    def customerReceivableAnticipationConfigService
    def hubspotEventService
    def grailsApplication
    def modulePermissionService

    public void save(Customer customer, ProductName productName) {
        ProductPromotion productPromotion = new ProductPromotion()
        productPromotion.customer = customer
        productPromotion.productName = productName
        productPromotion.save(failOnError: true)
    }

    public Boolean isEligibleForReceivableAnticipationPromotion(User user) {
        if (!modulePermissionService.allowed(user, "financial")) return false
        if (!customerReceivableAnticipationConfigService.isEnabled(user.customer.id)) return false
        if (!ProductPromotion.query([exists: true, customer: user.customer, productName: ProductName.RECEIVABLE_ANTICIPATION]).get().asBoolean()) return false
        if (CustomerStatistic.getTotalValueAvailableForAnticipation(user.customer) <= 0) return false

        return true
    }

    public Boolean canDisplayAnticipationPromotionModal(User user) {
        if (!isEligibleForReceivableAnticipationPromotion(user)) return false

        Boolean hasAnticipationInLastMonths = ReceivableAnticipation.query([column: "id", customer: user.customer, "dateCreated[ge]": CustomDateUtils.sumDays(new Date().clearTime(), -1 * ProductPromotion.ANTICIPATION_PROMOTION_RECURRENCE_DAYS)]).get().asBoolean()
        if (hasAnticipationInLastMonths) return false

        return true
    }

    public Boolean isEligibleForAsaasCardPromotionalBox(Customer customer) {
        if (!customer.asaasCardEloEnabled()) return false
        if (!UserUtils.getCurrentUser().hasFinancialModulePermission()) return false
        if (AsaasCard.query([customerId: customer.id, "type[in]": [AsaasCardType.DEBIT, AsaasCardType.CREDIT], exists: true]).get().asBoolean()) return false
        if (!ProductPromotion.query([customer: customer, productName: ProductName.ASAAS_CARD, exists: true]).get().asBoolean()) return false
        if (!AsaasCardCustomerRequirementsValidator.validate(customer, AsaasCardType.DEBIT).isValid()) return false

        return true
    }

    public Boolean isEligibleForAsaasCardActivatePromotionalBox(Customer customer) {
        if (!ProductPromotion.query([customer: customer, productName: ProductName.ASAAS_CARD_ACTIVATE, exists: true]).get().asBoolean()) return false
        Boolean hasAsaasCardEloAwaitingActivation = AsaasCard.awaitingActivation([customerId: customer.id, "type[in]": [AsaasCardType.DEBIT, AsaasCardType.PREPAID], exists: true]).get()
        if (!hasAsaasCardEloAwaitingActivation) return false

        return true
    }

    public Boolean isEligibleForMigrateAsaasCardToCredit(Customer customer) {
        if (!ProductPromotion.query([customer: customer, productName: ProductName.MIGRATE_ASAAS_CARD_TO_CREDIT, exists: true]).get().asBoolean()) return false
        if (!AsaasCard.query([customerId: customer.id, type: AsaasCardType.DEBIT, status: AsaasCardStatus.ACTIVE, exists: true]).get().asBoolean()) return false
        if (FinancialTransaction.getCustomerBalance(customer) < 0) return false

        return true
    }

    public Boolean isEligibleForRequestAsaasCreditCard(Customer customer) {
        if (!ProductPromotion.query([customer: customer, productName: ProductName.REQUEST_ASAAS_CREDIT_CARD, exists: true]).get().asBoolean()) return false
        if (AsaasCard.query([customerId: customer.id, "type[in]": [AsaasCardType.DEBIT, AsaasCardType.CREDIT], "status[notIn]": [AsaasCardStatus.CANCELLED, AsaasCardStatus.ERROR], exists: true]).get().asBoolean()) return false
        if (FinancialTransaction.getCustomerBalance(customer) < 0) return false

        return true
    }

    public BankSlipAnticipationPromotionalTaxesVO buildBankSlipAnticipationPromotionalTaxesVO(Customer customer) {
        BigDecimal bankSlipMonthlyFee = CustomerReceivableAnticipationConfig.getBankSlipMonthlyFee(customer)
        return BankSlipAnticipationPromotionalTaxesVO.build(bankSlipMonthlyFee)
    }

    public void createDoublePromotionV2(Customer customer) {
        createPromotionIfDoesNotExist(customer, ProductName.DOUBLE_PROMOTION_V2, true)
        final String description = "Cliente ativou a promoção dobradinha V2, com duração de ${ProductPromotion.DAYS_TO_EXPIRE_DOUBLE_PROMOTION_V2_AFTER_ACTIVATION} dias a partir da data de ativação. Toda cobrança confirmada via cartão de crédito gera uma cobrança grátis de boleto ou Pix para utilização até o final da promoção."
        customerInteractionService.save(customer, description)
        hubspotEventService.trackCustomerHasActivatedDoublePromotionV2(customer)
    }

    public void deleteIfNecessary(Customer customer, ProductName productName) {
        ProductPromotion productPromotion = ProductPromotion.query([customer: customer, productName: productName]).get()
        if (!productPromotion) return

        productPromotion.deleted = true
        productPromotion.save(failOnError: true)
    }

    public Boolean hasActivePromotion(Long customerId, ProductName productName) {
        return ProductPromotion.hasActivePromotion(customerId, productName)
    }

    public Map getPromotionData(ProductName productName, Long customerId) {
        switch (productName) {
            case ProductName.ANTICIPATION_PROMOTIONAL_BANK_SLIP_TAXES:
                return buildPromotionData("Promoção - Antecipação no boleto", 30)
            case ProductName.ANTICIPATION_PROMOTIONAL_TAXES:
            case ProductName.ANTICIPATION_PROMOTIONAL_TAXES_SECOND_VERSION:
                return buildPromotionData("Promoção - Antecipação desconto nas taxas", 30)
            case ProductName.DOUBLE_PROMOTION:
            case ProductName.DOUBLE_PROMOTION_V2:
                PromotionalCodeGroup promotionalCodeGroup = PromotionalCodeGroup.findByName(grailsApplication.config.asaas.doublePromotion.promotionalCodeGroup.name)

                Map searchParams = [
                    customerId: customerId,
                    promotionalCodeGroup: promotionalCodeGroup,
                    promotionalCodeType: PromotionalCodeType.FREE_PAYMENT,
                    valid: true
                ]
                PromotionalCode promotionalCode = PromotionalCode.query(searchParams).get()

                BigDecimal freePaymentAmount = promotionalCode ? promotionalCode.freePaymentAmount?.toBigDecimal() : new BigDecimal(0)
                return buildPromotionData("Promoção - Dobradinha (cartão > boleto e Pix)", 30, freePaymentAmount)
            default:
                throw new NotImplementedException("ProductPromotionService >> ProductName inválido: [${productName}] para o customer: [${customerId}]")
        }
    }

    private void createPromotionIfDoesNotExist(Customer customer, ProductName productName, Boolean shouldSaveCustomerBenefit) {
        Boolean hasProductPromotion = ProductPromotion.query([customer: customer, productName: productName, exists: true]).get().asBoolean()
        if (hasProductPromotion) return

        ProductPromotion productPromotion = new ProductPromotion()
        productPromotion.customer = customer
        productPromotion.productName = productName
        productPromotion.save(failOnError: true)

        if (shouldSaveCustomerBenefit) createCustomerBenefit(productPromotion)
    }

    private void createCustomerBenefit(ProductPromotion promotion) {
        Map promotionData = getPromotionData(promotion.productName, promotion.customer.id)

        Date expirationDate = CustomDateUtils.sumDays(promotion.dateCreated, promotionData.daysToExpire)
        Map params = [
            customer: promotion.customer,
            benefit: promotionData.benefit,
            activationDate: promotion.dateCreated,
            expirationDate: expirationDate,
            status: BenefitStatus.ACTIVE,
            customerBenefitItemsList: promotionData.customerBenefitItemsList
        ]

        CreateCustomerBenefitAdapter adapter = CreateCustomerBenefitAdapter.build(params)
        customerBenefitService.save(adapter)
    }

    private Map buildPromotionData(String benefitName, Integer daysToExpire) {
        return buildPromotionData(benefitName, daysToExpire, null)
    }

    private Map buildPromotionData(String benefitName, Integer daysToExpire, BigDecimal value) {
        Benefit benefit = getBenefitBasedOnName(benefitName)
        List<BenefitItem> benefitItemsList = getBenefitItemsBasedOnBenefit(benefit.id)
        List<Map> customerBenefitItemsList = benefitItemsList.collect { BenefitItem benefitItem ->
            [
                benefitItem: benefitItem,
                value: value ?: benefitItem.defaultValue
            ]
        }

        return [
            benefit: benefit,
            daysToExpire: daysToExpire,
            customerBenefitItemsList: customerBenefitItemsList
        ]
    }

    private Benefit getBenefitBasedOnName(String name) {
        return BenefitRepository.query(name: name).get()
    }

    private List<BenefitItem> getBenefitItemsBasedOnBenefit(Long benefitId) {
        return BenefitItemRepository.query(benefitId: benefitId).list()
    }
}
