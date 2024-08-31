package com.asaas.service.credittransferrequest

import com.asaas.credittransferrequest.CreditTransferRequestStatus
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.blacklist.BlackList
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CreditTransferRequestAdminService {

    def transferService

    public void authorize(List<Long> idList) {
        Utils.forEachWithFlushSession(idList, 50, { Long id ->
            Utils.withNewTransactionAndRollbackOnError({
                CreditTransferRequest creditTransferRequest = authorize(CreditTransferRequest.get(id))
                if (creditTransferRequest.hasErrors()) throw new Exception(creditTransferRequest.errors.allErrors[0].defaultMessage)
            }, [logErrorMessage: "CreditTransfeRequestAdminService.authorize >> Falha ao autorizar a transferência [${id}]"])
        })
    }

    public CreditTransferRequest authorize(CreditTransferRequest creditTransferRequest) {
        BusinessValidation validatedBusiness = canAuthorize(creditTransferRequest)
        if (!validatedBusiness.isValid()) return DomainUtils.addError(new CreditTransferRequest(), validatedBusiness.getFirstErrorMessage())

        if (!creditTransferRequest.transfer.status.isPending()) transferService.setAsPending(creditTransferRequest.transfer)

        creditTransferRequest.status = CreditTransferRequestStatus.AUTHORIZED
        creditTransferRequest.lastStatusUpdate = new Date().clearTime()
        creditTransferRequest.save(flush: true, failOnError: true)
    }

    public void authorizeAutomaticallyIfPossible(CreditTransferRequest creditTransferRequest) {
        BusinessValidation validatedBusiness = canAuthorizeAutomatically(creditTransferRequest.provider)
        if (!validatedBusiness.isValid()) return

        authorize(creditTransferRequest)
    }

    public List<CreditTransferRequest> list(Map search) {
        final String otherBanksCode = "000"

        if (search.inBankCodes?.contains(otherBanksCode)) {
            List<String> supportedBankList = SupportedBank.values().collect { it.code() }
            search.notInBankCodes = supportedBankList.findAll { !search.inBankCodes.contains(it) }
            search.remove("inBankCodes")
        }

        return CreditTransferRequest.query(search).list(max: search.max, offset: search.offset)
    }

    public void updatePendingTransfersToBlocked(Customer customer) {
        List<Long> creditTransferRequestIdList = CreditTransferRequest.query([column: "id", provider: customer, statusList: CreditTransferRequestStatus.allowedToBeBlocked()]).list()

        for (Long creditTransferRequestId : creditTransferRequestIdList) {
            block(creditTransferRequestId)
        }
    }

    public void block(Long id) throws BusinessException {
        CreditTransferRequest creditTransferRequest = CreditTransferRequest.get(id)

        if (!CreditTransferRequestStatus.allowedToBeBlocked().contains(creditTransferRequest.status)) {
            AsaasLogger.error("CreditTransferRequestAdminService.block >> CreditTransferRequest não pode ser bloqueada. [id: ${creditTransferRequest.id}, status: ${creditTransferRequest.status}]")
            throw new BusinessException("Esta transferência não pode ser bloqueada.")
        }

        creditTransferRequest.status = CreditTransferRequestStatus.BLOCKED
        creditTransferRequest.lastStatusUpdate = new Date().clearTime()
        creditTransferRequest.save(flush: true, failOnError: true)

        transferService.setAsBlocked(creditTransferRequest.transfer)
    }

    private BusinessValidation canAuthorize(CreditTransferRequest creditTransferRequest) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        Boolean isScheduledProcessing = creditTransferRequest.scheduledDate == new Date().clearTime()
        Boolean isStatusAllowsAuthorize = CreditTransferRequestStatus.allowedToBeAuthorized().contains(creditTransferRequest.status)
        if (!isStatusAllowsAuthorize && !isScheduledProcessing) {
            validatedBusiness.addError("creditTransferRequest.isNotPending")
            return validatedBusiness
        }

        if (creditTransferRequest.awaitingCriticalActionAuthorization) {
            validatedBusiness.addError("awaitingCriticalActionAuthorization.label")
            return validatedBusiness
        }

        return validatedBusiness
    }

    private BusinessValidation canAuthorizeAutomatically(Customer customer) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (customer.accountDisabled()) {
            validatedBusiness.addError("customer.disabled")
            return validatedBusiness
        }

        if (customer.isBlocked) {
            validatedBusiness.addError("customer.isBlocked")
            return validatedBusiness
        }

        if (customer.suspectedOfFraud) {
            validatedBusiness.addError("customer.suspectedOfFraud")
            return validatedBusiness
        }

        if (BlackList.isInBlackList(customer)) {
            validatedBusiness.addError("customer.isInBlacklist")
            return validatedBusiness
        }

        if (CustomerParameter.getValue(customer, CustomerParameterName.CHECKOUT_DISABLED)) {
            validatedBusiness.addError("customer.hasCheckoutDisabled")
            return validatedBusiness
        }

        return validatedBusiness
    }
}
