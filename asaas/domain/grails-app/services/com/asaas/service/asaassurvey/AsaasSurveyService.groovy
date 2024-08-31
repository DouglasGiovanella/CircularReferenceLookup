package com.asaas.service.asaassurvey

import com.asaas.asaassurveyanswer.adapter.AsaasSurveyAnswerAdapter
import com.asaas.domain.asaassurveyanswer.AsaasSurveyAnswer
import com.asaas.utils.DomainUtils

import grails.validation.ValidationException
import grails.transaction.Transactional

@Transactional
class AsaasSurveyService {

    def grailsApplication

    public AsaasSurveyAnswer saveOrUpdate(AsaasSurveyAnswerAdapter asaasSurveyAnswerAdapter) {
        AsaasSurveyAnswer customerSurveyAnswer = validateSurvey(asaasSurveyAnswerAdapter)

        if (customerSurveyAnswer.hasErrors()) {
            throw new ValidationException(null, customerSurveyAnswer.errors)
        }

        customerSurveyAnswer = buildAsaasSurveyAnswer(asaasSurveyAnswerAdapter)

        return customerSurveyAnswer.save(failOnError: true)
    }

    private AsaasSurveyAnswer buildAsaasSurveyAnswer(AsaasSurveyAnswerAdapter asaasSurveyAnswerAdapter) {
        AsaasSurveyAnswer customerSurveyAnswer = AsaasSurveyAnswer.query([
            customerId: asaasSurveyAnswerAdapter.customerId,
            surveyName: asaasSurveyAnswerAdapter.surveyName
        ]).get()

        if (!customerSurveyAnswer) {
            customerSurveyAnswer = new AsaasSurveyAnswer()
            customerSurveyAnswer.customerId = asaasSurveyAnswerAdapter.customerId
        }

        customerSurveyAnswer.surveyName = asaasSurveyAnswerAdapter.surveyName
        customerSurveyAnswer.vote = asaasSurveyAnswerAdapter.vote
        customerSurveyAnswer.voteLabel = asaasSurveyAnswerAdapter.voteLabel
        customerSurveyAnswer.customerComment = asaasSurveyAnswerAdapter.customerComment
        customerSurveyAnswer.attempts = asaasSurveyAnswerAdapter.attempts
        customerSurveyAnswer.nextSurveyDate = asaasSurveyAnswerAdapter.nextSurveyDate

        return customerSurveyAnswer
    }

    private AsaasSurveyAnswer validateSurvey(AsaasSurveyAnswerAdapter asaasSurveyAnswerAdapter) {
        AsaasSurveyAnswer customerSurveyAnswer = new AsaasSurveyAnswer()

        if (!asaasSurveyAnswerAdapter.surveyName) {
            DomainUtils.addError(customerSurveyAnswer, "Não foi possível identificar a pesquisa de satisfação")
        }

        if (!asaasSurveyAnswerAdapter.customerId) {
            DomainUtils.addError(customerSurveyAnswer, "Não foi possível identificar o customer da pesquisa")
        }

        return customerSurveyAnswer
    }
}
