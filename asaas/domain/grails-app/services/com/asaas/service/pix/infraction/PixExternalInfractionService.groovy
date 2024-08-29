package com.asaas.service.pix.infraction

import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.pix.adapter.infraction.ExternalInfractionAdapter
import com.asaas.pix.adapter.infraction.SaveExternalInfractionAnalysisAdapter
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PixExternalInfractionService {

    def fraudPreventionService
    def pixInfractionManagerService

    public void saveAnalysis(SaveExternalInfractionAnalysisAdapter analysisAdapter) {
        BusinessValidation validatedBusiness = validateSaveAnalysis(analysisAdapter)
        if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

        pixInfractionManagerService.saveAnalysis(analysisAdapter)

        if (analysisAdapter.fraudDetected) {
            String additionalInfo = Utils.getMessageProperty("customerInteraction.suspectedOfFraudFlaggedByExternalInfraction")
            fraudPreventionService.toggleSuspectedOfFraud(Customer.get(analysisAdapter.customerId), true, additionalInfo)
        }
    }

    public ExternalInfractionAdapter get(Long id) {
        Map infractionInfo = pixInfractionManagerService.getExternal(id)
        if (infractionInfo.success) return infractionInfo.infraction
        return null
    }

    public Map list(Map filters, Integer limit, Integer offset) {
        return pixInfractionManagerService.listExternal(filters, limit, offset, true)
    }

    public List<ExternalInfractionAdapter> listAll(Map filters) {
        return pixInfractionManagerService.listAllExternal(filters)
    }

    private BusinessValidation validateSaveAnalysis(SaveExternalInfractionAnalysisAdapter analysisAdapter) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!analysisAdapter.description) {
            validatedBusiness.addError("pixExternalInfraction.error.validateSaveAnalysis.description.notInformed")
            return validatedBusiness
        }

        if (analysisAdapter.fraudDetected && !analysisAdapter.typeFraudDetected) {
            validatedBusiness.addError("pixExternalInfraction.error.validateSaveAnalysis.typeFraudDetected.notInformed")
            return validatedBusiness
        }

        return validatedBusiness
    }

}
