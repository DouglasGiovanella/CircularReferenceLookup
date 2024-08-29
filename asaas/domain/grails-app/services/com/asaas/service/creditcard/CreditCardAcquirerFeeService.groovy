package com.asaas.service.creditcard

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardBrand
import com.asaas.creditcard.CreditCardGateway
import com.asaas.domain.asaasconfig.AsaasConfig
import com.asaas.domain.creditcard.CreditCardAcquirerFee
import com.asaas.domain.customer.Customer
import grails.transaction.Transactional

@Transactional
class CreditCardAcquirerFeeService {

    def acquiringManagerService

    public CreditCardGateway selectGateway(CreditCardBrand brand, Customer customer, Integer installmentCount) {
        CreditCardAcquirer acquirer = selectAcquirer(brand, customer, installmentCount)

        return getGatewayWithPrioritizedAcquirer(acquirer, brand)
    }

    public CreditCardAcquirer selectAcquirer(CreditCardBrand brand, Customer customer, Integer installmentCount) {
        if (!installmentCount) installmentCount = 1

        String mcc = getMcc(customer)

        Map queryParams = [:]
        queryParams.brand = brand
        queryParams.installmentCount = installmentCount
        queryParams.sortList = [[sort: "prioritize", order: "desc"], [sort: "creditCardFee", order: "asc"]]

        List<CreditCardAcquirer> disabledAcquirerForCaptureList = getDisabledAcquirerForCapture()
        if (disabledAcquirerForCaptureList) queryParams."acquirer[notIn]" = disabledAcquirerForCaptureList

        List<CreditCardAcquirerFee> creditCardAcquirerFeeList = []
        creditCardAcquirerFeeList.addAll(CreditCardAcquirerFee.query(queryParams + [mcc: mcc]).list())
        creditCardAcquirerFeeList.addAll(CreditCardAcquirerFee.query(queryParams + ["mcc[isNull]": true, notExistsSameAcquirerAndBrandWithMcc: mcc]).list())

        if (!creditCardAcquirerFeeList) return getDefaultAcquirer(brand)

        return selectPriorityAcquirer(creditCardAcquirerFeeList)
    }

    public BigDecimal findAcquirerFee(CreditCardAcquirer creditCardAcquirer, CreditCardBrand creditCardBrand, String mcc, Integer installmentCount) {
        Map queryParams = [:]
        queryParams.acquirer = creditCardAcquirer
        queryParams.brand = creditCardBrand
        queryParams.installmentCount = installmentCount
        queryParams.column = "creditCardFee"
        queryParams.disableSort = true

        BigDecimal creditCardAcquirerFeeWithMcc = CreditCardAcquirerFee.query(queryParams + [mcc: mcc]).get()

        if (creditCardAcquirerFeeWithMcc) return creditCardAcquirerFeeWithMcc

        return CreditCardAcquirerFee.query(queryParams + ["mcc[isNull]": true]).get()
    }

    public CreditCardGateway getGatewayForCielo(CreditCardBrand brand) {
        AsaasConfig asaasConfig = AsaasConfig.getInstance()

        if (asaasConfig.creditCardWithCieloEnabled && brand.isCieloApplicable()) return CreditCardGateway.CIELO
        if (asaasConfig.creditCardWithCyberSourceEnabled) return CreditCardGateway.CYBERSOURCE

        return CreditCardGateway.MUNDIPAGG
    }

    public CreditCardAcquirerFee update(CreditCardAcquirerFee creditCardAcquirerFee, BigDecimal creditCardFee, Boolean prioritize) {
        creditCardAcquirerFee.creditCardFee = creditCardFee
        creditCardAcquirerFee.prioritize = prioritize
        creditCardAcquirerFee.save(failOnError: true)

        final Boolean feeSavedOnAcquiring = acquiringManagerService.saveAcquirerFee(
            creditCardAcquirerFee.creditCardFee,
            creditCardAcquirerFee.prioritize,
            creditCardAcquirerFee.brand,
            creditCardAcquirerFee.acquirer,
            creditCardAcquirerFee.installmentCount,
            creditCardAcquirerFee.mcc
        )

        if (!feeSavedOnAcquiring) throw new RuntimeException("Não foi possível salvar a taxa no Acquiring.")

        return creditCardAcquirerFee
    }

    private CreditCardAcquirer selectPriorityAcquirer(List<CreditCardAcquirerFee> creditCardAcquirerFeeList) {
        List<CreditCardAcquirerFee> creditCardAcquirerFeeCompareList = creditCardAcquirerFeeList

        List<CreditCardAcquirerFee> creditCardAcquirerFeePrioritizedList = creditCardAcquirerFeeList.findAll( { it.prioritize } )
        if (creditCardAcquirerFeePrioritizedList) creditCardAcquirerFeeCompareList = creditCardAcquirerFeePrioritizedList

        CreditCardAcquirerFee creditCardAcquirerFee = creditCardAcquirerFeeCompareList.min({ left, right ->
            if (left.creditCardFee == right.creditCardFee) {
                int returnLeftFirst = -1
                int returnRightFirst = 0

                return left.acquirer.isRede() ? returnLeftFirst : returnRightFirst
            }

            return left.creditCardFee <=> right.creditCardFee
        })

        return creditCardAcquirerFee.acquirer
    }

    private String getMcc(Customer customer) {
        return customer.getMcc() ?: AsaasApplicationHolder.config.asaas.defaultMcc
    }

    private List<CreditCardAcquirer> getDisabledAcquirerForCapture() {
        AsaasConfig asaasConfig = AsaasConfig.getInstance()
        List<CreditCardAcquirer> creditCardGatewayList = []

        creditCardGatewayList.add(CreditCardAcquirer.CIELO)
        if (!asaasConfig.creditCardWithAdyenEnabled) creditCardGatewayList.add(CreditCardAcquirer.ADYEN)
        if (!asaasConfig.creditCardWithRedeEnabled) creditCardGatewayList.add(CreditCardAcquirer.REDE)

        return creditCardGatewayList
    }

    private CreditCardAcquirer getDefaultAcquirer(CreditCardBrand creditCardBrand) {
        AsaasConfig asaasConfig = AsaasConfig.getInstance()

        if (asaasConfig.creditCardWithAdyenEnabled) return CreditCardAcquirer.ADYEN
        if (asaasConfig.creditCardWithCieloEnabled && creditCardBrand.isCieloApplicable()) return CreditCardAcquirer.CIELO
        if (asaasConfig.creditCardWithRedeEnabled) return CreditCardAcquirer.REDE

        throw new RuntimeException("Adquirente default não habilitada.")
    }

    private CreditCardGateway getGatewayWithPrioritizedAcquirer(CreditCardAcquirer prioritizedAcquirer, CreditCardBrand brand) {
        AsaasConfig asaasConfig = AsaasConfig.getInstance()

        if (prioritizedAcquirer.isRede() && asaasConfig.creditCardWithRedeEnabled) return CreditCardGateway.REDE
        if (prioritizedAcquirer.isAdyen() && asaasConfig.creditCardWithAdyenEnabled) return CreditCardGateway.ADYEN

        return getGatewayForCielo(brand)
    }
}
