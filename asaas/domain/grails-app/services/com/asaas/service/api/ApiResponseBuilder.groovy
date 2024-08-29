package com.asaas.service.api

import com.asaas.domain.base.BaseEntity
import com.asaas.utils.Utils

import grails.validation.ValidationErrors

import org.springframework.http.HttpStatus
import org.springframework.validation.FieldError
import org.springframework.validation.ObjectError

class ApiResponseBuilder {

    public static Map buildSuccess(Map responseData) {
        Map model = [:]
        model.response = [:]
        model.response.httpStatus = HttpStatus.OK.value()
        model.response.data = responseData

        return model
    }

    public static Map buildSuccessList(List<Map> responseData, Integer limit, Integer offset, Integer totalCount) {
        Boolean hasMore = limit + offset < totalCount

        Map model = [:]
        model.response = [:]
        model.response.httpStatus = HttpStatus.OK.value()
        model.response.data = [object: "list", hasMore: hasMore, totalCount: totalCount, limit: limit, offset: offset, data: responseData]

        return model
    }

    public static Map buildBadRequest(BaseEntity entity) {
        Map model = [:]
        model.response = [:]
        model.response.httpStatus = HttpStatus.BAD_REQUEST.value()
        model.response.errors = buildValidationErrorList(entity.errors, [:])

        return model
    }

    public static Map buildNotFound() {
        Map model = [:]
        model.httpStatus = HttpStatus.NOT_FOUND.value()

        return model
    }

    private static List<Map> buildValidationErrorList(ValidationErrors errors, Map options) {
        List<Map> errorsMap = []

        for (ObjectError error : errors.allErrors) {
            String code = buildValidationErrorCode(error, options)
            String description = buildValidationErrorDescription(error, errors.objectName)

            errorsMap << [code: code, description: description]
        }

        return errorsMap
    }

    private static String buildValidationErrorCode(ObjectError error, Map options) {
        if (options.originalErrorCode) {
            if (error instanceof FieldError) {
                return error.getCode() + "." + error.getField()
            }

            return error.getCode()
        }

        if (error.hasProperty("field")) {
            return "invalid_" + error.field
        }

        if (error.arguments) {
            return "invalid_" + error.arguments[0]
        }

        return "invalid_object"
    }

    private static String buildValidationErrorDescription(ObjectError error, String objectName) {
        if (error.defaultMessage) {
            String description = error.defaultMessage

            error.arguments?.eachWithIndex { argument, index ->
                description = description.replace("{" + index + "}", String.valueOf(argument))
            }

            return description
        }

        return Utils.getMessageProperty(error.getCode() + "." + objectName + "." + error.getField(), error.arguments as List)
    }
}
