package com.asaas.service.riskanalysis

import com.asaas.analysisinteraction.AnalysisInteractionType
import com.asaas.analysisrequest.AnalysisRequestStatus
import com.asaas.analysisrequest.AnalysisRequestValidator
import com.asaas.customer.CustomerStatus
import com.asaas.customer.PersonType
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.domain.blacklist.BlackList
import com.asaas.domain.businessactivity.BusinessActivity
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBusinessActivity
import com.asaas.domain.customer.CustomerEconomicActivity
import com.asaas.domain.economicactivity.EconomicActivity
import com.asaas.domain.payment.Payment
import com.asaas.domain.riskAnalysis.RiskAnalysisRequest
import com.asaas.domain.riskAnalysis.RiskAnalysisRequestReason
import com.asaas.domain.riskAnalysis.RiskAnalysisTrigger
import com.asaas.domain.user.User
import com.asaas.log.AsaasLogger
import com.asaas.riskAnalysis.RiskAnalysisReason
import com.asaas.riskAnalysis.RiskAnalysisResult
import com.asaas.riskAnalysis.RiskAnalysisResultAction
import com.asaas.riskAnalysis.adapter.RiskAnalysisRequestAdapter
import com.asaas.riskAnalysis.adapter.RiskAnalysisRequestReasonObjectAdapter
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class RiskAnalysisRequestService {

    def analysisInteractionService
    def asaasSegmentioService
    def customerStageService
    def riskAnalysisTriggerCacheService
    def treasureDataService

    public RiskAnalysisRequest start(User analyst, Long analysisId, Map searchParams) {
        RiskAnalysisRequest analysis

        if (analysisId) {
            analysis = RiskAnalysisRequest.get(analysisId)
        } else {
            searchParams.status = AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED
            searchParams.analyzed = false

            analysis = RiskAnalysisRequest.orderedByAnalysisPriority(searchParams).get()
        }

        RiskAnalysisRequest validatedAnalysis = validateStart(analyst, analysis)
        if (validatedAnalysis.hasErrors()) return validatedAnalysis

        analysis.analyst = analyst
        analysis.startAnalysisDate = new Date()
        analysis.status = AnalysisRequestStatus.STARTED
        analysis.save(failOnError: true)

        analysisInteractionService.createForAnalysisRequest(analyst, AnalysisInteractionType.START, analysis)

        return analysis
    }

    public RiskAnalysisRequest save(RiskAnalysisRequestAdapter adapter) {
        Customer customer = Customer.read(adapter.customerId)
        RiskAnalysisRequest riskAnalysisRequest = RiskAnalysisRequest.notAnalysed([customer: customer]).get()

        if (!riskAnalysisRequest) {
            riskAnalysisRequest = new RiskAnalysisRequest()
            riskAnalysisRequest.customer = customer
            riskAnalysisRequest.status = AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED
            riskAnalysisRequest.score = 0
            riskAnalysisRequest.save(failOnError: true)
        }

        if (!adapter.riskAnalysisRequestReasonObjectAdapterList) {
            riskAnalysisRequest = saveWithoutObject(riskAnalysisRequest, customer, adapter.reason, adapter.additionalInfo)

            return riskAnalysisRequest
        }

        for (RiskAnalysisRequestReasonObjectAdapter reasonObjectAdapter : adapter.riskAnalysisRequestReasonObjectAdapterList) {
            Map params = reasonObjectAdapter.properties + [additionalInfo: adapter.additionalInfo]
            RiskAnalysisRequest validatedRiskAnalysisRequest = save(riskAnalysisRequest, customer, adapter.reason, params)
            if (validatedRiskAnalysisRequest.hasErrors()) {
                AsaasLogger.warn("RiskAnalysisRequestService.save >> não foi possível salvar RiskAnalysisRequestReason. RiskAnalysisRequest [${riskAnalysisRequest?.id}] ErrorMessage [${DomainUtils.getFirstValidationMessage(validatedRiskAnalysisRequest)}]")
            }
        }

        return riskAnalysisRequest
    }

    public RiskAnalysisRequest save(Customer customer, RiskAnalysisReason reason, Map params) {
        RiskAnalysisRequest riskAnalysisRequest = RiskAnalysisRequest.notAnalysed([customer: customer]).get()

        return save(riskAnalysisRequest, customer, reason, params)
    }

    public RiskAnalysisRequest save(RiskAnalysisRequest riskAnalysisRequest, Customer customer, RiskAnalysisReason reason, Map params) {
        if (params?.domainObject) {
            params.object = params.domainObject.class.simpleName
            params.objectId = params.domainObject.id.toString()
        } else if (params?.externalDomainObject) {
            params.object = params.externalDomainObject.name.toString()
            params.objectId = params.externalDomainObject.id.toString()
        }

        BusinessValidation validatedBusiness = validateBeforeSave(customer, riskAnalysisRequest, reason, params)
        if (!validatedBusiness.isValid()) return DomainUtils.addError(new RiskAnalysisRequest(), validatedBusiness.getFirstErrorMessage())

        if (!riskAnalysisRequest) {
            riskAnalysisRequest = new RiskAnalysisRequest()
            riskAnalysisRequest.customer = customer
            riskAnalysisRequest.status = AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED
            riskAnalysisRequest.createdBy = UserUtils.getCurrentUser()
            riskAnalysisRequest.scheduledDate = params?.scheduledDate?.clearTime()
            riskAnalysisRequest.additionalInfo = params?.additionalInfo
            riskAnalysisRequest.score = 0
            riskAnalysisRequest.save(failOnError: true)
        } else {
            addAdditionalInfoIfNecessary(riskAnalysisRequest, params?.additionalInfo)
        }

        saveReason(riskAnalysisRequest, reason, params?.object, params?.objectId)

        return riskAnalysisRequest
    }

    public void saveAnalysisWithNewTransaction(Long customerId, RiskAnalysisReason reason, Long paymentId) {
        Utils.withNewTransactionAndRollbackOnError({
            RiskAnalysisRequest riskAnalysisRequest = save(Customer.get(customerId), reason, [domainObject: Payment.get(paymentId)])
            if (riskAnalysisRequest.hasErrors()) throw new ValidationException("Erro na validação da análise", riskAnalysisRequest.errors)
        }, [logErrorMessage: "RiskAnalysisRequestService.saveAnalysisWithNewTransaction >> Falha ao salvar análise com o motivo [${reason}] para o cliente [${customerId}]"])
    }

    public BusinessValidation canScheduleNewAnalysis(Customer customer) {
        Boolean hasNotAnalysedRiskAnalysisRequest = RiskAnalysisRequest.notAnalysed([includeScheduled: true, customer: customer, column: "id"]).get().asBoolean()
        if (hasNotAnalysedRiskAnalysisRequest) {
            BusinessValidation validatedBusiness = new BusinessValidation()
            validatedBusiness.addError("riskAnalysisRequest.error.customerAnalysisAlreadyExists")
            return validatedBusiness
        }

        return validateCustomerCanBeAnalyzed(customer)
    }

    public RiskAnalysisRequest finish(Long id, User analyst, RiskAnalysisResultAction resultAction, RiskAnalysisResult result, String observations) {
        RiskAnalysisRequest riskAnalysisRequest = RiskAnalysisRequest.get(id)
        RiskAnalysisRequest validatedRiskAnalysisRequest = validateFinish(analyst, riskAnalysisRequest, result, observations)
        if (validatedRiskAnalysisRequest.hasErrors()) return validatedRiskAnalysisRequest

        riskAnalysisRequest.analyzed = true
        riskAnalysisRequest.status = AnalysisRequestStatus.DONE
        riskAnalysisRequest.analysisDate = new Date()
        riskAnalysisRequest.observations = observations
        riskAnalysisRequest.result = result
        riskAnalysisRequest.resultAction = resultAction
        riskAnalysisRequest.save(flush: true, failOnError: true)

        treasureDataService.track(riskAnalysisRequest.customer, TreasureDataEventType.RISK_ANALYSIS_REQUEST_ANALYZED, [riskAnalysisRequestId: riskAnalysisRequest.id, result: result.toString(), resultAction: resultAction.toString()])
        analysisInteractionService.createForAnalysisRequest(riskAnalysisRequest.analyst, AnalysisInteractionType.FINISH, riskAnalysisRequest)

        return riskAnalysisRequest
    }

    public void createToCustomerInfoAddedBlackList(BlackList blackList) {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.CUSTOMER_INFO_ADDED_TO_BLACKLIST
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        List<Customer> customerList = Customer.createCriteria().list() {
            createAlias('customerRegisterStatus', 'customerRegisterStatus')
            eq("customerRegisterStatus.generalApproval", GeneralApprovalStatus.APPROVED)

            eq("deleted", false)
            not { 'in'("status", [CustomerStatus.DISABLED, CustomerStatus.BLOCKED]) }
            or {
                eq("cpfCnpj", blackList.cpfCnpj)
                eq("email", blackList.email)
                eq("phone", blackList.phone)
                eq("mobilePhone", blackList.phone)
            }
        }

        for (Customer customer : customerList) {
            save(customer, riskAnalysisReason, [domainObject: blackList])
        }
    }

    public void createToCustomersWithTravelAgencyAsCommercialActivity() {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.TRAVEL_AGENCY_AS_COMMERCIAL_ACTIVITY
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        final Integer recurrenceOfAnalysisInMonths = 6
        Date referenceDate = CustomDateUtils.addMonths(new Date(), recurrenceOfAnalysisInMonths * -1)

        Map search = [column                 : "customer",
                      "customerStatus[notIn]": [CustomerStatus.BLOCKED, CustomerStatus.DISABLED]]

        List<Customer> customerList = []
        customerList.addAll(CustomerBusinessActivity.query(search + [businessActivityId: BusinessActivity.TRAVEL_AGENCY_ID]).list())
        customerList.addAll(CustomerEconomicActivity.query(search + ["economicActivity[in]": EconomicActivity.TRAVEL_AGENCY_ID_LIST]).list())

        for (Customer customer in customerList) {
            Boolean wasAnalyzedBySameReasonRecently = RiskAnalysisRequest.query([exists           : true,
                                                                                 customer         : customer,
                                                                                 reason           : riskAnalysisReason,
                                                                                 "dateCreated[ge]": referenceDate]).get().asBoolean()
            if (wasAnalyzedBySameReasonRecently) continue

            Date convertedDate = customerStageService.findConvertedDate(customer)
            if (!convertedDate || convertedDate >= referenceDate) continue

            Boolean hasCreatedPaymentInPeriod = Payment.query([exists           : true,
                                                               customerId       : customer.id,
                                                               "dateCreated[ge]": referenceDate]).get().asBoolean()
            if (!hasCreatedPaymentInPeriod) continue

            save(customer, riskAnalysisReason, null)
        }
    }

    public void createToCustomerWithEmergencyAidFgtsAsCommercialActivity() {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.EMERGENCY_AID_FGTS_AS_COMMERCIAL_ACTIVITY
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        List<Customer> customerList = CustomerBusinessActivity.query([column: "customer", "customer.personType": PersonType.FISICA, "businessActivityId[in]": BusinessActivity.EMERGENCY_AID_FGTS_ID_LIST]).list()

        for (Customer customer in customerList) {
            final Integer minPaymentsToCreateEmergencyAidFgtsRiskAnalysis = 3

            List<Long> customerPaymentList = Payment.createCriteria().list(max: minPaymentsToCreateEmergencyAidFgtsRiskAnalysis) {
                projections {
                    property "id"
                }

                eq("provider", customer)
                eq("deleted", false)

                notExists RiskAnalysisRequestReason.where {
                    setAlias("reason")
                    createAlias('riskAnalysisRequest', 'riskAnalysisRequest')

                    eqProperty("riskAnalysisRequest.customer", "this.provider")
                    eq("riskAnalysisRequest.deleted", false)
                    eq("reason.deleted", false)
                    eq('reason.riskAnalysisReason', riskAnalysisReason)
                }.id()
            }

            if (customerPaymentList.size() < minPaymentsToCreateEmergencyAidFgtsRiskAnalysis) continue

            save(customer, riskAnalysisReason, null)
        }
    }

    public void createToCustomerInfoInBlackListIfNecessary(Customer customer) {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.CUSTOMER_INFO_ADDED_TO_BLACKLIST
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        Long blockListId = BlackList.getBlockListIdByCustomer(customer)
        if (!blockListId) return

        save(customer, riskAnalysisReason, [domainObject: BlackList.read(blockListId)])
    }

    public void processIdleAnalysis() {
        final Integer maxItemsPerCycle = 50
        final Integer maxIdleAnalysisTimeInMinutes = -30

        Map searchParams = [
            column: "id",
            order: "asc",
            "startAnalysisDate[lt]": CustomDateUtils.sumMinutes(new Date(), maxIdleAnalysisTimeInMinutes),
            status: AnalysisRequestStatus.STARTED
        ]
        List<Long> analysisIdList = RiskAnalysisRequest.query(searchParams).list(max: maxItemsPerCycle)
        if (!analysisIdList) return

        for (Long analysisId : analysisIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                RiskAnalysisRequest analysis = RiskAnalysisRequest.get(analysisId)

                updateStatusToManualAnalysisRequired(analysis)
            }, [logErrorMessage: "RiskAnalysisRequestService.processIdleAnalysis >> Falha ao expirar análise de bait. Analysis [${analysisId}]"])
        }
    }

    public BusinessValidation canStartAnalysis(User analyst, RiskAnalysisRequest analysis) {
        BusinessValidation validatedBusiness = new BusinessValidation()
        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()

        if (!analysisRequestValidator.canStartAnalysis(analysis, analyst)) {
            validatedBusiness.addErrors(analysisRequestValidator.errors)
        }

        return validatedBusiness
    }

    public Boolean canAnalyze(User analyst, RiskAnalysisRequest analysis) {
        return analysis.analyst == analyst && analysis.status.isStarted()
    }

    public void updateStatusToManualAnalysisRequired(RiskAnalysisRequest analysis) {
        if (!analysis) return
        if (!analysis.status.isStarted()) return

        analysis.analyst = null
        analysis.startAnalysisDate = null
        analysis.status = AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED
        analysis.save(failOnError: true)
    }

    public void deleteInBatch(List<Long> idList) {
        if (!idList) return

        RiskAnalysisRequest.executeUpdate("UPDATE RiskAnalysisRequest SET version = version + 1, lastUpdated = :lastUpdated, deleted = 1 WHERE  id IN (:idList)", [lastUpdated: new Date(), idList: idList])
        RiskAnalysisRequestReason.executeUpdate("UPDATE RiskAnalysisRequestReason SET version = version + 1, lastUpdated = :lastUpdated, deleted = 1 WHERE riskAnalysisRequest.id IN (:idList)", [lastUpdated: new Date(), idList: idList])
    }

    private RiskAnalysisRequest validateStart(User analyst, RiskAnalysisRequest analysis) {
        RiskAnalysisRequest validatedAnalysis = new RiskAnalysisRequest()
        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()

        if (!analysisRequestValidator.validateStart(analyst, analysis)) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(analysisRequestValidator, validatedAnalysis)
        }

        Boolean hasStartedAnalysis = RiskAnalysisRequest.query([exists: true, analyst: analyst, status: AnalysisRequestStatus.STARTED]).get().asBoolean()
        if (hasStartedAnalysis) {
            DomainUtils.addError(validatedAnalysis, Utils.getMessageProperty("analysisRequestValidator.validateStart.error.analystAlreadyHasAnotherStartedAnalysis"))
        }

        return validatedAnalysis
    }

    private RiskAnalysisRequest saveWithoutObject(RiskAnalysisRequest riskAnalysisRequest, Customer customer, RiskAnalysisReason reason, String additionalInfo) {
        RiskAnalysisRequest validatedRiskAnalysisRequest = save(riskAnalysisRequest, customer, reason, [additionalInfo: additionalInfo])
        if (validatedRiskAnalysisRequest.hasErrors()) {
            AsaasLogger.warn("RiskAnalysisRequestService.saveWithoutObject >> não foi possível salvar RiskAnalysisRequestReason. RiskAnalysisRequest [${riskAnalysisRequest?.id}] ErrorMessage [${DomainUtils.getFirstValidationMessage(validatedRiskAnalysisRequest)}]")
        }

        return validatedRiskAnalysisRequest
    }

    private void saveReason(RiskAnalysisRequest riskAnalysisRequest, RiskAnalysisReason reason, String object, String objectId) {
        RiskAnalysisRequestReason riskAnalysisRequestReason = new RiskAnalysisRequestReason()
        riskAnalysisRequestReason.riskAnalysisReason = reason
        riskAnalysisRequestReason.riskAnalysisRequest = riskAnalysisRequest
        riskAnalysisRequestReason.object = object
        riskAnalysisRequestReason.objectId = objectId
        riskAnalysisRequestReason.save(flush: true, failOnError: true)

        calculateScore(riskAnalysisRequest)
    }

    private Boolean hasRiskAnalysisRequestToCustomerWithSameReason(RiskAnalysisRequest riskAnalysisRequest, RiskAnalysisReason reason, String object, String objectId) {
        if (!riskAnalysisRequest) return false
        if (!reason) return false

        Map params = [
            exists             : true,
            riskAnalysisRequest: riskAnalysisRequest,
            riskAnalysisReason : reason,
            object             : object,
            objectId           : objectId
        ].findAll { it.value }

        return RiskAnalysisRequestReason.query(params).get().asBoolean()
    }

    private BusinessValidation validateBeforeSave(Customer customer, RiskAnalysisRequest riskAnalysisRequest, RiskAnalysisReason reason, Map params) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (hasRiskAnalysisRequestToCustomerWithSameReason(riskAnalysisRequest, reason, params?.object, params?.objectId)) {
            validatedBusiness.addError("riskAnalysisRequest.error.customerHasRiskAnalysisWithSameReason")
            return validatedBusiness
        }

        validatedBusiness = validateCustomerCanBeAnalyzed(customer)
        if (!validatedBusiness.isValid()) return validatedBusiness

        if (!reason) {
            validatedBusiness.addError("default.null.message", ["motivo"])
            return validatedBusiness
        }

        if (params?.containsKey("scheduledDate")) return validateScheduledAnalysis(customer, params.scheduledDate)

        if ((!params?.object || !params?.objectId) && RiskAnalysisReason.listReasonWithDomainObject().contains(reason)) {
            validatedBusiness.addError("riskAnalysisRequest.error.reasonNeedObject")
            return validatedBusiness
        }


        if (isCustomerAlreadyAnalyzedWithoutRiskInTolerancePeriod(customer, reason)) {
            asaasSegmentioService.track(customer.id, "customer_already_analyzed_without_risk_in_tolerance_period", [action: "save"])

            validatedBusiness.addError("riskAnalysisRequest.error.customerAlreadyAnalyzedWithoutRiskInTolerancePeriod")
            return validatedBusiness
        }

        return validatedBusiness
    }

    private BusinessValidation validateScheduledAnalysis(Customer customer, Date scheduledDate) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!scheduledDate || scheduledDate < new Date().clearTime()) {
            validatedBusiness.addError("riskAnalysisRequest.error.invalidScheduledDate")
            return validatedBusiness
        }

        Boolean hasNotAnalysedRiskAnalysisRequest = RiskAnalysisRequest.notAnalysed([includeScheduled: true, customer: customer, column: "id"]).get().asBoolean()
        if (hasNotAnalysedRiskAnalysisRequest) {
            validatedBusiness.addError("riskAnalysisRequest.error.customerAnalysisAlreadyExists")
            return validatedBusiness
        }

        return validatedBusiness
    }

    private RiskAnalysisRequest validateFinish(User analyst, RiskAnalysisRequest analysis, RiskAnalysisResult result, String observations) {
        final AnalysisRequestStatus finalStatus = AnalysisRequestStatus.DONE
        RiskAnalysisRequest validatedAnalysis = new RiskAnalysisRequest()
        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()

        if (!analysisRequestValidator.validateFinish(analyst, analysis, finalStatus, observations)) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(analysisRequestValidator, validatedAnalysis)
        }

        if (!result) {
            DomainUtils.addError(validatedAnalysis, "É necessário informar o resultado da análise.")
            return validatedAnalysis
        }

        return validatedAnalysis
    }

    private BusinessValidation validateCustomerCanBeAnalyzed(Customer customer) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (customer.isAsaasProvider()) {
            validatedBusiness.addError("riskAnalysisRequest.error.customerIsAsaasProvider")
            return validatedBusiness
        }

        if (customer.getIsBlocked()) {
            validatedBusiness.addError("riskAnalysisRequest.error.customerIsBlocked")
            return validatedBusiness
        }

        if (customer.accountDisabled()) {
            validatedBusiness.addError("riskAnalysisRequest.error.customerIsDisabled")
            return validatedBusiness
        }

        return validatedBusiness
    }

    private void addAdditionalInfoIfNecessary(RiskAnalysisRequest riskAnalysisRequest, String additionalInfo) {
        if (!additionalInfo) return

        if (!riskAnalysisRequest.additionalInfo) {
            riskAnalysisRequest.additionalInfo = additionalInfo
        } else {
            riskAnalysisRequest.additionalInfo += System.lineSeparator() + additionalInfo
        }

        riskAnalysisRequest.save(failOnError: true)
    }

    private void calculateScore(RiskAnalysisRequest riskAnalysisRequest) {
        String hql = """    select
                                sum(rat.score)
                            from
                                RiskAnalysisRequestReason rarr,
                                RiskAnalysisTrigger rat
                            where
                                rat.riskAnalysisReason = rarr.riskAnalysisReason
                                and rarr.riskAnalysisRequest.id = :riskAnalysisRequestId """

        Integer score = RiskAnalysisTrigger.executeQuery(hql, [riskAnalysisRequestId: riskAnalysisRequest.id]).first()

        riskAnalysisRequest.score = score
        riskAnalysisRequest.save(failOnError: true)
    }

    private Boolean isCustomerAlreadyAnalyzedWithoutRiskInTolerancePeriod(Customer customer, RiskAnalysisReason reason) {
        Integer activationDelayInDays = riskAnalysisTriggerCacheService.getInstance(reason).activationDelayInDays
        if (!activationDelayInDays) return false

        Date toleranceDate = CustomDateUtils.sumDays(new Date(), -activationDelayInDays)
        Map searchParams = [
            column: "result",
            analyzed: true,
            customer: customer,
            reason: reason,
            "dateCreated[ge]": toleranceDate,
            includeScheduled: true]

        List<RiskAnalysisResult> riskAnalysisResultList = RiskAnalysisRequest.query(searchParams).list()
        if (!riskAnalysisResultList) return false

        Boolean hasNonFalsePositiveResult = riskAnalysisResultList.any { !it.isFalsePositive() }
        if (hasNonFalsePositiveResult) return false

        return true
    }
}
