package com.asaas.service.notificationtemplate

import com.asaas.domain.notification.CustomNotificationTemplate
import com.asaas.domain.notification.CustomNotificationTemplateAnalysis
import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.notification.CustomNotificationTemplateAnalysisStatus
import com.asaas.user.UserUtils

import grails.transaction.Transactional

@Transactional
class CustomNotificationTemplateAnalysisService {

    def customerAlertNotificationService
    def notificationTemplateService

    public CustomNotificationTemplateAnalysis save(CustomNotificationTemplate customTemplate) {
        CustomNotificationTemplateAnalysis analysis = new CustomNotificationTemplateAnalysis()
        analysis.customTemplate = customTemplate
        analysis.customer = customTemplate.customer
        analysis.status = CustomNotificationTemplateAnalysisStatus.PENDING
        analysis.lastEditor = UserUtils.getCurrentUser()
        analysis.templateBody = customTemplate.body
        analysis.templateSubject = customTemplate.subject
        analysis.templatePreHeader = customTemplate.preHeader
        analysis.templateHeaderTitle = customTemplate.headerTitle
        analysis.save(failOnError: true)

        return analysis
    }

    public void submit(CustomNotificationTemplateAnalysis templateAnalysis, CustomNotificationTemplateAnalysisStatus status, String observations, User analyst) {
        CustomNotificationTemplate customTemplate = templateAnalysis.customTemplate

        if (AsaasEnvironment.isDevelopment()) {
            throw new BusinessException("Não é possível aprovar templates no ambiente de desenvolvimento.")
        }

        if (!templateAnalysis.status.isPending()) {
            throw new BusinessException("Essa análise não se encontra mais pendente.")
        }

        customTemplate.lock()
        templateAnalysis.lock()

        templateAnalysis.analyst = analyst
        templateAnalysis.status = status
        templateAnalysis.observations = observations

        if (status.isApproved()) {
            notificationTemplateService.saveFromCustomTemplate(customTemplate)
            customerAlertNotificationService.notifyCustomNotificationTemplateAnalysisApproved(customTemplate)
        } else {
            customerAlertNotificationService.notifyCustomNotificationTemplateAnalysisReproved(customTemplate)
        }

        templateAnalysis.save(failOnError: true)
    }

    public void cancelIfNecessary(CustomNotificationTemplate customTemplate, String observations) {
        CustomNotificationTemplateAnalysis analysis = CustomNotificationTemplateAnalysis.query([
            customTemplate: customTemplate,
            status: CustomNotificationTemplateAnalysisStatus.PENDING,
        ]).get()

        if (!analysis) return
        analysis.lock()

        analysis.status = CustomNotificationTemplateAnalysisStatus.REJECTED
        analysis.observations = observations
        analysis.deleted = true
        analysis.save(failOnError: true)
    }
}
