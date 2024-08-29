package com.asaas.service.api.pix.bacen

import com.asaas.api.pix.bacen.ApiBacenPixParser
import com.asaas.api.pix.bacen.ApiBacenPixResponseBuilder
import com.asaas.api.pix.bacen.ApiBacenPixTransactionParser
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionRefund
import com.asaas.exception.BusinessException
import com.asaas.exception.api.ApiBacenPixViolationException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.pix.PixTransactionType
import com.asaas.service.api.ApiResponseBuilder
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.orm.PagedResultList
import grails.transaction.Transactional

@Transactional
class ApiBacenPixTransactionService extends ApiBacenPixBaseService {

    def pixRefundService
    def pixTransactionExternalIdentifierService

    public Map find(String endToEndIdentifier) {
        if (!PixUtils.isValidEndToEndIdentifierPattern(endToEndIdentifier)) throw new BusinessException("Necessário informar um Id fim a fim válido.")

        PixTransaction pixTransaction = PixTransaction.query([endToEndIdentifier: endToEndIdentifier, customerId: getProvider(params), type: PixTransactionType.CREDIT, readOnly: true]).get()

        if (!pixTransaction) throw new ResourceNotFoundException("Transação não encontrada.")
        return ApiResponseBuilder.buildSuccess(ApiBacenPixTransactionParser.buildResponseItem(pixTransaction))
    }

    public Map list(Map params) {
        validateListFilters(params)
        Map parsedFilters = ApiBacenPixTransactionParser.parseListingFilters(params) + [customerId: getProvider(params), type: PixTransactionType.CREDIT, readOnly: true]

        Integer offset = ApiBacenPixParser.getOffset(params)
        Integer max = ApiBacenPixParser.getMax(params)
        final Integer customQueryTimeoutInSeconds = 30

        PagedResultList pixTransactionList = PixTransaction.query(parsedFilters)
            .list(max: max, offset: offset, readOnly: true, timeout: customQueryTimeoutInSeconds)

        Map responseData = ApiBacenPixTransactionParser.buildResponseItemList(pixTransactionList)
        return ApiBacenPixResponseBuilder.buildSuccessList(
            ApiBacenPixTransactionParser.ALLOWED_FILTERS,
            params,
            responseData,
            max,
            offset,
            pixTransactionList.totalCount
        )
    }

    public Map refund(Map params) {
        Map parsedParams = ApiBacenPixTransactionParser.parseRefundParams(params)
        validateRefund(parsedParams)

        PixTransaction creditTransaction = PixTransaction.query([endToEndIdentifier: parsedParams.endToEndIdentifier, customerId: getProvider(params), type: PixTransactionType.CREDIT]).get()
        if (!creditTransaction) throw new ResourceNotFoundException("Transação não encontrada.")

        PixTransaction creditRefund = pixRefundService.refundCredit(creditTransaction, parsedParams.value, "Não informado", [authorizeSynchronous: false])
        pixTransactionExternalIdentifierService.save(creditRefund, parsedParams.id)

        if (creditRefund.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(creditRefund))
        return ApiResponseBuilder.buildSuccess(ApiBacenPixTransactionParser.buildRefundResponseItem(creditRefund))
    }

    public Map showRefund(String endToEndIdentifier, String id) {
        PixTransaction creditRefund = PixTransactionRefund.query([
            column: "transaction",
            refundedTransactionEndToEndIdentifier: endToEndIdentifier,
            refundedTransactionCustomerId: getProvider(params),
            readOnly: true
        ]).get()
        if (!creditRefund) throw new ResourceNotFoundException("Devolução não encontrada.")

        return ApiResponseBuilder.buildSuccess(ApiBacenPixTransactionParser.buildRefundResponseItem(creditRefund))
    }

    private void validateRefund(Map params) {
        if (Utils.toBigDecimal(params.value) <= 0) throw new BusinessException("Valor de reembolso inválido.")
    }

    private void validateListFilters(Map params) {
        if (!params.inicio) throw new ApiBacenPixViolationException("Necessário informar o parâmetro inicio.", "inicio", params.inicio)
        Date startDate = ApiBacenPixParser.parseDate(params.inicio)
        if (!startDate) throw new ApiBacenPixViolationException("Data de início inválida.", "inicio", params.inicio)

        if (!params.fim) throw new ApiBacenPixViolationException("Necessário informar o parâmetro fim.", "fim", params.fim)
        Date endDate = ApiBacenPixParser.parseDate(params.fim)
        if (!endDate) throw new ApiBacenPixViolationException("Data de fim inválida.", "fim", params.fim)

        if (startDate.after(endDate)) throw new ApiBacenPixViolationException("O timestamp representado pelo parâmetro fim é anterior ao timestamp representado pelo parâmetro inicio.", "inicio", params.inicio)

        if (params.cpf && params.cnpj) throw new ApiBacenPixViolationException("Ambos os parâmetros cpf e cnpj estão preenchidos.", "cpf", params.cpf)

        validatePagination(params)
    }
}
