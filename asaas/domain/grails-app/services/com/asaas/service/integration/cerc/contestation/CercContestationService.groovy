package com.asaas.service.integration.cerc.contestation

import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.cerc.contestation.CercContestation
import com.asaas.integration.cerc.enums.company.CercSyncStatus
import com.asaas.integration.cerc.enums.contestation.CercContestationProgressStatus
import com.asaas.integration.cerc.enums.contestation.CercContestationReason
import com.asaas.integration.cerc.enums.contestation.CercContestationType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CercContestationService {

    def customerMessageService
    def grailsApplication

    public void processContestationWithDeadlineForReplyExceeded() {
        Date estimatedDateToReceiveReply = CustomDateUtils.todayMinusBusinessDays(CercContestation.BUSINESS_DAYS_TO_RECEIVE_A_REPLY).getTime().clearTime()
        List<Long> cercContestationIdList = CercContestation.query([column: "id", progressStatus: CercContestationProgressStatus.REQUESTED, "syncDate[lt]": estimatedDateToReceiveReply]).list(max: 500)

        for (Long id in cercContestationIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CercContestation cercContestation = CercContestation.get(id)
                cercContestation.progressStatus = CercContestationProgressStatus.DEADLINE_FOR_REPLY_EXCEEDED
                cercContestation.save(failOnError: true)

                List<Customer> customerList = Customer.query([cpfCnpj: cercContestation.customerOrHolderCpfCnpj]).list()
                for (Customer customer in customerList) {
                    customerMessageService.notifyAboutCercContestationDeadlineForReplyExceeded(customer, cercContestation)
                }
            }, [logErrorMessage: "CercContestationService.processContestationWithDeadlineForReplyExceeded >> Falha ao marcar contestação ${id} com status DEADLINE_FOR_REPLY_EXCEEDED"])
        }
    }

    public CercContestation saveFromCustomerRequest(String customerCpfCnpj, String beneficiaryCpfCnpj, String subjectIdentifier, String description, CercContestationType type, CercContestationReason reason) {
        CercContestation validatedCercContestation = validateSaveFromCustomerRequest(customerCpfCnpj, subjectIdentifier, type, description, reason)
        if (validatedCercContestation.hasErrors()) return validatedCercContestation

        CercContestation contestation = new CercContestation()
        contestation.customerOrHolderCpfCnpj = Utils.removeNonNumeric(customerCpfCnpj)
        contestation.contestantCpfCnpj = Utils.removeNonNumeric(beneficiaryCpfCnpj)
        contestation.description = description
        contestation.externalIdentifier = grailsApplication.config.asaas.cnpj.substring(1) + "-${UUID.randomUUID()}"
        contestation.subjectIdentifier = subjectIdentifier
        contestation.type = type
        contestation.reason = reason
        contestation.syncStatus = CercSyncStatus.AWAITING_SYNC
        contestation.progressStatus = CercContestationProgressStatus.REQUESTED
        contestation.save(failOnError: true)

        return contestation
    }

    private CercContestation validateSaveFromCustomerRequest(String customerCpfCnpj, String subjectIdentifier, CercContestationType type, String description, CercContestationReason reason) {
        CercContestation validatedCercContestation = new CercContestation()

        Boolean alreadyContested = CercContestation.query([exists: true, customerOrHolderCpfCnpj: customerCpfCnpj, subjectIdentifier: subjectIdentifier, type: type, "progressStatus[ne]": CercContestationProgressStatus.DONE]).get().asBoolean()
        if (alreadyContested) {
            DomainUtils.addError(validatedCercContestation, "Contestação já solicitada.")
        }

        if (!description) {
            DomainUtils.addError(validatedCercContestation, "É necessário descrever o motivo da contestação.")
        }

        if (description && (description.size() < CercContestation.MIN_SIZE_DESCRIPTION || description.size() > CercContestation.MAX_SIZE_DESCRIPTION)) {
            DomainUtils.addError(validatedCercContestation, "A descrição da situação da contestação deve ter no mínimo ${CercContestation.MIN_SIZE_DESCRIPTION} caracteres e no máximo ${CercContestation.MAX_SIZE_DESCRIPTION}.")
        }

        if (!reason) {
            DomainUtils.addError(validatedCercContestation, "É necessário informar o motivo da contestação.")
        }

        return validatedCercContestation
    }
}
