package com.asaas.service.abtest

import com.asaas.abtest.cache.AbTestCache
import com.asaas.abtest.cache.AbTestCacheVO
import com.asaas.abtest.enums.AbTestPlatform
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerSignUpOriginChannel
import com.asaas.domain.abtest.AbTest
import com.asaas.domain.abtest.AbTestVariant
import com.asaas.domain.abtest.AbTestVariantChoice
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerSignUpOrigin
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.TransactionUtils

import grails.transaction.Transactional

import org.hibernate.criterion.CriteriaSpecification

@Transactional
class AbTestService {

    def abTestHistoryService
    def abTestVariantChoiceCacheService
    def abTestVariantHistoryService
    def grailsApplication

    public String chooseVariant(String abTestName, String distinctId, AbTestPlatform platform) {
        return chooseVariant(abTestName, null, distinctId, platform)
    }

    public String chooseVariant(String abTestName, Customer customer, AbTestPlatform platform) {
        return chooseVariant(abTestName, customer, null, platform)
    }

    public String chooseVariant(String abTestName, String distinctId, AbTestPlatform platform, Boolean shouldSaveVariantChoice) {
        return chooseVariant(abTestName, null, distinctId, platform, shouldSaveVariantChoice)
    }

    public String chooseVariant(String abTestName, domainInstance, String distinctId, AbTestPlatform platform) {
        return chooseVariant(abTestName, domainInstance, distinctId, platform, true)
    }

    public String chooseVariant(String abTestName, domainInstance, String distinctId, AbTestPlatform platform, Boolean shouldSaveVariantChoice) {
        AbTestCacheVO abTestVO = AbTestCache.instance.findByNameAndPlatform(abTestName, platform)
        if (!abTestVO) return grailsApplication.config.asaas.abtests.defaultVariantValue
        if (abTestVO.finishDate) return abTestVO.getWinnerVariantIfExists().value

        String chosenValue
        if (distinctId) {
            chosenValue = abTestVariantChoiceCacheService.getValueByNameAndDistinctIdAndPlatform(abTestVO.name, distinctId, platform)
        } else {
            chosenValue = abTestVariantChoiceCacheService.getValueByNameAndDomainInstanceAndPlatform(abTestVO.name, domainInstance, platform)
        }

        if (chosenValue) return chosenValue

        String variantValue = abTestVO.getNextVariantValue()

        if (!shouldSaveVariantChoice) return variantValue

        AbTestVariantChoice abTestVariantChoice
        if (domainInstance) {
            abTestVariantChoice = saveVariantChoice(domainInstance, abTestName, variantValue, platform)
        } else {
            abTestVariantChoice = saveAnonymousUserVariant(distinctId, abTestName, variantValue, platform)
        }

        TransactionUtils.afterRollback {
            abTestVariantChoiceCacheService.evict(abTestVariantChoice)
        }

        abTestVariantChoiceCacheService.put(abTestVariantChoice)

        return variantValue
    }

    public Boolean canDrawAbTestFollowingGrowthRules(Customer customer) {
        if (customer.accountOwner) return false

        if (CustomerPartnerApplication.hasBradesco(customer.id)) return false

        CustomerSignUpOriginChannel customerSignUpOriginChannel = CustomerSignUpOrigin.query([column: 'originChannel', customer: customer]).get()
        if (customerSignUpOriginChannel?.isApi()) return false

        Boolean isWhiteLabel = CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)
        if (isWhiteLabel) return false

        return true
    }

    public AbTestVariantChoice saveVariantChoice(domainInstance, String abTestName, String variantValue, AbTestPlatform platform) {
        AbTestVariant abTestVariant = AbTestCache.instance.findVariantByValue(abTestName, variantValue, platform).asDomain()

        AbTestVariantChoice abTestVariantChoice = new AbTestVariantChoice(abTestVariant: abTestVariant, object: domainInstance.class.simpleName, objectId: domainInstance.id)
        abTestVariantChoice.save(flush: true, failOnError: true)

        return abTestVariantChoice
    }

    public AbTestVariantChoice saveAnonymousUserVariant(String distinctId, String abTestName, String variantValue, AbTestPlatform platform) {
        if (!distinctId || !variantValue) {
            AsaasLogger.warn("AbTestService.saveAnonymousUserVariant -> Parametros nulos - Variant value: ${variantValue} | Distinct id: ${distinctId} | Test name: ${abTestName} | Platform: ${platform}")
            return
        }

        AbTestCacheVO abTestCacheVO = AbTestCache.instance.findByNameAndPlatform(abTestName, platform)
        if (!abTestCacheVO) return null

        AbTestVariantChoice existingAbTestVariantChoice = AbTestVariantChoice.query([abTestVariantAbTestId: abTestCacheVO.abTestId, distinctId: distinctId]).get()
        if (existingAbTestVariantChoice) return existingAbTestVariantChoice

        AbTestVariant abTestVariant = abTestCacheVO.getVariantByValue(variantValue).asDomain()

        AbTestVariantChoice abTestVariantChoice = new AbTestVariantChoice(abTestVariant: abTestVariant, distinctId: distinctId)
        abTestVariantChoice.save(flush: false, failOnError: true)

        return abTestVariantChoice
    }

    public AbTestVariantChoice updateOrSaveVariantChoice(domainInstance, Long abTestId, String variantValue) {
        AbTest abTest = AbTest.read(abTestId)
        if (!abTest) throw new BusinessException("Test A/B não encontrado [${abTestId}]")

        AbTestVariant abTestVariant = AbTestVariant.query([abTestId: abTestId, value: variantValue]).get()
        if (!abTestVariant) throw new BusinessException("Variante não encontrada para o Test A/B [${abTestId}]")

        AbTestVariantChoice abTestVariantChoice = AbTestVariantChoice.query([domainInstance: domainInstance, abTestVariantAbTestId: abTestId]).get()

        if (abTestVariantChoice?.abTestVariant?.value == variantValue) throw new BusinessException("Variante informada [${variantValue}] já definida para o cliente [${domainInstance.id}]")

        if (!abTestVariantChoice) {
            abTestVariantChoice = new AbTestVariantChoice(object: domainInstance.class.simpleName, objectId: domainInstance.id)
        }

        abTestVariantChoice.abTestVariant = abTestVariant
        abTestVariantChoice.save(flush: false, failOnError: true)

        abTestVariantChoiceCacheService.evict(abTestVariantChoice)

        return abTestVariantChoice
    }

    public void aliasCustomerIfNecessary(Customer customer, String distinctId) {
        if (!distinctId) return

        List<AbTestVariantChoice> abTestVariantChoiceList = AbTestVariantChoice.query([distinctId: distinctId, "objectId[isNull]": true]).list()

        if (!abTestVariantChoiceList) return

        for (AbTestVariantChoice abTestVariantChoice : abTestVariantChoiceList) {
            aliasABTestToCustomer(abTestVariantChoice, customer)
        }
    }

    public AbTest finishAbTest(Long id, String winnerVariantValue, String winningExplanation) {
        AbTest abTest = AbTest.get(id)

        AbTestVariant abTestVariant = AbTestCache.instance.findVariantByValue(abTest.name, winnerVariantValue, abTest.platform).asDomain()

        if (!abTestVariant) {
            DomainUtils.addError(abTest, "Selecione a variante vencedora para finalizar o Teste A/B.")
            return abTest
        }

        abTestHistoryService.create(abTest)
        abTestVariantHistoryService.create(abTestVariant)

        abTest.winningExplanation = winningExplanation
        abTest.finishDate = new Date()
        abTest.save(failOnError: true)

        abTestVariant.isWinner = true
        abTestVariant.save(failOnError: true)

        return abTest
    }

    public AbTest update(Long id, List<Map> variantList, String secondaryMetric, String secondaryMetricHypothesis) {
        AbTest abTest = AbTest.get(id)

        if (!validateWeightList(variantList, abTest)) {
            DomainUtils.addError(abTest, "O campo peso é obrigatório.")
            return abTest
        }

        if (abTest.secondaryMetric != secondaryMetric || abTest.secondaryMetricHypothesis != secondaryMetricHypothesis) {
            abTestHistoryService.create(abTest)
            abTest.secondaryMetric = secondaryMetric
            abTest.secondaryMetricHypothesis = secondaryMetricHypothesis
        }

        abTest.save(failOnError: true)

        for (Map variantInfo : variantList) {
            AbTestVariant abTestVariant = AbTestVariant.query([abTestId: id, value: variantInfo.value]).get()

            if (abTestVariant.weight != variantInfo.weight) abTestVariantHistoryService.create(abTestVariant)

            abTestVariant.weight = Integer.valueOf(variantInfo.weight)

            abTestVariant.save(failOnError: true)
        }

        return abTest
    }

    public List<Map> buildAbTestAndVariantsList(List<Map> abTestList) {
        if (!abTestList) return
        List<Map> abTestAndVariantsList = []
        List<Map> abTestVariantList = getAbTestVariantList(abTestList.id)

        for (Map abTest : abTestList) {
            Map abTestItem = [:]
            abTestItem.id = abTest.id
            abTestItem.name = abTest.name
            abTestItem.platform = abTest.platform
            abTestItem.description = abTest.description
            abTestItem.dateCreated = abTest.dateCreated
            abTestItem.finishDate = abTest.finishDate
            abTestItem.winnerValue = abTestVariantList.find{ it.id == abTest.id }.winnerValue

            abTestAndVariantsList.add(abTestItem)
        }

        return abTestAndVariantsList
    }

    private void aliasABTestToCustomer(AbTestVariantChoice abTestVariantChoice, Customer customer) {
        AbTest abTest = abTestVariantChoice.abTestVariant.abTest
        if (abTest.finishDate) return

        abTestVariantChoice.object = customer.class.simpleName
        abTestVariantChoice.objectId = customer.id
        abTestVariantChoice.save(failOnError: true)
    }

    private List<Map> getAbTestVariantList(List abTestListId) {
        if (!abTestListId) return
        List<Map> abTestVariantList = AbTestVariant.createCriteria().list() {
            resultTransformer(CriteriaSpecification.ALIAS_TO_ENTITY_MAP)

            projections {
                property("abTest.id", "id")
                sqlProjection("GROUP_CONCAT(CASE WHEN is_winner=true THEN value else '' END) AS winnerValue", ["winnerValue"], [STRING])
                groupProperty("abTest.id")
            }

            "in" ("abTest.id", abTestListId)
        }

        return abTestVariantList
    }

    private Boolean validateWeightList(List<Map> variantList, AbTest abTest) {
        if (variantList.any{ !it.weight }) return false
        if (!abTest) return false
        return true
    }
}
