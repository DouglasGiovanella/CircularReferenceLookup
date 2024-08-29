package com.asaas.service.abtest

import com.asaas.abtest.cache.AbTestCache
import com.asaas.abtest.enums.AbTestPlatform
import com.asaas.abtest.enums.AbTestResponsibleSquad
import com.asaas.abtest.enums.AbTestSubject
import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.abtest.AbTest
import com.asaas.domain.abtest.AbTestVariant
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional

@Transactional
class CreateAbTestService {

    private static final Integer MAX_CHARACTER_LENGTH = 255

    public AbTest save(Map abTestInfo, List<Map> variantsInfoListMap) {
        AbTest abTest = validateSaveAbTest(abTestInfo, variantsInfoListMap)

        if (abTest.hasErrors()) return abTest

        abTest.name = abTestInfo.name
        abTest.description = abTestInfo.description
        abTest.objective = abTestInfo.objective
        abTest.responsibleSquad = abTestInfo.responsibleSquad
        abTest.subject = abTestInfo.subject
        abTest.platform = abTestInfo.platform
        abTest.target = abTestInfo.target
        abTest.drawTrigger = abTestInfo.drawTrigger
        abTest.primaryMetric = abTestInfo.primaryMetric
        abTest.primaryMetricHypothesis = abTestInfo.primaryMetricHypothesis
        abTest.secondaryMetric = abTestInfo.secondaryMetric
        abTest.secondaryMetricHypothesis = abTestInfo.secondaryMetricHypothesis

        abTest.save(failOnError: true)
        saveVariantList(abTest, variantsInfoListMap)

        return abTest
    }

    private List<AbTestVariant> saveVariantList(AbTest abTest, List<Map> variantsInfoListMap) {
        List<AbTestVariant> abTestVariantList = []

        for (Map variantInfo in variantsInfoListMap) {
            AbTestVariant abTestVariant = new AbTestVariant()
            abTestVariant.value = variantInfo.value
            abTestVariant.weight = Integer.valueOf(variantInfo.weight)
            abTestVariant.isDefault = variantInfo.isDefault
            abTestVariant.description = variantInfo.variantDescription
            abTestVariant.abTest = abTest
            abTestVariant.save(failOnError: true)
            abTestVariantList.add(abTestVariant)
        }

        return abTestVariantList
    }

    private AbTest validateSaveAbTest(Map abTestInfo, List<Map> variantsInfoListMap) {
        AbTest abTest = new AbTest()

        Boolean abTestAlreadyExists = AbTestCache.instance.findByNameAndPlatform(abTestInfo.name, AbTestPlatform.valueOf(abTestInfo.platform.toString())).asBoolean()
        if (abTestAlreadyExists) {
            DomainUtils.addError(abTest, "Teste já cadastrado.")
            return abTest
        }

        if (!abTestInfo.name) {
            DomainUtils.addError(abTest, "Preencha o nome do teste.")
        }

        if (!abTestInfo.description) {
            DomainUtils.addError(abTest, "Preencha a descrição do teste.")
        }

        if (!abTestInfo.objective) {
            DomainUtils.addError(abTest, "Preencha o objetivo do teste.")
        }

        if (!AbTestResponsibleSquad.convert(abTestInfo.responsibleSquad)?.isResponsibleSquad()) {
            DomainUtils.addError(abTest, "Selecione a squad responsável do teste.")
        }

        if (!AbTestSubject.convert(abTestInfo.subject)?.isSubject()) {
            DomainUtils.addError(abTest, "Selecione a público-alvo do teste.")
        }

        if (!AbTestPlatform.valueOf(abTestInfo.platform.toString()).hasPlataform()) {
            DomainUtils.addError(abTest, "Selecione a plataforma do teste.")
        }

        if (!abTestInfo.target) {
            DomainUtils.addError(abTest, "Selecione o alvo do teste.")
        }

        if (!abTestInfo.drawTrigger) {
            DomainUtils.addError(abTest, "Preencha o acionador do teste.")
        }

        if (!abTestInfo.primaryMetric) {
            DomainUtils.addError(abTest, "Preencha a métrica principal do teste.")
        }

        if (!abTestInfo.primaryMetricHypothesis) {
            DomainUtils.addError(abTest, "Preencha a hipótese da métrica principal do teste.")
        }

        if (!variantsInfoListMap) {
            DomainUtils.addError(abTest, "Preencha as informações de variante do teste.")
        }

        if (!abTestNameContainsSpecialCharacters(abTestInfo.name)) {
            DomainUtils.addError(abTest, "O nome do teste não deve conter caracteres especiais.")
        }

        if (isGreaterThanMaxLength(abTestInfo.name)) {
            DomainUtils.addError(abTest, "O nome do teste não deve conter mais de ${MAX_CHARACTER_LENGTH} caracteres.")
        }

        if (isGreaterThanMaxLength(abTestInfo.description)) {
            DomainUtils.addError(abTest, "A descrição do teste não deve conter mais de ${MAX_CHARACTER_LENGTH} caracteres.")
        }

        return abTest
    }

    private Boolean abTestNameContainsSpecialCharacters(String abTestName) {
        String specialCharacterValidationRegex = AsaasApplicationHolder.grailsApplication.config.asaas.abTestNameValidation.regex
        return abTestName.matches(specialCharacterValidationRegex)
    }

    private Boolean isGreaterThanMaxLength(String value) {
        return value.length() > MAX_CHARACTER_LENGTH
    }

}
