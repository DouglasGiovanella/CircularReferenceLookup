package com.asaas.service.receivableanticipation

import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationDocument
import com.asaas.exception.BusinessException
import com.asaas.receivableanticipation.ReceivableAnticipationDocumentType
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationDocumentAdminService {

    public void update(Long id, String accessKey, ReceivableAnticipationDocumentType type) {
        ReceivableAnticipationDocument anticipationDocument = ReceivableAnticipationDocument.get(id)

        validateUpdate(anticipationDocument, accessKey, type)

        anticipationDocument.accessKey = type.hasAccessKey() ? Utils.removeNonNumeric(accessKey) : null
        anticipationDocument.type = type
        anticipationDocument.save(failOnError: true)
    }

    public void validateDocumentListInfo(ReceivableAnticipation anticipation) {
        List<ReceivableAnticipationDocument> anticipationDocumentList = anticipation.listDocuments()

        if (anticipationDocumentList.any { !it.type }) {
            throw new BusinessException("Existem documentos sem a informação do seu tipo. Por favor verifique.")
        }

        if (anticipationDocumentList.every { it.type.isNotSendToVortx() }) {
            throw new BusinessException("Pelo menos um dos documentos informados deve ser enviado ao FIDC.")
        }

        for (ReceivableAnticipationDocument anticipationDocument : anticipationDocumentList) {
            if (anticipationDocument.type.hasAccessKey()) {
                if (!anticipationDocument.accessKey) throw new BusinessException("Existem documentos sem a informação da chave de acesso. Por favor verifique.")
            }
        }
    }

    private void validateUpdate(ReceivableAnticipationDocument anticipationDocument, String accessKey, ReceivableAnticipationDocumentType type) {
        if (!anticipationDocument.receivableAnticipation.status.isPending()) {
            throw new BusinessException("Não é possível informar a chave de acesso para uma antecipação que não esteja em análise.")
        }

        if (!type) {
            throw new BusinessException("É necessário informar o tipo do documento.")
        }

        if (type.hasAccessKey()) {
            String accessKeyWithoutFormat = Utils.removeNonNumeric(accessKey)
            if (!accessKeyWithoutFormat) {
                throw new BusinessException("É necessário informar a chave do documento.")
            }

            if (accessKeyWithoutFormat.length()
                    != ReceivableAnticipationDocument.constraints.accessKey.minSize) {
                throw new BusinessException("A chave de acesso informada é inválida.")
            }

            Integer accessKeyModelIndexStart = 20
            Integer accessKeyModelIndexEnd = 22
            List<String> modelsByDocumentType = findAccessKeyModelsByDocumentType(type)
            String accessKeyModel = accessKeyWithoutFormat.substring(accessKeyModelIndexStart, accessKeyModelIndexEnd)
            if (!modelsByDocumentType.contains(accessKeyModel)) {
                throw new BusinessException("A chave de acesso informada não condiz com o tipo de documento selecionado.")
            }
        }
    }

    private List<String> findAccessKeyModelsByDocumentType(ReceivableAnticipationDocumentType type) {
        if (type.isNFE()) {
            return [ReceivableAnticipationDocument.NFE_ACCESS_KEY_IDENTIFICATION_MODEL, ReceivableAnticipationDocument.NFCE_ACCESS_KEY_IDENTIFICATION_MODEL]
        }

        if (type.isCTE()) {
            return [ReceivableAnticipationDocument.CTE_ACCESS_KEY_IDENTIFICATION_MODEL]
        }

        throw new RuntimeException("ReceivableAnticipationDocumentAdminService.validateUpdate >> Não foi encontrado um modelo para o tipo de documento selecionado")
    }
}
