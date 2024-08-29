package com.asaas.service.pix.infraction

import com.asaas.domain.customer.Customer
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
class PixAsaasInfractionService {

    def pixInfractionManagerService

    public Map save(SaveInfractionAdapter saveInfractionAdapter) {
        BusinessValidation validateSave = validateSave(saveInfractionAdapter)
        if (!validateSave.isValid()) throw new BusinessException(validateSave.getFirstErrorMessage())

        return pixInfractionManagerService.save(saveInfractionAdapter)
    }

    public void cancel(Long id, Long customerId) {
        if (!customerId) throw new BusinessException(Utils.getMessageProperty("pixAsaasInfraction.cancel.error.customer.notInformed"))

        if (!id) throw new BusinessException(Utils.getMessageProperty("pixAsaasInfraction.cancel.error.infraction.notInformed"))

        pixInfractionManagerService.cancel(id, PixInfractionCancellationRequester.CUSTOMER, customerId)
    }

    public BusinessValidation canBeCreatedForPixTransaction(PixTransaction pixTransaction) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (isReceivedWithAsaasQrCode(pixTransaction)) {
            validatedBusiness.addError("pixAsaasInfraction.error.cannotCreateInfractionForPixTransaction")
            return validatedBusiness
        }

        Map response = pixInfractionManagerService.validate(pixTransaction, PixInfractionOpeningRequester.CUSTOMER)
        if (!response.success) {
            validatedBusiness.addError("pixAsaasInfraction.error.cannotCreateInfractionForPixTransaction")
            return validatedBusiness
        }

        return validatedBusiness
    }

    public InfractionAdapter get(Long id, Long customerId) {
        if (!customerId) throw new BusinessException(Utils.getMessageProperty("pixAsaasInfraction.error.get.customerNotInformed"))

        if (!id) return null

        return pixInfractionManagerService.get(id, customerId)
    }

    public Map getInfoIfNecessary(PixTransaction pixTransaction, Customer customer) {
        if (!pixTransaction) return [:]

        if (!pixTransaction.status.isDone()) return [:]

        BusinessValidation canCreateInfractionValidation = canBeCreatedForPixTransaction(pixTransaction)

        if (!canCreateInfractionValidation.isValid()) {
            try {
                InfractionAdapter infractionAdapter = getByPixTransactionId(pixTransaction.id, customer.id)
                if (infractionAdapter) return [hasInfraction: true, infractionId: infractionAdapter.id]
            } catch (BusinessException exception) {
                return [canCreateInfraction: false, cannotCreateInfractionReason: exception.message]
            } catch (Exception ignored) {
                return [canCreateInfraction: false, cannotCreateInfractionReason: Utils.getMessageProperty("unknow.error")]
            }
        }

        return [canCreateInfraction: canCreateInfractionValidation.isValid(), cannotCreateInfractionReason: canCreateInfractionValidation.getFirstErrorMessage()]
    }

    public Boolean isReceivedWithAsaasQrCode(PixTransaction pixTransaction) {
        if (pixTransaction.receivedWithAsaasQrCode) return true

        if (pixTransaction.type.isRefund() && pixTransaction.getRefundedTransaction()?.receivedWithAsaasQrCode) return true

        return false
    }

    public BusinessValidation validateMandatoryFieldsForSave(SaveInfractionAdapter saveInfractionAdapter) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!saveInfractionAdapter.pixTransaction) {
            businessValidation.addError("pixAsaasInfraction.error.validateMandatoryFieldsForSave.pixTransactionNotInformed")
            return businessValidation
        }

        if (!saveInfractionAdapter.type) {
            businessValidation.addError("pixAsaasInfraction.error.validateMandatoryFieldsForSave.typeNotInformed")
            return businessValidation
        }

        if (!saveInfractionAdapter.openingReason) {
            businessValidation.addError("pixAsaasInfraction.error.validateMandatoryFieldsForSave.openingReasonNotInformed")
            return businessValidation
        }

        if (!saveInfractionAdapter.description) {
            businessValidation.addError("pixAsaasInfraction.error.validateMandatoryFieldsForSave.descriptionNotInformed")
            return businessValidation
        }

        if (!saveInfractionAdapter.openingRequester) {
            businessValidation.addError("pixAsaasInfraction.error.validateMandatoryFieldsForSave.openingRequesterNotInformed")
            return businessValidation
        }

        return businessValidation
    }

    private InfractionAdapter getByPixTransactionId(Long pixTransactionId, Long customerId) {
        if (!customerId) throw new BusinessException(Utils.getMessageProperty("pixAsaasInfraction.error.get.customerNotInformed"))

        PixTransaction pixTransaction = PixTransaction.query([id: pixTransactionId, customerId: customerId]).get()
        if (!pixTransaction) throw new BusinessException(Utils.getMessageProperty("pixAsaasInfraction.error.get.transactionNotFound"))

        return pixInfractionManagerService.getByPixTransaction(pixTransaction, customerId)
    }

    private BusinessValidation validateSave(SaveInfractionAdapter saveInfractionAdapter) {
        BusinessValidation businessValidation = validateMandatoryFieldsForSave(saveInfractionAdapter)
        if (!businessValidation.isValid()) return businessValidation

        if (!saveInfractionAdapter.openingRequester.isCustomer()) {
            businessValidation.addError("pixAsaasInfraction.error.validateSave.invalidOpeningRequester")
            return businessValidation
        }

        if (!saveInfractionAdapter.type.isRefundRequest()) {
            businessValidation.addError("pixAsaasInfraction.error.validateSave.invalidType")
            return businessValidation
        }

        if (saveInfractionAdapter.openingReason.isUnknown()) {
            businessValidation.addError("pixAsaasInfraction.error.validateSave.invalidOpeningReason")
            return businessValidation
        }

        return businessValidation
    }
}
