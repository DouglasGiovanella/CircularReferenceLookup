package com.asaas.service.api.pix

import com.asaas.api.ApiCriticalActionParser
import com.asaas.api.pix.ApiPixTransactionRefundParser
import com.asaas.api.pix.ApiPixTransactionParser
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionRefund
import com.asaas.exception.ResourceNotFoundException
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.pix.PixTransactionRefundReason
import com.asaas.service.api.ApiBaseService
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ApiPixTransactionRefundService extends ApiBaseService {

    def apiResponseBuilderService
    def pixCreditService
    def pixRefundService

    public Map list(Map params) {
        PixTransaction refundedTransaction
        if (PixUtils.isValidEndToEndIdentifierPattern(params.id.toString())) {
            refundedTransaction = PixTransaction.query([endToEndIdentifier: params.id, customerId: getProvider(params)]).get()
            if (!refundedTransaction) throw new ResourceNotFoundException("Transação não encontrada.")
        } else {
            refundedTransaction = PixTransaction.find(params.id, getProviderInstance(params))
        }

        Integer limit = getLimit(params)
        Integer offset = getOffset(params)

        List<PixTransactionRefund> refundedTransactions = PixTransactionRefund.query([refundedTransaction: refundedTransaction]).list(max: limit, offset: offset, readOnly: true)

        List<Map> responseMap = refundedTransactions.collect { ApiPixTransactionParser.buildResponseItem(it.transaction) }

        return apiResponseBuilderService.buildList(responseMap, limit, offset, refundedTransactions.totalCount)
    }

    public Map refund(Map params) {
        PixTransaction transactionToRefund = PixTransaction.find(params.id, getProviderInstance(params))

        Map parsedParams = ApiPixTransactionRefundParser.parseSaveParams(params)

        if (!parsedParams.value) {
            parsedParams.value = transactionToRefund.getRemainingValueToRefund()
        }

        PixTransaction pixTransaction = pixRefundService.refundCredit(transactionToRefund, parsedParams.value, parsedParams.description, parsedParams.tokenParams)

        return apiResponseBuilderService.buildSuccess(ApiPixTransactionParser.buildResponseItem(pixTransaction, [buildCriticalAction: true]))
    }

    public Map requestToken(Map params) {
        PixTransaction transactionToRefund = PixTransaction.find(params.id, getProviderInstance(params))
        BigDecimal valueToRefund = Utils.toBigDecimal(params.value) ?: transactionToRefund.getRemainingValueToRefund()

        CriticalActionGroup criticalActionGroup = pixCreditService.requestRefundToken(transactionToRefund, valueToRefund, PixTransactionRefundReason.getDefaultReason(), params.description)

        if (criticalActionGroup.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(criticalActionGroup)
        }

        return apiResponseBuilderService.buildSuccess(ApiCriticalActionParser.buildGroupResponseItem(criticalActionGroup))
    }
}
