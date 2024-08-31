package com.asaas.service.api.pix.bacen

import com.asaas.exception.api.ApiBacenPixViolationException
import com.asaas.service.api.ApiBaseService
import com.asaas.utils.Utils

class ApiBacenPixBaseService extends ApiBaseService {

    public void validatePagination(Map params) {
        if (!containsKeyAndValidateInstanceOf(params, "paginacao", Map)) return

        if (params.paginacao.containsKey("paginaAtual")) {
            Integer currentPage = Utils.toInteger(params.paginacao.paginaAtual)
            if (currentPage < 0) throw new ApiBacenPixViolationException("O parâmetro paginacao.paginaAtual é negativo.", "paginacao.paginaAtual", params.paginacao.paginaAtual)
        }

        if (params.paginacao.containsKey("itensPorPagina")) {
            Integer itemsPerPage = Utils.toInteger(params.paginacao.itensPorPagina)
            if (itemsPerPage <= 0) throw new ApiBacenPixViolationException("O parâmetro paginacao.itensPorPagina é negativo.", "paginacao.itensPorPagina", params.paginacao.itensPorPagina)
        }
    }

    public Boolean containsKeyAndValidateInstanceOf(Map params, String key, Class clazz) {
        if (params.containsKey(key)) {
            def object = params[key]

            if (clazz.isInstance(object)) {
                return true
            }

            String objectType = clazz == Map ? "objeto" : "campo"

            throw new ApiBacenPixViolationException("O ${objectType} '${key}' não respeita o _schema_.", key, object)
        }

        return false
    }
}
