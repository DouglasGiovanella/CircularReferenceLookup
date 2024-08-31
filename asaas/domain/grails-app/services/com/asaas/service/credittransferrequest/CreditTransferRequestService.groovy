package com.asaas.service.credittransferrequest

import com.asaas.authorizationrequest.AuthorizationRequestActionType
import com.asaas.authorizationrequest.AuthorizationRequestType
import com.asaas.checkout.CustomerCheckoutFee
import com.asaas.checkoutRiskAnalysis.CheckoutRiskAnalysisReasonObject
import com.asaas.checkoutRiskAnalysis.adapter.CheckoutRiskAnalysisInfoAdapter
import com.asaas.checkoutnotification.vo.CheckoutNotificationVO
import com.asaas.credittransferrequest.CreditTransferRequestCheckoutValidator
import com.asaas.credittransferrequest.CreditTransferRequestStatus
import com.asaas.credittransferrequest.TransferFeeReason
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.companypartnerquery.CompanyPartnerQuery
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.credittransferrequest.CreditTransferRequestTransferBatchFile
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.ReceivedPaymentBatchFile
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.integration.intercom.IntercomUtils
import com.asaas.log.AsaasLogger
import com.asaas.originrequesterinfo.EventOriginType
import com.asaas.transfer.TransferDestinationBankAccountType
import com.asaas.transfer.TransferDestinationBankAccountValidator
import com.asaas.transfer.TransferUtils
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.user.UserUtils
import com.asaas.userpermission.AdminUserPermissionUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import org.apache.commons.lang.time.DateUtils

@Transactional
public class CreditTransferRequestService {

    def asaasSegmentioService
    def authorizationRequestService
    def checkoutNotificationService
    def checkoutRiskAnalysisService
    def creditTransferRequestAdminService
    def customerAlertNotificationService
    def customerExternalAuthorizationRequestCreateService
    def customerMessageService
    def customerParameterService
    def customerProofOfLifeService
    def customerStageService
    def externalSettlementService
    def financialTransactionService
    def intercomService
    def lastCheckoutInfoService
    def messageService
    def mobilePushNotificationService
    def originRequesterInfoService
    def promotionalCodeService
    def receivedPaymentBatchFileService
    def transactionReceiptService
    def transferDestinationBankAccountRestrictionService
    def transferService
    def treasureDataService

	public AsaasError getDenialReasonIfExists(Customer customer, Long bankAccountId, BigDecimal value, Map options) throws Exception {
        if (!options.isAutomaticTransfer && CustomerParameter.getValue(customer, CustomerParameterName.ONLY_AUTOMATIC_TRANSFER_ALLOWED)) {
            return new AsaasError("checkout.denied.onlyAutomaticTransferAllowed")
        }

        if (CustomerParameter.getValue(customer, CustomerParameterName.TRANSFER_CREATE_THROUGH_USER_INTERFACE_DISABLED)) {
            EventOriginType eventOriginType = originRequesterInfoService.getEventOrigin()
            if (eventOriginType.isWeb() || eventOriginType.isMobile()) {
                return new AsaasError("checkout.denied.disabledForUserInterface")
            }
        }

        if (!bankAccountId) return new AsaasError("transfer.denied.null.bankAccountInfo")

        BankAccountInfo bankAccountInfo = BankAccountInfo.find(bankAccountId, customer.id)
        if (!bankAccountInfo.isApproved()) return new AsaasError("transfer.denied.bankAccountInfo.isNotApproved")
        if (!bankAccountInfo.financialInstitution.bank) return new AsaasError("transfer.denied.bankAccountInfo.financialInstitutionHasNoBank")

        return CreditTransferRequestCheckoutValidator.validateValue(customer, bankAccountInfo, value, options)
	}

	public CreditTransferRequest cancel(Long customerId, Long id) {
		CreditTransferRequest creditTransferRequest = CreditTransferRequest.find(id, customerId)
        return cancel(creditTransferRequest)
	}

    public CreditTransferRequest cancel(CreditTransferRequest creditTransferRequest) {
        if (creditTransferRequest.status == CreditTransferRequestStatus.CANCELLED) return creditTransferRequest

		validateCancelByCustomer(creditTransferRequest)

        Boolean providerTransferCriticalActionConfig = CustomerCriticalActionConfig.query([column: "transfer", customerId: creditTransferRequest.providerId]).get()
		if (providerTransferCriticalActionConfig && !creditTransferRequest.awaitingCriticalActionAuthorization) {
			CriticalAction.saveTransferCancel(creditTransferRequest)
			creditTransferRequest.save(flush: true, failOnError: true)
		} else {
			CriticalAction.deleteNotAuthorized(creditTransferRequest)
			executeCancellation(creditTransferRequest)
		}

		return creditTransferRequest
    }

	public void executeCancellation(CreditTransferRequest creditTransferRequest) {
		validateCancelByCustomer(creditTransferRequest)

		cancel(UserUtils.getCurrentUser(), creditTransferRequest.id)
		creditTransferRequest.refresh()
	}

	public CreditTransferRequest save(Customer customer, Long bankAccountId, BigDecimal value, Map options) throws Exception {
        AsaasError denialReason = getDenialReasonIfExists(customer, bankAccountId, value, options)
		if (denialReason) throw new BusinessException(denialReason.getMessage())

        Boolean isScheduledTransfer = TransferUtils.isScheduledTransfer(CustomDateUtils.toDate(options.scheduledDate))

		BankAccountInfo bankAccountInfo = BankAccountInfo.find(bankAccountId, customer.id)

		BigDecimal netValue = value
		BigDecimal transferFee = 0
        Boolean mustCalculateFee = !options.isAutomaticTransfer && !isScheduledTransfer
        if (mustCalculateFee) {
            Map transferFeeMap = calculateTransferFee(customer, bankAccountInfo, value)
            if (transferFeeMap.fee) transferFee = transferFeeMap.fee
        }

        CreditTransferRequest creditTransferRequest = new CreditTransferRequest()
        creditTransferRequest.provider = customer
        creditTransferRequest.bankAccountInfo = bankAccountInfo
        creditTransferRequest.value = value
        creditTransferRequest.netValue = netValue
        creditTransferRequest.transferFee = transferFee
        creditTransferRequest.originalCpfCnpj = bankAccountInfo.cpfCnpj
        creditTransferRequest.scheduledDate = (isScheduledTransfer) ? CustomDateUtils.toDate(options.scheduledDate) : null

        setEstimatedDebitDateOnSave(creditTransferRequest)

        AuthorizationRequestType authorizationRequestType = authorizationRequestService.findAuthorizationRequestType(customer, AuthorizationRequestActionType.CREDIT_TRANSFER_REQUEST_SAVE)

        creditTransferRequest.status = buildSaveCreditTransferRequestStatus(creditTransferRequest, authorizationRequestType)

        CheckoutRiskAnalysisInfoAdapter checkoutInfoAdapter = new CheckoutRiskAnalysisInfoAdapter(creditTransferRequest, bankAccountInfo.cpfCnpj)
        checkoutRiskAnalysisService.checkIfCheckoutIsSuspectedOfFraud(CheckoutRiskAnalysisReasonObject.CREDIT_TRANSFER_REQUEST, checkoutInfoAdapter)

		creditTransferRequest.save(flush: true, failOnError: true)

		if (!isScheduledTransfer) processCurrentDayTransfer(creditTransferRequest)

        if (options && Boolean.valueOf(options.notifyThirdPartyOnConfirmation)) {
            CheckoutNotificationVO checkoutNotificationVO = new CheckoutNotificationVO(options)
            checkoutNotificationService.saveConfig(creditTransferRequest, checkoutNotificationVO)
        }

        creditTransferRequest.transfer = transferService.save(customer, creditTransferRequest)

        if (authorizationRequestType.isCriticalAction()) {
            creditTransferRequest.awaitingCriticalActionAuthorization = true
            creditTransferRequest.save(flush: true, failOnError: true)
            CriticalAction.saveTransfer(creditTransferRequest)
        } else if (authorizationRequestType.isExternalAuthorization()) {
            customerExternalAuthorizationRequestCreateService.saveForTransfer(creditTransferRequest.transfer)
        }

        BusinessValidation businessValidation = TransferDestinationBankAccountValidator.validateRestrictionIfNecessary(creditTransferRequest.provider, creditTransferRequest.transfer.destinationBankAccount.cpfCnpj)
        if (!businessValidation.isValid()) {
            setAsFailed(creditTransferRequest)
            transferDestinationBankAccountRestrictionService.onTransferFailedByRestrictedDestination(creditTransferRequest.transfer)
            return creditTransferRequest
        }

        creditTransferRequestAdminService.authorizeAutomaticallyIfPossible(creditTransferRequest)

		return creditTransferRequest
	}

    public void processCurrentDayTransfer(CreditTransferRequest creditTransferRequest) {
        lastCheckoutInfoService.save(creditTransferRequest.provider)

        if (CustomerParameter.getValue(creditTransferRequest.provider, CustomerParameterName.IGNORE_PROVIDER_REGISTER_STATUS_ON_NEXT_TRANSFER)) {
            customerParameterService.save(creditTransferRequest.provider, CustomerParameterName.IGNORE_PROVIDER_REGISTER_STATUS_ON_NEXT_TRANSFER, false)
        }

        promotionalCodeService.consumeFeeDiscountPromotionalCodeAndSetNetValue(creditTransferRequest)

        Boolean bypassCheckoutLimit = mustBypassCheckoutLimitOnTransfer(creditTransferRequest.bankAccountInfo)
        financialTransactionService.saveTransfer(creditTransferRequest, bypassCheckoutLimit)

        Map estimatedDaysMap = getEstimatedDaysForDebit()
        asaasSegmentioService.track(creditTransferRequest.provider.id, "Logged :: Transferência :: Solicitação realizada", [providerEmail: creditTransferRequest.provider.email,
                                                                                                                            value: creditTransferRequest.value,
                                                                                                                            mainAccount: creditTransferRequest.bankAccountInfo.mainAccount,
                                                                                                                            bankName: creditTransferRequest.bankAccountInfo.financialInstitution.bank.name,
                                                                                                                            bankAccountType: creditTransferRequest.bankAccountInfo.bankAccountType,
                                                                                                                            apelidoBankAccount: creditTransferRequest.bankAccountInfo.accountName ?: "Sem apelido",
                                                                                                                            estimatedDaysForDebit: estimatedDaysMap.estimatedDaysForDebit])

        if (CreditTransferRequest.query([provider: creditTransferRequest.provider]).count() == 1) {
            asaasSegmentioService.identify(creditTransferRequest.provider.id, ["firstTransfer": new Date()])

            intercomService.includeUserOnTag(creditTransferRequest.provider.id, IntercomUtils.TAG_FOR_FIRST_TRANSFER)
        }

        if (CustomerParameter.getValue(creditTransferRequest.provider, CustomerParameterName.CREATE_RECEIVED_PAYMENT_BATCH_FILE_ON_TRANSFER)) {
            if (FinancialTransaction.getCustomerBalance(creditTransferRequest.provider) != 0) {
                throw new BusinessException("É necessário transferir todo o saldo para gerar o arquivo de pagamentos recebidos.")
            }

            saveReceivedPaymentBatchFile(creditTransferRequest)
        }
    }

    public List<CreditTransferRequest> listForTransferBatchFile(Map params) {
        List<CreditTransferRequest> transferListToBatchFile = []

        if (params.bankCodeList) {
            List<String> bankCodeList = (params.bankCodeList instanceof String) ? [params.bankCodeList] : params.bankCodeList

            for (String bankCode : bankCodeList) {
                Map paramsForSearch = params.clone()
                paramsForSearch.remove("bankCodeList")
                paramsForSearch.bankCode = bankCode

                if (params.batchFileBankCode in [SupportedBank.SICREDI.code(), SupportedBank.SANTANDER.code()]) {
                    paramsForSearch.transferDestinationBankAccountType = TransferDestinationBankAccountType.CHECKING_ACCOUNT
                    if (bankCode == SupportedBank.SICREDI.code()) {
                        paramsForSearch << buildSicrediSupportedAgenciesSearchMap()
                    }
                }

                transferListToBatchFile.addAll(CreditTransferRequest.readyForTransferBatchFile(paramsForSearch).list())
            }
        } else if (params.batchFileBankCode == SupportedBank.SICREDI.code()) {
            transferListToBatchFile = listForSicrediTransferBatchFile(params)
        } else if (params.batchFileBankCode == SupportedBank.SANTANDER.code()) {
            transferListToBatchFile = listForSantanderTransferBatchFile(params)
        } else {
            transferListToBatchFile.addAll(CreditTransferRequest.readyForTransferBatchFile(params + [inBankCodes: [params.batchFileBankCode]]).list())
            transferListToBatchFile.addAll(CreditTransferRequest.readyForTransferBatchFile(params + [notInBankCodes: [params.batchFileBankCode]]).list())
        }

        List<CreditTransferRequest> uniqueTransferListToBatchFile = transferListToBatchFile.unique { transfer ->
            transfer.id
        }

		if (!params.availableBalance) return uniqueTransferListToBatchFile

		BigDecimal availableBalance = Utils.toBigDecimal(params.availableBalance)
		List<CreditTransferRequest> finalTransferListToBatchFile = []
		BigDecimal finalTransferListNetValueSum = 0

		for (CreditTransferRequest transfer : uniqueTransferListToBatchFile) {
			if (finalTransferListNetValueSum + transfer.netValue <= availableBalance) {
				finalTransferListToBatchFile.add(transfer)
				finalTransferListNetValueSum += transfer.netValue
			}
		}

		return finalTransferListToBatchFile
	}

	public CreditTransferRequest updateStatus(Long id, CreditTransferRequestStatus status) {
		if (status == CreditTransferRequestStatus.CONFIRMED) {
			return confirm(id)
		} else if (status == CreditTransferRequestStatus.PENDING) {
			return setAsPending(id)
		} else if (status == CreditTransferRequestStatus.CANCELLED) {
			throw new RuntimeException("Cancelamento de transferência pelo método [updateStatus] não permitido.")
		} else if (status == CreditTransferRequestStatus.BANK_PROCESSING) {
			return setAsBankProcessing(id)
		}
	}

	public CreditTransferRequest confirm(Long id) {
		confirm(id, null)
	}

	public CreditTransferRequest confirm(Long id, Map options) {
		CreditTransferRequest creditTransferRequest = CreditTransferRequest.findWhere(id: id)

        BusinessValidation validatedCreditTransferRequest = validateConfirm(creditTransferRequest)
        if (!validatedCreditTransferRequest.isValid()) {
            AsaasLogger.warn("CreditTransferRequestService.confirm >> Tentativa de confirmação de transferência inválida [creditTransferRequest.id: ${creditTransferRequest.id}]")
            return DomainUtils.addError(creditTransferRequest, validatedCreditTransferRequest.getFirstErrorMessage())
        }

		creditTransferRequest.status = CreditTransferRequestStatus.CONFIRMED;
		creditTransferRequest.confirmedDate = DateUtils.truncate(new Date(), Calendar.DAY_OF_MONTH)
		creditTransferRequest.lastStatusUpdate = new Date().clearTime()
		creditTransferRequest.save(flush: true, failOnError: true)

        transferService.setAsDone(creditTransferRequest.transfer, creditTransferRequest.getDebitDate())

		customerStageService.processTransferConfirmed(creditTransferRequest.provider)

		transactionReceiptService.saveTransferConfirmed(creditTransferRequest)

        mobilePushNotificationService.notifyTransferConfirmed(creditTransferRequest)

		if (!options?.bypassCheckoutReceipt) sendCheckoutReceipt(creditTransferRequest)

        customerProofOfLifeService.approveByCreditTransferIfPossible(creditTransferRequest.provider)

        externalSettlementService.setAsPreProcessedIfPossible(creditTransferRequest.transfer)

		return creditTransferRequest
	}

	public void sendCheckoutReceipt(CreditTransferRequest creditTransferRequest) {
		checkoutNotificationService.sendReceiptEmail(creditTransferRequest)
		checkoutNotificationService.sendReceiptSms(creditTransferRequest)
	}

    public CreditTransferRequest setAsPending(Long id) {
        CreditTransferRequest creditTransferRequest = CreditTransferRequest.get(id)

        Boolean canBeSetAsPending = (CreditTransferRequestStatus.allowedToBeUpdatedToPending().contains(creditTransferRequest.status))
        if (!canBeSetAsPending) {
            AsaasLogger.warn("CreditTransferRequestService.setAsPending() -> A transferência ${creditTransferRequest.id} não pode ser alterada para pendente. [Status atual: ${creditTransferRequest.status}]")
            throw new BusinessException("Apenas transferências não processadas podem ser atualizadas para pendente.")
        }

        creditTransferRequest.status = CreditTransferRequestStatus.PENDING
        creditTransferRequest.lastStatusUpdate = new Date().clearTime()
        creditTransferRequest.save(flush: true, failOnError: true)

        transferService.setAsPending(creditTransferRequest.transfer)

        creditTransferRequestAdminService.authorizeAutomaticallyIfPossible(creditTransferRequest)

        return creditTransferRequest
    }

	public CreditTransferRequest cancel(User user, Long id) {
		cancel(user, id, null, null)
	}

    public CreditTransferRequest cancel(User user, Long id, String observations, Map options) {
        CreditTransferRequest creditTransferRequest = CreditTransferRequest.get(id)

        if (creditTransferRequest.status.isCancelled()) return creditTransferRequest

        if (!canBeCancelled(creditTransferRequest, user, options)) {
            AsaasLogger.info("CreditTransferRequestService.cancel >> Transfer cannot be cancelled: ${id}")
            throw new BusinessException("A transferência [${creditTransferRequest.id}] não pode ser cancelada pelo usuário [${user?.username}].")
        }

        Boolean isCompromised = !(creditTransferRequest.isScheduledTransferNotProcessed())
        creditTransferRequest.observations = "Cancelamento efetuado pelo usuário ${user?.username ?: 'ASAAS'} em ${new Date().format('dd/MM/yyyy')}. ${observations ? 'Motivo:' + observations : ''}"
        creditTransferRequest.status = CreditTransferRequestStatus.CANCELLED

        transferService.setAsCancelled(creditTransferRequest.transfer)

        return saveCancelledOrFailedTransfer(creditTransferRequest, isCompromised)
    }

	public CreditTransferRequest cancel(User user, Long id, String observations) {
		if (!observations) {
			throw new BusinessException("Informe o motivo para cancelar a transferência.")
		}

		return cancel(user, id, observations, null)
	}

    public CreditTransferRequest setAsFailed(CreditTransferRequest creditTransferRequest) {
        BusinessValidation businessValidation = canBeSetAsFailed(creditTransferRequest)
        if (!businessValidation.isValid()) return DomainUtils.addError(creditTransferRequest, businessValidation.getFirstErrorMessage())

        Boolean isCompromised = creditTransferRequest.isCompromised()

        creditTransferRequest.status = CreditTransferRequestStatus.FAILED
        transferService.setAsFailed(creditTransferRequest.transfer)

        externalSettlementService.setAsPreProcessedIfPossible(creditTransferRequest.transfer)

        return saveCancelledOrFailedTransfer(creditTransferRequest, isCompromised)
    }

    public BusinessValidation canBeSetAsFailed(CreditTransferRequest creditTransferRequest) {
        BusinessValidation validatedCreditTransferRequest = new BusinessValidation()

        if (creditTransferRequest.status.isFailed()) {
            validatedCreditTransferRequest.addError("creditTransferRequest.error.cannotBeSetAsFailed.invalidStatus")
        }

        return validatedCreditTransferRequest
    }

	public Boolean canBeCancelled(CreditTransferRequest transfer, User user, Map options) {
        if (transfer.status.isScheduled()) return true

		if (options?.batchFileProcessing) return true

		if (options?.onPaymentChargeback && transfer.stillNotProcessed()) return true

		if (transfer.alreadyProcessed() && user?.sysAdmin()) {
			return AdminUserPermissionUtils.cancelProcessedTransfer(user)
		} else if (transfer.stillNotProcessed()) {
			if (user && user.customer != transfer.provider && user.sysAdmin()) {
				return AdminUserPermissionUtils.cancelTransfer(user)
			} else {
				return true
			}
		}

		return false
	}

	public void block(Long id) throws BusinessException {
		CreditTransferRequest creditTransferRequest = CreditTransferRequest.get(id)

		if (!CreditTransferRequestStatus.allowedToBeBlocked().contains(creditTransferRequest.status)) {
            AsaasLogger.info("CreditTransferRequestService.block >> Transfer can not be blocked: ${creditTransferRequest.id}")
			throw new BusinessException("Esta transferência não pode ser bloqueada.")
		}

		creditTransferRequest.status = CreditTransferRequestStatus.BLOCKED
		creditTransferRequest.lastStatusUpdate = new Date().clearTime()
		creditTransferRequest.save(flush: true, failOnError: true)

        transferService.setAsBlocked(creditTransferRequest.transfer)
	}

	public Date calculateEstimatedDebitDate(Date requestDate, Integer estimatedDaysForConfirmation) {
		if (!requestDate) return null

        return CustomDateUtils.addBusinessDays(requestDate, estimatedDaysForConfirmation).clearTime()
	}

	public CreditTransferRequestTransferBatchFile getLastAttemptOfCreditTransferRequestTransferBatchFile(CreditTransferRequest creditTransferRequest) {
		return CreditTransferRequestTransferBatchFile.executeQuery("""from CreditTransferRequestTransferBatchFile as c
			                                                         where c.creditTransferRequest = :creditTransferRequest
												                     order by c.attempt desc limit 1""", [creditTransferRequest: creditTransferRequest])[0]
	}

    public Map getEstimatedDaysForDebit() {
        return [estimatedDaysForDebit: CustomDateUtils.getInstanceOfCalendar().get(Calendar.HOUR_OF_DAY) < CreditTransferRequest.getLimitHourToExecuteTransferToday() ? 0 : 1, listOfReason: []]
    }

	public void cancelProviderTransfers(Customer provider) {
		List<CreditTransferRequest> listOfCreditTransferRequest = CreditTransferRequest.listNotProcessed(provider)

		for (CreditTransferRequest creditTransferRequest : listOfCreditTransferRequest) {
			cancel(UserUtils.getCurrentUser(), creditTransferRequest.id)
		}
	}

    public void cancelAllowedByCustomer(Customer provider) {
        List<Long> creditTransferRequestIdList = CreditTransferRequest.query([column: "id", provider: provider, statusList: CreditTransferRequestStatus.allowedToBeCancelledByCustomer()]).list(readOnly: true) as List<Long>

        for (Long creditTransferRequestId : creditTransferRequestIdList) {
            cancel(UserUtils.getCurrentUser(), creditTransferRequestId)
        }
    }

    public void liberateTransfers() {
        List<Long> creditTransferRequestIdList = CreditTransferRequest.query([column: "id", status: CreditTransferRequestStatus.WAITING_LIBERATION, "estimatedDebitDate[le]": new Date()]).list()

        Utils.forEachWithFlushSession(creditTransferRequestIdList, 100, { Long id ->
            Utils.withNewTransactionAndRollbackOnError({
                CreditTransferRequest creditTransferRequest = CreditTransferRequest.get(id)
                if (creditTransferRequest.status == CreditTransferRequestStatus.WAITING_LIBERATION) {
                    setAsPending(id)
                }
            }, [logErrorMessage: "CreditTransferRequestService.liberateTransfers >> Falha ao liberar a transferência [${id}]"])
        })
    }

	public void updateEstimatedDebitDate(CreditTransferRequest creditTransferRequest, Date newEstimatedDebitDate, String reason) throws BusinessException {
		Boolean estimatedDebitDateWasUpdated = (newEstimatedDebitDate != creditTransferRequest.estimatedDebitDate)

        Boolean isReasonRequired = (estimatedDebitDateWasUpdated && !reason)
		if (isReasonRequired) throw new BusinessException("Ao alterar a data prevista da transferência indicada pelo sistema, é necessário informar o motivo.")

        Boolean currentStatusNotAllowEstimatedDebitDateUpdate = (!CreditTransferRequestStatus.allowedUpdateEstimatedDebitDate().contains(creditTransferRequest.status))
		if (currentStatusNotAllowEstimatedDebitDateUpdate) throw new BusinessException("A situação atual desta transferência não permite que a sua data prevista de transferência seja atualizada.")

		if (newEstimatedDebitDate > creditTransferRequest.estimatedDebitDate) {
			creditTransferRequest.transferDateHasBeenIncreased = true

			treasureDataService.track(creditTransferRequest.provider, TreasureDataEventType.TRANSFER_DELAYED, [creditTransferRequestId: creditTransferRequest.id])
		} else if (newEstimatedDebitDate < creditTransferRequest.estimatedDebitDate) {
			creditTransferRequest.transferDateHasBeenIncreased = false
		}

		creditTransferRequest.estimatedDebitDate = newEstimatedDebitDate.clearTime()
		creditTransferRequest.save(flush: true, failOnError: true)

		setAsPendingOrAwaitingLiberation(creditTransferRequest)

		if (estimatedDebitDateWasUpdated) {
			customerMessageService.notifyEstimatedDebitDateUpdated(creditTransferRequest.provider, creditTransferRequest, reason)
		}
	}

	public Boolean canBlocked(CreditTransferRequest creditTransferRequest) {
		return CreditTransferRequestStatus.allowedToBeBlocked().contains(creditTransferRequest.status)
	}

    public CreditTransferRequest setAsBankProcessing(long id) {
		CreditTransferRequest creditTransferRequest = CreditTransferRequest.findWhere(id: id)

		if (creditTransferRequest.status == CreditTransferRequestStatus.CONFIRMED) {
            AsaasLogger.warn("Ignorando alteração de status de transferência confirmada [${creditTransferRequest.id}] para processamento bancário")
            return creditTransferRequest
		}

		creditTransferRequest.lastStatusUpdate = new Date().clearTime()
		creditTransferRequest.status = CreditTransferRequestStatus.BANK_PROCESSING
		creditTransferRequest.save(flush: true, failOnError: true)

        transferService.setAsBankProcessing(creditTransferRequest.transfer)

		return creditTransferRequest
	}

	public CreditTransferRequest setAsWaitingLiberation(CreditTransferRequest creditTransferRequest) {
		if (creditTransferRequest.status == CreditTransferRequestStatus.WAITING_LIBERATION) {
			return creditTransferRequest
		}

		if (creditTransferRequest.id && ![CreditTransferRequestStatus.AWAITING_EXTERNAL_AUTHORIZATION, CreditTransferRequestStatus.PENDING].contains(creditTransferRequest.status)) {
			throw new BusinessException("A transferência [${creditTransferRequest.id}] está com status [${creditTransferRequest.status}], não é possível definir como aguardando liberação.")
		}

		creditTransferRequest.status = CreditTransferRequestStatus.WAITING_LIBERATION

		if (creditTransferRequest.id) {
			creditTransferRequest.save(flush: true, failOnError: true)

            transferService.setAsPending(creditTransferRequest.transfer)
		}

		return creditTransferRequest
	}

	public List<CreditTransferRequest> cancelWhileBalanceIsNegative(Customer customer) {
		List<CreditTransferRequest> cancelledTransferList = []

		if (FinancialTransaction.getCustomerBalance(customer) >= 0) return cancelledTransferList

		List<Long> transferListId = CreditTransferRequest.cancellable([column: 'id', provider: customer, sort: 'estimatedDebitDate', order: 'desc']).list()

		for (Long transferId : transferListId) {
			CreditTransferRequest transfer = cancel(UserUtils.getCurrentUser(), transferId, null, [onPaymentChargeback: true])

			if (transfer.hasErrors()) throw new RuntimeException("Não é possível cancelar a transferência [${transferId}]")

			cancelledTransferList.add(transfer)

			if (FinancialTransaction.getCustomerBalance(customer) >= 0) return cancelledTransferList
		}

		return cancelledTransferList
	}

    @Deprecated
	public Map calculateTransferFee(Customer customer, BankAccountInfo bankAccountInfo, BigDecimal value) {
        TransferFeeReason transferFeeReason = getTransferFeeReasonIfExists(customer, bankAccountInfo, value)
        BigDecimal transferFee = CustomerFee.calculateTransferFeeValue(customer)

		return [fee: transferFeeReason ? transferFee : 0, reason: transferFeeReason]
	}

    public Date calculateDefaultMaximumScheduledDate() {
        return CustomDateUtils.sumDays(new Date(), CreditTransferRequest.SCHEDULING_LIMIT_DAYS)
    }

    public CreditTransferRequest saveCancelledOrFailedTransfer(CreditTransferRequest transfer, Boolean isCompromised) {
        Boolean transferIsCancelledOrFailed = [CreditTransferRequestStatus.CANCELLED, CreditTransferRequestStatus.FAILED].contains(transfer.status)
        if (!transferIsCancelledOrFailed) return transfer

        transfer.lastStatusUpdate = new Date().clearTime()
        transfer.awaitingCriticalActionAuthorization = false

        if (isCompromised) {
            Boolean bypassCheckoutLimit = mustBypassCheckoutLimitOnTransfer(transfer.bankAccountInfo)
            financialTransactionService.reverseTransfer(transfer, true, bypassCheckoutLimit)
        }

        CriticalAction.deleteNotAuthorized(transfer)

        receivedPaymentBatchFileService.delete(transfer)

        transfer.save(flush: true, failOnError: true)

        if (transfer.status == CreditTransferRequestStatus.FAILED) {
            customerAlertNotificationService.notifyTransferFailed(transfer)
        }

        return transfer
    }

    public void setAsPendingOrAwaitingLiberation(CreditTransferRequest creditTransferRequest) {
        if (new Date().clearTime() >= creditTransferRequest.estimatedDebitDate) {
            if (!creditTransferRequest.id) {
                creditTransferRequest.status = CreditTransferRequestStatus.PENDING
            } else {
                setAsPending(creditTransferRequest.id)
            }
        } else {
            setAsWaitingLiberation(creditTransferRequest)
        }
    }

    public Boolean mustBypassCheckoutLimitOnTransfer(BankAccountInfo bankAccountInfo) {
        Customer customer = bankAccountInfo.customer

        if (customer.cpfCnpj == bankAccountInfo.cpfCnpj) return true

        if (customer.isMEI()) {
            if (CompanyPartnerQuery.isAdminPartner(bankAccountInfo.customer.cpfCnpj, bankAccountInfo.cpfCnpj)) return true

            RevenueServiceRegister revenueServiceRegister = RevenueServiceRegister.findLatest(customer.cpfCnpj)
            if (revenueServiceRegister?.getMEIOwnerCpf() == bankAccountInfo.cpfCnpj) return true
        }

        return false
    }

    public void setAsScheduled(CreditTransferRequest creditTransferRequest) {
        creditTransferRequest.status = CreditTransferRequestStatus.SCHEDULED
        creditTransferRequest.lastStatusUpdate = new Date().clearTime()
        creditTransferRequest.save(flush: true, failOnError: true)
    }

    public BusinessValidation validateConfirm(CreditTransferRequest creditTransferRequest) {
        BusinessValidation validatedCreditTransferRequest = new BusinessValidation()

        if (creditTransferRequest.awaitingCriticalActionAuthorization) {
            validatedCreditTransferRequest.addError("creditTransferRequest.error.cannotBeConfirmed.isAwaitingCriticalActionAuthorization")
            return validatedCreditTransferRequest
        }

        if (!CreditTransferRequestStatus.allowedToBeConfirmed().contains(creditTransferRequest.status)) {
            validatedCreditTransferRequest.addError("creditTransferRequest.error.cannotBeConfirmed.invalidStatus")
            return validatedCreditTransferRequest
        }

        return validatedCreditTransferRequest
    }

    private void setEstimatedDebitDateOnSave(CreditTransferRequest creditTransferRequest) {
        if (creditTransferRequest.scheduledDate) {
            creditTransferRequest.estimatedDebitDate = creditTransferRequest.scheduledDate
        } else {
            Map estimatedDaysMap = getEstimatedDaysForDebit()
            creditTransferRequest.estimatedDebitDate = calculateEstimatedDebitDate(new Date(), estimatedDaysMap.estimatedDaysForDebit)
        }
    }

    private CreditTransferRequestStatus buildSaveCreditTransferRequestStatus(CreditTransferRequest creditTransferRequest, AuthorizationRequestType authorizationRequestType) {
        if (authorizationRequestType.isExternalAuthorization()) {
            return CreditTransferRequestStatus.AWAITING_EXTERNAL_AUTHORIZATION
        }

        if (TransferUtils.isScheduledTransfer(creditTransferRequest.scheduledDate)) {
            return CreditTransferRequestStatus.SCHEDULED
        }

        return CreditTransferRequestStatus.PENDING
    }

    private void saveReceivedPaymentBatchFile(CreditTransferRequest transfer) {
        AsaasLogger.info("CreditTransferRequestService.saveReceivedPaymentBatchFile >> Creating ReceivedPaymentBatchFile for transfer [${transfer.id}]")
        List<Long> paymentList = Payment.received([column: "id", customer: transfer.provider, notAssociatedWithReceivedPaymentBatchFile: true]).list()
        AsaasLogger.info("CreditTransferRequestService.saveReceivedPaymentBatchFile >> Found [${paymentList.size()}] payments to associate with the ReceivedPaymentBatchFile")

        ReceivedPaymentBatchFile batchFile = receivedPaymentBatchFileService.createBatchFile("ASAAS_PAYMENTS_${transfer.id}.txt", paymentList, transfer)

        messageService.notifyReceivedPaymentBatchFileCreated(batchFile)

        AsaasLogger.info("CreditTransferRequestService.saveReceivedPaymentBatchFile >> ReceivedPaymentBatchFile for transfer [${transfer.id}] created")
    }

    private void validateCancelByCustomer(CreditTransferRequest creditTransferRequest) {
        BusinessValidation businessValidation = creditTransferRequest.canBeCancelled()
        if (!businessValidation.isValid()) {
            throw new BusinessException(businessValidation.getFirstErrorMessage())
        }
    }

    @Deprecated
    private TransferFeeReason getTransferFeeReasonIfExists(Customer customer, BankAccountInfo bankAccountInfo, BigDecimal value) {
        if (!CustomerCheckoutFee.shouldChargeCreditTransferRequestFee(customer)) return null

        if (CustomerFee.getAlwaysChargeTransferFee(customer.id)) return TransferFeeReason.ALWAYS_CHARGE_TRANSFER_FEE

        if (value < CreditTransferRequest.MINIMUM_VALUE_WITHOUT_TRANSFER_FEE) return TransferFeeReason.VALUE

        if (customer.isMEI()) {
            if (CompanyPartnerQuery.isAdminPartner(bankAccountInfo.customer.cpfCnpj, bankAccountInfo.cpfCnpj)) return null

            RevenueServiceRegister revenueServiceRegister = RevenueServiceRegister.findLatest(customer.cpfCnpj)
            if (revenueServiceRegister?.getMEIOwnerCpf() == bankAccountInfo.cpfCnpj) return null
        }

        if (bankAccountInfo.customer.bankAccountInfoApprovalIsNotRequired()) {
            if (bankAccountInfo.customer.cpfCnpj != bankAccountInfo.cpfCnpj) return TransferFeeReason.CUSTOMER_IS_NOT_BANK_ACCOUNT_OWNER
        } else {
            if (!bankAccountInfo.mainAccount) return TransferFeeReason.NOT_MAIN_ACCOUNT
        }

        return null
    }

    private List<CreditTransferRequest> listForSicrediTransferBatchFile(Map params) {
        List<CreditTransferRequest> transferListToBatchFile = []

        Map baseSearchMap = params + [transferDestinationBankAccountType: TransferDestinationBankAccountType.CHECKING_ACCOUNT]

        Map sicrediSupportedAgenciesSearchMap = baseSearchMap + [inBankCodes: [SupportedBank.SICREDI.code()]] + buildSicrediSupportedAgenciesSearchMap()
        transferListToBatchFile.addAll(CreditTransferRequest.readyForTransferBatchFile(sicrediSupportedAgenciesSearchMap).list())

        Map notSicrediSantanderBradescoSearchMap = baseSearchMap + [
                notInBankCodes: [SupportedBank.SICREDI.code(), SupportedBank.SANTANDER.code(), SupportedBank.BRADESCO.code()]
        ]
        transferListToBatchFile.addAll(CreditTransferRequest.readyForTransferBatchFile(notSicrediSantanderBradescoSearchMap).list())

        Map santanderBradescoSearchMap = baseSearchMap + [
                inBankCodes: [SupportedBank.SANTANDER.code(), SupportedBank.BRADESCO.code()]
        ]
        transferListToBatchFile.addAll(CreditTransferRequest.readyForTransferBatchFile(santanderBradescoSearchMap).list())

        return transferListToBatchFile
    }

    private List<CreditTransferRequest> listForSantanderTransferBatchFile(Map params) {
        List<CreditTransferRequest> transferListToBatchFile = []

        Map baseSearchMap = params + [transferDestinationBankAccountType: TransferDestinationBankAccountType.CHECKING_ACCOUNT]

        Map santanderSearchMap = baseSearchMap + [inBankCodes: [SupportedBank.SANTANDER.code()]]
        transferListToBatchFile.addAll(CreditTransferRequest.readyForTransferBatchFile(santanderSearchMap).list())

        Map notSantanderSicrediBradescoSearchMap = baseSearchMap + [
                notInBankCodes: [SupportedBank.SANTANDER.code(), SupportedBank.SICREDI.code(), SupportedBank.BRADESCO.code()]
        ]
        transferListToBatchFile.addAll(CreditTransferRequest.readyForTransferBatchFile(notSantanderSicrediBradescoSearchMap).list())

        Map sicrediBradescoSearchMap = baseSearchMap + [inBankCodes: [SupportedBank.SICREDI.code(), SupportedBank.BRADESCO.code()]]
        transferListToBatchFile.addAll(CreditTransferRequest.readyForTransferBatchFile(sicrediBradescoSearchMap).list())

        return transferListToBatchFile
    }

    private Map buildSicrediSupportedAgenciesSearchMap() {
        return [
            supportedAgenciesBank: SupportedBank.SICREDI.code(),
            supportedAgencies: SupportedBank.SICREDI.getSupportedAgencies()
        ]
    }
}
