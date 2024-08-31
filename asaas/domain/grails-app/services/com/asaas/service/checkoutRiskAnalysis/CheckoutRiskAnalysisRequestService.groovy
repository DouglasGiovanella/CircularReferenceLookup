package com.asaas.service.checkoutRiskAnalysis

import com.asaas.analysisinteraction.AnalysisInteractionType
import com.asaas.analysisrequest.AnalysisRequestStatus
import com.asaas.analysisrequest.AnalysisRequestValidator
import com.asaas.checkoutRiskAnalysis.CheckoutRiskAnalysisReason
import com.asaas.checkoutRiskAnalysis.CheckoutRiskAnalysisReasonObject
import com.asaas.checkoutRiskAnalysis.CheckoutRiskAnalysisRequestDenyReason
import com.asaas.customerknownapirequestpattern.CustomerKnownApiRequestPatternVO
import com.asaas.domain.api.knownrequestpattern.CustomerKnownApiRequestPattern
import com.asaas.domain.api.knownrequestpattern.CustomerKnownApiTransferRequest
import com.asaas.domain.bill.BillOriginRequesterInfo
import com.asaas.domain.checkoutRiskAnalysis.CheckoutRiskAnalysisRequest
import com.asaas.domain.checkoutRiskAnalysis.CheckoutRiskAnalysisRequestReason
import com.asaas.domain.customer.Customer
import com.asaas.domain.transfer.TransferOriginRequester
import com.asaas.domain.user.User
import com.asaas.log.AsaasLogger
import com.asaas.originrequesterinfo.EventOriginType
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CheckoutRiskAnalysisRequestService {

    def analysisInteractionService
    def checkoutRiskAnalysisApprovalService

    public void save(Customer customer, Object objectInstance) {
        CheckoutRiskAnalysisReason checkoutRiskAnalysisReason = buildReason(objectInstance)
        CheckoutRiskAnalysisRequest checkoutRiskAnalysisRequest = CheckoutRiskAnalysisRequest.notAnalysed([customer: customer, "reason[exists]": checkoutRiskAnalysisReason]).get()

        Boolean isNewAnalysis = false
        if (!checkoutRiskAnalysisRequest) {
            checkoutRiskAnalysisRequest = new CheckoutRiskAnalysisRequest()
            checkoutRiskAnalysisRequest.status = AnalysisRequestStatus.AWAITING_AUTOMATIC_ANALYSIS
            checkoutRiskAnalysisRequest.customer = customer
            isNewAnalysis = true
        }

        checkoutRiskAnalysisRequest.score += checkoutRiskAnalysisReason.score
        checkoutRiskAnalysisRequest.save(failOnError: true)

        if (isNewAnalysis) {
            checkoutRiskAnalysisRequest.expirationDate = checkoutRiskAnalysisRequest.calculateExpirationDate()
            checkoutRiskAnalysisRequest.save(failOnError: true)
        }

        CheckoutRiskAnalysisRequestReason checkoutRiskAnalysisRequestReason = new CheckoutRiskAnalysisRequestReason()
        checkoutRiskAnalysisRequestReason.checkoutRiskAnalysisReason = checkoutRiskAnalysisReason
        checkoutRiskAnalysisRequestReason.checkoutRiskAnalysisRequest = checkoutRiskAnalysisRequest
        checkoutRiskAnalysisRequestReason.object = objectInstance.getClass().getSimpleName()
        checkoutRiskAnalysisRequestReason.objectId = objectInstance.id.toString()
        checkoutRiskAnalysisRequestReason.save(failOnError: true)
    }

    public CheckoutRiskAnalysisRequest start(User analyst, Long analysisRequestId) {
        CheckoutRiskAnalysisRequest analysisRequestPending = CheckoutRiskAnalysisRequest
            .query([id: analysisRequestId, status: AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED]).get()

        CheckoutRiskAnalysisRequest validatedAnalysisRequest = validateStart(analyst, analysisRequestPending)

        if (validatedAnalysisRequest.hasErrors()) return validatedAnalysisRequest

        analysisRequestPending.analyst = analyst
        analysisRequestPending.status = AnalysisRequestStatus.STARTED
        analysisRequestPending.save(failOnError: true)

        analysisInteractionService.createForAnalysisRequest(analyst, AnalysisInteractionType.START, analysisRequestPending)

        return analysisRequestPending
    }

    public CheckoutRiskAnalysisRequest finish(User analyst,
                                              Long analysisRequestId,
                                              AnalysisRequestStatus status,
                                              String observations) {
        CheckoutRiskAnalysisRequest analysisRequest = CheckoutRiskAnalysisRequest.get(analysisRequestId)

        CheckoutRiskAnalysisRequest validatedAnalysisRequest = validateFinish(analyst, analysisRequest, status, observations)

        if (validatedAnalysisRequest.hasErrors()) return validatedAnalysisRequest

        analysisRequest.status = status
        analysisRequest.analyst = analyst
        analysisRequest.analysisDate = new Date()
        analysisRequest.observations = observations
        analysisRequest.analyzed = true
        analysisRequest.denyReason = analysisRequest.status.isDenied() ? CheckoutRiskAnalysisRequestDenyReason.CHECKOUT_SUSPECTED_OF_FRAUD : null
        analysisRequest.save(failOnError: true)

        if (analysisRequest.status.isApproved()) {
            Boolean shouldSetDeviceAsTrustedToCheckout = true
            checkoutRiskAnalysisApprovalService.approveTransactions(analysisRequest, shouldSetDeviceAsTrustedToCheckout)
        } else {
            checkoutRiskAnalysisApprovalService.denyTransactions(analysisRequest)
        }

        analysisInteractionService.createForAnalysisRequest(analyst, AnalysisInteractionType.FINISH, analysisRequest)

        return analysisRequest
    }

    public BusinessValidation canStartAnalysis(CheckoutRiskAnalysisRequest analysisRequest, User analyst) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()
        if (!analysisRequestValidator.canStartAnalysis(analysisRequest, analyst)) {
            validatedBusiness.addErrors(analysisRequestValidator.errors)
        }

        return validatedBusiness
    }

    public void processExpired() {
        final Integer expiredAnalysisLimit = 100
        final String description = "Tempo limite de análise excedido conforme normas do Banco Central."
        List<Long> expiredCheckoutRiskAnalysisRequestIdList = CheckoutRiskAnalysisRequest.query([column: "id", status: AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED, isExpired: true, "expirationDate[isNotNull]": true]).list(max: expiredAnalysisLimit)

        if (!expiredCheckoutRiskAnalysisRequestIdList) return

        for (Long checkoutRiskAnalysisRequestId : expiredCheckoutRiskAnalysisRequestIdList) {
            Utils.withNewTransactionAndRollbackOnError ({
                CheckoutRiskAnalysisRequest checkoutRiskAnalysisRequest = CheckoutRiskAnalysisRequest.get(checkoutRiskAnalysisRequestId)
                denyAutomatically(checkoutRiskAnalysisRequest, CheckoutRiskAnalysisRequestDenyReason.CHECKOUT_RISK_ANALYSIS_REQUEST_TIME_OUT, description)
            }, [onError: { Exception exception ->
                AsaasLogger.error("CheckoutRiskAnalysisRequestService.processExpired >> Erro ao negar análise de saque por tempo limite excedido. Id: [${checkoutRiskAnalysisRequestId}].", exception)
            }])
        }
    }

    public List<CustomerKnownApiRequestPatternVO> buildCustomerKnownApiRequestPatternInAnalysisList(CheckoutRiskAnalysisRequest checkoutRiskAnalysisRequest) {
        List<Long> pixTransactionIdList = checkoutRiskAnalysisRequest.buildCheckoutRiskAnalysisRequestReasonList().findAll { it.object == "PixTransaction" }.collect { it.objectId as Long }
        if (!pixTransactionIdList) return []

        List<CustomerKnownApiRequestPattern> customerKnownApiRequestPatternList = CustomerKnownApiTransferRequest.query([column: "requestPattern",
                                                                                                                         "pixTransactionId[in]": pixTransactionIdList,
                                                                                                                         distinct: "requestPattern",
                                                                                                                         disableSort: true]).list()

        return customerKnownApiRequestPatternList.collect { new CustomerKnownApiRequestPatternVO(it) }
    }

    private CheckoutRiskAnalysisReason buildReason(Object objectInstance) {
        CheckoutRiskAnalysisReasonObject reasonObject = CheckoutRiskAnalysisReasonObject.convertFromObjectName(objectInstance.getClass().getSimpleName())

        EventOriginType eventOriginType
        switch (reasonObject) {
            case CheckoutRiskAnalysisReasonObject.PIX_TRANSACTION:
                eventOriginType = TransferOriginRequester.query([column: "eventOrigin", pixTransactionId: objectInstance.id]).get()
                break
            case CheckoutRiskAnalysisReasonObject.BILL:
                eventOriginType = BillOriginRequesterInfo.query([column: "eventOrigin", billId: objectInstance.id]).get()
                break
            default:
                throw new RuntimeException("Objeto não suportado. ${reasonObject}")
        }

        return CheckoutRiskAnalysisReason.parseFromEventOriginType(eventOriginType)
    }

    private void denyAutomatically(CheckoutRiskAnalysisRequest analysis, CheckoutRiskAnalysisRequestDenyReason reason, String observation) {
        analysis.status = AnalysisRequestStatus.DENIED
        analysis.analysisDate = new Date()
        analysis.observations = "Negada automaticamente pelo sistema."
        if (observation) {
            analysis.observations += System.lineSeparator() + "${observation}"
        }
        analysis.analyzed = true
        analysis.denyReason = reason
        analysis.save(failOnError: true)

        checkoutRiskAnalysisApprovalService.denyTransactions(analysis)
    }

    private CheckoutRiskAnalysisRequest validateStart(User analyst, CheckoutRiskAnalysisRequest analysisRequest) {
        CheckoutRiskAnalysisRequest validatedAnalysisRequest = new CheckoutRiskAnalysisRequest()

        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()
        if (!analysisRequestValidator.validateStart(analyst, analysisRequest)) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(analysisRequestValidator, validatedAnalysisRequest)
            return validatedAnalysisRequest
        }

        CheckoutRiskAnalysisRequest pendingAnalysisRequest = CheckoutRiskAnalysisRequest
            .query([analystId: analyst.id, status: AnalysisRequestStatus.STARTED]).get()

        if (pendingAnalysisRequest) {
            DomainUtils.addError(validatedAnalysisRequest, "Analista já possui uma análise iniciada, volte para a fila e finalize a sua análise em aberto antes de iniciar outra")
            return validatedAnalysisRequest
        }

        return validatedAnalysisRequest
    }

    private CheckoutRiskAnalysisRequest validateFinish(User analyst,
                                                       CheckoutRiskAnalysisRequest analysisRequest,
                                                       AnalysisRequestStatus status,
                                                       String observations) {
        CheckoutRiskAnalysisRequest validatedAnalysisRequest = new CheckoutRiskAnalysisRequest()

        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()
        if (!analysisRequestValidator.validateFinish(analyst, analysisRequest, status, observations)) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(analysisRequestValidator, validatedAnalysisRequest)
        }

        return validatedAnalysisRequest
    }
}
