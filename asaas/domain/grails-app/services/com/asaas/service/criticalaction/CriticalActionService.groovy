package com.asaas.service.criticalaction

import com.asaas.authorizationdevice.AuthorizationDeviceValidator
import com.asaas.credittransferrequest.CreditTransferRequestStatus
import com.asaas.criticalaction.CriticalActionStatus
import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CriticalActionService {

    def asaasCardRechargeService
    def asaasSegmentioService
    def bankAccountInfoService
    def bankAccountInfoUpdateRequestService
    def billService
    def creditTransferRequestAuthorizationService
    def creditTransferRequestService
    def criticalActionConfigService
    def criticalActionNotificationService
    def crypterService
    def customerInteractionService
    def customerMessageService
    def customerStatusService
    def customerUpdateRequestService
    def internalTransferService
    def mobilePhoneRechargeService
    def mobilePushNotificationService
    def pixDebitAuthorizationService
    def pixRefundService

    public CriticalActionGroup saveAndSendSynchronous(Customer customer, CriticalActionType type, Object object, String authorizationMessage) {
        return saveAndGroupSynchronous(customer, type, object, null, authorizationMessage)
    }

    public CriticalActionGroup saveAndSendSynchronous(Customer customer, CriticalActionType type, String hash) {
        return saveAndGroupSynchronous(customer, type, null, hash, null)
    }

    public CriticalActionGroup saveAndSendSynchronous(Customer customer, CriticalActionType type, String hash, String authorizationMessage) {
        return saveAndGroupSynchronous(customer, type, null, hash, authorizationMessage)
    }

    public CriticalActionGroup group(Customer customer, List<Long> actions) {
        return group(customer, actions, false, null)
    }

    public CriticalActionGroup group(Customer customer, List<Long> actions, Boolean synchronous, String authorizationMessage) {
        List<CriticalAction> criticalActionList = CriticalAction.pending([customer: customer, idList: actions, includeSynchronous: synchronous]).list()
        if (!criticalActionList) {
            AsaasLogger.warn("CriticalActionService.CriticalActionService >> Nenhuma ação pendente localizada para o cliente [${customer.id}]. Ids das ações: ${actions}.")
            throw new ResourceNotFoundException(Utils.getMessageProperty("criticalAction.error.resourceNotFound"))
        }

        CriticalActionGroup group = new CriticalActionGroup()
        group.customer = customer
        group.authorizationDevice = findValidAuthorizationDevice(customer)
        group.synchronous = synchronous
        group.save(failOnError: true)

        for (CriticalAction criticalAction : criticalActionList) {
            criticalAction.group = group
            criticalAction.status = CriticalActionStatus.AWAITING_AUTHORIZATION
            criticalAction.save(failOnError: true)
            group.addToActions(criticalAction)
        }

        if (group.authorizationDevice.type.isSmsToken()) {
            String token = group.authorizationDevice.buildToken()
            group.authorizationToken = crypterService.encryptDomainProperty(group, "authorizationToken", token)
            criticalActionNotificationService.sendAuthorizationTokenSMS(customer, group, authorizationMessage)
            asaasSegmentioService.track(customer.id, "Logged :: Token :: Token enviado", [providerEmail: customer.email, group: group.id, actionListSize: criticalActionList.size()])
        }

        return group
    }

    public CriticalActionGroup groupAndAuthorize(Customer customer, List<Long> actions, String authorizationToken) {
        CriticalAction criticalAction = CriticalAction.query([customer: customer, id: actions.first(), includeSynchronous: true, includeDeleted: true]).get()
        if (criticalAction.deleted) throw new BusinessException("A ação crítica não encontrada.")
        if (!criticalAction.status.isPending() && !criticalAction.status.isVerified()) throw new BusinessException("A ação crítica não está aguardando autorização.")
        CriticalActionGroup group = criticalAction.group ?: group(customer, actions)

        return authorizeGroup(customer, group.id, authorizationToken)
    }

    public BusinessValidation verifySynchronous(Customer customer, Long groupId, String token, CriticalActionType type, String hash) {
        CriticalAction criticalAction = getSynchronous(customer, type, null, hash)

        BusinessValidation businessValidation = validateSynchronous(customer, groupId, criticalAction, type, null, hash)
        if (!businessValidation.isValid()) return businessValidation

        CriticalActionGroup verifiedCriticalActionGroup = verifyGroup(customer, criticalAction.group.id, token)
        if (!verifiedCriticalActionGroup.isVerified()) {
            String errorCode = "criticalAction.error.invalid.token.withRemainingAuthorizationAttempts"
            List errorArguments = [verifiedCriticalActionGroup.getRemainingAuthorizationAttempts()]

            if (verifiedCriticalActionGroup.getRemainingAuthorizationAttempts() == 1) {
                errorCode = "criticalAction.error.invalid.token.withoutRemainingAuthorizationAttempts"
                errorArguments = [AuthorizationDevice.findCurrentTypeDescription(customer)]
            }

            businessValidation.addError(errorCode, errorArguments)
            return businessValidation
        }

        return businessValidation
    }

    public BusinessValidation authorizeSynchronousWithNewTransaction(Long customerId, Long groupId, String token, CriticalActionType type, String hash) {
        BusinessValidation businessValidation
        Utils.withNewTransactionAndRollbackOnError({
            Customer customer = Customer.read(customerId)
            businessValidation = authorizeSynchronous(customer, groupId, token, type, null, hash)
        }, [
            logErrorMessage: "${this.getClass().getSimpleName()}.authorizeSynchronousWithNewTransaction >> Erro ao autorizar ação crítica síncrona [criticalActionType: ${type}, customerId: ${customerId}, groupId: ${groupId}]",
            onError: { Exception exception -> throw exception }
        ])
        return businessValidation
    }

    /**
     * use authorizeSynchronousWithNewTransaction
     */
    @Deprecated
    public BusinessValidation authorizeSynchronous(Customer customer, Long groupId, String token, CriticalActionType type, Object object, String hash) {
        CriticalAction criticalAction = getSynchronous(customer, type, object, hash)

        BusinessValidation businessValidation = validateSynchronous(customer, groupId, criticalAction, type, object, hash)
        if (!businessValidation.isValid()) return businessValidation

        CriticalActionGroup authorizedCriticalActionGroup = authorizeGroup(customer, criticalAction.group.id, token)
        if (!authorizedCriticalActionGroup.isAuthorized()) {
            String errorCode = "criticalAction.error.invalid.token.withRemainingAuthorizationAttempts"
            List errorArguments = [authorizedCriticalActionGroup.getRemainingAuthorizationAttempts()]

            if (authorizedCriticalActionGroup.getRemainingAuthorizationAttempts() == 1) {
                errorCode = "criticalAction.error.invalid.token.withoutRemainingAuthorizationAttempts"
                errorArguments = [AuthorizationDevice.findCurrentTypeDescription(customer)]
            }

            businessValidation.addError(errorCode, errorArguments)
            return businessValidation
        }

        return businessValidation
    }

    public CriticalActionGroup verifyGroup(Customer customer, Long groupId, String authorizationToken) {
        CriticalActionGroup group = CriticalActionGroup.find(customer, groupId)

        if (!group.status.isAwaitingAuthorization()) throw new RuntimeException("A ação crítica não está aguardando autorização.")
        if (!group.authorizationDevice.status.isActive() || group.authorizationDevice.locked) throw new RuntimeException("A verificação desta ação crítica está bloqueada.")
        if (group.isMaxAuthorizationAttemptsExceeded()) return group

        group.authorizationAttempts++

        Boolean isValidToken = validateToken(group, authorizationToken)
        if (isValidToken) {
            processGroupVerification(group)
            asaasSegmentioService.track(customer.id, "Logged :: Token :: Validação verificada", [providerEmail: customer.email, group: group.id, authorizationToken: authorizationToken, type: group.authorizationDevice.type.toString()])
        } else {
            processNotValidToken(customer, group, authorizationToken)
        }

        return group
    }

    public CriticalActionGroup authorizeGroup(Customer customer, Long groupId, String authorizationToken) {
        CriticalActionGroup group = CriticalActionGroup.find(customer, groupId)

        if (!group.status.isAwaitingAuthorization() && !group.status.isVerified()) throw new BusinessException("A ação crítica não está aguardando autorização.")
        if (!group.authorizationDevice.status.isActive()) throw new BusinessException(Utils.getMessageProperty("criticalAction.error.authorizationDeviceNotFound"))
        if (group.authorizationDevice.locked) throw new BusinessException(Utils.getMessageProperty("criticalAction.error.authorizationDeviceLocked"))
        if (group.isMaxAuthorizationAttemptsExceeded()) return group

        if (!group.status.isVerified()) group.authorizationAttempts++

        Boolean isValidToken = validateToken(group, authorizationToken)
        if (isValidToken) {
            processGroupAuthorization(group)
            asaasSegmentioService.track(customer.id, "Logged :: Token :: Validação confirmada", [providerEmail: customer.email, group: group.id, authorizationToken: authorizationToken, type: group.authorizationDevice.type.toString()])
        } else {
            processNotValidToken(customer, group, authorizationToken)
        }

        return group
    }

    public CriticalActionGroup processGroupAuthorization(CriticalActionGroup criticalActionGroup) {
        criticalActionGroup.status = CriticalActionStatus.AUTHORIZED
        criticalActionGroup.authorizer = UserUtils.getCurrentUser()
        criticalActionGroup.save(failOnError: true)

        processGroupActionsAuthorization(criticalActionGroup)

        return criticalActionGroup
    }

    public CriticalActionGroup cancelGroup(Customer customer, Long groupId) {
        CriticalActionGroup group = CriticalActionGroup.find(customer, groupId)
        group.status = CriticalActionStatus.CANCELLED
        group.deleted = true

        group.save(failOnError: true)

        asaasSegmentioService.track(customer.id, "Logged :: Token :: Validação cancelada", [providerEmail: customer.email, group: groupId])

        for (CriticalAction action : group.actions) {
            if (action.status == CriticalActionStatus.AUTHORIZED) {
                AsaasLogger.error("Cancelamento do acao critica [${action.id}] impedida.")
                throw new RuntimeException("Não é possível cancelar ações já autorizadas.")
            }

            action.status = CriticalActionStatus.PENDING
            action.group = null

            action.save(failOnError: true)
        }
    }

    public void cancelGroupAndActions(Customer customer, Long groupId) {
        CriticalActionGroup group = CriticalActionGroup.find(customer, groupId)
        group.status = CriticalActionStatus.CANCELLED
        group.deleted = true

        group.save(failOnError: true)

        for (CriticalAction action : group.actions) {
            cancel(action)
        }
    }

    public void cancelList(Customer customer, List<Long> actionList) {
        asaasSegmentioService.track(customer.id, "Logged :: Token :: Eventos excluídos", [providerEmail: customer.email, actionList: actionList.toString(), actionListSize: actionList.size()])

        for (Long id : actionList) {
            cancel(customer, id)
        }
    }

    public CriticalAction cancel(Customer customer, Long actionId) {
        cancel(CriticalAction.find(customer, actionId))
    }

    public void cancel(CriticalAction action) {
        if (action.group && !action.group.status.isCancelled()) {
            cancelGroup(action.customer, action.group.id)
        }

        action.cancel()

        customerInteractionService.saveCriticalActionCancelEvent(action.customerId, action.type)

        if (CriticalActionType.getNothingNecessaryOnCancellationList().contains(action.type)) return

        if (action.type == CriticalActionType.TRANSFER) {
            creditTransferRequestAuthorizationService.onCriticalActionCancellation(action)
        } else if (action.type == CriticalActionType.COMMERCIAL_INFO_UPDATE && action.customerUpdateRequest) {
            customerUpdateRequestService.onCriticalActionCancellation(action)
        } else if (action.type == CriticalActionType.BANK_ACCOUNT_UPDATE && action.bankAccountInfoUpdateRequest) {
            bankAccountInfoUpdateRequestService.onCriticalActionCancellation(action)
        } else if (action.type == CriticalActionType.BANK_ACCOUNT_UPDATE && action.bankAccountInfo) {
            bankAccountInfoService.onCriticalActionUpdateCancellation(action)
        } else if (action.type == CriticalActionType.BANK_ACCOUNT_INSERT && action.bankAccountInfo) {
            bankAccountInfoService.onCriticalActionUpdateCancellation(action)
        } else if (action.type == CriticalActionType.BILL_INSERT) {
            if (action.bill && !action.synchronous) billService.onCriticalActionInsertCancellation(action)
        } else if (action.type == CriticalActionType.ASAAS_CARD_RECHARGE && action.asaasCardRecharge) {
            asaasCardRechargeService.onCriticalActionCancellation(action)
        } else if (action.type == CriticalActionType.INTERNAL_TRANSFER) {
            internalTransferService.onCriticalActionCancellation(action)
        } else if (action.type == CriticalActionType.ACCOUNT_DISABLE) {
            customerStatusService.onCriticalActionCancellation(action.customer)
        } else if (action.type == CriticalActionType.MOBILE_PHONE_RECHARGE_INSERT) {
            mobilePhoneRechargeService.onCriticalActionInsertCancellation(action)
        } else if (action.type == CriticalActionType.PIX_DEBIT_TRANSACTION) {
            pixDebitAuthorizationService.onCriticalActionCancellation(action)
        } else if (action.type == CriticalActionType.PIX_REFUND_CREDIT) {
            pixRefundService.onCriticalActionCancellation(action)
        } else {
            throw new RuntimeException("Não é possível cancelar a ação [${action.id}]: tipo [${action.type.toString()}] não suportado.")
        }
    }

    public void cancelExpired(Customer customer) {
        List<CriticalAction> actionList = CriticalAction.query([customer: customer, status: CriticalActionStatus.EXPIRED]).list()
        actionList = actionList.findAll { it.group == null }

        actionList.each {
            cancel(it)
        }
        asaasSegmentioService.track(customer.id, "Logged :: Token :: Eventos expirados excluídos", [providerEmail: customer.email, actionListSize: actionList.size()])
    }

    public void expire(CriticalAction action) {
        if (!action.status.isPending() && !action.status.isAwaitingAuthorization()) throw new BusinessException("Ação critica [${action.id}] em status que não pode ser expirado.")

        if (action.synchronous && action.group) {
            action.group.status = CriticalActionStatus.EXPIRED
            action.group.save(failOnError: true)
        }

        action.status = CriticalActionStatus.EXPIRED
        action.group = null
        action.deleted = false
        action.save(failOnError: true)

        if (!action.synchronous) {
            customerMessageService.notifyExpiredCriticalAction(action.customer, action)
            mobilePushNotificationService.notifyExpiredCriticalAction(action)

            if (action.bill) {
                customerMessageService.notifyBillExpiredCriticalAction(action.customer, action)
            } else if (action.type == CriticalActionType.PIX_DEBIT_TRANSACTION) {
                pixDebitAuthorizationService.onCriticalActionAuthorizationExpiration(action)
            }
        }
    }

    public void notifyNotAuthorizedTransferRequestActions() {
        List<CriticalAction> transferCriticalActionList = CriticalAction.pendingOrAwaitingAuthorization([ignoreCustomer: true, type: CriticalActionType.TRANSFER, transfer: [estimatedDebitDate: new Date().clearTime()]]).list()

        transferCriticalActionList.groupBy { it.customer }.each { customer, v ->
            customerMessageService.notifyNotAuthorizedTransferCriticalActions(customer)
            mobilePushNotificationService.notifyNotAuthorizedTransferCriticalActions(customer)
        }
    }

    public void deferNotAuthorizedTransferRequestActions() {
        List<Long> transferCriticalActionIdList = CriticalAction.pendingOrAwaitingAuthorization([column: "id", ignoreCustomer: true, type: CriticalActionType.TRANSFER, transfer: [estimatedDebitDate: new Date().clearTime()]]).list()

        for (Long transferCriticalActionId : transferCriticalActionIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CriticalAction transferCriticalAction = CriticalAction.get(transferCriticalActionId)

                transferCriticalAction.transfer.estimatedDebitDate = CustomDateUtils.sumDays(transferCriticalAction.transfer.estimatedDebitDate, 1).clearTime()
                CustomDateUtils.setDateForNextBusinessDayIfHoliday(transferCriticalAction.transfer.estimatedDebitDate)

                if (transferCriticalAction.transfer.status == CreditTransferRequestStatus.PENDING) {
                    creditTransferRequestService.setAsPendingOrAwaitingLiberation(transferCriticalAction.transfer)
                }

                transferCriticalAction.transfer.save(failOnError: true)

                customerMessageService.notifyDeferredNotAuthorizedTransferCriticalAction(transferCriticalAction.customer, transferCriticalAction)
                mobilePushNotificationService.notifyDeferredNotAuthorizedTransferCriticalAction(transferCriticalAction)
            }, [logErrorMessage: "Erro ao adiar a transferencia para autorização via Token SMS/App [${transferCriticalActionId}]"])
        }
    }

    public void deferNotAuthorizedCardRechargeActions() {
        List<CriticalAction> rechargeCriticalActionList = CriticalAction.pendingOrAwaitingAuthorization([ignoreCustomer: true, type: CriticalActionType.ASAAS_CARD_RECHARGE, asaasCardRecharge: ['creditDate[lt]': new Date().clearTime()]]).list()

        for (CriticalAction rechargeCriticalAction : rechargeCriticalActionList) {
            rechargeCriticalAction.asaasCardRecharge.creditDate = CustomDateUtils.sumDays(rechargeCriticalAction.asaasCardRecharge.creditDate, 1).clearTime()
            CustomDateUtils.setDateForNextBusinessDayIfHoliday(rechargeCriticalAction.asaasCardRecharge.creditDate)
            rechargeCriticalAction.asaasCardRecharge.save(failOnError: true)
        }
    }

    public void expireSynchronousCriticalAction() {
        try {
            List<Long> synchronousCriticalActionIdList = CriticalAction.pendingOrAwaitingAuthorization([column: "id",
                                                                                                        includeSynchronous: true,
                                                                                                        synchronous: true,
                                                                                                        ignoreCustomer: true,
                                                                                                        "dateCreated[le]": CustomDateUtils.sumMinutes(new Date(), CriticalAction.SYNCHRONOUS_EXPIRATION_MINUTES * -1)]).list()
            if (!synchronousCriticalActionIdList) return

            for (Long criticalActionId : synchronousCriticalActionIdList) {
                Utils.withNewTransactionAndRollbackOnError({
                    CriticalAction criticalAction = CriticalAction.get(criticalActionId)
                    expire(criticalAction)
                }, [logErrorMessage: "CriticalActionService.expireSynchronousCriticalAction -> Erro ao expirar CriticalAction Síncrona [CriticalAction.id: ${criticalActionId}]."])
            }
        } catch (Exception exception) {
            AsaasLogger.error("CriticalActionService.expireSynchronousCriticalAction -> Erro ao expirar CriticalAction Síncrona. ", exception)
        }
    }

    public void notifyCriticalActionsAwaitingAuthorization() {
        Map customerCriticalActionMap = CriticalAction.pendingOrAwaitingAuthorization([ignoreCustomer: true, typeList: [CriticalActionType.ASAAS_CARD_RECHARGE, CriticalActionType.BILL_INSERT, CriticalActionType.BILL_DELETE, CriticalActionType.TRANSFER, CriticalActionType.TRANSFER_CANCELLING]]).list().groupBy { it.customer }

        customerCriticalActionMap.each { Customer customer, List<CriticalAction> criticalActionList ->
            customerMessageService.notifyCriticalActionsAwaitingAuthorization(customer)
            mobilePushNotificationService.notifyCriticalActionsAwaitingAuthorization(customer)
        }
    }

    public void cancelPendingOrAwaitingAuthorizationFromCustomer(Customer customer) {
        List<Long> pendingCriticalActionList = CriticalAction.pendingOrAwaitingAuthorization([column: "id", customer: customer]).list()

        if (!pendingCriticalActionList) return

        cancelList(customer, pendingCriticalActionList)
    }

    private CriticalActionGroup saveAndGroupSynchronous(Customer customer, CriticalActionType type, Object object, String hash, String authorizationMessage) {
        Map search = [includeSynchronous: true, synchronous: true, customer: customer, type: type]
        if (hash) search.actionHash = hash
        if (object) search."${CriticalAction.getAttributeNameByObject(object)}" = object

        CriticalAction existingCriticalAction = CriticalAction.pendingOrAwaitingAuthorization(search).get()
        if (existingCriticalAction) cancelGroupAndActions(customer, existingCriticalAction.group.id)

        if (type.isValidatedWithHash()) {
            if (!hash) throw new RuntimeException("Operação inválida.")
        } else {
            if (!object) throw new RuntimeException("Operação inválida.")
        }

        CriticalAction criticalAction = CriticalAction.save(customer, type, object)
        if (hash) criticalAction.actionHash = hash
        criticalAction.synchronous = true
        criticalAction.save(failOnError: true)

        if (!authorizationMessage) {
            authorizationMessage = Utils.getMessageProperty("criticalActionDefaultAuthorizationMessage.${type.toString()}")
        }

        return group(customer, [criticalAction.id], true, authorizationMessage)
    }

    private CriticalActionGroup processGroupVerification(CriticalActionGroup criticalActionGroup) {
        criticalActionGroup.status = CriticalActionStatus.VERIFIED
        criticalActionGroup.save(failOnError: true)

        processGroupActionsVerification(criticalActionGroup)

        return criticalActionGroup
    }

    private Boolean validateToken(CriticalActionGroup group, String authorizationToken) {
        if (group.authorizationDevice.type.isMobileAppToken() && group.status.isVerified()) return true
        AuthorizationDeviceValidator authorizationDeviceValidator = AuthorizationDeviceValidator.getInstance(group)

        return authorizationDeviceValidator.validate(authorizationToken)
    }

    private void processNotValidToken(Customer customer, CriticalActionGroup group, String authorizationToken) {
        if (group.isMaxAuthorizationAttemptsExceeded()) {
            group.authorizationDevice.setAsLocked()
            group.authorizationDevice.save(failOnError: true)
            asaasSegmentioService.track(customer.id, "Logged :: Token :: Validação bloqueada", [providerEmail: customer.email, group: group.id, authorizationToken: authorizationToken])
        }
        asaasSegmentioService.track(customer.id, "Logged :: Token :: Validação falhada", [providerEmail: customer.email, group: group.id, authorizationToken: authorizationToken])
    }

    private CriticalAction getSynchronous(Customer customer, CriticalActionType type, Object object, String hash) {
        Map search = [includeSynchronous: true, synchronous: true, customer: customer, type: type, sort: "id", order: "desc"]
        if (type.isValidatedWithHash()) {
            search.actionHash = hash
        } else {
            search."${CriticalAction.getAttributeNameByObject(object)}" = object
        }

        return CriticalAction.verifiedOrAwaitingAuthorization(search).get()
    }

    private BusinessValidation validateSynchronous(Customer customer, Long groupId, CriticalAction criticalAction, CriticalActionType type, Object object, String hash) {
        BusinessValidation businessValidation = new BusinessValidation()
        if (!criticalAction) {
            businessValidation.addError("criticalAction.error.mustBeInformed")
            return businessValidation
        }

        CriticalActionGroup criticalActionGroup = CriticalActionGroup.find(customer, groupId)
        if (criticalActionGroup != criticalAction.group) {
            businessValidation.addError("criticalAction.error.expired")
            return businessValidation
        }
        Boolean invalidCriticalAction = ((type.isValidatedWithHash() && hash != criticalAction.actionHash) || (!type.isValidatedWithHash() && object != criticalAction.getObject()))
        if (invalidCriticalAction) {
            businessValidation.addError("criticalAction.error.expired")
        }
        return businessValidation
    }

    private void processGroupActionsVerification(CriticalActionGroup group) {
        for (CriticalAction action : group.actions) {
            if (action.status == CriticalActionStatus.EXPIRED) {
                action.deleted = true
                action.save(flush: true)
            } else {
                processActionVerification(action)
            }
        }
    }

    private void processGroupActionsAuthorization(CriticalActionGroup group) {
        for (CriticalAction action : group.actions) {
            if (action.status == CriticalActionStatus.EXPIRED) {
                action.deleted = true
                action.save(flush: true)
            } else {
                processActionAuthorization(action)
            }
        }
    }

    private void processActionVerification(CriticalAction action) {
        action.status = CriticalActionStatus.VERIFIED
        action.save(flush: true)

        customerInteractionService.saveCriticalActionVerificationEvent(action.customerId, action.type)
    }

    private void processActionAuthorization(CriticalAction action) {
        action.status = CriticalActionStatus.AUTHORIZED
        action.deleted = true
        action.save(flush: true)

        if (action.synchronous) return

        if ([CriticalActionType.TRANSFER, CriticalActionType.TRANSFER_CANCELLING].contains(action.type)) {
            creditTransferRequestAuthorizationService.onCriticalActionAuthorization(action)
        } else if (action.type == CriticalActionType.BANK_ACCOUNT_UPDATE && action.bankAccountInfoUpdateRequest) {
            bankAccountInfoUpdateRequestService.onCriticalActionAuthorization(action)
        } else if (action.type == CriticalActionType.COMMERCIAL_INFO_UPDATE && action.customerUpdateRequest) {
            customerUpdateRequestService.onCriticalActionAuthorization(action.customerUpdateRequest)
        } else if (action.type == CriticalActionType.BANK_ACCOUNT_UPDATE && action.bankAccountInfo) {
            bankAccountInfoService.onCriticalActionUpdateAuthorization(action)
        } else if (action.type == CriticalActionType.BANK_ACCOUNT_INSERT && action.bankAccountInfo) {
            bankAccountInfoService.onCriticalActionInsertAuthorization(action)
        } else if (action.type == CriticalActionType.BANK_ACCOUNT_DELETE && action.bankAccountInfo) {
            bankAccountInfoService.onCriticalActionDeleteAuthorization(action)
        } else if (action.type == CriticalActionType.BILL_INSERT && action.bill) {
            billService.onCriticalActionInsertAuthorization(action)
        } else if (action.type == CriticalActionType.BILL_DELETE && action.bill) {
            billService.onCriticalActionDeleteAuthorization(action)
        } else if (action.type == CriticalActionType.ASAAS_CARD_RECHARGE && action.asaasCardRecharge) {
            asaasCardRechargeService.onCriticalActionAuthorization(action)
        } else if (action.type in [CriticalActionType.DISABLE_TRANSFER, CriticalActionType.DISABLE_USER_UPDATE, CriticalActionType.DISABLE_BANK_ACCOUNT_UPDATE, CriticalActionType.DISABLE_BILL, CriticalActionType.DISABLE_COMMERCIAL_INFO_UPDATE, CriticalActionType.DISABLE_ASAAS_CARD_RECHARGE, CriticalActionType.DISABLE_FOR_PIX_TRANSACTION, CriticalActionType.DISABLE_MOBILE_PHONE_RECHARGE, CriticalActionType.DISABLE_PIX_TRANSACTION_CREDIT_REFUND]) {
            criticalActionConfigService.onCriticalActionAuthorization(action)
        } else if (action.type == CriticalActionType.ACCOUNT_DISABLE) {
            customerStatusService.executeAccountDisable(action.customer.id, action.requester)
        } else if (action.type == CriticalActionType.INTERNAL_TRANSFER) {
            internalTransferService.onCriticalActionAuthorization(action)
        } else if (action.type == CriticalActionType.MOBILE_PHONE_RECHARGE_INSERT) {
            mobilePhoneRechargeService.onCriticalActionInsertAuthorization(action)
        } else if (action.type == CriticalActionType.PIX_DEBIT_TRANSACTION) {
            pixDebitAuthorizationService.onCriticalActionAuthorization(action)
        } else if (action.type == CriticalActionType.PIX_REFUND_CREDIT) {
            pixRefundService.onCriticalActionAuthorization(action)
        } else {
            throw new RuntimeException("Não é possível autorizar a ação [${action.id}]: tipo [${action.type.toString()}] não suportado.")
        }

        customerInteractionService.saveCriticalActionAuthorizationEvent(action.customerId, action.type)
    }

    private AuthorizationDevice findValidAuthorizationDevice(Customer customer) {
        if (!customer) throw new RuntimeException("Cliente não informado na busca de um dispositivo de autorização")

        AuthorizationDevice device = AuthorizationDevice.active([customer: customer]).get()

        if (!device) {
            AsaasLogger.warn("CriticalActionService.findValidAuthorizationDevice > Cliente sem dispositivo de autorização. CustomerId: [${customer.id}]")
            throw new BusinessException(Utils.getMessageProperty("criticalAction.error.authorizationDeviceNotFound"))
        }

        if (device.locked) {
            AsaasLogger.warn("CriticalActionService.findValidAuthorizationDevice > Cliente com dispositivo de autorização bloqueado. CustomerId: [${customer.id}]")
            throw new BusinessException(Utils.getMessageProperty("criticalAction.error.authorizationDeviceLocked"))
        }

        return device
    }
}
