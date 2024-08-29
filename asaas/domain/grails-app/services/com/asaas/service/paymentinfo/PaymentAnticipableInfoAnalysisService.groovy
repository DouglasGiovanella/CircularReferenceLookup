package com.asaas.service.paymentinfo

import com.asaas.domain.payment.Payment
import com.asaas.domain.paymentinfo.PaymentAnticipableInfo
import com.asaas.log.AsaasLogger
import com.asaas.namedqueries.SqlOrder
import com.asaas.paymentinfo.PaymentAnticipableInfoStatus
import com.asaas.receivableanticipation.validator.ReceivableAnticipationNonAnticipableReasonVO
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.springframework.transaction.HeuristicCompletionException

@Transactional
class PaymentAnticipableInfoAnalysisService {

    def receivableAnticipationValidationService

    public void processAwaitingAnalysisPaymentsAnticipable(Map filters) {
        List<Long> awaitingAnalysisPaymentIdList = PaymentAnticipableInfo.query(filters + [
            "column": "payment.id",
            "status": PaymentAnticipableInfoStatus.AWAITING_ANALYSIS,
            "anticipated": false,
            "anticipable": false,
            "sqlOrder": new SqlOrder("DATE(this_.due_date) ASC")
        ]).list(max: 2000)

        List<Map> analyzedPaymentsInfoList = buildAnalyzedPaymentsInfoList(awaitingAnalysisPaymentIdList)

        analyzedPaymentsInfoList.groupBy { [
            message: it.reasonVO?.message,
            reason: it.reasonVO?.reason,
            validationSchedule: it.validationSchedule
        ] }.each { Map groupInfo, List<Map> groupAnalyzedPaymentInfoList ->
            List<Long> groupPaymentIdList = groupAnalyzedPaymentInfoList.collect { it.paymentId }
            ReceivableAnticipationNonAnticipableReasonVO reasonVO = groupInfo.reason ? new ReceivableAnticipationNonAnticipableReasonVO(groupInfo.reason, groupInfo.message) : null
            Utils.withNewTransactionAndRollbackOnError({
                if (groupInfo.validationSchedule && reasonVO) {
                    receivableAnticipationValidationService.setAsNotAnticipableAndSchedulable(groupPaymentIdList, reasonVO)
                } else {
                    receivableAnticipationValidationService.setAnticipableAndSchedulable(groupPaymentIdList, reasonVO)
                }
            }, [
                ignoreStackTrace: true,
                onError: { Exception exception ->
                    if (exception instanceof HeuristicCompletionException) return
                    AsaasLogger.error("PaymentAnticipableInfoAnalysisService.bulkAnalyzePaymentsAnticipable >> Falha ao setar anticipable para a cobranças [groupPaymentIdList: ${groupPaymentIdList}, reason: ${groupInfo.reason}].", exception)
                }
            ])
        }
    }

    private List<Map> buildAnalyzedPaymentsInfoList(List<Long> paymentIdList) {
        final Integer itemsPerThread = 200
        final Integer flushEvery = 50

        List<Map> analyzedPaymentsInfoList = Collections.synchronizedList(new ArrayList<Map>())

        ThreadUtils.processWithThreadsOnDemand(paymentIdList, itemsPerThread, { List<Long> paymentIdListFromThread ->
            Utils.withNewTransactionAndRollbackOnError({
                Utils.forEachWithFlushSession(paymentIdListFromThread, flushEvery, { Long paymentId ->
                    try {
                        Payment payment = Payment.read(paymentId)

                        ReceivableAnticipationNonAnticipableReasonVO cannotScheduleAnticipationReason = payment.cannotScheduleAnticipationReason()
                        if (cannotScheduleAnticipationReason) {
                            analyzedPaymentsInfoList.add([paymentId: paymentId, reasonVO: cannotScheduleAnticipationReason, validationSchedule: true])
                        } else {
                            ReceivableAnticipationNonAnticipableReasonVO cannotAnticipateIfSchedulableReason = payment.cannotAnticipateIfSchedulableReason()
                            analyzedPaymentsInfoList.add([paymentId: paymentId, reasonVO: cannotAnticipateIfSchedulableReason, validationSchedule: false])
                        }
                    } catch (Exception exception) {
                        AsaasLogger.error("PaymentAnticipableInfoAnalysisService.buildAnalyzedPaymentsInfoList >> Erro ao processar validações para cobrança [paymentId: ${paymentId}]", exception)
                    }
                })
            })
        })

        return analyzedPaymentsInfoList
    }
}
