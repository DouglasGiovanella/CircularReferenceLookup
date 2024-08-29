package com.asaas.service.bankaccountinfo

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.bankaccountinfo.BankAccountCantBeApprovedException
import com.asaas.bankaccountinfo.BankAccountInfoDenialReasonType
import com.asaas.bankaccountinfo.BankAccountType
import com.asaas.criticalaction.CriticalActionType
import com.asaas.customer.CompanyType
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.bank.Bank
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.bankaccountinfo.BankAccountInfoUpdateRequest
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.financialinstitution.FinancialInstitution
import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimit
import com.asaas.domain.pix.PixTransactionDestinationKey
import com.asaas.domain.pix.PixTransactionExternalAccount
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.pix.PixAddressKeyType
import com.asaas.pix.PixTransactionType
import com.asaas.service.bankaccountinfo.parser.BankAccountInfoParser
import com.asaas.status.Status
import com.asaas.transfer.TransferDestinationBankAccountValidator
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.utils.DomainUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

import static grails.async.Promises.task

@Transactional
class BankAccountInfoService {

    def bankAccountInfoPixKeyService
    def customerInteractionService
    def customerRegisterStatusService
    def pixTransactionBankAccountInfoCheckoutLimitService
    def messageService
    def revenueServiceRegisterService
    def springSecurityService
    def treasureDataService

	public BankAccountInfo save(Customer customer, Map params) {
		BankAccountInfo validateBankAccountInfo = validateSaveParams(customer, params)
		if (validateBankAccountInfo.hasErrors()) return validateBankAccountInfo

        BankAccountInfo bankAccountInfo = new BankAccountInfo()
        bankAccountInfo.customer = customer
        bankAccountInfo.thirdPartyAccount = (params.thirdPartyAccount != null) ? Boolean.valueOf(params.thirdPartyAccount) : null
        bankAccountInfo.bank = Bank.read(params.bank)
        bankAccountInfo.financialInstitution = FinancialInstitution.read(params.financialInstitution)
        bankAccountInfo.bankAccountType = BankAccountType.convert(params.bankAccountType.toString())
        bankAccountInfo.agency = params.agency
        bankAccountInfo.agencyDigit = params.agencyDigit
        bankAccountInfo.account = params.account
        bankAccountInfo.accountDigit = params.accountDigit
        bankAccountInfo.accountName = params.accountName
        bankAccountInfo.name = params.name
        bankAccountInfo.cpfCnpj = params.cpfCnpj
        bankAccountInfo.ownerBirthDate = params.ownerBirthDate
        bankAccountInfo.responsiblePhone = params.responsiblePhone
        bankAccountInfo.responsibleEmail = params.responsibleEmail
        if (params.mainAccount) bankAccountInfo.mainAccount = params.mainAccount
        if (params.accountBeingUpdated?.mainAccount) bankAccountInfo.mainAccount = true

        bankAccountInfo.status = buildStatusOnSave(customer, params.bypassCriticalAction)
        if (bankAccountInfo.status.isAwaitingApproval()) bankAccountInfo.mainAccount = true

		bankAccountInfo.save(flush: true, failOnError: false)
        if (bankAccountInfo.hasErrors()) return bankAccountInfo

        if (bankAccountInfo.status.isAwaitingActionAuthorization()) {
            saveOrUpdateCriticalAction(bankAccountInfo, params.updateCriticalAction)
        } else {
            approveIfPossible(bankAccountInfo)
        }

		return bankAccountInfo
	}

	public BankAccountInfo saveOrUpdateMainAccount(Customer customer, Map params) {
		BankAccountInfo previousBankAccountInfo = BankAccountInfo.findWhere(customer: customer, deleted: false)

		if (previousBankAccountInfo) {
			setAsDeleted(previousBankAccountInfo)
		}

		BankAccountInfo bankAccountInfo = new BankAccountInfo([customer: customer] + params)
		bankAccountInfo.status = Status.APPROVED
		bankAccountInfo.mainAccount = true
		bankAccountInfo.save(flush: true, failOnError: false)

		return bankAccountInfo
	}

	public BankAccountInfo findFromCustomer(Customer customer) {
		return BankAccountInfo.findWhere(customer: customer, deleted: false)
	}

	public void onCriticalActionInsertAuthorization(CriticalAction action) {
		if (action.bankAccountInfo.mainAccount) {
			action.bankAccountInfo.status = Status.AWAITING_APPROVAL
		} else {
			action.bankAccountInfo.status = Status.APPROVED
		}

		action.bankAccountInfo.save(flush: true, failOnError: true)

        approveIfPossible(action.bankAccountInfo)
	}

	public void onCriticalActionUpdateAuthorization(CriticalAction action) {
		setAsDeleted(action.previousBankAccountInfo)

		onCriticalActionInsertAuthorization(action)
	}

	public void onCriticalActionUpdateCancellation(CriticalAction action) {
		setAsDeleted(action.bankAccountInfo)
	}

	public list(Map params) {
		def listOfBankAccountInfo = BankAccountInfo.createCriteria().list(max: params.max, offset: params.offset) {
			createAlias("customer", "customer")
			createAlias("bank", "bank")

            if (!Boolean.valueOf(params.includeDeleted)) {
                eq("deleted", false)
            }

            if (Boolean.valueOf(params.mainAccount)) {
                eq("mainAccount", Boolean.valueOf(params.mainAccount))
            }

			if (params.customerId) {
				eq("customer.id", Long.valueOf(params.customerId))
			}

			if (params.cpfCnpj) {
				like("cpfCnpj", "%" + params.cpfCnpj + "%")
			}

			if (params.search) {
				or {
					like("name", "%" + params.search + "%")
					like("accountName", "%" + params.search + "%")
					like("cpfCnpj", "%" + params.search + "%")
					like("agency", "%" + params.search + "%")
					like("account", "%" + params.search + "%")
					like("bank.code", "%" + params.search + "%")
					like("bank.name", "%" + params.search + "%")
				}
			}

			if (params.agency) {
				eq("agency", params.agency)
			}

			if (params.account) {
				eq("account", params.account)
			}

			if (params.customerName) {
				or {
					like("customer.name", "%" + params.customerName + "%")
					like("customer.company", "%" + params.customerName + "%")
					like("customer.email", "%" + params.customerName + "%")
				}
			}

			if (params.containsKey("thirdPartyAccount")) {
				eq("thirdPartyAccount", Boolean.valueOf(params.thirdPartyAccount))
			}

			if (params.status) {
				Status status
				if (params.status instanceof String) {
					status = Status.convert(params.status)
				} else if (params.status instanceof Status) {
					status = params.status
				}

				if (status) {
					eq("status", status)
				}
			}

			if (params.inStatus) {
				'in'("status", params.inStatus)
			}

			if (params.sortBy) {
				order(params.sortBy, params.order ?: "asc")
			}
		}

		return listOfBankAccountInfo
	}

	public void approve(BankAccountInfo bankAccountInfo, Map params) throws BankAccountCantBeApprovedException {
		if (bankAccountInfo.status != Status.AWAITING_APPROVAL) throw new BankAccountCantBeApprovedException("A conta bancária não pode ser aprovada, pois não está aguardando aprovação")

		bankAccountInfo.status = Status.APPROVED
		bankAccountInfo.denialReason = ""
		bankAccountInfo.user = springSecurityService.currentUser
		bankAccountInfo.observations = params.observations
		bankAccountInfo.save(flush: true, failOnError: true)

		updateCustomerRegisterStatus(bankAccountInfo.customer)

		customerInteractionService.saveBankAccountApproval(bankAccountInfo.customer.id, bankAccountInfo.observations)

		if (!params.approvedAutomatically) {
			messageService.sendResultBankAccountInfoUpdateRequest(bankAccountInfo)

			treasureDataService.track(bankAccountInfo.customer, TreasureDataEventType.BANK_ACCOUNT_INFO_ANALYZED, [bankAccountInfoId: bankAccountInfo.id])
		}
	}

    public void reject(BankAccountInfo bankAccountInfo, Map params) {
        bankAccountInfo.status = Status.REJECTED
        bankAccountInfo.denialReason = params.denialReason
        bankAccountInfo.denialReasonType = BankAccountInfoDenialReasonType.convert(params.denialReasonType)
        bankAccountInfo.user = springSecurityService.currentUser
        bankAccountInfo.observations = params.observations
        bankAccountInfo.save(flush: true, failOnError: true)

        updateCustomerRegisterStatus(bankAccountInfo.customer)

        customerInteractionService.saveBankAccountDenial(bankAccountInfo.customer.id, bankAccountInfo.observations, bankAccountInfo.denialReason)

        messageService.sendResultBankAccountInfoUpdateRequest(bankAccountInfo)

        treasureDataService.track(bankAccountInfo.customer, TreasureDataEventType.BANK_ACCOUNT_INFO_ANALYZED, [bankAccountInfoId: bankAccountInfo.id])
    }

    public void onCriticalActionDeleteAuthorization(CriticalAction action) {
        setAsDeleted(action.bankAccountInfo)
    }

    public Object deleteByCustomer(Long bankAccountId, Long customerId) {
        BankAccountInfo bankAccountInfo = BankAccountInfo.find(bankAccountId, customerId)

        if (bankAccountInfo.mainAccount && bankAccountInfo.status != Status.AWAITING_ACTION_AUTHORIZATION) throw new RuntimeException("Não é possível excluir a conta principal [${bankAccountInfo.id}].")

        if (bankAccountInfo.status == Status.AWAITING_ACTION_AUTHORIZATION) return executeDeletion(bankAccountInfo)

        Boolean criticalActionConfigBankAccountUpdate = CustomerCriticalActionConfig.query([column: "bankAccountUpdate", customerId: bankAccountInfo.customerId]).get()
        if (criticalActionConfigBankAccountUpdate && Payment.received([customer: bankAccountInfo.customer]).count() > 0) {
            CriticalAction criticalAction = CriticalAction.pendingOrAwaitingAuthorization([customer: bankAccountInfo.customer, type: CriticalActionType.BANK_ACCOUNT_DELETE, object: bankAccountInfo]).get()
            if (criticalAction) return criticalAction

            return CriticalAction.saveBankAccountDelete(bankAccountInfo)
        } else {
            return executeDeletion(bankAccountInfo)
        }
    }

    public BankAccountInfo executeDeletion(BankAccountInfo bankAccountInfo) {
        if (bankAccountInfo.mainAccount && bankAccountInfo.status != Status.AWAITING_ACTION_AUTHORIZATION) throw new RuntimeException("Não é possível excluir a conta principal [${bankAccountInfo.id}].")

        setAsDeleted(bankAccountInfo)

        CriticalAction.deleteNotAuthorized(bankAccountInfo)

        updateCustomerRegisterStatus(bankAccountInfo.customer)

        return bankAccountInfo
    }

    public BankAccountInfo validateSaveOrUpdateParams(Customer customer, Long bankAccountInfoId, Map params) {
        BankAccountInfo bankAccountInfo = new BankAccountInfo()
        BankAccountType bankAccountType
        FinancialInstitution financialInstitution

        BusinessValidation bankAccountChangeDisabledBusinessValidation = TransferDestinationBankAccountValidator.validateBankAccountChangeDisabled(customer)
        if (!bankAccountChangeDisabledBusinessValidation.isValid()) {
            DomainUtils.addError(bankAccountInfo, bankAccountChangeDisabledBusinessValidation.getFirstErrorMessage())
        }

        Boolean isBankAccountUpdate = bankAccountInfoId
        Boolean isAccountDisabled = customer.accountDisabled()

        if (isBankAccountUpdate && isAccountDisabled) {
            DomainUtils.addError(bankAccountInfo, Utils.getMessageProperty("bankAccountInfo.deny.message"))
        }

        Boolean ignoreCustomerStatus = CustomerParameter.getValue(customer, CustomerParameterName.IGNORE_PROVIDER_REGISTER_STATUS_ON_NEXT_TRANSFER)
        if (isAccountDisabled && !ignoreCustomerStatus) {
            DomainUtils.addError(bankAccountInfo, Utils.getMessageProperty("bankAccountInfo.deny.message"))
        }

        if (!params.financialInstitution) {
            DomainUtils.addError(bankAccountInfo, "Informe o banco.")
        } else {
            financialInstitution = FinancialInstitution.get(Long.valueOf(params.financialInstitution))
            if (!financialInstitution) DomainUtils.addError(bankAccountInfo, "Instituição financeira informada é inválida.")
        }

        if (!Utils.emailIsValid(params.responsibleEmail)) {
            DomainUtils.addError(bankAccountInfo, "O email informado é inválido.")
        }

        if (Utils.isEmptyOrNull(params.bankAccountType)) {
            DomainUtils.addError(bankAccountInfo, "Informe o tipo de conta.")
        } else {
            bankAccountType = BankAccountType.convert(params.bankAccountType.toString())
            if (!bankAccountType) DomainUtils.addError(bankAccountInfo, "Tipo de conta informado é inválido.")
        }

        if (bankAccountType && financialInstitution) {
            if (!BankAccountType.getAllowsTEDList().contains(bankAccountType) && !financialInstitution.paymentServiceProvider) DomainUtils.addError(bankAccountInfo, "O banco informado não suporta o tipo de conta informado.")

            if (!BankAccountInfo.canSaveBankAccountWithoutBank(customer)) {
                if (!financialInstitution.bank) DomainUtils.addError(bankAccountInfo, "Informe uma instituição financeira que permita transferências TED.")
            }
        }

        if (Boolean.valueOf(params.thirdPartyAccount) && params.responsiblePhone && !PhoneNumberUtils.validateMobilePhone(params.responsiblePhone)) {
            DomainUtils.addError(bankAccountInfo, "O número de celular informado é inválido.")
        }

        if (params.ownerBirthDate && !Customer.olderThanMinimumRequired(params.ownerBirthDate)) {
            DomainUtils.addError(bankAccountInfo, Utils.getMessageProperty("bankAccountOwner.youngerThanMinimumRequired"))
        }

        if (customer.isMEI() && !Boolean.valueOf(params.thirdPartyAccount)) {
            DomainUtils.addError(bankAccountInfo, "Contas bancárias MEI devem ser de terceiros.")
        }

        BusinessValidation businessValidation = TransferDestinationBankAccountValidator.validateFields(params.agency, params.account)
        bankAccountInfo = DomainUtils.copyAllErrorsFromBusinessValidation(businessValidation, bankAccountInfo)

        return bankAccountInfo
    }

    public BankAccountInfo saveWithMultipleAccounts(Customer customer, Map params) {
        if (params.bankAccountInfoId) {
            return update(customer.id, Long.valueOf(params.bankAccountInfoId), params)
        } else {
            return save(customer, params)
        }
    }

    public Map parseParams(Long bankAccountInfoId, Long customerId, Map params) {
        if (params.responsiblePhone) {
            if (PhoneNumberUtils.isObfuscated(params.responsiblePhone.toString())) {
                if (!bankAccountInfoId) {
                    throw new BusinessException("Não é possível salvar responsiblePhone ofuscado para uma nova conta bancária.")
                }
                String responsiblePhone = BankAccountInfo.query([column: "responsiblePhone", id: bankAccountInfoId, customerId: customerId]).get()
                params.responsiblePhone = responsiblePhone
            }
        }
        return params
    }

    public void deleteAllCustomerBankAccountInfo(Customer customer) {
        List<BankAccountInfo> bankAccountInfoList = BankAccountInfo.query([customer: customer]).list()
        for (BankAccountInfo bankAccountInfo : bankAccountInfoList) {
            setAsDeleted(bankAccountInfo)
        }
    }

    public void changeForThirdPartyAccountIfNecessary(CustomerUpdateRequest customerUpdateRequest) {
        if (customerUpdateRequest.provider.companyType == CompanyType.INDIVIDUAL && customerUpdateRequest.companyType == CompanyType.MEI) {
            BankAccountInfo bankAccountInfo = BankAccountInfo.findMainAccount(customerUpdateRequest.provider.id)

            if (bankAccountInfo && bankAccountInfo.cpfCnpj == customerUpdateRequest.provider.cpfCnpj && !bankAccountInfo.thirdPartyAccount) {
                bankAccountInfo.thirdPartyAccount = true
                bankAccountInfo.responsiblePhone = customerUpdateRequest.provider.mobilePhone

                bankAccountInfo.save(failOnError: true)
            }
        }
    }

    public Boolean canApproveAutomatically(def bankAccountInfo) {
        if (bankAccountInfo.status == Status.APPROVED) return false

        if (bankAccountInfo.customer.hasAccountAutoApprovalEnabled()) return true

        if (bankAccountInfo.customer.bankAccountInfoApprovalIsNotRequired()) return true

        if (bankAccountInfo.customer.cpfCnpj == bankAccountInfo.cpfCnpj) return true

        if (bankAccountInfo.customer.hasConfirmedTransfer()) return true

        if (bankAccountInfo.belongsToLegalPerson()) return false

        return true
    }

    public void deleteSecondaryAccountsIfExists(Customer customer) {
        List<BankAccountInfo> listOfBankAccountInfo = BankAccountInfo.query([customer: customer, mainAccount: false]).list()

        for (BankAccountInfo secondaryAccount : listOfBankAccountInfo) {
            executeDeletion(secondaryAccount)
        }
    }

    public void changeToThirdyPartyAccountAndSendToApproval(Customer customer) {
        changeMainAccountToAwaitingApprovalIfNecessary(customer)
        changeBankAccountsWithSameCpfCnpjToThirdPartyAccount(customer)
    }

    public void createAsyncFromPixTransactionWithNewTransactionIfPossible(PixTransactionType type, Customer customer, PixTransactionExternalAccount pixTransactionExternalAccount, PixTransactionDestinationKey pixTransactionDestinationKey) {
        String pixKey = pixTransactionDestinationKey?.pixKey
        PixAddressKeyType pixAddressKeyType = pixTransactionDestinationKey?.pixAddressKeyType

        task {
            Utils.withNewTransactionAndRollbackOnError({
                if (!(type.isDebit() && customer.multipleBankAccountsEnabled() && pixTransactionExternalAccount)) return

                FinancialInstitution financialInstitution = FinancialInstitution.query([paymentServiceProviderIspb: PixUtils.parseIspb(pixTransactionExternalAccount.ispb)]).get()
                if (!financialInstitution) return

                Map bankAccountInfoSaveParams = BankAccountInfoParser.parseSaveParams(customer, pixTransactionExternalAccount, financialInstitution)
                BankAccountInfo bankAccountInfo = save(customer, bankAccountInfoSaveParams)

                if (bankAccountInfo.hasErrors()) return

                if (pixKey) bankAccountInfoPixKeyService.save(bankAccountInfo, pixKey, pixAddressKeyType)
            }, [logErrorMessage: "bankAccountInfoService.createAsyncFromPixTransactionWithNewTransactionIfPossible >> Erro ao salvar conta bancária. [pixTransactionExternalAccount.id: ${pixTransactionExternalAccount.id}]"])
        }
    }

    public Boolean customerCanSaveBankAccountInfoFromPixTransaction(Customer customer) {
        if (!customer.multipleBankAccountsEnabled()) return false
        if (CustomerParameter.getValue(customer, CustomerParameterName.DISABLE_BANK_ACCOUNT_CHANGE)) return false

        return true
    }

    public BankAccountInfo update(Long customerId, Long bankAccountInfoId, Map params) {
        Customer customer = Customer.get(customerId)

        Map parsedParams = parseParams(bankAccountInfoId, customerId, params)

        BankAccountInfo validatedBankAccountInfo = validateUpdateParams(customer, bankAccountInfoId, parsedParams)
        if (validatedBankAccountInfo.hasErrors()) return validatedBankAccountInfo

        BankAccountInfo bankAccountInfo = BankAccountInfo.find(bankAccountInfoId, customerId)

        if (bankAccountInfo.status == Status.AWAITING_ACTION_AUTHORIZATION) throw new RuntimeException("Não é possível atualizar a conta [${bankAccountInfoId}] pois ela está aguardando autorização.")

        CriticalAction previousCriticalAction = CriticalAction.query(customer: bankAccountInfo.customer, type: CriticalActionType.BANK_ACCOUNT_UPDATE, previousObject: bankAccountInfo).get()
        if (previousCriticalAction) {
            previousCriticalAction.cancel()
            onCriticalActionUpdateCancellation(previousCriticalAction)
        }

        if (shouldSaveOrUpdateCriticalActionOnSave(customer, false)) {
            parsedParams.updateCriticalAction = CriticalAction.saveBankAccountUpdate(bankAccountInfo, true)

            if (bankAccountInfo.isDirty()) {
                bankAccountInfo.save(flush: true, failOnError: true)
            }
        } else {
            setAsDeleted(bankAccountInfo)
        }

        parsedParams.accountBeingUpdated = bankAccountInfo

        BankAccountInfo newBankAccountInfo = save(customer, parsedParams)

        if (newBankAccountInfo.hasErrors()) transactionStatus.setRollbackOnly()

        return newBankAccountInfo
    }

    public Boolean canAddAccount(Customer customer) {
        Boolean hasBankAccountInfo = BankAccountInfo.hasBankAccountInfo(customer)
        if (hasBankAccountInfo) return (customer.hasApprovedProofOfLife() && customer.multipleBankAccountsEnabled())

        Boolean ignoreRegisterStatusOnNextTransfer = CustomerParameter.getValue(customer, CustomerParameterName.IGNORE_PROVIDER_REGISTER_STATUS_ON_NEXT_TRANSFER)
        if (ignoreRegisterStatusOnNextTransfer) return true
        if (customer.accountDisabled()) return false
        return true
    }

    private void saveOrUpdateCriticalAction(BankAccountInfo bankAccountInfo, CriticalAction existingCriticalAction) {
        if (existingCriticalAction) {
            existingCriticalAction.bankAccountInfo = bankAccountInfo
            existingCriticalAction.save(failOnError: true)
        } else {
            CriticalAction.saveBankAccountInsert(bankAccountInfo)
        }
    }

    private void deleteMainAccountAwaitingActionAuthorization(Customer customer) {
        List<BankAccountInfo> bankAccountInfoList = BankAccountInfo.query([customer: customer, mainAccount: true, status: Status.AWAITING_ACTION_AUTHORIZATION]).list()
        for (BankAccountInfo bankAccountInfo : bankAccountInfoList) {
            setAsDeleted(bankAccountInfo)
            CriticalAction.deleteNotAuthorized(bankAccountInfo)
        }
    }

	private void approveIfPossible(BankAccountInfo bankAccountInfo) {
		if (canApproveAutomatically(bankAccountInfo)) {
			approveAutomatically(bankAccountInfo)
		} else {
			updateCustomerRegisterStatus(bankAccountInfo.customer)
		}
	}

	private void approveAutomatically(BankAccountInfo bankAccountInfo) {
        String observations = Utils.getMessageProperty("system.automaticApproval.description")
		approve(bankAccountInfo, [observations: observations, approvedAutomatically: true])
	}

	private void updateCustomerRegisterStatus(Customer customer) {
		Status newStatus

		BankAccountInfo bankAccountInfo = BankAccountInfo.findMainAccount(customer.id)

		if (customer.bankAccountInfoApprovalIsNotRequired()) {
			newStatus = Status.APPROVED
		} else if (bankAccountInfo?.status == Status.APPROVED) {
			newStatus = Status.APPROVED
		} else if (bankAccountInfo?.status == Status.AWAITING_APPROVAL) {
			newStatus = Status.AWAITING_APPROVAL
		} else if (bankAccountInfo?.status == Status.REJECTED) {
			newStatus = Status.REJECTED
		} else {
			newStatus = Status.PENDING
		}

		customerRegisterStatusService.updateBankAccountInfoStatus(customer, newStatus)
	}

	private BankAccountInfo validateUpdateParams(Customer customer, Long bankAccountInfoId, Map params) {
		BankAccountInfo bankAccountInfo = validateSaveOrUpdateParams(customer, bankAccountInfoId, params)
        if (bankAccountInfo.hasErrors()) return bankAccountInfo

		if (BankAccountInfo.alreadyExists(customer.id, bankAccountInfoId, params.agency, params.account, Long.valueOf(params.financialInstitution))) {
			DomainUtils.addError(bankAccountInfo, "A conta bancária informada já existe.")
		}

		if (bankAccountIsUsedAsAnotherMainAccount(customer, params) && BankAccountInfo.find(bankAccountInfoId, customer.id).mainAccount) {
			DomainUtils.addError(bankAccountInfo, "Conta bancária informada já está em uso por outro cliente.")
		}

		if (!DomainUtils.attributesHaveChanged(BankAccountInfo.find(bankAccountInfoId, customer.id), params)) {
			DomainUtils.addError(bankAccountInfo, "Nenhum dado da conta bancária foi alterado. Faça as alterações desejadas e salve novamente.")
		}

		return bankAccountInfo
	}

    private BankAccountInfo validateSaveParams(Customer customer, Map params) {
        BankAccountInfo bankAccountInfo = validateSaveOrUpdateParams(customer, null, params)
        if (bankAccountInfo.hasErrors()) return bankAccountInfo

        if (!customer.cpfCnpj) {
            DomainUtils.addError(bankAccountInfo, Utils.getMessageProperty("bankAccountInfo.validate.save.fillCommercialInfoBefore"))
            return bankAccountInfo
        }

        if (BankAccountInfo.alreadyExists(customer.id, params.accountBeingUpdated?.id, params.agency, params.account, Long.valueOf(params.financialInstitution))) {
            DomainUtils.addError(bankAccountInfo, Utils.getMessageProperty("bankAccountInfo.validate.save.alreadyExists"))
            return bankAccountInfo
        }

        if (bankAccountIsUsedAsAnotherMainAccount(customer, params) && !BankAccountInfo.findMainAccount(customer.id)) {
            DomainUtils.addError(bankAccountInfo, Utils.getMessageProperty("bankAccountInfo.validate.save.alreadyUsedAsAnotherMainAccount"))
            return bankAccountInfo
        }

        if (!params.accountBeingUpdated?.mainAccount) {
            if (customer.getProofOfLifeType().isConfirmedTransfer()) {
                BankAccountInfo mainAccount = BankAccountInfo.findMainAccount(customer.id)
                if (mainAccount) {
                    if (!mainAccount.isApproved()) {
                        DomainUtils.addError(bankAccountInfo, Utils.getMessageProperty("bankAccountInfo.validate.save.mainAccountNotApproved"))
                        return bankAccountInfo
                    }

                    if (!customer.hasConfirmedTransfer()) {
                        DomainUtils.addError(bankAccountInfo, Utils.getMessageProperty("bankAccountInfo.validate.save.hasNotConfirmedTransfer"))
                        return bankAccountInfo
                    }
                }
            }
        }

        if (shouldValidateOwnership(customer, params)) {
            if (!revenueServiceRegisterService.isSameOwnership(customer, params.cpfCnpj)) {
                DomainUtils.addError(bankAccountInfo, Utils.getMessageProperty("bankAccountInfo.validate.save.isFirstAccountNotSameOwnership"))
                return bankAccountInfo
            }
        }

        return bankAccountInfo
	}

	private Boolean bankAccountIsUsedAsAnotherMainAccount(Customer customer, Map params) {
		if (!params.financialInstitution) return false
		if (CustomerParameter.getValue(customer, CustomerParameterName.ALLOW_DUPLICATE_MAIN_BANK_ACCOUNT)) return false
		return BankAccountInfo.alreadyInUseAsMainAccountByAnotherCustomer(customer.id, params.agency, params.account, Long.valueOf(params.financialInstitution))
	}

	private void changeBankAccountsWithSameCpfCnpjToThirdPartyAccount(Customer customer) {
		for (BankAccountInfo item in BankAccountInfo.query([customer: customer, cpfCnpj: customer.cpfCnpj]).list()) {
			item.responsiblePhone = customer.mobilePhone
			item.responsibleEmail = customer.email
			item.ownerBirthDate = customer.birthDate
			item.thirdPartyAccount = true
			item.bypassCpfCnpjValidator = true
			item.save(flush: true, failOnError: true)
		}
	}

	private void changeMainAccountToAwaitingApprovalIfNecessary(Customer customer) {
		BankAccountInfo bankAccountInfo = BankAccountInfo.findMainAccount(customer.id)

		if (!bankAccountInfo) return

        deleteMainAccountAwaitingActionAuthorization(customer)

        bankAccountInfo.status = Status.AWAITING_APPROVAL
        bankAccountInfo.bypassCpfCnpjValidator = true
        bankAccountInfo.save(flush: true, failOnError: true)

        if (!customer.multipleBankAccountsEnabled()) {
            AsaasApplicationHolder.applicationContext.bankAccountInfoUpdateRequestService.cancelRequestsAwaitingActionAuthorization(customer)

            BankAccountInfoUpdateRequest bankAccountInfoUpdateRequest = BankAccountInfoUpdateRequest.findOrCreateWhere(customer: customer, status: Status.PENDING, deleted: false)
            bankAccountInfoUpdateRequest.properties = bankAccountInfo.properties
            bankAccountInfoUpdateRequest.status = Status.PENDING
            bankAccountInfoUpdateRequest.bypassCpfCnpjValidator = true
            bankAccountInfoUpdateRequest.save(flush: true, failOnError: true)
        }

        updateCustomerRegisterStatus(customer)
	}

    private void setAsDeleted(BankAccountInfo bankAccountInfo) {
        AsaasApplicationHolder.grailsApplication.mainContext.pixTransactionBankAccountInfoCheckoutLimitChangeRequestService.cancelRequestedIfExists(bankAccountInfo)

        PixTransactionBankAccountInfoCheckoutLimit pixTransactionBankAccountInfoCheckoutLimit = PixTransactionBankAccountInfoCheckoutLimit.query([bankAccountInfo: bankAccountInfo]).get()
        if (pixTransactionBankAccountInfoCheckoutLimit) pixTransactionBankAccountInfoCheckoutLimitService.delete(pixTransactionBankAccountInfoCheckoutLimit)

        bankAccountInfo.bypassCpfCnpjValidator = true
        bankAccountInfo.deleted = true
        bankAccountInfo.save(flush: true, failOnError: true)
    }

    private Status buildStatusOnSave(Customer customer, Boolean bypassCriticalAction) {
        if (shouldSaveOrUpdateCriticalActionOnSave(customer, bypassCriticalAction)) return Status.AWAITING_ACTION_AUTHORIZATION
        if (BankAccountInfo.findMainAccount(customer.id) || customer.bankAccountInfoApprovalIsNotRequired()) return Status.APPROVED

        return Status.AWAITING_APPROVAL
    }

    private Boolean shouldSaveOrUpdateCriticalActionOnSave(Customer customer, Boolean bypassCriticalAction) {
        if (bypassCriticalAction) return false

        Boolean criticalActionConfigBankAccountUpdate = CustomerCriticalActionConfig.query([column: "bankAccountUpdate", customerId: customer.id]).get()
        if (!criticalActionConfigBankAccountUpdate) return false

        Boolean existsApprovedAccount = BankAccountInfo.query([customer: customer, status: Status.APPROVED, includeDeleted: true, exists: true]).get().asBoolean()
        if (!existsApprovedAccount) return false

        if (!customer.hasReceivedPayments()) return false

        return true
    }

    private Boolean shouldValidateOwnership(Customer customer, Map params) {
        Boolean isThirdPartyAccountBeingSave = Boolean.valueOf(params.thirdPartyAccount) || Boolean.valueOf(params.accountBeingUpdate?.thirdPartyAccount)
        if (!isThirdPartyAccountBeingSave) return false

        if (!customer.getProofOfLifeType().isConfirmedTransfer()) return false

        Boolean isMainAccountBeingUpdated = params.accountBeingUpdated?.mainAccount
        if (isMainAccountBeingUpdated) return true

        Boolean hasApprovedBankAccountInfo = BankAccountInfo.query([exists: true, customerId: customer.id, status: Status.APPROVED]).get().asBoolean()
        if (!hasApprovedBankAccountInfo) return true

        return false
    }
}
