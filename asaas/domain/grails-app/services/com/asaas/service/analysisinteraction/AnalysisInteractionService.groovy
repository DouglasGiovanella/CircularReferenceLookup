package com.asaas.service.analysisinteraction

import com.asaas.analysisinteraction.AnalysisInteractionType
import com.asaas.analysisrequest.BaseAnalysisRequest
import com.asaas.domain.analysisinteraction.AnalysisInteraction
import com.asaas.domain.analysisinteraction.AnalysisInteractionAuthorizationDeviceUpdateRequest
import com.asaas.domain.analysisinteraction.AnalysisInteractionCashInRiskAnalysisRequest
import com.asaas.domain.analysisinteraction.AnalysisInteractionCheckoutRiskAnalysisRequest
import com.asaas.domain.analysisinteraction.AnalysisInteractionCustomerGeneralAnalysis
import com.asaas.domain.analysisinteraction.AnalysisInteractionCustomerRegisterUpdateAnalysisRequest
import com.asaas.domain.analysisinteraction.AnalysisInteractionCustomerUpdateRequest
import com.asaas.domain.analysisinteraction.AnalysisInteractionDocumentAnalysis
import com.asaas.domain.analysisinteraction.AnalysisInteractionFacematchCriticalActionAnalysisRequest
import com.asaas.domain.analysisinteraction.AnalysisInteractionIdentificationDocumentDoubleCheckAnalysis
import com.asaas.domain.analysisinteraction.AnalysisInteractionPixTransactionCheckoutLimitChangeRequest
import com.asaas.domain.analysisinteraction.AnalysisInteractionRiskAnalysisRequest
import com.asaas.domain.authorizationdevice.AuthorizationDeviceUpdateRequest
import com.asaas.domain.cashinriskanalysis.CashInRiskAnalysisRequest
import com.asaas.domain.checkoutRiskAnalysis.CheckoutRiskAnalysisRequest
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.customerdocument.IdentificationDocumentDoubleCheckAnalysis
import com.asaas.domain.customergeneralanalysis.CustomerGeneralAnalysis
import com.asaas.domain.customerregisterupdate.CustomerRegisterUpdateAnalysisRequest
import com.asaas.domain.documentanalysis.DocumentAnalysis
import com.asaas.domain.facematchcriticalaction.FacematchCriticalActionAnalysisRequest
import com.asaas.domain.pix.PixTransactionCheckoutLimitChangeRequest
import com.asaas.domain.riskAnalysis.RiskAnalysisRequest
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class AnalysisInteractionService {

    public AnalysisInteraction createForCustomerGeneralAnalysis(User analyst, AnalysisInteractionType analysisInteractionType, CustomerGeneralAnalysis customerGeneralAnalysis) {
        try {
            AnalysisInteraction analysisInteraction = create(analyst, analysisInteractionType)

            createAnalysisInteractionCustomerGeneralAnalysis(analysisInteraction, customerGeneralAnalysis)
            return analysisInteraction
        } catch (Exception exception) {
            AsaasLogger.error("AnalysisInteractionService.createForCustomerGeneralAnalysis >> erro ao salvar AnalysisInteraction para análise geral. Analyst [${analyst?.id}] CustomerGeneralAnalysis [${customerGeneralAnalysis.id}]", exception)
            return null
        }
    }

    public AnalysisInteraction createForCustomerRegisterUpdateAnalysisRequest(User analyst, AnalysisInteractionType analysisInteractionType, CustomerRegisterUpdateAnalysisRequest customerRegisterUpdateAnalysisRequest) {
        try {
            AnalysisInteraction analysisInteraction = create(analyst, analysisInteractionType)
            createAnalysisInteractionCustomerRegisterUpdateAnalysisRequest(analysisInteraction, customerRegisterUpdateAnalysisRequest)

            return analysisInteraction
        } catch (Exception exception) {
            AsaasLogger.error("AnalysisInteractionService.createForCustomerRegisterUpdateAnalysisRequest >> erro ao salvar AnalysisInteraction para atualização cadastral. Analyst [${analyst?.id}] CustomerRegisterUpdateAnalysisRequest [${customerRegisterUpdateAnalysisRequest.id}]", exception)
            return null
        }
    }

    public AnalysisInteraction createForDocumentAnalysis(User analyst, AnalysisInteractionType analysisInteractionType, DocumentAnalysis documentAnalysis) {
        try {
            AnalysisInteraction analysisInteraction = create(analyst, analysisInteractionType)

            createAnalysisInteractionDocumentAnalysis(analysisInteraction, documentAnalysis)
            return analysisInteraction
        } catch (Exception exception) {
            AsaasLogger.error("AnalysisInteractionService.createForDocumentAnalysis >> erro ao salvar AnalysisInteraction para análise de documentos. Analyst [${analyst?.id}] DocumentAnalysis [${documentAnalysis.id}]", exception)
            return null
        }
    }

    public AnalysisInteraction createForIdentificationDocumentDoubleCheckAnalysis(User analyst, AnalysisInteractionType analysisInteractionType, IdentificationDocumentDoubleCheckAnalysis identificationDocumentDoubleCheckAnalysis) {
        try {
            AnalysisInteraction analysisInteraction = create(analyst, analysisInteractionType)
            createAnalysisInteractionIdentificationDocumentDoubleCheckAnalysis(analysisInteraction, identificationDocumentDoubleCheckAnalysis)

            return analysisInteraction
        } catch (Exception exception) {
            AsaasLogger.error("AnalysisInteractionService.createForIdentificationDocumentDoubleCheckAnalysis >> erro ao salvar AnalysisInteraction para análise de double check de documentos. Analyst [${analyst?.id}] IdentificationDocumentDoubleCheckAnalysis [${identificationDocumentDoubleCheckAnalysis.id}]", exception)
            return null
        }
    }

    public AnalysisInteraction createForPixTransactionCheckoutLimitChangeRequest(User analyst, AnalysisInteractionType analysisInteractionType, PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        try {
            AnalysisInteraction analysisInteraction = create(analyst, analysisInteractionType)

            createAnalysisInteractionPixTransactionCheckoutLimitChangeRequest(analysisInteraction, pixTransactionCheckoutLimitChangeRequest)
            return analysisInteraction
        } catch (Exception exception) {
            AsaasLogger.error("AnalysisInteractionService.createForPixTransactionCheckoutLimitChangeRequest >> erro ao salvar AnalysisInteraction para análise de limite de Pix. Analyst [${analyst?.id}] PixTransactionCheckoutLimitChangeRequest [${pixTransactionCheckoutLimitChangeRequest.id}]", exception)
            return null
        }
    }

    public AnalysisInteraction createForAuthorizationDeviceUpdateRequest(User analyst, AnalysisInteractionType analysisInteractionType, AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest) {
        try {
            AnalysisInteraction analysisInteraction = create(analyst, analysisInteractionType)

            createAnalysisInteractionAuthorizationDeviceUpdateRequestRequest(analysisInteraction, authorizationDeviceUpdateRequest)
            return analysisInteraction
        } catch (Exception exception) {
            AsaasLogger.error("AnalysisInteractionService.createForAuthorizationDeviceUpdateRequest >> erro ao salvar AnalysisInteraction para análise de troca de dispositivo seguro Analyst [${analyst?.id}] AuthorizationDeviceUpdateRequest [${authorizationDeviceUpdateRequest.id}]", exception)
            return null
        }
    }

    public AnalysisInteraction createForCustomerUpdateRequest(User analyst, AnalysisInteractionType analysisInteractionType, CustomerUpdateRequest customerUpdateRequest) {
        try {
            AnalysisInteraction analysisInteraction = create(analyst, analysisInteractionType)

            createAnalysisInteractionCustomerUpdateRequestRequest(analysisInteraction, customerUpdateRequest)
            return analysisInteraction
        } catch (Exception exception) {
            AsaasLogger.error("AnalysisInteractionService.createForCustomerUpdateRequest >> erro ao salvar AnalysisInteraction para análise de dados comerciais Analyst [${analyst?.id}] AuthorizationDeviceUpdateRequest [${customerUpdateRequest.id}]", exception)
            return null
        }
    }

    public AnalysisInteraction createForAnalysisRequest(User analyst, AnalysisInteractionType analysisInteractionType, BaseAnalysisRequest analysisRequest) {
        try {
            AnalysisInteraction analysisInteraction = create(analyst, analysisInteractionType)

            switch (analysisRequest) {
                case FacematchCriticalActionAnalysisRequest:
                    createAnalysisInteractionFacematchCriticalActionAnalysis(analysisInteraction, analysisRequest)
                    break
                case RiskAnalysisRequest:
                    createAnalysisInteractionRiskAnalysisRequest(analysisInteraction, analysisRequest)
                    break
                case CheckoutRiskAnalysisRequest:
                    createAnalysisInteractionCheckoutRiskAnalysisRequest(analysisInteraction, analysisRequest)
                    break
                case CashInRiskAnalysisRequest:
                    createAnalysisInteractionCashInRiskAnalysisRequest(analysisInteraction, analysisRequest)
                    break
            }

            return analysisInteraction
        } catch (Exception exception) {
            AsaasLogger.error("AnalysisInteractionService.createForAnalysisRequest >> Erro ao salvar AnalysisInteraction para solicitação de análise. Analyst [${analyst?.id}] ${analysisRequest.getClass().getSimpleName()} [${analysisRequest.id}]", exception)
            return null
        }
    }

    public Date findLastStartDate(Object analysis) {
        Date lastStartDate
        Map searchParams = [column: "analysisInteraction.dateCreated", "analysisInteraction.analysisInteractionType": AnalysisInteractionType.START]
        switch (analysis) {
            case CashInRiskAnalysisRequest:
                lastStartDate = AnalysisInteractionCashInRiskAnalysisRequest.query(searchParams + [cashInRiskAnalysisRequest: analysis]).get()
                break
            case AuthorizationDeviceUpdateRequest:
                lastStartDate = AnalysisInteractionAuthorizationDeviceUpdateRequest.query(searchParams + [authorizationDeviceUpdateRequest: analysis]).get()
                break
            case CustomerUpdateRequest:
                lastStartDate = AnalysisInteractionCustomerUpdateRequest.query(searchParams + [customerUpdateRequest: analysis]).get()
                break
            default:
                throw new BusinessException("Análise não implementada")
        }
        return lastStartDate
    }

    private AnalysisInteraction create(User analyst, AnalysisInteractionType analysisInteractionType) {
        AnalysisInteraction analysisInteraction = new AnalysisInteraction()
        analysisInteraction.analyst = analyst
        analysisInteraction.analysisInteractionType = analysisInteractionType
        analysisInteraction.save(failOnError: true)

        return analysisInteraction
    }

    private AnalysisInteractionCustomerGeneralAnalysis createAnalysisInteractionCustomerGeneralAnalysis(AnalysisInteraction analysisInteraction, CustomerGeneralAnalysis customerGeneralAnalysis) {
        AnalysisInteractionCustomerGeneralAnalysis analysisInteractionCustomerGeneralAnalysis = new AnalysisInteractionCustomerGeneralAnalysis()
        analysisInteractionCustomerGeneralAnalysis.analysisInteraction = analysisInteraction
        analysisInteractionCustomerGeneralAnalysis.customerGeneralAnalysis = customerGeneralAnalysis
        analysisInteractionCustomerGeneralAnalysis.save(failOnError: true)

        return analysisInteractionCustomerGeneralAnalysis
    }

    private AnalysisInteractionCustomerRegisterUpdateAnalysisRequest createAnalysisInteractionCustomerRegisterUpdateAnalysisRequest(AnalysisInteraction analysisInteraction, CustomerRegisterUpdateAnalysisRequest customerRegisterUpdateAnalysisRequest) {
        AnalysisInteractionCustomerRegisterUpdateAnalysisRequest analysisInteractionCustomerRegisterUpdateAnalysisRequest = new AnalysisInteractionCustomerRegisterUpdateAnalysisRequest()
        analysisInteractionCustomerRegisterUpdateAnalysisRequest.analysisInteraction = analysisInteraction
        analysisInteractionCustomerRegisterUpdateAnalysisRequest.customerRegisterUpdateAnalysisRequest = customerRegisterUpdateAnalysisRequest
        analysisInteractionCustomerRegisterUpdateAnalysisRequest.save(failOnError: true)

        return analysisInteractionCustomerRegisterUpdateAnalysisRequest
    }

    private AnalysisInteractionDocumentAnalysis createAnalysisInteractionDocumentAnalysis(AnalysisInteraction analysisInteraction, DocumentAnalysis documentAnalysis) {
        AnalysisInteractionDocumentAnalysis analysisInteractionDocumentAnalysis = new AnalysisInteractionDocumentAnalysis()
        analysisInteractionDocumentAnalysis.analysisInteraction = analysisInteraction
        analysisInteractionDocumentAnalysis.documentAnalysis = documentAnalysis
        analysisInteractionDocumentAnalysis.save(failOnError: true)

        return analysisInteractionDocumentAnalysis
    }

    private AnalysisInteractionIdentificationDocumentDoubleCheckAnalysis createAnalysisInteractionIdentificationDocumentDoubleCheckAnalysis(AnalysisInteraction analysisInteraction, IdentificationDocumentDoubleCheckAnalysis identificationDocumentDoubleCheckAnalysis) {
        AnalysisInteractionIdentificationDocumentDoubleCheckAnalysis analysisInteractionIdentificationDocumentDoubleCheckAnalysis = new AnalysisInteractionIdentificationDocumentDoubleCheckAnalysis()
        analysisInteractionIdentificationDocumentDoubleCheckAnalysis.analysisInteraction = analysisInteraction
        analysisInteractionIdentificationDocumentDoubleCheckAnalysis.identificationDocumentDoubleCheckAnalysis = identificationDocumentDoubleCheckAnalysis
        analysisInteractionIdentificationDocumentDoubleCheckAnalysis.save(failOnError: true)

        return analysisInteractionIdentificationDocumentDoubleCheckAnalysis
    }

    private AnalysisInteractionPixTransactionCheckoutLimitChangeRequest createAnalysisInteractionPixTransactionCheckoutLimitChangeRequest(AnalysisInteraction analysisInteraction, PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        AnalysisInteractionPixTransactionCheckoutLimitChangeRequest analysisInteractionPixTransactionCheckoutLimitChangeRequest = new AnalysisInteractionPixTransactionCheckoutLimitChangeRequest()
        analysisInteractionPixTransactionCheckoutLimitChangeRequest.analysisInteraction = analysisInteraction
        analysisInteractionPixTransactionCheckoutLimitChangeRequest.pixTransactionCheckoutLimitChangeRequest = pixTransactionCheckoutLimitChangeRequest
        analysisInteractionPixTransactionCheckoutLimitChangeRequest.save(failOnError: true)

        return analysisInteractionPixTransactionCheckoutLimitChangeRequest
    }

    private AnalysisInteractionRiskAnalysisRequest createAnalysisInteractionRiskAnalysisRequest(AnalysisInteraction analysisInteraction, RiskAnalysisRequest riskAnalysisRequest) {
        AnalysisInteractionRiskAnalysisRequest analysisInteractionRiskAnalysisRequest = new AnalysisInteractionRiskAnalysisRequest()
        analysisInteractionRiskAnalysisRequest.analysisInteraction = analysisInteraction
        analysisInteractionRiskAnalysisRequest.riskAnalysisRequest = riskAnalysisRequest
        analysisInteractionRiskAnalysisRequest.save(failOnError: true)

        return analysisInteractionRiskAnalysisRequest
    }

    private AnalysisInteractionFacematchCriticalActionAnalysisRequest createAnalysisInteractionFacematchCriticalActionAnalysis(AnalysisInteraction analysisInteraction, FacematchCriticalActionAnalysisRequest facematchCriticalActionAnalysisRequest) {
        AnalysisInteractionFacematchCriticalActionAnalysisRequest analysisInteractionFacematchCriticalActionAnalysisRequest = new AnalysisInteractionFacematchCriticalActionAnalysisRequest()
        analysisInteractionFacematchCriticalActionAnalysisRequest.analysisInteraction = analysisInteraction
        analysisInteractionFacematchCriticalActionAnalysisRequest.facematchCriticalActionAnalysisRequest = facematchCriticalActionAnalysisRequest
        analysisInteractionFacematchCriticalActionAnalysisRequest.save(failOnError: true)

        return analysisInteractionFacematchCriticalActionAnalysisRequest
    }

    private AnalysisInteractionCheckoutRiskAnalysisRequest createAnalysisInteractionCheckoutRiskAnalysisRequest(AnalysisInteraction analysisInteraction, CheckoutRiskAnalysisRequest checkoutRiskAnalysisRequest) {
        AnalysisInteractionCheckoutRiskAnalysisRequest analysisInteractionCheckoutRiskAnalysisRequest = new AnalysisInteractionCheckoutRiskAnalysisRequest()
        analysisInteractionCheckoutRiskAnalysisRequest.analysisInteraction = analysisInteraction
        analysisInteractionCheckoutRiskAnalysisRequest.checkoutRiskAnalysisRequest = checkoutRiskAnalysisRequest
        analysisInteractionCheckoutRiskAnalysisRequest.save(failOnError: true)

        return analysisInteractionCheckoutRiskAnalysisRequest
    }

    private AnalysisInteractionCashInRiskAnalysisRequest createAnalysisInteractionCashInRiskAnalysisRequest(AnalysisInteraction analysisInteraction, CashInRiskAnalysisRequest cashInRiskAnalysisRequest) {
        AnalysisInteractionCashInRiskAnalysisRequest analysisInteractionCashIntRiskAnalysisRequest = new AnalysisInteractionCashInRiskAnalysisRequest()
        analysisInteractionCashIntRiskAnalysisRequest.analysisInteraction = analysisInteraction
        analysisInteractionCashIntRiskAnalysisRequest.cashInRiskAnalysisRequest = cashInRiskAnalysisRequest
        analysisInteractionCashIntRiskAnalysisRequest.save(failOnError: true)

        return analysisInteractionCashIntRiskAnalysisRequest
    }

    private AnalysisInteractionAuthorizationDeviceUpdateRequest createAnalysisInteractionAuthorizationDeviceUpdateRequestRequest(AnalysisInteraction analysisInteraction, AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest) {
        AnalysisInteractionAuthorizationDeviceUpdateRequest analysisInteractionAuthorizationDeviceUpdateRequest = new AnalysisInteractionAuthorizationDeviceUpdateRequest()
        analysisInteractionAuthorizationDeviceUpdateRequest.analysisInteraction = analysisInteraction
        analysisInteractionAuthorizationDeviceUpdateRequest.authorizationDeviceUpdateRequest = authorizationDeviceUpdateRequest
        analysisInteractionAuthorizationDeviceUpdateRequest.save(failOnError: true)

        return analysisInteractionAuthorizationDeviceUpdateRequest
    }

    private AnalysisInteractionCustomerUpdateRequest createAnalysisInteractionCustomerUpdateRequestRequest(AnalysisInteraction analysisInteraction, CustomerUpdateRequest customerUpdateRequest) {
        AnalysisInteractionCustomerUpdateRequest analysisInteractionCustomerUpdateRequest = new AnalysisInteractionCustomerUpdateRequest()
        analysisInteractionCustomerUpdateRequest.analysisInteraction = analysisInteraction
        analysisInteractionCustomerUpdateRequest.customerUpdateRequest = customerUpdateRequest
        analysisInteractionCustomerUpdateRequest.save(failOnError: true)

        return analysisInteractionCustomerUpdateRequest
    }
}
