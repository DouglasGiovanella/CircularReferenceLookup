package com.asaas.service.api

import com.asaas.api.ApiBaseParser
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import com.asaas.exception.ResourceNotFoundException

import grails.validation.ValidationErrors
import grails.validation.ValidationException

import org.grails.datastore.mapping.validation.ValidationErrors as DataStoreValidationErrors
import org.springframework.validation.FieldError

class ApiResponseBuilderService {

	def grailsApplication

	def buildError(String error, String className, String field) {
		return buildError(error, className, field, [field])
	}

	def buildError(String error, String className, String field, ArrayList args) {
		Object[] oArgs = null
		if(args) {
			oArgs = args.toArray();
		}

        def code = error + "_" + className
        if(field) {
        	code += "_" + field
        }

        def description = Utils.getMessageProperty("com.asaas.api.error." + error + ".description", oArgs as List)

        return [errors: [[code: code, description: description]]]
	}

    public Map buildErrorList(String code, List<AsaasError> asaasErrorList) {
        return [errors: asaasErrorList.collect { [code: code, description: it.getMessage()] }]
    }

	def buildErrorList(entity) {
		return buildErrorList(entity, [:])
	}

	def buildErrorList(entity, Map options) {
        return buildValidationErrorList(entity.errors, options)
	}

	def buildErrorList(ValidationErrors errors) {
		return buildValidationErrorList(errors, [:])
	}

    def buildErrorList(DataStoreValidationErrors errors) {
        return buildValidationErrorList(errors, [:])
    }

	def buildValidationErrorList(errors, Map options) {
		List<Map> errorsMap = []

		for (error in errors.allErrors) {
			String code = ""

			if (error.hasProperty("field")) {
				code = "invalid_" + replaceCustomerAccountForCustomer(error.field)
			} else if (error.arguments) {
				code = "invalid_" + error.arguments[0]
			} else {
				code = "invalid_object"
			}

			String description = ""
			if (error.defaultMessage) {
				int position = 1

				description = error.defaultMessage

				if (error.hasProperty("field")) {
					description = description.replace("{" + 0 +"}", error.field)
				} else if (error.arguments) {
					description = description.replace("{" + 0 +"}", error.arguments[0])
				}

				while (position < error.arguments?.size()) {
					if (error.arguments[position] instanceof java.lang.String || error.arguments[position] instanceof java.lang.Integer)
						description = description.replace("{" + position +"}", String.valueOf(error.arguments[position]))

					position++
				}

			} else {
				description = Utils.getMessageProperty(error.getCode() + "." + errors.objectName + "." + error.getField(), error.arguments as List)
			}

            if (options.originalErrorCode) {
                if (error instanceof FieldError) {
                    code = error.getCode() + "." + error.getField()
                } else {
                    code = error.getCode()
                }
            }

			description = replaceCustomerAccountForCustomer(description)

			errorsMap << [code: code, description: description]
        }

        return [errors: errorsMap]
	}

    public Map buildSchemaValidationErrorList(List<Map> errors) {
        List<Map> errorsMap = []

        for (Map error in errors) {
            String field = error.path.values.last()

            String code = "${error.schema.required ? "required" : "invalid"}_${field}"

            List arguments = [field]

            if (error.message == "groovyschema.minimum.message") {
                arguments.add(error.schema.minimum.toString())
            } else if (error.message == "groovyschema.maximum.message") {
                arguments.add(error.schema.maximum.toString())
            }

            errorsMap << [code: code, description: Utils.getMessageProperty(error.message, arguments)]
        }

        return [errors: errorsMap]
    }

	def buildValidationExceptionError(ValidationException validationException) {
		return buildErrorList(validationException.errors)
	}

	def buildExceptionError(Exception e) {
		return buildExceptionError(e, true)
	}

	def buildExceptionError(Exception e, Boolean showStackTrace) {
        if (showStackTrace) AsaasLogger.error(e.message, e)

		return [exception: e]
	}

	def buildApiParseExceptionError(Exception exception) {
		return [errors: [ [code: "parse_error", description: exception.getCause()?.getMessage() ?: exception.getMessage()]] ]
	}

	def buildInvalidActionExceptionError(Exception exception) {
        String message = exception.getCause()?.getMessage() ?: exception.getMessage()
        if (!message || message == "null") {
            AsaasLogger.error("ApiResponseBuilderService.buildInvalidActionExceptionError -> Retornado mensagem de errors como null. RequestId: ${ApiBaseParser.getParams().apiRequestLogId}", exception)
        }

		return [errors: [ [code: "invalid_action", description: exception.getCause()?.getMessage() ?: exception.getMessage()]] ]
	}

    def buildActionAlreadyExecutedExceptionError(Exception exception) {
        return [
            errors: [
                [
                    code: "action_already_executed",
                    description: exception.message
                ]
            ]
        ]
    }

	def buildErrorFromCode(String code) {
		return [errors: [ [code: code, description: Utils.getMessageProperty(code)]] ]
	}

	def buildErrorFrom(String code, String description) {
		return [errors: [ [code: code, description: description]] ]
	}

	def buildSuccess(entity) {
		return entity
	}

	def buildDeleted(id) {
		return [deleted: true, id: id]
	}

    public Map buildFile(byte[] fileBytes, String fileName) {
        return [file: fileBytes, fileName: fileName]
    }

    def buildListWithThreads(List<Long> list, Closure closure, List<Map> extraData) {
        Map[] responseItems = new Map[list.size()]

        Utils.processWithThreads(list, 2, { List<Long> items ->
            Utils.withNewTransactionAndRollbackOnError({
                for (Long itemId : items) {
                    Integer itemIndex = list.indexOf(itemId)

                    responseItems[itemIndex] = closure(itemId)
                }
            }, [onError: { Exception ex -> throw ex }])
        })

        return buildList(Arrays.asList(responseItems), ApiBaseParser.getPaginationLimit(), ApiBaseParser.getPaginationOffset(), list.totalCount, extraData)
    }

	def buildList(data, limit, offset, totalCount) {
		return buildList(data, limit, offset, totalCount, null)
	}

	def buildList(data, limit, offset, totalCount, List<Map> extraData) {
		Boolean hasMore = getLimit(limit) + getOffset(offset) < totalCount
		Map responseData =  [object: "list", hasMore: hasMore, totalCount: totalCount, limit: getLimit(limit), offset: getOffset(offset), data: data]

		if (extraData) responseData.extraData = extraData

		return responseData
	}

    def buildNotFoundExceptionError(ResourceNotFoundException exception) {
        return buildNotFoundItem()
	}

	def buildNotFoundItem() {
		return [httpStatus: 404]
	}

	def buildUnauthorized() {
		return [httpStatus: 401]
	}

	def buildForbidden(String reason) {
		return buildErrorFrom("forbidden", reason ?: "Você não possui permissão para utilizar este recurso. Entre em contato com seu gerente de contas.") << [httpStatusWithResponse: 403]
	}

    def buildExceptionErrorWithResponse(String errorCode) {
        return buildErrorFromCode(errorCode) << [httpStatusWithResponse: 500]
    }

    public Map buildServiceUnavailable(String message) {
        return buildErrorFrom("service_unavailable", message) << [httpStatusWithResponse: 503]
    }

	private replaceCustomerAccountForCustomer(String field) {
		if (!field) return field

		field = field.replaceAll("customerAccount", "customer")
		field = field.replaceAll("customeraccount", "customer")

		return field
	}

	Integer getOffset(offset) {
		try {
			return Integer.valueOf(offset)
		} catch (Exception e) {
			return 0
		}
	}

	Integer getLimit(limit) {
		try {
			return Integer.valueOf(limit)
		} catch (Exception e) {
			return grailsApplication.config.asaas.api.pagination.limit.default
		}
	}
}
