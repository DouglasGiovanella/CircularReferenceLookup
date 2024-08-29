package com.asaas.service.customerregisterupdate

import com.asaas.analysisinteraction.AnalysisInteractionType
import com.asaas.customerregisterupdate.adapter.CustomerRegisterUpdateAnalysisRequestAdapter
import com.asaas.customerregisterupdate.adapter.CustomerRegisterUpdateAnalysisRequestReasonAdapter
import com.asaas.customerregisterupdate.enums.CustomerRegisterUpdateAnalysisReason
import com.asaas.customerregisterupdate.enums.CustomerRegisterUpdateAnalysisStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerregisterupdate.CustomerRegisterUpdateAnalysisRequest
import com.asaas.domain.customerregisterupdate.CustomerRegisterUpdateAnalysisRequestReason
import com.asaas.domain.user.User
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CustomerRegisterUpdateAnalysisRequestService {

    def analysisInteractionService

    public CustomerRegisterUpdateAnalysisRequest save(CustomerRegisterUpdateAnalysisRequestAdapter analysisRequestAdapter) {
        CustomerRegisterUpdateAnalysisRequest validatedAnalysisRequest = validateSave(analysisRequestAdapter)
        if (validatedAnalysisRequest.hasErrors()) return validatedAnalysisRequest

        CustomerRegisterUpdateAnalysisRequest analysisRequest = CustomerRegisterUpdateAnalysisRequest
            .query([customerId: analysisRequestAdapter.customer.id, "status[in]": CustomerRegisterUpdateAnalysisStatus.listAwaitingAutomaticAnalysisOrPending()])
            .get()

        if (!analysisRequest) {
            analysisRequest = new CustomerRegisterUpdateAnalysisRequest()
            analysisRequest.customer = analysisRequestAdapter.customer
            analysisRequest.externalId = analysisRequestAdapter.externalId
            analysisRequest.scheduledBy = analysisRequestAdapter.scheduledBy
            analysisRequest.scheduledDate = analysisRequestAdapter.scheduledDate
            analysisRequest.origin = analysisRequestAdapter.origin
            analysisRequest.status = CustomerRegisterUpdateAnalysisStatus.AWAITING_AUTOMATIC_ANALYSIS
            analysisRequest.save(flush: true, failOnError: true)
        }

        List<CustomerRegisterUpdateAnalysisReason> savedReasonList = analysisRequest.getAnalysisReasonList()

        analysisRequestAdapter.reasons.each { CustomerRegisterUpdateAnalysisRequestReasonAdapter reasonAdapter ->
            saveReasonIfNecessary(analysisRequest, savedReasonList, reasonAdapter)
        }

        if (analysisRequest.isScheduled() || analysisRequestAdapter.origin?.isExpiredDocument()) {
            analysisRequest.status = CustomerRegisterUpdateAnalysisStatus.PENDING
        } else {
            analysisRequest.status = CustomerRegisterUpdateAnalysisStatus.AWAITING_AUTOMATIC_ANALYSIS
        }

        analysisRequest.score = analysisRequest.sumTotalScore()
        analysisRequest.save(flush: true, failOnError: true)
        return analysisRequest
    }

    public CustomerRegisterUpdateAnalysisRequest finish(User analyst,
                                                        Long analysisRequestId,
                                                        String observations) {
        CustomerRegisterUpdateAnalysisRequest analysisRequest = CustomerRegisterUpdateAnalysisRequest.query([id: analysisRequestId]).get()

        CustomerRegisterUpdateAnalysisRequest validatedAnalysisRequest = validateFinish(analyst, analysisRequest, observations)

        if (validatedAnalysisRequest.hasErrors()) return validatedAnalysisRequest

        analysisRequest.status = CustomerRegisterUpdateAnalysisStatus.FINISHED
        analysisRequest.analyst = analyst
        analysisRequest.analysisDate = new Date()
        analysisRequest.observations = observations

        analysisRequest.save(failOnError: true)

        analysisInteractionService.createForCustomerRegisterUpdateAnalysisRequest(analyst, AnalysisInteractionType.FINISH, analysisRequest)

        return analysisRequest
    }

    public CustomerRegisterUpdateAnalysisRequest schedule(CustomerRegisterUpdateAnalysisRequestAdapter adapter) {
        CustomerRegisterUpdateAnalysisRequest validatedAnalysisRequest = validateSchedule(adapter)

        if (validatedAnalysisRequest.hasErrors()) return validatedAnalysisRequest

        return save(adapter)
    }

    public CustomerRegisterUpdateAnalysisRequest startByQueue(User analyst) {
        CustomerRegisterUpdateAnalysisRequest validatedAnalysisRequest = validateBeforeStart(analyst)

        if (validatedAnalysisRequest.hasErrors()) return validatedAnalysisRequest

        CustomerRegisterUpdateAnalysisRequest analysisRequestAlreadyStarted = CustomerRegisterUpdateAnalysisRequest
            .query([analystId: analyst.id, status: CustomerRegisterUpdateAnalysisStatus.STARTED]).get()

        if (analysisRequestAlreadyStarted) return analysisRequestAlreadyStarted

        CustomerRegisterUpdateAnalysisRequest nextPendingAnalysis = getNextRankedPendingAnalysis()

        return start(analyst, nextPendingAnalysis)
    }


    public CustomerRegisterUpdateAnalysisRequest start(User analyst, CustomerRegisterUpdateAnalysisRequest analysisRequestPending) {
        CustomerRegisterUpdateAnalysisRequest validatedAnalysisRequest = validateStart(analyst, analysisRequestPending)

        if (validatedAnalysisRequest.hasErrors()) return validatedAnalysisRequest

        analysisRequestPending.analyst = analyst
        analysisRequestPending.status = CustomerRegisterUpdateAnalysisStatus.STARTED
        analysisRequestPending.save(failOnError: true)

        analysisInteractionService.createForCustomerRegisterUpdateAnalysisRequest(analyst, AnalysisInteractionType.START, analysisRequestPending)

        return analysisRequestPending
    }

    public BusinessValidation canScheduleAnalysis(Customer customer) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (customer.getIsBlocked()) {
            validatedBusiness.addError("customerRegisterUpdateAnalysisRequest.error.isBlockedCustomer")
            return validatedBusiness
        }

        Boolean hasAnalysisInProgress = CustomerRegisterUpdateAnalysisRequest
            .hasInProgress([customerId: customer.id])
            .get().asBoolean()


        if (hasAnalysisInProgress) {
            validatedBusiness.addError("customerRegisterUpdateAnalysisRequest.error.hasAnalysisInProgress")
            return validatedBusiness
        }

        return validatedBusiness
    }

    public BusinessValidation canStartAnalysis(CustomerRegisterUpdateAnalysisRequest analysisRequest, User analyst) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!analyst) {
            validatedBusiness.addError("customerRegisterUpdateAnalysisRequest.error.analystNotIdentified")
            return validatedBusiness
        }

        if (!analysisRequest) {
            validatedBusiness.addError("customerRegisterUpdateAnalysisRequest.error.analysisNotFound")
            return validatedBusiness
        }

        if (analysisRequest.status.isFinished()) {
            validatedBusiness.addError("customerRegisterUpdateAnalysisRequest.error.alreadyFinished")
            return validatedBusiness
        }

        if (analysisRequest.status.isStarted() && analysisRequest.analyst.id != analyst.id) {
            validatedBusiness.addError("customerRegisterUpdateAnalysisRequest.error.alreadyStartedByAnotherAnalyst")
            return validatedBusiness
        }

        if (analysisRequest.status.isStarted()) {
            validatedBusiness.addError("customerRegisterUpdateAnalysisRequest.error.alreadyStarted")
            return validatedBusiness
        }

        return validatedBusiness
    }

    public void cancelPending(Long customerId, String observations) {
        Map searchParams = [customerId: customerId, status: CustomerRegisterUpdateAnalysisStatus.PENDING]
        CustomerRegisterUpdateAnalysisRequest customerRegisterUpdateAnalysisRequest = CustomerRegisterUpdateAnalysisRequest.query(searchParams).get()
        if (!customerRegisterUpdateAnalysisRequest) return

        customerRegisterUpdateAnalysisRequest.status = CustomerRegisterUpdateAnalysisStatus.CANCELLED
        customerRegisterUpdateAnalysisRequest.observations = observations
        customerRegisterUpdateAnalysisRequest.save(failOnError: true)
    }

    private CustomerRegisterUpdateAnalysisRequest getNextRankedPendingAnalysis() {
        try {
            Map search = [:]
            search.sort = 'score'
            search.order = 'desc'
            search.sortList = [[sort: "id", order: "asc"]]
            search.status = CustomerRegisterUpdateAnalysisStatus.PENDING
            CustomerRegisterUpdateAnalysisRequest rankedAnalysis = CustomerRegisterUpdateAnalysisRequest.query(search).get()

            return rankedAnalysis
        } catch (Exception e) {
            AsaasLogger.error("CustomerRegisterUpdateAnalysisRequestService.getNextRankedPendingAnalysis >> Não foi possível executar a consulta.", e)
            return null
        }
    }

    private CustomerRegisterUpdateAnalysisRequestReason saveReasonIfNecessary(CustomerRegisterUpdateAnalysisRequest analysisRequest,
                                                                   List<CustomerRegisterUpdateAnalysisReason> savedReasonList,
                                                                   CustomerRegisterUpdateAnalysisRequestReasonAdapter reasonAdapter) {
        if (savedReasonList.contains(reasonAdapter.reason)) return null

        CustomerRegisterUpdateAnalysisRequestReason analysisRequestReason = new CustomerRegisterUpdateAnalysisRequestReason()
        analysisRequestReason.analysisRequest = analysisRequest
        analysisRequestReason.reason = reasonAdapter.reason

        analysisRequestReason.save(flush: true, failOnError: true)

        return analysisRequestReason
    }

    private CustomerRegisterUpdateAnalysisRequest validateSchedule(CustomerRegisterUpdateAnalysisRequestAdapter adapter) {
        final Date today = new Date().clearTime()
        CustomerRegisterUpdateAnalysisRequest validatedAnalysisRequest = new CustomerRegisterUpdateAnalysisRequest()

        if (!adapter) {
            DomainUtils.addError(validatedAnalysisRequest, "Não foi possível agendar esta análise de atualização cadastral")
            return validatedAnalysisRequest
        }

        if (!adapter.scheduledBy) {
            DomainUtils.addError(validatedAnalysisRequest, "Não foi possível identificar o analista ao agendar esta análise de atualização cadastral")
            return validatedAnalysisRequest
        }

        if (!adapter.scheduledDate) {
            DomainUtils.addError(validatedAnalysisRequest, "É necessário informar uma data para realizar o agendamento da análise de atualização cadastral")
            return validatedAnalysisRequest
        }

        if (adapter.scheduledDate < today) {
            DomainUtils.addError(validatedAnalysisRequest, "A data de agendamento para a análise de atualização cadastral não pode ser inferior a atual")
            return validatedAnalysisRequest
        }

        Boolean hasAnalysisInProgress = CustomerRegisterUpdateAnalysisRequest
            .hasInProgress([customerId: adapter.customer.id])
            .get().asBoolean()

        if (hasAnalysisInProgress) {
            DomainUtils.addError(validatedAnalysisRequest, "Não é possível agendar uma análise enquanto houver uma análise de atualização cadastral aguardando")
            return validatedAnalysisRequest
        }

        if (!adapter.reasons) {
            DomainUtils.addError(validatedAnalysisRequest, "Não é possível agendar uma análise sem selecionar ao menos um motivo")
            return validatedAnalysisRequest
        }

        return validatedAnalysisRequest
    }

    private CustomerRegisterUpdateAnalysisRequest validateStart(User analyst, CustomerRegisterUpdateAnalysisRequest analysisRequest) {
        CustomerRegisterUpdateAnalysisRequest validatedAnalysisRequest = validateBeforeStart(analyst)

        if (validatedAnalysisRequest.hasErrors()) {
            return validatedAnalysisRequest
        }

        CustomerRegisterUpdateAnalysisRequest pendingAnalysisRequest = CustomerRegisterUpdateAnalysisRequest
            .query([analystId: analyst.id, status: CustomerRegisterUpdateAnalysisStatus.STARTED]).get()

        if (pendingAnalysisRequest) {
            DomainUtils.addError(validatedAnalysisRequest, "Analista já possui uma análise iniciada, volte para a fila e finalize a sua análise em aberto antes de iniciar outra")
            return validatedAnalysisRequest
        }

        if (!analysisRequest) {
            DomainUtils.addError(validatedAnalysisRequest, "Não foi possível encontrar uma análise de atualização cadastral para iniciar")
            return validatedAnalysisRequest
        }

        if (!analysisRequest.status.isPending()) {
            DomainUtils.addError(validatedAnalysisRequest, "Não é possível iniciar uma análise de atualização cadastral com situação diferente de pendente")
            return validatedAnalysisRequest
        }

        return validatedAnalysisRequest
    }

    private CustomerRegisterUpdateAnalysisRequest validateBeforeStart(User analyst) {
        CustomerRegisterUpdateAnalysisRequest validatedAnalysisRequest = new CustomerRegisterUpdateAnalysisRequest()

        if (!analyst) {
            DomainUtils.addError(validatedAnalysisRequest, "Não é possível iniciar uma análise de atualização cadastral sem identificar o analista")
            return validatedAnalysisRequest
        }

        return validatedAnalysisRequest
    }

    private CustomerRegisterUpdateAnalysisRequest validateFinish(User analyst, CustomerRegisterUpdateAnalysisRequest analysisRequest, String observations) {
        CustomerRegisterUpdateAnalysisRequest validatedAnalysisRequest = new CustomerRegisterUpdateAnalysisRequest()

        if (!analyst) {
            DomainUtils.addError(validatedAnalysisRequest, "Não foi possível identificar o analista ao finalizar a análise")
            return validatedAnalysisRequest
        }

        if (!analysisRequest) {
            DomainUtils.addError(validatedAnalysisRequest, "Não foi possível encontrar a análise a ser finalizada")
            return validatedAnalysisRequest
        }

        if (analysisRequest.status.isFinished()) {
            DomainUtils.addError(validatedAnalysisRequest, "Não é possível encerrar uma análise com status finalizada")
            return validatedAnalysisRequest
        }

        if (!analysisRequest.status.isStarted()) {
            DomainUtils.addError(validatedAnalysisRequest, "Não é possível encerrar uma análise com situação diferente de iniciada")
            return validatedAnalysisRequest
        }

        if (analysisRequest.status.isStarted() && analysisRequest.analyst.id != analyst.id) {
            DomainUtils.addError(validatedAnalysisRequest, "Não é possível encerrar a análise iniciada por outro analista")
            return validatedAnalysisRequest
        }

        if (!observations) {
            DomainUtils.addError(validatedAnalysisRequest, "Deve ser incluída uma observação para finalizar a análise")
            return validatedAnalysisRequest
        }

        return validatedAnalysisRequest
    }

    private CustomerRegisterUpdateAnalysisRequest validateSave(CustomerRegisterUpdateAnalysisRequestAdapter adapter) {
        CustomerRegisterUpdateAnalysisRequest validatedAnalysisRequest = new CustomerRegisterUpdateAnalysisRequest()

        if (!adapter) {
            DomainUtils.addError(validatedAnalysisRequest, "Não foi encontrada uma atualização cadastral válida")
            return validatedAnalysisRequest
        }

        if (!adapter.customer) {
            DomainUtils.addError(validatedAnalysisRequest, "Não é possível criar uma análise sem informar um cliente")
            return validatedAnalysisRequest
        }

        if (!adapter.externalId) {
            DomainUtils.addError(validatedAnalysisRequest, "Não ś possível criar uma análise sem informar um external Id")
            return validatedAnalysisRequest
        }

        if (adapter.customer.isNaturalPerson()) {
            return validateSaveNaturalPersonReasons(adapter, validatedAnalysisRequest)
        } else {
            return validateSaveLegalPersonReasons(adapter, validatedAnalysisRequest)
        }
    }

    private CustomerRegisterUpdateAnalysisRequest validateSaveLegalPersonReasons(
        CustomerRegisterUpdateAnalysisRequestAdapter adapter,
        CustomerRegisterUpdateAnalysisRequest validatedAnalysisRequest) {
        if (adapter.origin && !adapter.origin.isDivergencesInRevenueServiceRegister()) return validatedAnalysisRequest

        Boolean isAllLegalPersonReason = adapter.reasons.every {
            CustomerRegisterUpdateAnalysisRequestReasonAdapter reasonAdapter ->
                reasonAdapter.reason.isLegalPersonReason()
        }

        if (!isAllLegalPersonReason) {
            DomainUtils.addError(validatedAnalysisRequest, "Não é possível registrar divergência de Pessoa Física para clientes do tipo Pessoa Jurídica")
        }
        return validatedAnalysisRequest
    }

    private CustomerRegisterUpdateAnalysisRequest validateSaveNaturalPersonReasons(
        CustomerRegisterUpdateAnalysisRequestAdapter adapter,
        CustomerRegisterUpdateAnalysisRequest validatedAnalysisRequest) {
        if (adapter.origin && !adapter.origin.isDivergencesInRevenueServiceRegister()) return validatedAnalysisRequest

        Boolean isAllNaturalPersonReason = adapter.reasons.every {
            CustomerRegisterUpdateAnalysisRequestReasonAdapter reasonAdapter ->
                reasonAdapter.reason.isNaturalPersonReason()
        }
        if (!isAllNaturalPersonReason) {
            DomainUtils.addError(validatedAnalysisRequest, "Não é possível registrar divergência de Pessoa Jurídica para clientes do tipo Pessoa Física")
        }
        return validatedAnalysisRequest
    }

}
