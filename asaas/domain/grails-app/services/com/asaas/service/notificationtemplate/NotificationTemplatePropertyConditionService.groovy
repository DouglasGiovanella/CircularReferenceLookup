package com.asaas.service.notificationtemplate

import com.asaas.domain.notification.NotificationTemplatePropertyCondition
import com.asaas.notification.NotificationType
import com.asaas.notificationtemplate.NotificationTemplateProperty
import com.asaas.utils.StringUtils
import com.asaas.validation.AsaasError

import grails.transaction.Transactional

import java.util.regex.Matcher
import java.util.regex.Pattern

@Transactional
class NotificationTemplatePropertyConditionService {

    public AsaasError validateTemplateBodyMandatoryProperties(Long templateGroupId, NotificationType type, String body) {
        List<NotificationTemplateProperty> mandatoryPropertyList = NotificationTemplatePropertyCondition.query([
            templateGroupId: templateGroupId,
            type           : type,
            isMandatory    : true,
            column         : "property",
            disableSort    : true,
        ]).list(readOnly: true) as List<NotificationTemplateProperty>

        for (NotificationTemplateProperty property : mandatoryPropertyList) {
            String propertyLabel = "{{" + property.getLabel() + "}}"
            if (body.contains(propertyLabel)) continue

            String errorMessageCode = "notificationTemplateProperty.error.isMandatory.message"
            return new AsaasError(errorMessageCode, [propertyLabel])
        }
    }

    public AsaasError validatePaymentValueCorrelatedProperties(List<NotificationTemplateProperty> correlatedPropertyList, String body) {
        List<String> propertyLabelList = correlatedPropertyList.collect {
            "{{" + it.getLabel() + "}}"
        }

        Set<Boolean> propertyUseSet = []
        Integer maxAcceptableUseStatus = 1
        for (String propertyLabel : propertyLabelList) {
            propertyUseSet.add(body.contains(propertyLabel))
            if (propertyUseSet.size() == maxAcceptableUseStatus) continue

            String errorMessageCode = "notificationTemplateProperty.error.correlatedVariables.message"
            return new AsaasError(errorMessageCode, [StringUtils.listItemsWithCommaSeparator(propertyLabelList)])
        }
    }

    public String translateEmailTemplateProperties(Long templateGroupId, String field) {
        List<NotificationTemplateProperty> emailTemplatePropertyList = NotificationTemplatePropertyCondition.query([
            column         : "property",
            templateGroupId: templateGroupId,
            type           : NotificationType.EMAIL,
            disableSort    : true
        ]).list(readOnly: true) as List<NotificationTemplateProperty>

        for (NotificationTemplateProperty property : emailTemplatePropertyList) {
            field = field.replaceAll("\\{\\{" + property.getLabel() + "}}", "\\{\\{" + property.toString() + "}}")
        }

        return field
    }

    public AsaasError validateInvalidProperties(Long templateGroupId, NotificationType type, String field) {
        final String errorMessageCode = "notificationTemplateProperty.error.invalid.message"

        List<String> propertyLabelList = NotificationTemplatePropertyCondition.query([
            templateGroupId: templateGroupId,
            type           : type,
            column         : "property",
            disableSort    : true,
        ]).list(readOnly: true).collect { "{{" + it.getLabel() + "}}" }

        Pattern reservedPattern = Pattern.compile("\\{\\{\\{.*?}}}")
        Matcher patternMatcher = reservedPattern.matcher(field)

        if (patternMatcher.find()) {
            String matchedValue = patternMatcher.group()
            return new AsaasError(errorMessageCode, [matchedValue])
        }

        Pattern validPattern = Pattern.compile("\\{\\{.*?}}")
        patternMatcher = validPattern.matcher(field)

        while(patternMatcher.find()) {
            String matchedValue = patternMatcher.group()

            if (propertyLabelList.contains(matchedValue)) continue

            return new AsaasError(errorMessageCode, [matchedValue])
        }
    }

    public Integer getSmsBodyLength(Long templateGroupId, String field) {
        List<NotificationTemplateProperty> smsTemplatePropertyList = NotificationTemplatePropertyCondition.query([
            column         : "property",
            templateGroupId: templateGroupId,
            type           : NotificationType.SMS,
            disableSort    : true
        ]).list(readOnly: true) as List<NotificationTemplateProperty>

        Integer totalPropertiesWeight = 0
        Integer totalPropertiesLabelLength = 0

        for (NotificationTemplateProperty property : smsTemplatePropertyList) {
            String propertyLabel = "{{" + property.getLabel() + "}}"
            Integer occurrencesCount = field.count(propertyLabel)

            if (occurrencesCount == 0) continue

            totalPropertiesLabelLength += propertyLabel.length() * occurrencesCount
            totalPropertiesWeight += property.getSmsWeight() * occurrencesCount
        }

        return (field.length() - totalPropertiesLabelLength + totalPropertiesWeight)
    }
}
