package com.asaas.service.bankaccountinfo

import com.asaas.bankaccountinfo.BankAccountCantBeApprovedException
import com.asaas.bankaccountinfo.BankAccountInfoDenialReasonType
import com.asaas.bankaccountinfo.BaseBankAccount
import com.asaas.credittransferrequest.CreditTransferRequestStatus
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.bankaccountinfo.BankAccountInfoUpdateRequest
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.financialinstitution.FinancialInstitution
import com.asaas.status.Status
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class BankAccountInfoUpdateRequestService {

	def bankAccountInfoService
	def customerAlertNotificationService
	def messageService
	def springSecurityService
	def customerRegisterStatusService
	def customerInteractionService
	def treasureDataService

    public BaseBankAccount save(Long customerId, Map params) {
        Customer customer = Customer.get(customerId)
        Map parsedSaveParams = parseSaveParams(params)

		if (customer.multipleBankAccountsEnabled()) return bankAccountInfoService.saveWithMultipleAccounts(customer, parsedSaveParams)

        return saveWithUniqueAccount(customer, parsedSaveParams)
    }

    public void onCriticalActionAuthorization(CriticalAction action) {
        action.bankAccountInfoUpdateRequest.status = Status.PENDING
        customerRegisterStatusService.updateBankAccountInfoStatus(action.bankAccountInfoUpdateRequest.customer, Status.AWAITING_APPROVAL)

        if (bankAccountInfoService.canApproveAutomatically(action.bankAccountInfoUpdateRequest)) {
            approveAutomatically(action.bankAccountInfoUpdateRequest)
        }
    }

    public void onCriticalActionCancellation(CriticalAction action) {
        action.bankAccountInfoUpdateRequest.status = Status.CANCELLED
    }

    def list(Map params) {
        def list = BankAccountInfoUpdateRequest.createCriteria().list(max: params.max, offset: params.offset) {
            createAlias('customer', 'customer')

            if (params.containsKey("providerId")) {
                and { eq("customer.id", Long.parseLong(params.providerId)) }
            }

            if (params?.providerName) {
                or {
                    like("customer.name", "%" + params.description + "%")
                    like("customer.email", "%" + params.description + "%")
                }
            }

            if (params?.status) {
                and { eq("status", Status.valueOf(params.status)) }
            }

            order(params?.sort ?: "lastUpdated", params?.order ?: "desc")
        }

        return list
    }

    public BankAccountInfoUpdateRequest approve(BankAccountInfoUpdateRequest bankAccountInfoUpdateRequest, Map params) throws BankAccountCantBeApprovedException {
        if (bankAccountInfoUpdateRequest.customer.multipleBankAccountsEnabled()) throw new BankAccountCantBeApprovedException("A conta bancária não pode ser aprovada.")
        if (bankAccountInfoUpdateRequest.status != Status.PENDING) throw new BankAccountCantBeApprovedException("O BankAccountInfoUpdateRequest [${bankAccountInfoUpdateRequest.id}] não pode ser aprovado pois não encontra-se pendente.")

        BankAccountInfo bankAccountInfo = bankAccountInfoService.saveOrUpdateMainAccount(bankAccountInfoUpdateRequest.customer, bankAccountInfoUpdateRequest.properties)

        bankAccountInfoUpdateRequest.status = Status.APPROVED
        bankAccountInfoUpdateRequest.denialReason = ''
        bankAccountInfoUpdateRequest.user = springSecurityService.currentUser
        bankAccountInfoUpdateRequest.observations = params.observations
        bankAccountInfoUpdateRequest.save(flush: true, failOnError: true)

        customerRegisterStatusService.updateBankAccountInfoStatus(bankAccountInfoUpdateRequest.customer, Status.APPROVED)

        customerInteractionService.saveBankAccountApproval(bankAccountInfoUpdateRequest.customer.id, bankAccountInfoUpdateRequest.observations)

        if (!params.approvedAutomatically) {
            messageService.sendResultBankAccountInfoUpdateRequest(bankAccountInfoUpdateRequest)

            treasureDataService.track(bankAccountInfoUpdateRequest.customer, TreasureDataEventType.BANK_ACCOUNT_INFO_ANALYZED, [bankAccountInfoUpdateRequestId: bankAccountInfoUpdateRequest.id])
        }

        customerAlertNotificationService.notifyBankAccountAnalysisResult(bankAccountInfoUpdateRequest)

        return bankAccountInfoUpdateRequest
    }

    public BankAccountInfoUpdateRequest deny(params) {
        BankAccountInfoUpdateRequest bankAccountInfoUpdateRequest = BankAccountInfoUpdateRequest.get(params.long("id"))
        bankAccountInfoUpdateRequest.status = Status.DENIED
        bankAccountInfoUpdateRequest.denialReason = params.denialReason
        bankAccountInfoUpdateRequest.denialReasonType = BankAccountInfoDenialReasonType.convert(params.denialReasonType)
        bankAccountInfoUpdateRequest.user = springSecurityService.currentUser
        bankAccountInfoUpdateRequest.observations = params.observations
        bankAccountInfoUpdateRequest.bypassCpfCnpjValidator = true
        bankAccountInfoUpdateRequest.save(flush: true, failOnError: true)

        messageService.sendResultBankAccountInfoUpdateRequest(bankAccountInfoUpdateRequest)

        customerRegisterStatusService.updateBankAccountInfoStatus(bankAccountInfoUpdateRequest.customer, Status.REJECTED)

        customerInteractionService.saveBankAccountDenial(bankAccountInfoUpdateRequest.customer.id, bankAccountInfoUpdateRequest.observations, bankAccountInfoUpdateRequest.denialReason)

        treasureDataService.track(bankAccountInfoUpdateRequest.customer, TreasureDataEventType.BANK_ACCOUNT_INFO_ANALYZED, [bankAccountInfoUpdateRequestId: bankAccountInfoUpdateRequest.id])

        customerAlertNotificationService.notifyBankAccountAnalysisResult(bankAccountInfoUpdateRequest)

        return bankAccountInfoUpdateRequest
    }

    public BankAccountInfoUpdateRequest findPreviousBankAccountInfoUpdateRequest(BankAccountInfoUpdateRequest bankAccountInfoUpdateRequest) {
        return findLastApprovedForCustomer(bankAccountInfoUpdateRequest) ?: findLastForCustomer(bankAccountInfoUpdateRequest)
    }

    public Boolean shouldBankAccountInfoBeThirdPartyAccount(Customer customer, String bankAccountCpfCnpj) {
        if (customer.isMEI()) {
            return true
        }

        if (bankAccountCpfCnpj && customer.cpfCnpj && bankAccountCpfCnpj != customer.cpfCnpj) {
            return true
        }

        return false
    }

    private Map parseSaveParams(Map params) {
        if (params.ownerBirthDate) params.ownerBirthDate = params.ownerBirthDate instanceof Date ? params.ownerBirthDate : CustomDateUtils.fromString(params.ownerBirthDate)
        if (params.account) params.account = params.account.trim()

        if (params.bank != null) {
            Long financialInstitutionId = FinancialInstitution.query([column: "id", bankId: Long.valueOf(params.bank)]).get()
            params.financialInstitution = financialInstitutionId
        } else if (params.financialInstitution != null) {
            Long bankId = FinancialInstitution.query([column: "bank.id", id: Long.valueOf(params.financialInstitution)]).get()
            params.bank = bankId
        }

        if (params.cpfCnpj) {
            params.cpfCnpj = Utils.removeNonNumeric(params.cpfCnpj)
        }

        return params
    }

    private BaseBankAccount saveWithUniqueAccount(Customer customer, Map params) {
        BankAccountInfo bankAccountInfoToBeUpdated = bankAccountInfoService.findFromCustomer(customer)

        Map parsedParams = bankAccountInfoService.parseParams(bankAccountInfoToBeUpdated?.id, customer.id, params)

        Map bankAccountInfoUpdateRequestParams = [:] << parsedParams
        bankAccountInfoUpdateRequestParams.remove("mainAccount")
        bankAccountInfoUpdateRequestParams.remove("accountName")
        if (!DomainUtils.attributesHaveChanged(bankAccountInfoToBeUpdated, bankAccountInfoUpdateRequestParams)) {
            return bankAccountInfoToBeUpdated
        }

        BankAccountInfo bankAccountInfo = validateSaveOrUpdateParams(customer.id, parsedParams)
        if (bankAccountInfo.hasErrors()) return bankAccountInfo

        Boolean hasApprovedUpdateRequest = hasApprovedUpdateRequest(customer)
        if (!hasApprovedUpdateRequest) {
            Boolean hasAuthorizedCreditTransferRequest = CreditTransferRequest.query([exists: true, provider: customer, statusList: CreditTransferRequestStatus.authorized()]).get().asBoolean()
            if (!hasAuthorizedCreditTransferRequest) {
                bankAccountInfo = bankAccountInfoService.saveOrUpdateMainAccount(customer, parsedParams)

                if (bankAccountInfo.hasErrors()) return bankAccountInfo
            }
        }

        cancelRequestsAwaitingActionAuthorization(customer)

        BankAccountInfoUpdateRequest bankAccountInfoUpdateRequest = BankAccountInfoUpdateRequest.findOrCreateWhere(customer: customer, status: Status.PENDING, deleted: false)
        bankAccountInfoUpdateRequest.properties = parsedParams
        bankAccountInfoUpdateRequest.ownerBirthDate = parsedParams.ownerBirthDate ?: null
        bankAccountInfoUpdateRequest.status = Status.PENDING
        bankAccountInfoUpdateRequest.thirdPartyAccount = Boolean.valueOf(parsedParams.thirdPartyAccount)

        bankAccountInfoUpdateRequest.save(flush: true)

        if (bankAccountInfoUpdateRequest.hasErrors()) return bankAccountInfoUpdateRequest

        Boolean criticalActionConfigBankAccountUpdate = CustomerCriticalActionConfig.query([column: "bankAccountUpdate", customerId: customer.id]).get()
        if (hasApprovedUpdateRequest && criticalActionConfigBankAccountUpdate) {
            bankAccountInfoUpdateRequest.status = Status.AWAITING_ACTION_AUTHORIZATION
            CriticalAction.saveBankAccountUpdate(bankAccountInfoUpdateRequest)
        } else {
            customerRegisterStatusService.updateBankAccountInfoStatus(customer, Status.AWAITING_APPROVAL)
        }

        if (!bankAccountInfoUpdateRequest.status.isAwaitingActionAuthorization() && bankAccountInfoService.canApproveAutomatically(bankAccountInfoUpdateRequest)) {
            approveAutomatically(bankAccountInfoUpdateRequest)
        }

        return bankAccountInfoUpdateRequest
    }

    private void cancelRequestsAwaitingActionAuthorization(Customer customer) {
    	for (BankAccountInfoUpdateRequest request in BankAccountInfoUpdateRequest.query([customer: customer, status: Status.AWAITING_ACTION_AUTHORIZATION]).list()) {
    		request.status = Status.CANCELLED
			request.bypassCpfCnpjValidator = true
    		request.save(flush: true, failOnError: true)

            CriticalAction.deleteNotAuthorized(request)
    	}
    }

	private Boolean hasApprovedUpdateRequest(Customer customer) {
		return BankAccountInfoUpdateRequest.approved([customer: customer]).count() > 0
	}

	private BankAccountInfo validateSaveOrUpdateParams(Long customerId, params) {
		Customer customer = Customer.get(customerId)

		BankAccountInfo bankAccountInfo = bankAccountInfoService.validateSaveOrUpdateParams(customer, null, params)
        if (bankAccountInfo.hasErrors()) return bankAccountInfo

		if (!customer.cpfCnpj) {
			DomainUtils.addError(bankAccountInfo, Utils.getMessageProperty("bankAccountInfo.validate.save.fillCommercialInfoBefore"))
		}

		if (BankAccountInfo.alreadyInUseAsMainAccountByAnotherCustomer(customerId, params.agency, params.account, Long.valueOf(params.financialInstitution)) && !CustomerParameter.getValue(customer, CustomerParameterName.ALLOW_DUPLICATE_MAIN_BANK_ACCOUNT)) {
			DomainUtils.addError(bankAccountInfo, "Conta bancária informada já está em uso por outro cliente")
		}

		return bankAccountInfo
	}

	private void approveAutomatically(BankAccountInfoUpdateRequest bankAccountInfoUpdateRequest) {
        String observations = Utils.getMessageProperty("system.automaticApproval.description")
		approve(bankAccountInfoUpdateRequest, [observations: observations, approvedAutomatically: true])
	}

	private BankAccountInfoUpdateRequest findLastApprovedForCustomer(BankAccountInfoUpdateRequest bankAccountInfoUpdateRequest) {
		Map namedParams = [bankAccountInfoUpdateRequestId: bankAccountInfoUpdateRequest.id, customer: bankAccountInfoUpdateRequest.customer, approved: Status.APPROVED]

		return BankAccountInfoUpdateRequest.executeQuery("""from BankAccountInfoUpdateRequest
						                                   where id = (select max(id)
						                                                 from BankAccountInfoUpdateRequest
						                                                where id < :bankAccountInfoUpdateRequestId
						                                                  and customer = :customer
						                                                  and status = :approved)""", namedParams)[0]
	}

	private BankAccountInfoUpdateRequest findLastForCustomer(BankAccountInfoUpdateRequest bankAccountInfoUpdateRequest) {
		Map namedParams = [bankAccountInfoUpdateRequestId: bankAccountInfoUpdateRequest.id, customer: bankAccountInfoUpdateRequest.customer]

		return BankAccountInfoUpdateRequest.executeQuery("""from BankAccountInfoUpdateRequest
						                                   where id = (select max(id)
						                                                 from BankAccountInfoUpdateRequest
						                                                where id < :bankAccountInfoUpdateRequestId
						                                                  and customer = :customer)""", namedParams)[0]
	}
}
