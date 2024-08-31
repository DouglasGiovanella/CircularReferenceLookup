package com.asaas.service.asaascard

import com.asaas.asaascard.AsaasCardRechargeStatus
import com.asaas.asaascard.AsaasCardStatus
import com.asaas.checkout.CheckoutValidator
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascard.AsaasCardRecharge
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.userpermission.AdminUserPermissionUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class AsaasCardRechargeService {

    def bifrostPrepaidCardService
    def customerInteractionService
    def financialTransactionService
    def lastCheckoutInfoService
    def messageService
    def mobilePushNotificationService
    def smsSenderService

    public AsaasCardRecharge save(AsaasCard asaasCard, BigDecimal value) {
        lastCheckoutInfoService.save(asaasCard.customer)

        AsaasCardRecharge asaasCardRecharge =  validateSave(asaasCard, value)
        if (asaasCardRecharge.hasErrors()) return asaasCardRecharge

        asaasCardRecharge.value = value
        asaasCardRecharge.asaasCard = asaasCard
        asaasCardRecharge.creator = UserUtils.getCurrentUser()
        asaasCardRecharge.creditDate = new Date().clearTime()

        Boolean criticalActionConfigAsaasCardRechargeEnabled = CustomerCriticalActionConfig.query([column: "asaasCardRecharge", customerId: asaasCard.customerId]).get()
        asaasCardRecharge.awaitingCriticalActionAuthorization = criticalActionConfigAsaasCardRechargeEnabled

        asaasCardRecharge = setAsPending(asaasCardRecharge)
        financialTransactionService.saveAsaasCardRecharge(asaasCardRecharge)

        if (asaasCardRecharge.awaitingCriticalActionAuthorization) CriticalAction.saveAsaasCardRecharge(asaasCardRecharge)

        return asaasCardRecharge
    }

    public AsaasCardRecharge validateMonthlyRechargeLimit(Long id, Long customerId, BigDecimal value) {
        if (!value) value = 0

        AsaasCardRecharge asaasCardRecharge = new AsaasCardRecharge()
        AsaasCard asaasCard = AsaasCard.find(id, customerId)

        List<Long> cardIdsForCpfCnpj = AsaasCard.query([column: 'id', holderCpfCnpj: asaasCard.holder.cpfCnpj]).list()

        Date rechargeMonthStart = CustomDateUtils.getFirstDayOfMonth(new Date().clearTime())
        Date rechargeMonthEnd =  CustomDateUtils.getLastDayOfMonth(new Date())
        BigDecimal holderRechargedInMonth = AsaasCardRecharge.sumValueAbs(["asaasCardId[in]": cardIdsForCpfCnpj, "creditDate[ge]": rechargeMonthStart, "creditDate[le]": rechargeMonthEnd, "status[ne]": AsaasCardRechargeStatus.CANCELLED]).get()

        if (holderRechargedInMonth + value > AsaasCardRecharge.MONTHLY_LIMIT) {
            DomainUtils.addError(asaasCardRecharge, "Você não pode ter mais de " + FormUtils.formatCurrencyWithMonetarySymbol(AsaasCardRecharge.MONTHLY_LIMIT) + " mensais em recargas nos cartões desse portador.")
        }

        return asaasCardRecharge
    }

    public AsaasCardRecharge resend(Long id) {
        AsaasCardRecharge asaasCardRecharge = AsaasCardRecharge.get(id)
        validateResend(asaasCardRecharge)
        return updateStatus(asaasCardRecharge, AsaasCardRechargeStatus.PENDING)
    }

    public AsaasCardRecharge cancel(Long id, String reason) {
        AsaasCardRecharge asaasCardRecharge = AsaasCardRecharge.get(id)

        cancel(asaasCardRecharge)

        if (!asaasCardRecharge.hasErrors()) {
            customerInteractionService.save(asaasCardRecharge.asaasCard.customer, "Motivo do cancelamento de recarga do cartão Asaas: ${reason}")
        }

        return asaasCardRecharge
    }

    public AsaasCardRecharge cancel(AsaasCardRecharge asaasCardRecharge) {
        if (!asaasCardRecharge.canBeCancelled()) return DomainUtils.addError(asaasCardRecharge, asaasCardRecharge.cancelDisabledReason)

        updateStatus(asaasCardRecharge, AsaasCardRechargeStatus.CANCELLED)
        financialTransactionService.cancelAsaasCardRecharge(asaasCardRecharge)
        CriticalAction.deleteNotAuthorized(asaasCardRecharge)

        return asaasCardRecharge
    }

    public List<AsaasCardRecharge> cancelWhileBalanceIsNegative(Customer customer) {
        List<AsaasCardRecharge> cancelledAsaasCardRechargeList = []

        if (FinancialTransaction.getCustomerBalance(customer) >= 0) return cancelledAsaasCardRechargeList

        List<AsaasCardRecharge> asaasCardRechargeList = AsaasCardRecharge.cancellable([customer: customer, sort: "creditDate", order: "desc"]).list()

        for (AsaasCardRecharge asaasCardRecharge : asaasCardRechargeList) {
            cancel(asaasCardRecharge)

            if (asaasCardRecharge.hasErrors()) throw new RuntimeException("Não é possível cancelar a recarga [${asaasCardRecharge.id}]")

            cancelledAsaasCardRechargeList.add(asaasCardRecharge)

            if (FinancialTransaction.getCustomerBalance(customer) >= 0) return cancelledAsaasCardRechargeList
        }

        return cancelledAsaasCardRechargeList
    }

    public void cancelIfPossible(AsaasCard asaasCard) {
        List<AsaasCardRecharge> unprocessedRecharges = AsaasCardRecharge.cancellable([asaasCard: asaasCard, customer: asaasCard.customer]).list()

        for (AsaasCardRecharge recharge : unprocessedRecharges) {
            cancel(recharge)
        }
    }

    public void releaseRechargesWaitingCardActivation(AsaasCard asaasCard) {
        List<AsaasCardRecharge> asaasCardRecharges = AsaasCardRecharge.query([customer: asaasCard.customer, asaasCard: asaasCard, status: AsaasCardRechargeStatus.WAITING_CARD_ACTIVATION]).list()

        for (AsaasCardRecharge asaasCardRecharge : asaasCardRecharges) {
            asaasCardRecharge.creditDate = new Date().clearTime()
            asaasCardRecharge.status = AsaasCardRechargeStatus.PENDING
            asaasCardRecharge.save(failOnError: true)
        }
    }

    public void processPendingRecharges() {
        List<Long> asaasCardRechargeIds = AsaasCardRecharge.readyForProcessing([column: "id"]).list()

        for (Long rechargeId : asaasCardRechargeIds) {
            Boolean rechargeSuccess

            AsaasCardRecharge.withNewTransaction { status ->
                try {
                    AsaasCardRecharge asaasCardRecharge = AsaasCardRecharge.get(rechargeId)
                    executeRecharge(asaasCardRecharge)

                    rechargeSuccess = true
                } catch (Exception exception) {
                    status.setRollbackOnly()
                    AsaasLogger.warn("AsaasCardRechargeService.processPendingRecharges() -> Erro na recarga do cartão. [id: ${rechargeId}] ${exception.message}")

                    rechargeSuccess = false
                }
            }

            if (rechargeSuccess) continue

            AsaasCardRecharge.withNewTransaction { status ->
                try {
                    AsaasCardRecharge asaasCardRecharge = AsaasCardRecharge.get(rechargeId)
                    setAsError(asaasCardRecharge)
                } catch (Exception exception) {
                    status.setRollbackOnly()
                    AsaasLogger.warn("AsaasCardRechargeService.processPendingRecharges() -> Erro ao atualizar o status da recarga para erro. [id:${rechargeId}] ${exception.message}")
                }
            }
        }
    }

    public void onCriticalActionAuthorization(CriticalAction criticalAction) {
        AsaasCardRecharge asaasCardRecharge = criticalAction.asaasCardRecharge
        asaasCardRecharge.awaitingCriticalActionAuthorization = false

        asaasCardRecharge.save(failOnError: true)
    }

    public void onCriticalActionCancellation(CriticalAction criticalAction) {
        criticalAction.asaasCardRecharge.awaitingCriticalActionAuthorization = false

        cancel(criticalAction.asaasCardRecharge)
    }

    public void updateBlockedRechargesToPending(Customer customer) {
        List<AsaasCardRecharge> asaasCardRechargeList = AsaasCardRecharge.query([customer: customer, status: AsaasCardRechargeStatus.BLOCKED]).list()

        for (AsaasCardRecharge asaasCardRecharge : asaasCardRechargeList) {
            setAsPending(asaasCardRecharge)
        }
    }

    public void updatePendingRechargesToBlocked(Customer customer) {
        List<AsaasCardRecharge> asaasCardRechargeList = AsaasCardRecharge.query([customer: customer, "status[in]": AsaasCardRechargeStatus.editableList()]).list()

        for (AsaasCardRecharge asaasCardRecharge : asaasCardRechargeList) {
            block(asaasCardRecharge)
        }
    }

    private AsaasCardRecharge validateSave(AsaasCard asaasCard, BigDecimal value) {
        AsaasCardRecharge asaasCardRecharge = new AsaasCardRecharge()

        BusinessValidation validatedBusiness = asaasCard.canRecharge()
        if (!validatedBusiness.isValid()) return DomainUtils.addError(asaasCardRecharge, validatedBusiness.getFirstErrorMessage())

        CheckoutValidator checkoutValidator = new CheckoutValidator(asaasCard.customer)
        if (!checkoutValidator.customerCanUseAsaasCard()) return DomainUtils.addError(asaasCardRecharge, Utils.getMessageProperty("asaasCardRecharge.denied.proofOfLife.${ asaasCard.customer.getProofOfLifeType() }.notApproved"))

        List<AsaasError> listOfAsaasError = checkoutValidator.validate(value)
        if (listOfAsaasError) return DomainUtils.addError(asaasCardRecharge, Utils.getMessageProperty("asaasCardRecharge." + listOfAsaasError.first().code))

        return validateMonthlyRechargeLimit(asaasCard.id, asaasCard.customer.id, value)
    }

    private void validateResend(AsaasCardRecharge asaasCardRecharge) {
        if (!AdminUserPermissionUtils.resendAsaasCardRecharge(UserUtils.getCurrentUser())) throw new RuntimeException("Usuário não tem permissão para reenviar uma recarga de AsaasCard.")
        if (!asaasCardRecharge) throw new RuntimeException("Recarga não encontrada.")
        if (!asaasCardRecharge.status.isError()) throw new RuntimeException("Você só pode reenviar uma recarga com erro.")
    }

    private AsaasCardRecharge updateStatus(AsaasCardRecharge asaasCardRecharge, AsaasCardRechargeStatus status) {
        asaasCardRecharge.status = status
        asaasCardRecharge.save(failOnError: true)

        return asaasCardRecharge
    }

    private void block(AsaasCardRecharge asaasCardRecharge) {
        updateStatus(asaasCardRecharge, AsaasCardRechargeStatus.BLOCKED)
    }

    private AsaasCardRecharge setAsPending(AsaasCardRecharge asaasCardRecharge) {
        Boolean isCardNotActivated = AsaasCardStatus.isNotActivated(asaasCardRecharge.asaasCard.status)
        return updateStatus(asaasCardRecharge, isCardNotActivated ? AsaasCardRechargeStatus.WAITING_CARD_ACTIVATION : AsaasCardRechargeStatus.PENDING)
    }

    private void setAsError(AsaasCardRecharge asaasCardRecharge) {
        updateStatus(asaasCardRecharge, AsaasCardRechargeStatus.ERROR)
    }

    private void executeRecharge(AsaasCardRecharge asaasCardRecharge) {
        validateExecution(asaasCardRecharge)

        AsaasCardRecharge bifrostAsaasCardRecharge = bifrostPrepaidCardService.save(asaasCardRecharge)
        if (bifrostAsaasCardRecharge.hasErrors()) {
            setAsError(asaasCardRecharge)
            return
        }

        asaasCardRecharge.creditDate = new Date().clearTime()
        asaasCardRecharge.processedDate = new Date()
        asaasCardRecharge.status = AsaasCardRechargeStatus.DONE
        asaasCardRecharge.save(failOnError: true)

        notifyExecuted(asaasCardRecharge)
    }

    private void validateExecution(AsaasCardRecharge asaasCardRecharge) {
        if (!asaasCardRecharge.asaasCard.type.isPrepaid()) throw new RuntimeException("Não é possível efetuar recargas para este tipo de cartão. [asaasCardRecharge: ${asaasCardRecharge}, asaasCardType: ${asaasCardRecharge.asaasCard.type}]")

        BusinessValidation validatedBusiness = asaasCardRecharge.canBeProcessed()
        if (!validatedBusiness.isValid()) throw new RuntimeException(validatedBusiness.getFirstErrorMessage())
    }

    private void notifyExecuted(AsaasCardRecharge asaasCardRecharge) {
        String smsMessage = "A recarga para seu cartão ASAAS (Final ${asaasCardRecharge.asaasCard.lastDigits}) no valor de ${FormUtils.formatCurrencyWithMonetarySymbol(asaasCardRecharge.value)} acabou de ser efetuada!"
        smsSenderService.send(smsMessage, asaasCardRecharge.asaasCard.holder.mobilePhone, false, [:])
        mobilePushNotificationService.notifyAsaasCardRechargeDone(asaasCardRecharge)
    }
}
