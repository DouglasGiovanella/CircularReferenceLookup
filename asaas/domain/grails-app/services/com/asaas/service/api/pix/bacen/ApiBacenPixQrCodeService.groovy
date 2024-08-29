package com.asaas.service.api.pix.bacen

import com.asaas.api.ApiBaseParser
import com.asaas.api.pix.bacen.ApiBacenPixParser
import com.asaas.api.pix.bacen.ApiBacenPixQrCodeParser
import com.asaas.api.pix.bacen.ApiBacenPixResponseBuilder
import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.exception.api.ApiBacenPixViolationException
import com.asaas.pix.adapter.qrcode.PixImmediateQrCodeListAdapter
import com.asaas.service.api.ApiResponseBuilder
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiBacenPixQrCodeService extends ApiBacenPixBaseService {

    def pixQrCodeService

    public Map saveImmediate(Map params) {
        validateSaveImmediate(params)

        Map parsedParams = ApiBacenPixQrCodeParser.parseImmediateParams(params)
        Map qrCodeInfo = pixQrCodeService.createImmediateQrCode(ApiBaseParser.getProviderId(), parsedParams)

        return ApiBacenPixResponseBuilder.buildCreated(ApiBacenPixQrCodeParser.buildImmediateResponseItem(qrCodeInfo))
    }

    public Map getImmediate(Map params) {
        Long customerId = ApiBaseParser.getProviderId()
        String conciliationIdentifier = params.txId

        Map qrCodeInfo = pixQrCodeService.getImmediate(customerId, conciliationIdentifier)
        if (!qrCodeInfo) throw new ResourceNotFoundException("Cobrança não encontrada para o txid informado")

        return ApiResponseBuilder.buildSuccess(ApiBacenPixQrCodeParser.buildShowImmediateResponseItem(qrCodeInfo))
    }

    public Map listImmediate(Map params) {
        validateListImmediate(params)

        Map parsedFilters = ApiBacenPixQrCodeParser.parseListFilters(params)
        PixImmediateQrCodeListAdapter qrCodeListAdapter = pixQrCodeService.listImmediate(ApiBaseParser.getProviderId(), parsedFilters)

        Map response = ApiBacenPixQrCodeParser.buildImmediateResponseItemList(qrCodeListAdapter)

        return ApiBacenPixResponseBuilder.buildSuccessList(
            ApiBacenPixQrCodeParser.ALLOWED_FILTERS,
            params,
            response,
            qrCodeListAdapter.limit,
            qrCodeListAdapter.offset,
            qrCodeListAdapter.totalCount
        )
    }

    public Map updateImmediate(Map params) {
        validateImmediate(params)

        Map parsedParams = ApiBacenPixQrCodeParser.parseImmediateParams(params)

        Boolean hasFieldsForUpdate = parsedParams.any { it.key != "conciliationIdentifier" }
        if (!hasFieldsForUpdate) throw new BusinessException("Nenhum campo para revisão foi informado")

        Map qrCodeInfo = pixQrCodeService.updateImmediate(ApiBaseParser.getProviderId(), parsedParams)

        return ApiResponseBuilder.buildSuccess(ApiBacenPixQrCodeParser.buildImmediateResponseItem(qrCodeInfo))
    }

    private void validateSaveImmediate(Map params) {
        if (!params.containsKey("calendario")) throw new ApiBacenPixViolationException("O objeto 'cob.calendario' não respeita o _schema_.", "cob.calendario", params.calendario)

        if (!params.containsKey("valor")) throw new ApiBacenPixViolationException("O objeto 'cob.valor' não respeita o _schema_.", "cob.valor", params.valor)
        if (!params.valor.containsKey("original")) throw new ApiBacenPixViolationException("O campo 'cob.valor.original' não respeita o _schema_.", "cob.valor.original", params.valor.original)

        if (!params.containsKey("chave")) throw new ApiBacenPixViolationException("O campo 'cob.chave' não respeita o _schema_.", "cob.chave", params.chave)

        validateImmediate(params)
    }

    private void validateImmediate(Map params) {
        if (containsKeyAndValidateInstanceOf(params, "calendario", Map)) {
            containsKeyAndValidateInstanceOf(params.calendario, "expiracao", Integer)
        }

        if (containsKeyAndValidateInstanceOf(params, "devedor", Map)) {
            Boolean hasNotInformedCpfAndCnpj = !params.devedor.containsKey("cpf") && !params.devedor.containsKey("cnpj")
            Boolean hasInformedBothDocuments = params.devedor.containsKey("cpf") && params.devedor.containsKey("cnpj")
            if (hasNotInformedCpfAndCnpj || hasInformedBothDocuments) throw new ApiBacenPixViolationException("O objeto 'cob.devedor' não respeita o _schema_.", "cob.devedor", params.devedor)

            if (params.devedor.containsKey("cpf") && !CpfCnpjUtils.isCpf(params.devedor.cpf)) throw new ApiBacenPixViolationException("O objeto 'cob.devedor' não respeita o _schema_.", "cob.devedor.cpf", params.devedor.cpf)
            if (params.devedor.containsKey("cnpj") && !CpfCnpjUtils.isCnpj(params.devedor.cnpj)) throw new ApiBacenPixViolationException("O objeto 'cob.devedor' não respeita o _schema_.", "cob.devedor.cnpj", params.devedor.cnpj)

            if (!containsKeyAndValidateInstanceOf(params.devedor, "nome", String)) throw new ApiBacenPixViolationException("O objeto 'cob.devedor' não respeita o _schema_.", "cob.devedor.nome", params.devedor)
        }

        if (params.containsKey("loc")) {
            throw new IllegalArgumentException("O campo 'loc' não é suportado.")
        }

        if (params.containsKey("status")) {
            throw new IllegalArgumentException("O campo 'status' não é suportado.")
        }

        if (containsKeyAndValidateInstanceOf(params, "valor", Map)) {
            if (containsKeyAndValidateInstanceOf(params.valor, "original", String)) {
                BigDecimal value = Utils.toBigDecimal(params.valor.original)
                Boolean hasInvalidValue = !value || BigDecimalUtils.hasMoreThanTwoDecimalPlaces(value) || value < BigDecimal.ZERO
                if (hasInvalidValue) throw new ApiBacenPixViolationException("O campo 'cob.valor.original' não respeita o _schema_.", "cob.valor.original", params.valor.original)
            }

            if (params.valor.containsKey("modalidadeAlteracao")) {
                Integer enablePaymentWithDifferentValue = Utils.toInteger(params.valor.modalidadeAlteracao)
                List<Integer> enablePaymentWithDifferentValueOptions = [0, 1]
                if (!enablePaymentWithDifferentValueOptions.contains(enablePaymentWithDifferentValue)) throw new ApiBacenPixViolationException("O campo 'cob.valor.modalidadeAlteracao' não respeita o _schema_.", "cob.valor.modalidadeAlteracao", params.valor.modalidadeAlteracao)
            }

            if (params.valor.containsKey("retirada")) throw new IllegalArgumentException("O campo 'cob.valor.retirada' não é suportado.")
        }

        containsKeyAndValidateInstanceOf(params, "chave", String)

        containsKeyAndValidateInstanceOf(params, "solicitacaoPagador", String)

        if (containsKeyAndValidateInstanceOf(params, "infoAdicionais", List)) {
            Boolean hasInvalidAdditionalInfo = params.infoAdicionais.any {
                if (!(it instanceof Map)) return true

                if (!containsKeyAndValidateInstanceOf(it, "nome", String)) return true
                if (!containsKeyAndValidateInstanceOf(it, "valor", String)) return true

                return false
            }

            if (hasInvalidAdditionalInfo) throw new ApiBacenPixViolationException("O campo 'infoAdicionais' não respeita o _schema_.", "infoAdicionais", params.infoAdicionais)
        }
    }

    private void validateListImmediate(Map params) {
        Date startDate = ApiBacenPixParser.parseDate(params.inicio)
        if (!startDate) throw new ApiBacenPixViolationException("O parâmetro 'inicio' não respeita o _schema_.", "inicio", params.inicio)

        Date endDate = ApiBacenPixParser.parseDate(params.fim)
        if (!endDate) throw new ApiBacenPixViolationException("O parâmetro 'fim' não respeita o _schema_.", "fim", params.fim)

        if (endDate < startDate) throw new ApiBacenPixViolationException("O timestamp representado pelo parâmetro 'fim' é anterior ao timestamp representado pelo parâmetro 'inicio'.", "inicio", params.inicio)

        if (params.cpf && params.cnpj) throw new ApiBacenPixViolationException("Ambos os parâmetros 'cpf' e 'cnpj' estão preenchidos.", "cpf", params.cpf)
        if (params.cpf && !CpfCnpjUtils.isCpf(params.cpf)) throw new ApiBacenPixViolationException("O parâmetro 'cpf' não respeita o _schema_.", "cpf", params.cpf)
        if (params.cnpj && !CpfCnpjUtils.isCnpj(params.cnpj)) throw new ApiBacenPixViolationException("O parâmetro 'cnpj' não respeita o _schema_.", "cnpj", params.cnpj)

        if (params.containsKey("locationPresente")) throw new IllegalArgumentException("O parâmetro 'locationPresente' não é suportado.")

        if (params.containsKey("status") && params.status == "REMOVIDO_PELO_PSP") throw new IllegalArgumentException("O status '${params.status}' não é suportado.")

        validatePagination(params)
    }
}
