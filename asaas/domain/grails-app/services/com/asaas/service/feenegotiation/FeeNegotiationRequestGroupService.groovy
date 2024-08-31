package com.asaas.service.feenegotiation

import com.asaas.domain.feenegotiation.FeeNegotiationRequest
import com.asaas.domain.feenegotiation.FeeNegotiationRequestGroup
import com.asaas.domain.feenegotiation.FeeNegotiationRequestItem
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class FeeNegotiationRequestGroupService {

    def feeNegotiationRequestItemService

    public FeeNegotiationRequestGroup save(FeeNegotiationRequest request, FeeNegotiationProduct product, FeeNegotiationReplicationType replicationType) {
        FeeNegotiationRequestGroup validatedDomain = validateSave(request, product, replicationType)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        FeeNegotiationRequestGroup requestGroup = new FeeNegotiationRequestGroup()

        requestGroup.feeNegotiationRequest = request
        requestGroup.product = product
        requestGroup.replicationType = replicationType
        requestGroup.save(failOnError: true)

        return requestGroup
    }

    public void delete(Long id) {
        FeeNegotiationRequestGroup feeNegotiationRequestGroup = FeeNegotiationRequestGroup.get(id)

        List<Long> feeNegotiationRequestItemIdList = FeeNegotiationRequestItem.query([column: "id", feeNegotiationRequestGroupId: feeNegotiationRequestGroup.id]).list()

        for (Long feeNegotiationRequestItemId : feeNegotiationRequestItemIdList) {
            feeNegotiationRequestItemService.delete(feeNegotiationRequestItemId)
        }

        feeNegotiationRequestGroup.deleted = true
        feeNegotiationRequestGroup.save(failOnError: true)
    }

    private FeeNegotiationRequestGroup validateSave(FeeNegotiationRequest request, FeeNegotiationProduct product, FeeNegotiationReplicationType replicationType) {
        FeeNegotiationRequestGroup validatedDomain = new FeeNegotiationRequestGroup()

        if (!request) DomainUtils.addError(validatedDomain, "A solicitação de negociação de taxa não existe")

        if (!product) DomainUtils.addError(validatedDomain, "É necessário informar o produto")

        if (!replicationType) DomainUtils.addError(validatedDomain, "É necessário informar o tipo de replicação")

        return validatedDomain
    }
}
