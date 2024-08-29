package com.asaas.service.notificationtemplate

import com.asaas.abtest.enums.AbTestPlatform
import com.asaas.customnotificationtemplate.CustomNotificationTemplateRepository
import com.asaas.customnotificationtemplate.TemplateGroupVO
import com.asaas.domain.customer.Customer
import com.asaas.domain.notification.CustomNotificationTemplate
import com.asaas.domain.notification.CustomNotificationTemplateAnalysis
import com.asaas.domain.notification.CustomNotificationTemplateGroup
import com.asaas.domain.notification.NotificationTemplatePropertyCondition
import com.asaas.notification.CustomNotificationTemplateAnalysisStatus
import com.asaas.notification.NotificationType
import com.asaas.notificationtemplate.CustomNotificationTemplateVO
import com.asaas.notificationtemplate.CustomNotificationVO
import com.asaas.notificationtemplate.NotificationTemplateProperty
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError

import grails.converters.JSON
import grails.transaction.Transactional

import java.util.regex.Matcher
import java.util.regex.Pattern

@Transactional
class CustomNotificationTemplateService {

    def abTestService
    def customNotificationTemplateAnalysisService
    def customNotificationTemplateCriticalActionService
    def notificationTemplatePropertyConditionService
    def customerPlanService
    def grailsApplication
    def asaasSegmentioService

    public CustomNotificationTemplate save(CustomNotificationTemplateVO customNotificationTemplateVO, Map tokenParams) {
        customNotificationTemplateCriticalActionService.authorize(customNotificationTemplateVO, tokenParams)

        CustomNotificationTemplate validatedCustomNotificationTemplate = validateSave(customNotificationTemplateVO)
        if (validatedCustomNotificationTemplate.hasErrors()) {
            return validatedCustomNotificationTemplate
        }

        CustomNotificationTemplate customTemplate = CustomNotificationTemplateRepository.customTemplate([
            customerId: customNotificationTemplateVO.customer.id,
            templateGroup: customNotificationTemplateVO.templateGroup,
            type: customNotificationTemplateVO.type
        ]).get()

        if (customTemplate) {
            customTemplate.lock()
            customNotificationTemplateAnalysisService.cancelIfNecessary(
                customTemplate,
                "Análise cancelada, uma nova análise foi criada para esta notificação."
            )
        } else {
            customTemplate = new CustomNotificationTemplate()
        }

        trackCustomNotificationTemplateEditedFields(customTemplate, customNotificationTemplateVO)

        customTemplate.customer = customNotificationTemplateVO.customer
        customTemplate.templateGroup = customNotificationTemplateVO.templateGroup
        customTemplate.type = customNotificationTemplateVO.type
        customTemplate.subject = customNotificationTemplateVO.subject
        customTemplate.body = customNotificationTemplateVO.body
        customTemplate.preHeader = customNotificationTemplateVO.preHeader
        customTemplate.headerTitle = customNotificationTemplateVO.headerTitle

        customTemplate.save(failOnError: true)

        CustomNotificationTemplateAnalysis customTemplateAnalysis = customNotificationTemplateAnalysisService.save(customTemplate)

        if (customTemplate.isAsaasModel()) {
            customNotificationTemplateAnalysisService.submit(
                customTemplateAnalysis,
                CustomNotificationTemplateAnalysisStatus.APPROVED,
                Utils.getMessageProperty("CustomNotificationTemplateAnalysis.autoApprove"),
                null
            )
        }

        return customTemplate
    }

    public List<TemplateGroupVO> buildTemplateGroupVOList(Customer customer, List<CustomNotificationTemplateGroup> templateGroupList) {
        List<TemplateGroupVO> templateGroupVOList = []

        for (CustomNotificationTemplateGroup templateGroup : templateGroupList) {
            Long emailTemplateId = CustomNotificationTemplateRepository.customTemplate([
                customerId: customer.id,
                templateGroup: templateGroup,
                type: NotificationType.EMAIL
            ]).column("id").get()

            CustomNotificationTemplateAnalysisStatus emailTemplateAnalysisStatus
            if (emailTemplateId) {
                emailTemplateAnalysisStatus = CustomNotificationTemplateAnalysis.query([
                    column          : "status",
                    customTemplateId: emailTemplateId,
                    sort            : "id",
                    order           : "desc"
                ]).get() as CustomNotificationTemplateAnalysisStatus
            }

            Long smsTemplateId = CustomNotificationTemplateRepository.customTemplate([
                customerId: customer.id,
                templateGroup: templateGroup,
                type: NotificationType.SMS
            ]).column("id").get()

            CustomNotificationTemplateAnalysisStatus smsTemplateAnalysisStatus
            if (smsTemplateId) {
                smsTemplateAnalysisStatus = CustomNotificationTemplateAnalysis.query([
                    column          : "status",
                    customTemplateId: smsTemplateId,
                    sort            : "id",
                    order           : "desc"
                ]).get() as CustomNotificationTemplateAnalysisStatus
            }

            TemplateGroupVO templateGroupVO = new TemplateGroupVO()

            templateGroupVO.emailTemplateAnalysisStatus = emailTemplateAnalysisStatus
            templateGroupVO.smsTemplateAnalysisStatus = smsTemplateAnalysisStatus
            templateGroupVO.templateGroup = templateGroup

            templateGroupVOList.add(templateGroupVO)
        }

        return templateGroupVOList
    }

    public CustomNotificationVO buildCustomNotificationVO(Customer customer, Long templateGroupId) {
        CustomNotificationTemplateGroup templateGroup = CustomNotificationTemplateGroup.read(templateGroupId)

        CustomNotificationTemplate emailTemplate = CustomNotificationTemplateRepository.customTemplate([
            customerId: customer.id,
            templateGroup: templateGroup,
            type: NotificationType.EMAIL
        ]).readOnly().get()

        if (!emailTemplate) {
            emailTemplate = CustomNotificationTemplateRepository.asaasTemplate([
                templateGroup: templateGroup,
                type         : NotificationType.EMAIL
            ]).readOnly().get()
        }

        CustomNotificationTemplate smsTemplate = CustomNotificationTemplateRepository.customTemplate([
            customerId: customer.id,
            templateGroup: templateGroup,
            type: NotificationType.SMS
        ]).readOnly().get()

        if (!smsTemplate) {
            smsTemplate = CustomNotificationTemplateRepository.asaasTemplate([
                templateGroup: templateGroup,
                type         : NotificationType.SMS
            ]).readOnly().get()
        }

        List<String> emailTemplatePropertyLabelList = NotificationTemplatePropertyCondition.query([
            column       : "property",
            templateGroup: templateGroup,
            type         : NotificationType.EMAIL,
            sort         : "priority",
            order        : "asc"
        ]).list(readOnly: true).collect { "{{" + it.getLabel() + "}}" }

        List<Map> smsTemplatePropertyList = NotificationTemplatePropertyCondition.query([
            column       : "property",
            templateGroup: templateGroup,
            type         : NotificationType.SMS,
            sort         : "priority",
            order        : "asc"
        ]).list(readOnly: true).collect { [propertyLabel: "{{" + it.getLabel() + "}}", weight: it.getSmsWeight()] }

        CustomNotificationVO customNotificationVO = new CustomNotificationVO()

        customNotificationVO.templateGroup = templateGroup
        customNotificationVO.emailTemplate = emailTemplate
        customNotificationVO.smsTemplate = smsTemplate
        customNotificationVO.emailTemplatePropertyLabelList = emailTemplatePropertyLabelList.join(",")
        customNotificationVO.smsTemplatePropertyLabelList = smsTemplatePropertyList*.propertyLabel.join(",")
        customNotificationVO.smsTemplatePropertyDataList = smsTemplatePropertyList as JSON

        return customNotificationVO
    }

    public void setTemplatePropertyConditionListInCustomNotificationVO(CustomNotificationVO customNotificationVO, Long templateGroupId, Long max) {
        List<NotificationTemplatePropertyCondition> emailTemplatePropertyConditionList = NotificationTemplatePropertyCondition.query([
            templateGroupId: templateGroupId,
            type           : NotificationType.EMAIL,
            sort           : "priority",
            order          : "asc"
        ]).list([max: max, offset: 0, readOnly: true])

        List<NotificationTemplatePropertyCondition> smsTemplatePropertyConditionList = NotificationTemplatePropertyCondition.query([
            templateGroupId: templateGroupId,
            type           : NotificationType.SMS,
            sort           : "priority",
            order          : "asc"
        ]).list([max: max, offset: 0, readOnly: true])

        customNotificationVO.emailTemplatePropertyConditionList = emailTemplatePropertyConditionList
        customNotificationVO.smsTemplatePropertyConditionList = smsTemplatePropertyConditionList
    }

    public CustomNotificationTemplate validateSave(CustomNotificationTemplateVO customNotificationTemplateVO) {
        if (!customNotificationTemplateVO.templateGroup) {
            throw new RuntimeException("O template precisa ter um templateGroup definido")
        }

        if (!customNotificationTemplateVO.type) {
            throw new RuntimeException("O template precisa ter um type definido")
        }

        NotificationType type = customNotificationTemplateVO.type

        if (type.isEmail()) {
            return validateEmailTemplate(customNotificationTemplateVO)
        }

        if (type.isSms()) {
            return validateSmsTemplate(customNotificationTemplateVO)
        }

        throw new RuntimeException("type [${ type }] inesperado para essa operação")
    }

    public Boolean canAccessCustomNotificationTemplateFeature(Customer customer) {
        if (customerPlanService.isAdvancedPlanSubscriptionEnabled(customer)) return true
        if (customerPlanService.isCustomNotificationTemplatesEnabled(customer)) return true

        return false
    }

    public Boolean canUseCustomNotificationTemplateFeature(Customer customer) {
        return customerPlanService.isCustomNotificationTemplatesEnabled(customer)
    }

    public void drawAbTestForCustomNotificationHotspot(Customer customer) {
        if (!customerPlanService.isAdvancedPlanSubscriptionEnabled(customer)) return
        if (CustomNotificationTemplateRepository.customTemplate([customerId: customer.id]).count() > 0) return

        String abTestName = grailsApplication.config.asaas.abtests.customNotificationTemplateHotspot.name
        AbTestPlatform platform = AbTestPlatform.WEB_DESKTOP

        abTestService.chooseVariant(abTestName, customer, platform)
    }

    private CustomNotificationTemplate validateEmailTemplate(CustomNotificationTemplateVO customNotificationTemplateVO) {
        String unsafeBody = CustomNotificationTemplate.removeRenderPrevention(customNotificationTemplateVO.body)
        String unsafeSubject = CustomNotificationTemplate.removeRenderPrevention(customNotificationTemplateVO.subject)
        String unsafeHeaderTitle = CustomNotificationTemplate.removeRenderPrevention(customNotificationTemplateVO.headerTitle)
        String unsafePreHeader = CustomNotificationTemplate.removeRenderPrevention(customNotificationTemplateVO.preHeader ?: "")
        Long templateGroupId = customNotificationTemplateVO.templateGroup.id

        if (!unsafeSubject) return getEmptyFieldError("Assunto")
        if (!unsafeHeaderTitle) return getEmptyFieldError("Título do cabeçalho")
        if (!unsafeBody) return getEmptyFieldError("Texto do corpo do e-mail")

        final Integer subjectMaxLength = 120
        if (unsafeSubject.length() > subjectMaxLength) {
            return getMaxLengthFieldError("Assunto", subjectMaxLength)
        }

        final Integer preHeaderMaxLength = 60
        if (unsafePreHeader.length() > preHeaderMaxLength) {
            return getMaxLengthFieldError("Texto de pré-visualização", preHeaderMaxLength)
        }

        final Integer headerTitleMaxLength = 60
        if (unsafeHeaderTitle.length() > headerTitleMaxLength) {
            return getMaxLengthFieldError("Título do cabeçalho", headerTitleMaxLength)
        }

        String body = customNotificationTemplateVO.body
        String subject = customNotificationTemplateVO.subject
        String headerTitle = customNotificationTemplateVO.headerTitle
        String preHeader = customNotificationTemplateVO.preHeader ?: ""
        AsaasError validationError

        for (String field : [body, subject, preHeader, headerTitle]) {
            validationError = validateCodeInjection(field)
            if (validationError) return getErrorFromAsaasError(validationError)
        }

        validationError = notificationTemplatePropertyConditionService.validateInvalidProperties(templateGroupId, NotificationType.EMAIL, subject)
        if (validationError) return getErrorFromAsaasError(validationError)

        validationError = notificationTemplatePropertyConditionService.validateInvalidProperties(templateGroupId, NotificationType.EMAIL, body)
        if (validationError) return getErrorFromAsaasError(validationError)

        validationError = notificationTemplatePropertyConditionService.validateTemplateBodyMandatoryProperties(templateGroupId, NotificationType.EMAIL, body)
        if (validationError) return getErrorFromAsaasError(validationError)

        List<NotificationTemplateProperty> correlatedPropertyList = [
            NotificationTemplateProperty.PAYMENT_VALUE,
            NotificationTemplateProperty.INSTALLMENT_COUNT,
            NotificationTemplateProperty.INSTALLMENT_VALUE
        ]

        validationError = notificationTemplatePropertyConditionService.validatePaymentValueCorrelatedProperties(correlatedPropertyList, body)
        if (validationError) return getErrorFromAsaasError(validationError)

        return new CustomNotificationTemplate()
    }

    private CustomNotificationTemplate validateSmsTemplate(CustomNotificationTemplateVO customNotificationTemplateVO) {
        CustomNotificationTemplateGroup templateGroup = customNotificationTemplateVO.templateGroup
        String unsafeBody = CustomNotificationTemplate.removeRenderPrevention(customNotificationTemplateVO.body)

        if (!unsafeBody) return getEmptyFieldError("Texto do corpo do SMS")

        final Integer bodyMaxLength = 130
        Integer bodyLength = notificationTemplatePropertyConditionService.getSmsBodyLength(templateGroup.id, unsafeBody)
        if (bodyLength > bodyMaxLength) {
            return getMaxLengthFieldError("Texto do corpo do SMS", bodyMaxLength)
        }

        String body = customNotificationTemplateVO.body
        AsaasError validationError

        validationError = validateGroovyCodeInjection(body)
        if (validationError) return getErrorFromAsaasError(validationError)

        validationError = notificationTemplatePropertyConditionService.validateInvalidProperties(templateGroup.id, NotificationType.SMS, body)
        if (validationError) return getErrorFromAsaasError(validationError)

        validationError = notificationTemplatePropertyConditionService.validateTemplateBodyMandatoryProperties(templateGroup.id, NotificationType.SMS, body)
        if (validationError) return getErrorFromAsaasError(validationError)

        if (templateGroup.event.isSendLinhaDigitavel()) return new CustomNotificationTemplate()

        List<NotificationTemplateProperty> correlatedPropertyList = [
            NotificationTemplateProperty.INSTALLMENT_COUNT,
            NotificationTemplateProperty.INSTALLMENT_VALUE
        ]

        validationError = notificationTemplatePropertyConditionService.validatePaymentValueCorrelatedProperties(correlatedPropertyList, body)
        if (validationError) return getErrorFromAsaasError(validationError)

        return new CustomNotificationTemplate()
    }

    private AsaasError validateCodeInjection(String field) {
        AsaasError validationError = validateGroovyCodeInjection(field)
        if (validationError) return validationError

        return validateHtmlTags(field)
    }

    private AsaasError validateGroovyCodeInjection(String field) {
        String unsafeField = CustomNotificationTemplate.removeRenderPrevention(field)

        String injectionCodeError = "customNotificationTemplate.error.codeInjection"
        List<String> injectionCodeBlocklist = [
            '.all',
            '.clear',
            '.count',
            '.delete',
            '.discard',
            '.execute',
            '.find',
            '.first',
            '.get',
            '.has',
            '.hash',
            '.id',
            '.invoke',
            '.last',
            '.list',
            '.load',
            '.lock',
            '.query',
            '.read',
            '.save',
            '.set',
            '.where',
            '.with',
            '${',
            '<%',
        ]

        unsafeField = unsafeField.toLowerCase()
        for (String injectionCode : injectionCodeBlocklist) {
            if (!unsafeField.contains(injectionCode)) continue

            return new AsaasError(injectionCodeError, [injectionCode])
        }
    }

    private AsaasError validateHtmlTags(String field) {
        String unsafeField = CustomNotificationTemplate.removeRenderPrevention(field)

        List<String> simpleHtmlTagAllowedList = [
            "<i>",
            "</i>",
            "<s>",
            "</s>",
            "<u>",
            "</u>",
            "<p>",
            "</p>",
            "<br>",
            "</span>",
            "<strong>",
            "</strong>",
        ]

        Pattern propertyTagPattern = Pattern.compile("<span class=\"mention\" data-mention=\"\\{\\{[a-zç_]+}}\">")
        Pattern alignedParagraphTagPattern = Pattern.compile("<p style=\"text-align:(center|right);\">")

        Pattern htmlTagPattern = Pattern.compile("<.*?>")
        Matcher patternMatcher = htmlTagPattern.matcher(unsafeField)

        while (patternMatcher.find()) {
            String matchedValue = patternMatcher.group()

            if (simpleHtmlTagAllowedList.contains(matchedValue)) continue

            Matcher htmlTagMatcher = propertyTagPattern.matcher(matchedValue)
            if (htmlTagMatcher.find()) continue

            htmlTagMatcher = alignedParagraphTagPattern.matcher(matchedValue)
            if (htmlTagMatcher.find()) continue

            return new AsaasError("unknow.error")
        }
    }

    private CustomNotificationTemplate getEmptyFieldError(String fieldName) {
        String emptyFieldCodeError = "customNotificationTemplate.error.fieldShouldNotBeEmpty"
        String errorMessage = Utils.getMessageProperty(emptyFieldCodeError, [fieldName])

        return DomainUtils.addError(new CustomNotificationTemplate(), errorMessage)
    }

    private CustomNotificationTemplate getMaxLengthFieldError(String fieldName, Integer maxLength) {
        String maxLengthFieldCodeError = "customNotificationTemplate.error.fieldMaxLength"
        String errorMessage = Utils.getMessageProperty(maxLengthFieldCodeError, [fieldName, maxLength])

        return DomainUtils.addError(new CustomNotificationTemplate(), errorMessage)
    }

    private CustomNotificationTemplate getErrorFromAsaasError(AsaasError validationError) {
        return DomainUtils.addError(new CustomNotificationTemplate(), validationError.getMessage())
    }

    private void trackCustomNotificationTemplateEditedFields(CustomNotificationTemplate oldCustomTemplate, CustomNotificationTemplateVO newCustomTemplate) {
        asaasSegmentioService.track(newCustomTemplate.customer.id, "custom_notification_template_edit_fields", [
            action: "template edited",
            templateGroupId: newCustomTemplate.templateGroup.id,
            templateTypeId: newCustomTemplate.type.getName(),
            editedSubject: oldCustomTemplate.subject != newCustomTemplate.subject,
            editedPreHeader: oldCustomTemplate.preHeader != newCustomTemplate.preHeader,
            editedHeaderTitle: oldCustomTemplate.headerTitle != newCustomTemplate.headerTitle,
            editedBody: oldCustomTemplate.body != newCustomTemplate.body
        ])
    }
}
