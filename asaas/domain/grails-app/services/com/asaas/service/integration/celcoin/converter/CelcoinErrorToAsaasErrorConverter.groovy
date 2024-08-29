package com.asaas.service.integration.celcoin.converter

import com.asaas.log.AsaasLogger
import com.asaas.validation.AsaasError

class CelcoinErrorToAsaasErrorConverter {

    public static AsaasError convertToAsaasErrorOnAuthorize(String errorCode) {
        if (!errorCode) return new AsaasError("bill.payWithGateway.paid.error")
        switch (errorCode) {
            case "481":
            case "619":
            case "258":
            case "245":
                return new AsaasError("bill.payWithGateway.assignorNotFound")
            case "628":
            case "260":
            case "639":
            case "820":
                return new AsaasError("bill.payWithGateway.dateLimitToPayExceeded")
            case "68":
            case "183":
                return new AsaasError("bill.payWithGateway.alreadyPaid")
            case "244":
                return new AsaasError("bill.payWithGateway.cannotBePaidAfterDueDate")
            case "630":
            case "631":
            case "632":
            case "637":
            case "638":
                return new AsaasError("bill.payWithGateway.hasPendingIssuesWithAssignor")
            case "640":
            case "642":
            case "822":
                return new AsaasError("bill.payWithGateway.invalidBarCode")
            case "613":
                return new AsaasError("bill.payWithGateway.cannotChangeBillValue")
            case "629":
                return new AsaasError("bill.payWithGateway.blockedBankSlipPayment")
            case "623":
                return new AsaasError("bill.payWithGateway.overMaxValue")
            case "171":
            case "648":
            case "649":
            case "999":
            case "901":
                return new AsaasError("bill.payWithGateway.paid.error")
            default:
                AsaasLogger.error("CelcoinUtils.buildErrorOnAuthorize >>> Erro ao mapear código de erro de consulta de dados da Celcoin. [errorCode: ${errorCode}]")
                return new AsaasError("bill.payWithGateway.paid.error")
        }
    }

    public static AsaasError convertToAsaasErrorOnRegister(String errorCode) {
        if (!errorCode) return new AsaasError("bill.payWithGateway.paid.error")
        switch (errorCode) {
            case "050":
                return new AsaasError("bill.payWithGateway.limitExceededCelcoin")
            case "44":
            case "184":
                return new AsaasError("bill.payWithGateway.forbiddenValue")
            case "24":
            case "240":
            case "243":
            case "655":
            case "640":
                return new AsaasError("bill.payWithGateway.invalidBarCode")
            case "263":
            case "613":
                return new AsaasError("bill.payWithGateway.cannotChangeBillValue")
            case "628":
            case "652":
            case "621":
                return new AsaasError("bill.payWithGateway.alreadyPaid")
            case "598":
            case "620":
            case "653":
            case "658":
            case "714":
            case "000":
                return new AsaasError("bill.payWithGateway.communicationError")
            case "622":
            case "657":
                return new AsaasError("bill.payWithGateway.underMinValue")
            case "623":
            case "656":
                return new AsaasError("bill.payWithGateway.overMaxValue")
            case "999":
                return new AsaasError("bill.payWithGateway.paid.error")
            case "481":
                return new AsaasError("bill.payWithGateway.assignorNotFound")
            default:
                AsaasLogger.error("CelcoinUtils.buildAsaasErrorOnRegister >>> Erro ao mapear código de erro de consulta de dados da Celcoin. [errorCode: ${errorCode}]")
                return new AsaasError("bill.payWithGateway.paid.error")
        }
    }

    public static AsaasError convertToAsaasErrorOnConfirm(String errorCode) {
        if (!errorCode) return new AsaasError("bill.payWithGateway.paid.error")
        switch (errorCode) {
            case "24":
                return new AsaasError("bill.payWithGateway.invalidBarCode")
            case "480":
                return new AsaasError("bill.payWithGateway.assignorNotFound")
            case "613":
                return new AsaasError("bill.payWithGateway.cannotChangeBillValue")
            case "620":
            case "658":
            case "714":
                return new AsaasError("bill.payWithGateway.communicationError")
            case "819":
                return new AsaasError("bill.payWithGateway.billAlreadyProcessing")
            case "999":
                return new AsaasError("bill.payWithGateway.paid.error")
            default:
                AsaasLogger.error("CelcoinUtils.buildAsaasErrorOnConfirm >>> Erro ao mapear código de erro de consulta de dados da Celcoin. [errorCode: ${errorCode}]")
                return new AsaasError("bill.payWithGateway.paid.error")
        }
    }
}
