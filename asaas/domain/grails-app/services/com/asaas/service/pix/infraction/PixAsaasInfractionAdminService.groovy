package com.asaas.service.pix.infraction

import com.asaas.domain.pix.PixTransaction
import com.asaas.exception.BusinessException
import com.asaas.pix.PixInfractionCancellationRequester
import com.asaas.pix.PixInfractionOpeningRequester
import com.asaas.pix.adapter.infraction.InfractionAdapter
import com.asaas.pix.adapter.infraction.SaveInfractionAdapter
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PixAsaasInfractionAdminService {

    def pixAsaasInfractionService
    def pixInfractionManagerService

    public Map save(SaveInfractionAdapter saveInfractionAdapter) {
        BusinessValidation validateSave = validateSave(saveInfractionAdapter)
        if (!validateSave.isValid()) throw new BusinessException(validateSave.getFirstErrorMessage())

        return pixInfractionManagerService.save(saveInfractionAdapter)
    }

    public InfractionAdapter get(Long id) {
        if (!id) return null

        return pixInfractionManagerService.get(id, null)
    }

    public Map list(Map filters, Integer limit, Integer offset) {
        return pixInfractionManagerService.list(filters, limit, offset)
    }

    public BusinessValidation canBeCreatedForPixTransaction(PixTransaction pixTransaction) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (pixAsaasInfractionService.isReceivedWithAsaasQrCode(pixTransaction)) {
            validatedBusiness.addError("pixInfraction.create.error.receivedWithAsaasQrCode")
            return validatedBusiness
        }

        Map response = pixInfractionManagerService.validate(pixTransaction, PixInfractionOpeningRequester.ANALYST)
        if (!response.success) {
            validatedBusiness.addError("pixInfraction.create.error.default", [response.error])
            return validatedBusiness
        }

        return validatedBusiness
    }

    public void cancel(Long id) {
        if (!id) throw new BusinessException(Utils.getMessageProperty("pixAsaasInfraction.cancel.error.infraction.notInformed"))

        pixInfractionManagerService.cancel(id, PixInfractionCancellationRequester.ANALYST, null)
    }

    private BusinessValidation validateSave(SaveInfractionAdapter saveInfractionAdapter) {
        BusinessValidation businessValidation = pixAsaasInfractionService.validateMandatoryFieldsForSave(saveInfractionAdapter)
        if (!businessValidation.isValid()) return businessValidation

        if (!saveInfractionAdapter.openingRequester.isAnalyst()) {
            businessValidation.addError("pixAsaasInfractionAdmin.error.validateSave.invalidOpeningRequester")
            return businessValidation
        }

        return businessValidation
    }
}
