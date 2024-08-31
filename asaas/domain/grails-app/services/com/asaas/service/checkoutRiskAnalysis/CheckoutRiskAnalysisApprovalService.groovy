package com.asaas.service.checkoutRiskAnalysis

import com.asaas.analysisrequest.AnalysisRequestStatus
import com.asaas.cache.checkoutriskanalysis.CheckoutRiskAnalysisConfigCacheVO
import com.asaas.checkoutRiskAnalysis.CheckoutRiskAnalysisReason
import com.asaas.checkoutRiskAnalysis.CheckoutRiskAnalysisReasonObject
import com.asaas.checkoutRiskAnalysis.vo.CheckoutRiskAnalysisVO
import com.asaas.domain.bill.BillOriginRequesterInfo
import com.asaas.domain.checkoutRiskAnalysis.CheckoutRiskAnalysisRequest
import com.asaas.domain.checkoutRiskAnalysis.CheckoutRiskAnalysisRequestReason
import com.asaas.domain.customer.Customer
import com.asaas.domain.transfer.TransferOriginRequester
import com.asaas.log.AsaasLogger
import com.asaas.originrequesterinfo.BaseOriginRequesterInfoEntity
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CheckoutRiskAnalysisApprovalService {

    def customerKnownApiTransferRequestService
    def billService
    def checkoutRiskAnalysisConfigCacheService
    def customerKnownRemoteIpService
    def pixDebitAuthorizationService
    def userKnownDeviceService

    public void analyzeAutomaticallyNotTrustedApiRemoteIp() {
        final CheckoutRiskAnalysisReason checkoutRiskAnalysisReason = CheckoutRiskAnalysisReason.NOT_TRUSTED_API_REMOTE_IP
        final Integer automaticAnalysisLimit = 100
        Integer delayMinutesToAutomaticAnalysis = checkoutRiskAnalysisConfigCacheService.getInstance(checkoutRiskAnalysisReason).delayMinutesToAutomaticAnalysis

        Map search = [:]
        search.column = "id"
        search.status = AnalysisRequestStatus.AWAITING_AUTOMATIC_ANALYSIS
        search."reason[exists]" = checkoutRiskAnalysisReason
        search."dateCreated[le]" = CustomDateUtils.sumMinutes(new Date(), -delayMinutesToAutomaticAnalysis)

        List<Long> checkoutRiskAnalysisRequestIdList = CheckoutRiskAnalysisRequest.query(search).list([max: automaticAnalysisLimit])

        if (!checkoutRiskAnalysisRequestIdList) return

        for (Long checkoutRiskAnalysisRequestId : checkoutRiskAnalysisRequestIdList) {
            process(checkoutRiskAnalysisRequestId, checkoutRiskAnalysisReason)
        }
    }

    public void analyzeAutomaticallyNotTrustedDevice() {
        final CheckoutRiskAnalysisReason checkoutRiskAnalysisReason = CheckoutRiskAnalysisReason.NOT_TRUSTED_DEVICE
        final Integer automaticAnalysisLimit = 100
        Integer delayMinutesToAutomaticAnalysis = checkoutRiskAnalysisConfigCacheService.getInstance(checkoutRiskAnalysisReason).delayMinutesToAutomaticAnalysis

        Map search = [:]
        search.column = "id"
        search.status = AnalysisRequestStatus.AWAITING_AUTOMATIC_ANALYSIS
        search."reason[exists]" = checkoutRiskAnalysisReason
        search."dateCreated[le]" = CustomDateUtils.sumMinutes(new Date(), -delayMinutesToAutomaticAnalysis)

        List<Long> checkoutRiskAnalysisRequestIdList = CheckoutRiskAnalysisRequest.query(search).list([max: automaticAnalysisLimit])

        if (!checkoutRiskAnalysisRequestIdList) return

        for (Long checkoutRiskAnalysisRequestId : checkoutRiskAnalysisRequestIdList) {
            process(checkoutRiskAnalysisRequestId, checkoutRiskAnalysisReason)
        }
    }

    public void approveTransactions(CheckoutRiskAnalysisRequest analysis, Boolean shouldSetAsTrustedToCheckout) {
        List<CheckoutRiskAnalysisRequestReason> analysisRequestReasonList = analysis.buildCheckoutRiskAnalysisRequestReasonList()
        List<Map> baseOriginRequesterInfoEntityMapList = []

        for (CheckoutRiskAnalysisRequestReason analysisRequestReason : analysisRequestReasonList) {
            CheckoutRiskAnalysisReasonObject checkoutRiskAnalysisReasonObject = CheckoutRiskAnalysisReasonObject.convertFromObjectName(analysisRequestReason.object)
            Long objectId = Long.valueOf(analysisRequestReason.objectId)
            Map baseOriginRequesterInfoMap
            List<String> baseOriginRequesterInfoColumnList = ["device", "remoteIp", "eventOrigin"]

            switch (checkoutRiskAnalysisReasonObject) {
                case CheckoutRiskAnalysisReasonObject.PIX_TRANSACTION:
                    pixDebitAuthorizationService.onCheckoutRiskAnalysisRequestApproved(objectId)
                    if (shouldSetAsTrustedToCheckout) {
                        baseOriginRequesterInfoMap = TransferOriginRequester.query([columnList: baseOriginRequesterInfoColumnList + ["transfer.id"], pixTransactionId: objectId]).get()
                        baseOriginRequesterInfoEntityMapList.add(baseOriginRequesterInfoMap)
                    }
                    break
                case CheckoutRiskAnalysisReasonObject.BILL:
                    billService.onCheckoutRiskAnalysisRequestApproved(objectId)
                    if (shouldSetAsTrustedToCheckout) {
                        baseOriginRequesterInfoMap = BillOriginRequesterInfo.query([columnList: baseOriginRequesterInfoColumnList, billId: objectId]).get()
                        baseOriginRequesterInfoEntityMapList.add(baseOriginRequesterInfoMap)
                    }
                    break
                default:
                    AsaasLogger.warn("CheckoutRiskAnalysisApprovalService.approveTransactions: tipo de objeto não implementado: ${checkoutRiskAnalysisReasonObject}")
            }
        }

        if (shouldSetAsTrustedToCheckout) {
            setAsTrustedToCheckout(analysis.customer, baseOriginRequesterInfoEntityMapList)
        }
    }

    public void denyTransactions(CheckoutRiskAnalysisRequest analysis) {
        List<CheckoutRiskAnalysisRequestReason> analysisRequestReasonList = analysis.buildCheckoutRiskAnalysisRequestReasonList()
        for (CheckoutRiskAnalysisRequestReason analysisRequestReason : analysisRequestReasonList) {
            CheckoutRiskAnalysisReasonObject checkoutRiskAnalysisReasonObject = CheckoutRiskAnalysisReasonObject.convertFromObjectName(analysisRequestReason.object)
            Long objectId = Long.valueOf(analysisRequestReason.objectId)
            switch (checkoutRiskAnalysisReasonObject) {
                case CheckoutRiskAnalysisReasonObject.PIX_TRANSACTION:
                    pixDebitAuthorizationService.onCheckoutRiskAnalysisRequestDenied(objectId, analysis.denyReason.pixTransactionRefusalReason)
                    break
                case CheckoutRiskAnalysisReasonObject.BILL:
                    billService.onCheckoutRiskAnalysisRequestDenied(objectId)
                    break
                default:
                    AsaasLogger.warn("CheckoutRiskAnalysisApprovalService.denyTransactions: tipo de objeto não implementado: ${checkoutRiskAnalysisReasonObject}")
            }
        }
    }

    private void process(Long checkoutRiskAnalysisRequestId, CheckoutRiskAnalysisReason checkoutRiskAnalysisReason) {
        Boolean error = false
        Utils.withNewTransactionAndRollbackOnError ({
            CheckoutRiskAnalysisRequest checkoutRiskAnalysisRequest = CheckoutRiskAnalysisRequest.get(checkoutRiskAnalysisRequestId)
            approveAutomaticallyIfPossible(checkoutRiskAnalysisRequest, checkoutRiskAnalysisReason)
        }, [onError: { Exception exception ->
            error = true
            AsaasLogger.error("CheckoutRiskAnalysisApprovalService.process >> Erro ao tentar aprovar análise de saque. Análise será redirecionada para fila manual [${checkoutRiskAnalysisRequestId}].", exception)
        }])
        if (error) {
            CheckoutRiskAnalysisRequest checkoutRiskAnalysisRequest = CheckoutRiskAnalysisRequest.get(checkoutRiskAnalysisRequestId)
            sendToManualAnalysis(checkoutRiskAnalysisRequest)
        }
    }

    private void approveAutomaticallyIfPossible(CheckoutRiskAnalysisRequest analysis, CheckoutRiskAnalysisReason checkoutRiskAnalysisReason) {
        if (canApproveAutomatically(analysis, checkoutRiskAnalysisReason)) {
            approveAutomatically(analysis)
            return
        }

        sendToManualAnalysis(analysis)
    }

    private Boolean canApproveAutomatically(CheckoutRiskAnalysisRequest analysis, CheckoutRiskAnalysisReason checkoutRiskAnalysisReason) {
        List<CheckoutRiskAnalysisVO> checkoutRiskAnalysisVOList = analysis.buildCheckoutRiskAnalysisVOList()
        CheckoutRiskAnalysisConfigCacheVO config = checkoutRiskAnalysisConfigCacheService.getInstance(checkoutRiskAnalysisReason)

        BigDecimal maxValueToAutomaticallyApprove = config.maxValueToAutomaticallyApprove
        Integer hoursToCheckUntrustedDeviceTransactions = config.hoursToCheckUntrustedDeviceTransactions

        if (isTotalValueAboveAutomaticApprovalLimit(checkoutRiskAnalysisVOList, maxValueToAutomaticallyApprove)) return false

        if (isTotalValueAboveAutomaticApprovalLimitByOriginRequester(checkoutRiskAnalysisVOList, maxValueToAutomaticallyApprove, hoursToCheckUntrustedDeviceTransactions, checkoutRiskAnalysisReason)) return false

        return true
    }

    private Boolean isTotalValueAboveAutomaticApprovalLimit(List<CheckoutRiskAnalysisVO> checkoutRiskAnalysisVOList, BigDecimal maxValueToAutomaticallyApprove) {
        BigDecimal totalValue = checkoutRiskAnalysisVOList.collect { it.value }.sum()

        if (totalValue > maxValueToAutomaticallyApprove) return true

        return false
    }

    private Boolean isTotalValueAboveAutomaticApprovalLimitByOriginRequester(List<CheckoutRiskAnalysisVO> checkoutRiskAnalysisVOList, BigDecimal maxValueToAutomaticallyApprove, Integer hoursToCheckUntrustedApiRemoteIpTransactions, CheckoutRiskAnalysisReason checkoutRiskAnalysisReason) {
        final Date startDateToCheckUntrustedTransactions = CustomDateUtils.sumHours(new Date(), -hoursToCheckUntrustedApiRemoteIpTransactions)

        BigDecimal requestedTotalValueByOriginRequester

        for (CheckoutRiskAnalysisVO checkoutRiskAnalysisVO : checkoutRiskAnalysisVOList) {
            switch (checkoutRiskAnalysisReason) {
                case CheckoutRiskAnalysisReason.NOT_TRUSTED_DEVICE:
                    requestedTotalValueByOriginRequester = calculateTotalValueByDevice(checkoutRiskAnalysisVO, startDateToCheckUntrustedTransactions)
                    break
                case CheckoutRiskAnalysisReason.NOT_TRUSTED_API_REMOTE_IP:
                    requestedTotalValueByOriginRequester = calculateTotalValueByApiRemoteIp(checkoutRiskAnalysisVO, startDateToCheckUntrustedTransactions)
                    break
                default:
                    throw new IllegalArgumentException("Motivo da análise inválido: [${checkoutRiskAnalysisReason}]")
            }

            if (requestedTotalValueByOriginRequester > maxValueToAutomaticallyApprove) return true
        }

        return false
    }

    private BigDecimal calculateTotalValueByDevice(CheckoutRiskAnalysisVO checkoutRiskAnalysisVO, Date startDateToCheckUntrustedDeviceTransactions) {
        Long deviceId = checkoutRiskAnalysisVO.originDevice.id
        BaseOriginRequesterInfoEntity baseOrigin = checkoutRiskAnalysisVO.object.getOriginRequesterInstance()
        BigDecimal totalValue = baseOrigin.calculateTotalValueByDevice(deviceId, startDateToCheckUntrustedDeviceTransactions)

        return totalValue
    }

    private BigDecimal calculateTotalValueByApiRemoteIp(CheckoutRiskAnalysisVO checkoutRiskAnalysisVO, Date startDateToCheckUntrustedRemoteIpTransactions) {
        String remoteIp = checkoutRiskAnalysisVO.remoteIp
        Long customerId = checkoutRiskAnalysisVO.customerId
        BaseOriginRequesterInfoEntity baseOrigin = checkoutRiskAnalysisVO.object.getOriginRequesterInstance()
        BigDecimal totalValue = baseOrigin.calculateTotalValueByRemoteIp(remoteIp, customerId, startDateToCheckUntrustedRemoteIpTransactions)

        return totalValue
    }

    private void approveAutomatically(CheckoutRiskAnalysisRequest analysis) {
        analysis.status = AnalysisRequestStatus.APPROVED
        analysis.analysisDate = new Date()
        analysis.observations = Utils.getMessageProperty("system.automaticApproval.description")
        analysis.analyzed = true
        analysis.save(failOnError: true)

        Boolean shouldSetDeviceAsTrustedToCheckout = false
        approveTransactions(analysis, shouldSetDeviceAsTrustedToCheckout)
    }

    private void setAsTrustedToCheckout(Customer customer, List<Map> baseOriginRequesterInfoEntityMapList) {
        for (Map baseOriginRequesterInfoMap : baseOriginRequesterInfoEntityMapList) {
            if (baseOriginRequesterInfoMap.eventOrigin.isApi()) {
                customerKnownRemoteIpService.setAsTrustedToCheckoutIfPossible(customer, baseOriginRequesterInfoMap.remoteIp, baseOriginRequesterInfoMap.eventOrigin)
                if (baseOriginRequesterInfoMap."transfer.id") {
                    customerKnownApiTransferRequestService.approveIfPossible(baseOriginRequesterInfoMap."transfer.id")
                }

                continue
            }

            userKnownDeviceService.setAsTrustedToCheckoutIfNecessary(baseOriginRequesterInfoMap.device)
        }
    }

    private void sendToManualAnalysis(CheckoutRiskAnalysisRequest analysis) {
        analysis.status = AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED
        analysis.save(failOnError: true)
    }
}
