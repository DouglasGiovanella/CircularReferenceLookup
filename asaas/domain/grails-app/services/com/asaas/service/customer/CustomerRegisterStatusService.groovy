package com.asaas.service.customer

import com.asaas.abtest.enums.AbTestPlatform
import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.customer.CustomerEventName
import com.asaas.customer.CustomerStatus
import com.asaas.customerregisterstatus.CustomerRegisterStatusAnalysisType
import com.asaas.customerregisterstatus.CustomerRegisterStatusQueryBuilder
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.customerregisterstatus.vo.CustomerRegisterStatusAnalysisSearchParamsVO
import com.asaas.documentanalysis.DocumentAnalysisStatus
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerRegisterStatus
import com.asaas.domain.customer.commercialinfo.CustomerCommercialInfoExpiration
import com.asaas.domain.customergeneralanalysis.CustomerGeneralAnalysis
import com.asaas.domain.payment.Payment
import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.receivableanticipation.ReceivableAnticipationCancelReason
import com.asaas.referral.ReferralStatus
import com.asaas.referral.ReferralValidationOrigin
import com.asaas.status.CustomerGeneralAnalysisStatus
import com.asaas.status.Status
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.user.UserUtils
import com.asaas.userpermission.AdminUserPermissionUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

import static grails.async.Promises.task

@Transactional
class CustomerRegisterStatusService {

    def abTestService
    def sageAccountService
    def asyncActionService
    def bacenCcsService
    def callCenterOutgoingPhoneCallManagerService
    def customerAlertNotificationService
    def customerBankSlipBeneficiaryService
    def customerCommercialInfoExpirationService
    def customerDocumentAnalysisStatusService
    def customerEventService
    def customerInteractionService
    def customerMessageService
    def customerOnboardingService
    def customerOnboardingStepService
    def customerProofOfLifeService
    def customerReceivableAnticipationConfigService
    def customerRegisterUpdateAnalysisRequestService
    def fraudTrackingAccountService
    def grailsApplication
    def hubspotContactService
    def mobilePushNotificationService
    def notificationDispatcherCustomerOutboxService
    def pushNotificationRequestAccountStatusService
    def receivableAnticipationCancellationService
    def receivableAnticipationPartnerConfigService
    def receivableAnticipationValidationCacheService
    def referralService
    def revenueServiceRegisterManagerService
    def smsSenderService
    def thirdPartyDocumentationOnboardingService
    def treasureDataService

    public List<Long> listCustomerIdReadyForAnalysis(CustomerRegisterStatusAnalysisSearchParamsVO searchParamsVO) {
        Map queryParams = [
            column: "customer.id",
            commercialInfoStatus: Status.APPROVED,
            boletoInfoStatus: Status.APPROVED,
            bankAccountInfoApprovedOrProofOfLifeIsSelfie: true,
            "customerStatus[ne]": CustomerStatus.DISABLED
        ]

        if (searchParamsVO.analysisType.isGeneral()) {
            queryParams += [documentStatus: Status.APPROVED, generalApproval: GeneralApprovalStatus.AWAITING_APPROVAL]
        } else if (searchParamsVO.analysisType.isDocument()) {
            queryParams += [documentStatus: Status.AWAITING_APPROVAL]
        }

        if (searchParamsVO.personType) queryParams += [customerPersonType: searchParamsVO.personType]
        if (searchParamsVO.companyType) queryParams += [customerCompanyType: searchParamsVO.companyType]

        if (searchParamsVO.customerSignUpOriginPlatform) queryParams += [customerSignUpOriginPlatform: searchParamsVO.customerSignUpOriginPlatform]

        return CustomerRegisterStatus.query(queryParams).list()
    }

    public CustomerRegisterStatus save(Customer customer) {
		CustomerRegisterStatus customerRegisterStatus = new CustomerRegisterStatus(customer: customer)

		customerRegisterStatus.save(flush: true, failOnError: true)

		return customerRegisterStatus
    }

    public CustomerRegisterStatus updateManually(Long id, Map params) {
        if (!AdminUserPermissionUtils.updateCustomerRegisterStatusManually(UserUtils.getCurrentUser())) throw new RuntimeException("Usuário não tem permissão para alterar o status da análise cadastral manualmente.")

        CustomerRegisterStatus customerRegisterStatus = CustomerRegisterStatus.get(id)
        Customer customer = customerRegisterStatus.customer

        customerRegisterStatus.bankAccountInfoStatus = params.bankAccountInfoStatus
        customerRegisterStatus.commercialInfoStatus = params.commercialInfoStatus
        customerRegisterStatus.documentStatus = params.documentStatus
        customerRegisterStatus.generalApproval = params.generalApproval

        customerInteractionService.saveCustomerRegisterStatusEditUpdatedManually(customer)

        customerRegisterStatus.save(failOnError: true)

        if (customerRegisterStatus.generalApproval.isRejected()) {
            asyncActionService.saveBlockHermesAccount(customer)
            sageAccountService.onCustomerInactivated(customer.id)
        }
        if (customerRegisterStatus.generalApproval.isApproved()) {
            asyncActionService.saveActiveHermesAccount(customer)
            sageAccountService.onCustomerActivated(customer.id)
            customerEventService.save(customer, CustomerEventName.GENERAL_APPROVAL, null, new Date())
            scheduleCommercialInfoExpirationIfNecessary(customer)
        }

        notificationDispatcherCustomerOutboxService.onCustomerUpdated(customer)
        receivableAnticipationValidationCacheService.evictIsFirstUse(customer.id)

        return customerRegisterStatus
    }

    public void updateNewInternalUserStatus(User user, Boolean shouldUseFakeInfo) {
        if (!user.sysAdmin()) throw new RuntimeException("Esta aprovação automática de informações deve ser aplicada somente para usuários de colaboradores Asaas")

        updateBankAccountInfoStatus(user.customer, Status.APPROVED)
        updateBoletoInfoStatus(user.customer, Status.APPROVED)

        if (shouldUseFakeInfo) {
            updateBoletoInfoStatus(user.customer, Status.APPROVED)
            updateDocumentStatus(user.customer, Status.APPROVED)
            updateGeneralStatus(user.customer, GeneralApprovalStatus.APPROVED, "Aprovado automaticamente pelo cadastro de usuários administrativos")
        }
    }

    public Map canSendDocumentsToAnalysis(Customer customer) {
        Map canSendDocuments = [allowedToSendDocument: true]

        if (customer.accountDisabled()) {
            canSendDocuments.allowedToSendDocument = false
            canSendDocuments.reason = "Conta desabilitada."
            return canSendDocuments
        }

        Boolean isApprovedDocumentStatus = customer.customerRegisterStatus.documentStatus.isApproved()
        Boolean hasAccountAutoApprovalEnabled = customer.hasAccountAutoApprovalEnabled()

        if (isApprovedDocumentStatus && !hasAccountAutoApprovalEnabled) {
            canSendDocuments.allowedToSendDocument = false
            canSendDocuments.reason = "Documentos já foram aprovados."
            return canSendDocuments
        }

        return canSendDocuments
    }

    public void updateAllStatusAsApprovedToSandboxAccount(Customer customer) {
        if (!AsaasEnvironment.isSandbox()) return

        updateCommercialInfoStatus(customer, Status.APPROVED)
        updateBankAccountInfoStatus(customer, Status.APPROVED)
        updateBoletoInfoStatus(customer, Status.APPROVED)
        updateDocumentStatus(customer, Status.APPROVED)
        updateGeneralStatus(customer, GeneralApprovalStatus.APPROVED, Utils.getMessageProperty("system.automaticApproval.description"))
    }

    public void setGeneralApprovalToPreApprovedIfPossible(Customer customer) {
        updateGeneralStatus(customer, GeneralApprovalStatus.PRE_APPROVED, null)
    }

    public void updateCommercialInfoStatus(Customer customer, Status status) {
    	updateStatus(customer, status, "commercialInfoStatus")

        if (!status.isPending()) {
            customerOnboardingStepService.finishCommercialInfoStep(customer)
        }
    }

    public void updateBankAccountInfoStatus(Customer customer, Status status) {
    	updateStatus(customer, status, "bankAccountInfoStatus")

        if (!status.isPending()) {
            customerOnboardingStepService.finishBankAccountStep(customer)
        }
    }

    public void updateBoletoInfoStatus(Customer customer, Status status) {
    	updateStatus(customer, status, "boletoInfoStatus")
    }

    public void updateDocumentStatus(Customer customer, Status status) {
        updateStatus(customer, status, "documentStatus")

        if (!status.isPending()) {
            customerOnboardingStepService.finishDocumentationStep(customer)
        }

        if (status == Status.AWAITING_APPROVAL) {
            setGeneralApprovalToPreApprovedIfPossible(customer)
            drawEmailValidationTestIfPossible(customer)
        }
    }

	public void updateGeneralStatus(Customer customer, GeneralApprovalStatus status, String observations) {
		updateStatus(customer, status, "generalApproval")

		if (status in [GeneralApprovalStatus.APPROVED, GeneralApprovalStatus.REJECTED]) {
			AsaasApplicationHolder.applicationContext.customerDocumentProxyService.deletePendingDocuments(customer)
			customerMessageService.notifyGeneralApprovalAnalysisResult(customer, status, observations)
            hubspotContactService.saveContactStatusUpdate(customer)
		}
		customerInteractionService.saveCustomerRegisterStatusGeneralApproval(customer, status, observations)

		if (status.isApproved()) {
            customerReceivableAnticipationConfigService.drawCreditCardAnticipationFeePricingAbTestIfNecessary(customer)

			customer.customerConfig.boletoBlockDate = null
			customerAlertNotificationService.notifyGeneralApprovalApproved(customer)

            customerEventService.save(customer, CustomerEventName.GENERAL_APPROVAL, null, new Date())

            customerProofOfLifeService.approveBySelfieIfPossible(customer)

            bacenCcsService.createStartOfRelationshipIfNecessary(customer)

            asyncActionService.saveActiveHermesAccount(customer)
            sageAccountService.onCustomerActivated(customer.id)

            customerOnboardingService.finish(customer.id)

            customerBankSlipBeneficiaryService.createAsyncBeneficiaryRegistrationIfNecessary(Payment.ASAAS_ONLINE_BOLETO_BANK_ID, customer)

            fraudTrackingAccountService.saveFraudTrackingAccountIfPossible(customer)
            referralService.saveUpdateToValidatedReferralStatus(customer.id, ReferralValidationOrigin.ACCOUNT_APPROVAL)

            scheduleCommercialInfoExpirationIfNecessary(customer)
        }

        if (status.isRejected()) {
            asyncActionService.saveBlockHermesAccount(customer)
            sageAccountService.onCustomerInactivated(customer.id)
        }
        receivableAnticipationValidationCacheService.evictIsFirstUse(customer.id)
	}

    public Long findNextGeneralAnalysis(User analyst, Map params) {
        Long customerIdGeneralAnalysisInProgress = CustomerGeneralAnalysis.query([column: "customer.id", analyst: analyst, status: CustomerGeneralAnalysisStatus.IN_PROGRESS]).get()
        if (customerIdGeneralAnalysisInProgress) return customerIdGeneralAnalysisInProgress

        Long customerId = findCustomerIdOfNextItemToBeAnalyzed(CustomerRegisterStatusAnalysisType.GENERAL, params)
        return customerId
    }

    public Integer countPendingAnalysisInQueue(CustomerRegisterStatusAnalysisType analysisType, Map params) {
        final Integer limit = 1
        final Integer offset = 0
        final Boolean isCount = true

        params += [analysisType: analysisType.isDocument() ? "documentAnalysis" : "customerGeneralAnalysis"]

        def queryTotalCount = CustomerRegisterStatusQueryBuilder.buildDocumentAndGeneralAnalysisListToBeAnalyzed(limit, offset, isCount, params)

        return queryTotalCount.list().get(0)
    }

    public void rejectGeneralStatus(Customer customer, String observations) {
		if (customer.customerRegisterStatus.generalApproval.isRejected()) return

        updateDocumentStatus(customer, Status.PENDING)
        updateGeneralStatus(customer, GeneralApprovalStatus.REJECTED, observations)

        callCenterOutgoingPhoneCallManagerService.saveAsyncCancellation(customer.id)
		mobilePushNotificationService.notifyGeneralApprovalAnalysisResult(customer)
        receivableAnticipationCancellationService.cancelAllPossible(customer, ReceivableAnticipationCancelReason.CUSTOMER_GENERAL_STATUS_REJECTED)
		treasureDataService.track(customer, TreasureDataEventType.GENERAL_STATUS_REJECTED)
        thirdPartyDocumentationOnboardingService.invalidateLastThirdPartyDocumentationOnboarding(customer.id)
        customerRegisterUpdateAnalysisRequestService.cancelPending(customer.id, "Cancelado por reprovação da conta")
    }

	public void approveGeneralStatus(Long customerId) {
		Customer customer = Customer.get(customerId)
		if (customer.customerRegisterStatus.generalApproval.isApproved()) return

		updateGeneralStatus(customer, GeneralApprovalStatus.APPROVED, null)
		mobilePushNotificationService.notifyGeneralApprovalAnalysisResult(customer)
		treasureDataService.track(customer, TreasureDataEventType.GENERAL_STATUS_APPROVED)

        receivableAnticipationPartnerConfigService.save(customer)
        queryRevenueServiceRegisterPartnerTreeIfNecessary(customer)
	}

	public void notifyIncompleteInfoForCheckoutIfNecessary(Customer customer) {
		if (!customer.mobilePhone) return
		if (![Status.PENDING, Status.REJECTED].contains(customer.customerRegisterStatus.documentStatus)) return

		if (customer.customerRegisterStatus.commercialInfoStatus == Status.PENDING) {
            smsSenderService.send("Olá! Para sua segurança, a retirada dos valores recebidos só poderá ser feita após seu cadastro estar completo.", customer.mobilePhone, false, [:])
            smsSenderService.send("Olá! Para agilizar a retirada dos valores, preencha o quanto antes as informações pendentes na área Minha Conta no ASAAS.", customer.mobilePhone, false, [:])
		} else {
            smsSenderService.send("Olá! Para sua segurança o pedido de transferência só será habilitado após seu cadastro estar completo.", customer.mobilePhone, false, [:])
            smsSenderService.send("Olá! Envie, o mais rápido possível, os documentos pendentes. Desta forma, você agiliza a retirada dos valores recebidos.", customer.mobilePhone, false, [:])
		}
	}

	public Map getDocumentationEditPermission(Customer customer) {
		Boolean hasBankAccountInfo = BankAccountInfo.hasBankAccountInfo(customer)

		Boolean shouldFillBankAccountInfo = !hasBankAccountInfo && !customer.bankAccountInfoApprovalIsNotRequired()
		Boolean shouldFillCommercialInfo = !customer.commercialInfoIsComplete()

		Map returnMap = [documentationEditAllowed: !shouldFillBankAccountInfo && !shouldFillCommercialInfo, shouldFillBankAccountInfo: shouldFillBankAccountInfo, shouldFillCommercialInfo: shouldFillCommercialInfo]

		return returnMap
	}

    public void updateDocumentStatusToAwaitingApproval(Long customerId, DocumentAnalysisStatus documentAnalysisStatus) {
        Customer customer = Customer.read(customerId)

        updateDocumentStatus(customer, Status.AWAITING_APPROVAL)
        customerDocumentAnalysisStatusService.updateDocumentAnalysisStatus(customer, documentAnalysisStatus)
    }

    private void drawEmailValidationTestIfPossible(Customer customer) {
        if (!abTestService.canDrawAbTestFollowingGrowthRules(customer)) return

        if (customer.isSignedUpThroughMobile()) return

        final Date emailValidationTestReleaseDate = CustomDateUtils.fromString("04/07/2024")
        if (customer.dateCreated < emailValidationTestReleaseDate) return

        abTestService.chooseVariant(grailsApplication.config.asaas.abtests.emailValidationAfterOnboarding.name, customer, AbTestPlatform.WEB)
    }

    private Long findCustomerIdOfNextItemToBeAnalyzed(CustomerRegisterStatusAnalysisType analysisType, Map params) {
        final Integer limit = 1
        final Integer offset = 0
        final Boolean isCount = false

        params += [analysisType: analysisType.isDocument() ? "documentAnalysis" : "customerGeneralAnalysis"]

        def query = CustomerRegisterStatusQueryBuilder.buildDocumentAndGeneralAnalysisListToBeAnalyzed(limit, offset, isCount, params)

        List result = query.list()
        if (!result) return null

        return Utils.toLong(result.first())
    }

    private void updateStatus(Customer customer, def status, String statusFieldName) {
        if (customer.customerRegisterStatus[statusFieldName] == status) return

        customer.customerRegisterStatus[statusFieldName] = status

        AsaasLogger.info(">>> Atualizando CustomerRegisterStatus do cliente [${customer.providerName}] [${statusFieldName} >> ${status.toString()}]")

        customer.customerRegisterStatus.save(flush: true, failOnError: true)

        if (statusFieldName != "generalApproval" && status == Status.APPROVED) {
            setGeneralApprovalToAwaitingApprovalIfNecessary(customer)
        }

        if (statusFieldName in ["commercialInfoStatus", "documentStatus"] && status in [Status.PENDING, Status.AWAITING_APPROVAL, Status.REJECTED]) {
            setGeneralApprovalToPendingIfNecessary(customer)
        }

        notifyAccountStatusChanged(customer, statusFieldName, status)
    }

    private void setGeneralApprovalToAwaitingApprovalIfNecessary(Customer customer) {
        if (!customer.customerRegisterStatus.isReadyForGeneralApprovalAnalysis()) return
        if (!customer.customerRegisterStatus.generalApproval.isPendingOrPreApproved()) return

        updateGeneralStatus(customer, GeneralApprovalStatus.AWAITING_APPROVAL, null)
        customer.customerRegisterStatus.lastGeneralStatusChange = new Date()
    }

    private void setGeneralApprovalToPendingIfNecessary(Customer customer) {
        if (customer.customerRegisterStatus.generalApproval.isPending()) return

        updateGeneralStatus(customer, GeneralApprovalStatus.PENDING, null)
    }

    private void notifyAccountStatusChanged(Customer customer, String statusFieldName, status) {
        PushNotificationRequestEvent event

        if (statusFieldName == "commercialInfoStatus") {
            switch (status) {
                case Status.APPROVED:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_COMMERCIAL_INFO_APPROVED
                    break
                case Status.AWAITING_APPROVAL:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_COMMERCIAL_INFO_AWAITING_APPROVAL
                    break
                case Status.PENDING:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_COMMERCIAL_INFO_PENDING
                    break
                case Status.REJECTED:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_COMMERCIAL_INFO_REJECTED
                    break
            }
        }

        if (statusFieldName == "bankAccountInfoStatus") {
            switch (status) {
                case Status.APPROVED:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_BANK_ACCOUNT_INFO_APPROVED
                    break
                case Status.AWAITING_APPROVAL:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_BANK_ACCOUNT_INFO_AWAITING_APPROVAL
                    break
                case Status.PENDING:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_BANK_ACCOUNT_INFO_PENDING
                    break
                case Status.REJECTED:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_BANK_ACCOUNT_INFO_REJECTED
                    break
            }
        }

        if (statusFieldName == "documentStatus") {
            switch (status) {
                case Status.APPROVED:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_DOCUMENT_APPROVED
                    break
                case Status.AWAITING_APPROVAL:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_DOCUMENT_AWAITING_APPROVAL
                    break
                case Status.PENDING:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_DOCUMENT_PENDING
                    break
                case Status.REJECTED:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_DOCUMENT_REJECTED
                    break
            }
        }

        if (statusFieldName == "generalApproval") {
            switch (status) {
                case GeneralApprovalStatus.APPROVED:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_GENERAL_APPROVAL_APPROVED
                    break
                case GeneralApprovalStatus.AWAITING_APPROVAL:
                case GeneralApprovalStatus.PRE_APPROVED:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_GENERAL_APPROVAL_AWAITING_APPROVAL
                    break
                case GeneralApprovalStatus.PENDING:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_GENERAL_APPROVAL_PENDING
                    break
                case GeneralApprovalStatus.REJECTED:
                    event = PushNotificationRequestEvent.ACCOUNT_STATUS_GENERAL_APPROVAL_REJECTED
                    break
            }
        }

        if (event) {
            pushNotificationRequestAccountStatusService.save(event, customer.id)
        }
    }

    private void queryRevenueServiceRegisterPartnerTreeIfNecessary(Customer customer) {
        if (!customer.isLegalPerson()) return
        task {
            Utils.withNewTransactionAndRollbackOnError({
                revenueServiceRegisterManagerService.savePartnerTreeRequest(customer.id, customer.cpfCnpj)
            }, [onError: { Exception exception ->
                AsaasLogger.error("CustomerRegisterStatusService.queryRevenueServiceRegisterPartnerTreeIfNecessary >> ocorreu um erro ao solicitar a consulta de arvore de sócios. Customer [${customer.id}].", exception)
            }])
        }
    }

    private void scheduleCommercialInfoExpirationIfNecessary(Customer customer) {
        Boolean hasCommercialInfoExpiration = CustomerCommercialInfoExpiration.query([exists: true, customerId: customer.id]).get().asBoolean()
        if (hasCommercialInfoExpiration) return

        customerCommercialInfoExpirationService.save(customer)
    }
}
