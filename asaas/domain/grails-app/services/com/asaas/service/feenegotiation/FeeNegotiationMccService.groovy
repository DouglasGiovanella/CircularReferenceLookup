package com.asaas.service.feenegotiation

import com.asaas.domain.economicactivity.EconomicActivity
import com.asaas.domain.feenegotiation.FeeNegotiationMcc
import com.asaas.feenegotiation.FeeNegotiationMccRepository

import com.asaas.utils.DomainUtils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class FeeNegotiationMccService {

    public FeeNegotiationMcc findOrCreate(String mcc) {
        FeeNegotiationMcc feeNegotiationMcc = FeeNegotiationMccRepository.query(["mcc": mcc]).get()

        if (feeNegotiationMcc) return feeNegotiationMcc

        return save(mcc)
    }

    private FeeNegotiationMcc save(String mcc) {
        FeeNegotiationMcc validatedFeeNegotiationMcc = validateSave(mcc)
        if (validatedFeeNegotiationMcc.hasErrors()) throw new ValidationException(null, validatedFeeNegotiationMcc.errors)

        FeeNegotiationMcc feeNegotiationMcc = new FeeNegotiationMcc()
        feeNegotiationMcc.mcc = mcc
        feeNegotiationMcc.save(failOnError: true)

        return feeNegotiationMcc
    }

    private FeeNegotiationMcc validateSave(String mcc) {
        FeeNegotiationMcc validatedFeeNegotiationMcc = new FeeNegotiationMcc()
        Boolean mccExists = EconomicActivity.query([mcc: mcc, exists: true]).get()

        if (!mccExists) DomainUtils.addError(validatedFeeNegotiationMcc, "MCC [$mcc] não foi encontrado.")

        return validatedFeeNegotiationMcc
    }
}
