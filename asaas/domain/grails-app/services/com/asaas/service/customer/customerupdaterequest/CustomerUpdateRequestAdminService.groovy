package com.asaas.service.customer.customerupdaterequest

import com.asaas.analysisinteraction.AnalysisInteractionType
import com.asaas.customerupdaterequest.CustomerUpdateRequestDenialReasonType
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.user.User
import com.asaas.log.AsaasLogger
import com.asaas.status.Status
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CustomerUpdateRequestAdminService {

    def analysisInteractionService
    def bankAccountInfoService
    def customerAlertNotificationService
    def customerCheckoutLimitService
    def customerCommercialInfoExpirationService
    def customerInteractionService
    def customerMessageService
    def customerRegisterStatusService
    def customerUpdateRequestService
    def mobilePushNotificationService
    def paymentService
    def springSecurityService
    def treasureDataService

    public CustomerUpdateRequest startAnalysis(Long customerUpdateRequestId, User analyst) {
        CustomerUpdateRequest analysisRequestPending = CustomerUpdateRequest.get(customerUpdateRequestId)

        CustomerUpdateRequest validatedAnalysisRequest = validateStartAnalysis(analysisRequestPending, analyst)

        if (validatedAnalysisRequest.hasErrors()) return validatedAnalysisRequest

        analysisRequestPending.user = analyst
        analysisRequestPending.status = Status.MANUAL_ANALYSIS_STARTED
        analysisRequestPending.save(failOnError: true)

        analysisInteractionService.createForCustomerUpdateRequest(analyst, AnalysisInteractionType.START, analysisRequestPending)

        return analysisRequestPending
    }

    public BusinessValidation canStartAnalysis(CustomerUpdateRequest customerUpdateRequest, User analyst) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!analyst) {
            validatedBusiness.addError("customerUpdateRequestAdmin.canStartAnalysis.error.analystNotIdentified")
            return validatedBusiness
        }

        if (!customerUpdateRequest) {
            validatedBusiness.addError("customerUpdateRequestAdmin.canStartAnalysis.error.analysisNotFound")
            return validatedBusiness
        }

        if (customerUpdateRequest.status.isApproved() || customerUpdateRequest.status.isRejected()) {
            validatedBusiness.addError("customerUpdateRequestAdmin.canStartAnalysis.error.alreadyFinished")
            return validatedBusiness
        }

        if (customerUpdateRequest.status.isManualAnalysisStarted()) {
            validatedBusiness.addError("customerUpdateRequestAdmin.canStartAnalysis.error.alreadyStarted")
            return validatedBusiness
        }

        if (customerUpdateRequest.user && (customerUpdateRequest.user.id != analyst.id)) {
            validatedBusiness.addError("customerUpdateRequestAdmin.canStartAnalysis.error.analysisStartedByAnotherAnalyst")
            return validatedBusiness
        }

        return validatedBusiness
    }

    public void finishAnalysis(Long customerUpdateRequestId, User analyst, Map params) {
        CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.get(customerUpdateRequestId)
        Status status = Status.valueOf(params.status)
        CustomerUpdateRequestDenialReasonType denialReasonType = CustomerUpdateRequestDenialReasonType.convert(params.denialReasonType)
        CustomerUpdateRequest validatedCustomerUpdateRequest = validateFinishAnalysis(customerUpdateRequest, analyst, status, denialReasonType)
        if (validatedCustomerUpdateRequest.hasErrors()) {
            throw new ValidationException(null, validatedCustomerUpdateRequest.errors)
        }

        if (status.isApproved()) {
            approve(customerUpdateRequest, params)
            return
        }

        deny(customerUpdateRequest, denialReasonType, params)

        analysisInteractionService.createForCustomerUpdateRequest(analyst, AnalysisInteractionType.FINISH, customerUpdateRequest)
    }

    public void processIdleAnalysis() {
        final Integer maxItemsPerCycle = 50

        Map searchParams = [column: "id", order: "asc", status: Status.MANUAL_ANALYSIS_STARTED]
        List<Long> analysisIdList = CustomerUpdateRequest.query(searchParams).list(max: maxItemsPerCycle)
        if (!analysisIdList) return

        for (Long analysisId : analysisIdList) {
            Utils.withNewTransactionAndRollbackOnError( {
                CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.get(analysisId)
                updateStatusToPendingIfNecessary(customerUpdateRequest)
            }, [onError: { Exception exception ->
                AsaasLogger.error("CustomerUpdateRequestAdminService.processIdleAnalysis >> Falha ao expirar análise ID: [${analysisId}]", exception)
            }])
        }
    }

    private CustomerUpdateRequest validateStartAnalysis(CustomerUpdateRequest customerUpdateRequest, User analyst) {
        CustomerUpdateRequest validatedCustomerUpdateRequest = new CustomerUpdateRequest()

        if (!analyst) {
            DomainUtils.addError(validatedCustomerUpdateRequest, "Não é possível iniciar uma análise sem identificar o analista")
            return validatedCustomerUpdateRequest
        }

        if (!customerUpdateRequest) {
            DomainUtils.addError(validatedCustomerUpdateRequest, "Não foi possível encontrar uma análise para iniciar")
            return validatedCustomerUpdateRequest
        }

        if (!customerUpdateRequest.status.isPending()) {
            DomainUtils.addError(validatedCustomerUpdateRequest, "Não é possível iniciar uma análise com situação diferente de pendente")
            return validatedCustomerUpdateRequest
        }

        Map searchParams = [exists: true, userId: analyst.id, status: Status.MANUAL_ANALYSIS_STARTED]
        Boolean hasManualAnalysisStarted = CustomerUpdateRequest.query(searchParams).get().asBoolean()

        if (hasManualAnalysisStarted) {
            DomainUtils.addError(validatedCustomerUpdateRequest, "Analista já possui uma análise iniciada, volte para a fila e finalize a sua análise em aberto antes de iniciar outra")
            return validatedCustomerUpdateRequest
        }

        return validatedCustomerUpdateRequest
    }

    private CustomerUpdateRequest validateFinishAnalysis(CustomerUpdateRequest customerUpdateRequest, User analyst, Status status, CustomerUpdateRequestDenialReasonType  denialReasonType) {
        CustomerUpdateRequest validatedCustomerUpdateRequest = new CustomerUpdateRequest()

        if (!analyst) {
            DomainUtils.addError(validatedCustomerUpdateRequest, "Não foi possível identificar o analista ao finalizar a análise")
            return validatedCustomerUpdateRequest
        }

        if (!customerUpdateRequest.status.isManualAnalysisStarted()) {
            DomainUtils.addError(validatedCustomerUpdateRequest, "Esta análise não foi iniciada")
            return validatedCustomerUpdateRequest
        }

        if (customerUpdateRequest.user && (customerUpdateRequest.user.id != analyst.id)) {
            DomainUtils.addError(validatedCustomerUpdateRequest, "Não é possível encerrar a análise iniciada por outro analista")
            return validatedCustomerUpdateRequest
        }

        if (!status) {
            DomainUtils.addError(validatedCustomerUpdateRequest, "É necessário informar uma situação")
            return validatedCustomerUpdateRequest
        }

        if (!status.isApproved() && !status.isDenied())  {
            DomainUtils.addError(validatedCustomerUpdateRequest, "A situação deve ser aprovar ou reprovar")
            return validatedCustomerUpdateRequest
        }

        if (status.isDenied() && !denialReasonType) {
            DomainUtils.addError(validatedCustomerUpdateRequest, "É necessário informar o motivo de reprovação")
            return validatedCustomerUpdateRequest
        }

        return validatedCustomerUpdateRequest
    }

    private void approve(CustomerUpdateRequest customerUpdateRequest, Map params) {
        customerUpdateRequestService.approve(customerUpdateRequest.id, params)
        notifyAnalysisResult(customerUpdateRequest)
    }

    private void deny(CustomerUpdateRequest customerUpdateRequest, CustomerUpdateRequestDenialReasonType denialReasonType, Map params) {
        customerUpdateRequest.status = Status.DENIED
        customerUpdateRequest.denialReason = params.denialReason
        customerUpdateRequest.denialReasonType = denialReasonType
        customerUpdateRequest.user = springSecurityService.currentUser
        customerUpdateRequest.observations = params.observations
        customerUpdateRequest.save(flush: true, failOnError: true)

        notifyAnalysisResult(customerUpdateRequest)
        customerRegisterStatusService.updateCommercialInfoStatus(customerUpdateRequest.provider, Status.REJECTED)
        customerInteractionService.saveCustomerUpdateRequestDenial(customerUpdateRequest.provider.id, customerUpdateRequest.observations, customerUpdateRequest.denialReason)
    }

    private void notifyAnalysisResult(CustomerUpdateRequest customerUpdateRequest) {
        customerMessageService.notifyCustomerUpdateRequestResult(customerUpdateRequest.provider, customerUpdateRequest)
        mobilePushNotificationService.notifyCommercialInfoAnalysisResult(customerUpdateRequest)
        customerAlertNotificationService.notifyCommercialInfoAnalysisResult(customerUpdateRequest)
        treasureDataService.track(customerUpdateRequest.provider, TreasureDataEventType.COMMERCIAL_INFO_ANALYZED, [customerUpdateRequestId: customerUpdateRequest.id])
    }

    private void updateStatusToPendingIfNecessary(CustomerUpdateRequest customerUpdateRequest) {
        if (!customerUpdateRequest) return

        if (!customerUpdateRequest.status.isManualAnalysisStarted()) return

        final Integer maxIdleAnalysisTimeInMinutes = 30
        Date idleAnalysisDateLimit = CustomDateUtils.sumMinutes(new Date(), -maxIdleAnalysisTimeInMinutes)

        Date lastStartDate = analysisInteractionService.findLastStartDate(customerUpdateRequest)
        if (!lastStartDate) return

        if (lastStartDate.after(idleAnalysisDateLimit)) return

        customerUpdateRequest.user = null
        customerUpdateRequest.status = Status.PENDING
        customerUpdateRequest.save(failOnError: true)
    }
}
