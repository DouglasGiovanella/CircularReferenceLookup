package com.asaas.service.outgoingPhoneCall

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.integration.callcenter.outgoingphonecall.adapter.OutgoingPhoneCallAdapter
import com.asaas.integration.callcenter.phonecall.PhoneCallType
import com.asaas.integration.callcenter.phonecall.adapter.DeleteOutgoingPhoneCallAdapter
import com.asaas.integration.callcenter.phonecall.adapter.FinishIncomingCallAttendanceAdapter as FinishPhoneCallAttendanceAdapter
import com.asaas.integration.callcenter.supportattendance.adapter.SupportAttendanceAdapter
import com.asaas.userpermission.AdminUserPermissionUtils
import com.asaas.utils.OfficeHoursChecker
import com.asaas.utils.PhoneNumberUtils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class OutgoingPhoneCallService {

    def asyncActionService
    def callCenterOutgoingPhoneCallFormManagerService
    def callCenterOutgoingPhoneCallManagerService
    def customerAttendanceService
    def customerInteractionService
    def supportAttendanceProtocolService

    public void save(OutgoingPhoneCallAdapter outgoingPhoneCallAdapter, Customer customer) {
        if (!customer && outgoingPhoneCallAdapter.dialedPhoneNumber) customer = customerAttendanceService.findNotDisabledCustomer(null, outgoingPhoneCallAdapter.dialedPhoneNumber)

        BusinessValidation businessValidation = validateSave(outgoingPhoneCallAdapter, customer)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        callCenterOutgoingPhoneCallManagerService.save(outgoingPhoneCallAdapter)
    }

    public void scheduleBaseErpManualCall(OutgoingPhoneCallAdapter outgoingPhoneCallAdapter, Customer customer) {
        BusinessValidation businessValidation = validateScheduleBaseErpManualCall(outgoingPhoneCallAdapter, customer)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        save(outgoingPhoneCallAdapter, customer)
    }

    public void scheduleManuallyScheduledCall(OutgoingPhoneCallAdapter outgoingPhoneCallAdapter, Customer customer) {
        BusinessValidation businessValidation = validateScheduleManuallyScheduledCall(outgoingPhoneCallAdapter)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        save(outgoingPhoneCallAdapter, customer)

        if (customer) customerInteractionService.saveScheduledManuallyCall(outgoingPhoneCallAdapter, customer)
    }

    public void finishAttendance(FinishPhoneCallAttendanceAdapter finishPhoneCallAttendanceAdapter) {
        SupportAttendanceAdapter supportAttendanceAdapter = callCenterOutgoingPhoneCallFormManagerService.finishAttendance(finishPhoneCallAttendanceAdapter)

        saveCustomerInteractionIfNecessary(finishPhoneCallAttendanceAdapter, false)

        AccountManager accountManager = AccountManager.get(finishPhoneCallAttendanceAdapter.accountManagerId)
        supportAttendanceProtocolService.saveSendSupportAttendanceEmailAsyncActionIfPossible(accountManager, supportAttendanceAdapter)
    }

    public void callAgainWhenConnectionDropped(FinishPhoneCallAttendanceAdapter finishPhoneCallAttendanceAdapter) {
        saveCustomerInteractionIfNecessary(finishPhoneCallAttendanceAdapter, true)

        callCenterOutgoingPhoneCallFormManagerService.callAgainWhenConnectionDropped(finishPhoneCallAttendanceAdapter.phoneCallId)
    }

    public void deleteManually(DeleteOutgoingPhoneCallAdapter deleteOutgoingPhoneCallAdapter, User currentUser) {
        if (!canDeleteManually(deleteOutgoingPhoneCallAdapter, currentUser)) throw new BusinessException("A ligação não pode ser removida.")

        callCenterOutgoingPhoneCallManagerService.delete(deleteOutgoingPhoneCallAdapter.id)

        if (!deleteOutgoingPhoneCallAdapter.asaasCustomerId) return

        String observations = "Ligação do tipo '${deleteOutgoingPhoneCallAdapter.type.getLabel()}' foi removida.\nMotivo: ${deleteOutgoingPhoneCallAdapter.deletedReason}"
        Customer customer = Customer.read(deleteOutgoingPhoneCallAdapter.asaasCustomerId)

        customerInteractionService.save(customer, observations)
    }

    public Boolean canDeleteManually(DeleteOutgoingPhoneCallAdapter deleteOutgoingPhoneCallAdapter, User currentUser) {
        if (!deleteOutgoingPhoneCallAdapter.type.isDeletable()) return false

        if (!deleteOutgoingPhoneCallAdapter.status.isQueued()) return false

        if (!isDeletableCallFromUserTeam(currentUser, deleteOutgoingPhoneCallAdapter.type)) return false

        if (AdminUserPermissionUtils.deletePhoneCall(currentUser)) return true

        if (!isDeletableCallForUser(deleteOutgoingPhoneCallAdapter.createdByAsaasUserId, currentUser)) return false

        return true
    }

    public void cancelDebtRecoveryAsyncAction() {
        Map asyncActionData = asyncActionService.getPending(AsyncActionType.CANCEL_DEBT_RECOVERY_PHONE_CALL)
        if (!asyncActionData) return

        Long customerId = Long.valueOf(asyncActionData.customerId)
        Long asyncActionId = Long.valueOf(asyncActionData.asyncActionId)

        Boolean success = callCenterOutgoingPhoneCallManagerService.cancelDebtRecovery(customerId)

        if (success) {
            asyncActionService.delete(asyncActionId)
            return
        }

        asyncActionService.sendToReprocessIfPossible(asyncActionId)
    }

    private BusinessValidation validateSave(OutgoingPhoneCallAdapter saveAdapter, Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!saveAdapter.phoneCallType) {
            businessValidation.addError("Não é possível salvar ligações sem um tipo")
            return businessValidation
        }

        if (saveAdapter.phoneCallType.isIncomingPhoneCall()) {
            businessValidation.addError("Não é possível salvar ligações de entrada")
            return businessValidation
        }

        if (saveAdapter.phoneCallType.isManuallyScheduledOrBaseErpCall()) {
            BusinessValidation manuallyScheduledPhoneCallValidation = validateSaveManuallyScheduledPhoneCall(saveAdapter)
            businessValidation.addErrors(manuallyScheduledPhoneCallValidation.asaasErrors)
            return businessValidation
        }

        if (!customer) {
            businessValidation.addError("Para criar este tipo de ligação, informe qual o cliente")
            return businessValidation
        }

        Boolean customerHasWhiteLabel = CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL).asBoolean()
        if (customerHasWhiteLabel) {
            businessValidation.addError("Este cliente está marcado como White Label e só pode ser contatado via ligação agendada manualmente")
            return businessValidation
        }

        if (saveAdapter.phoneCallType.isDebtRecovery() && !saveAdapter.reason) {
            businessValidation.addError("Preencha o motivo da ligação")
            return businessValidation
        }

        if (saveAdapter.phoneNumberType?.isManual() && !saveAdapter.dialedPhoneNumber) {
            businessValidation.addError("Preencha o número a ser discado")
            return businessValidation
        }

        return businessValidation
    }

    private Boolean isDeletableCallFromUserTeam(User currentUser, PhoneCallType phoneCallType) {
        if (currentUser.belongsToCustomerExperienceTeam() && phoneCallType.isDeletableForCustomerExperienceTeam()) return true

        if (currentUser.belongsToDebtRecoveryAnalystTeam() && phoneCallType.isDeletableForDebtRecoveryAnalystTeam()) return true

        if (currentUser.belongsToBaseErpAttendanceTeam() && phoneCallType.isDeletableForBaseErpAttendanceTeam()) return true

        return false
    }

    private Boolean isDeletableCallForUser(Long createdByUserId, User currentUser) {
        Boolean isPhoneCallCreatedByCurrentUser = createdByUserId == currentUser.id
        if (isPhoneCallCreatedByCurrentUser) return true

        return false
    }

    private void saveCustomerInteractionIfNecessary(FinishPhoneCallAttendanceAdapter finishPhoneCallAttendanceAdapter, Boolean isConnectionDropped) {
        if (!finishPhoneCallAttendanceAdapter.asaasCustomerId) return

        String description = "Realizado contato na ligação do tipo \"${finishPhoneCallAttendanceAdapter.phoneCallType.getLabel()}\""

        if (finishPhoneCallAttendanceAdapter.reason) description += "\nMotivo: ${finishPhoneCallAttendanceAdapter.reason}"
        if (finishPhoneCallAttendanceAdapter.subject) description += "\nAssunto: ${finishPhoneCallAttendanceAdapter.subject.getLabel()}"
        if (isConnectionDropped) description += "\nA ligação caiu."

        description += "\nObservações: ${finishPhoneCallAttendanceAdapter.observations ?: 'Nenhuma'}."

        Customer customer = Customer.read(finishPhoneCallAttendanceAdapter.asaasCustomerId)
        User user = User.read(finishPhoneCallAttendanceAdapter.asaasUserId)
        customerInteractionService.save(customer, description, user)
    }

    private BusinessValidation validateSaveManuallyScheduledPhoneCall(OutgoingPhoneCallAdapter outgoingPhoneCallAdapter) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!outgoingPhoneCallAdapter.dialedPhoneNumber) {
            businessValidation.addError("default.null.message", ["telefone"])
            return businessValidation
        }

        if (!PhoneNumberUtils.validatePhoneOrMobilePhone(outgoingPhoneCallAdapter.dialedPhoneNumber)) {
            businessValidation.addError("invalid.phone")
            return businessValidation
        }

        BusinessValidation nextAttemptBusinessValidation = validateNextAttempt(outgoingPhoneCallAdapter)
        businessValidation.addErrors(nextAttemptBusinessValidation.asaasErrors)

        return businessValidation
    }

    private BusinessValidation validateScheduleManuallyScheduledCall(OutgoingPhoneCallAdapter outgoingPhoneCallAdapter) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!outgoingPhoneCallAdapter.contactAdapter.contactName) {
            businessValidation.addError("default.null.message", ["nome"])
            return businessValidation
        }

        if (!outgoingPhoneCallAdapter.subject) {
            businessValidation.addError("default.null.message", ["assunto"])
            return businessValidation
        }

        return businessValidation
    }

    private BusinessValidation validateScheduleBaseErpManualCall(OutgoingPhoneCallAdapter outgoingPhoneCallAdapter, Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!customer) {
            businessValidation.addError("Cliente não encontrado")
            return businessValidation
        }

        if (!outgoingPhoneCallAdapter.reason) {
            businessValidation.addError("default.null.message", ["motivo"])
            return businessValidation
        }

        if (outgoingPhoneCallAdapter.asaasAccountManagerId) {
            AccountManager accountManager = AccountManager.read(outgoingPhoneCallAdapter.asaasAccountManagerId)
            if (!accountManager.department?.isBaseErp()) {
                businessValidation.addError("accountManager.cannotAnswerPhoneCallType")
                return businessValidation
            }
        }

        return businessValidation
    }

    private BusinessValidation validateNextAttempt(OutgoingPhoneCallAdapter outgoingPhoneCallAdapter) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (outgoingPhoneCallAdapter.nextAttemptTime && !outgoingPhoneCallAdapter.nextAttemptDate) {
            businessValidation.addError("default.null.message", ["data da próxima ligação"])
            return businessValidation
        }

        if (outgoingPhoneCallAdapter.nextAttemptTime && outgoingPhoneCallAdapter.nextAttemptDate) {
            Boolean isValidDate = OfficeHoursChecker.isValidHourForOutgoingCalls(outgoingPhoneCallAdapter.nextAttempt)
            if (!isValidDate) {
                businessValidation.addError("invalid.outgoingPhoneCall.attendanceHours")
                return businessValidation
            }
        }

        return businessValidation
    }
}
