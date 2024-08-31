package com.asaas.service.debtrecovery

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customerdebtappropriation.CustomerDebtAppropriationStatus
import com.asaas.debtrecovery.DebtRecoveryNegotiationStatus
import com.asaas.debtrecovery.DebtRecoveryStatus
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customerdebtappropriation.CustomerDebtAppropriation
import com.asaas.domain.debtrecovery.DebtRecovery
import com.asaas.domain.debtrecovery.DebtRecoveryNegotiation
import com.asaas.domain.debtrecovery.DebtRecoveryNegotiationPayment
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.exception.BusinessException
import com.asaas.integration.callcenter.outgoingphonecall.adapter.OutgoingPhoneCallAdapter
import com.asaas.user.UserUtils
import com.asaas.utils.OfficeHoursChecker
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class DebtRecoveryService {

    def asyncActionService
    def customerAccountService
    def debtRecoveryHistoryService
    def debtRecoveryNegotiationService
    def grailsApplication
    def outgoingPhoneCallService

    public DebtRecovery save(Customer customer, BigDecimal value, Map params) {
        BusinessValidation businessValidation = validateSave(customer, value)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        DebtRecovery debtRecovery = new DebtRecovery()
        debtRecovery.debtorCustomer = customer
        debtRecovery.value = value
        debtRecovery.status = DebtRecoveryStatus.IN_NEGOTIATION
        debtRecovery.debtorCustomerAccount = saveOrUpdateAsaasCustomerAccountFromCustomer(customer)
        debtRecovery.paymentDunningEnabled = Boolean.valueOf(params.paymentDunningEnabled)
        debtRecovery.save(failOnError: true)

        debtRecoveryNegotiationService.save(debtRecovery, debtRecovery.value, Utils.toBigDecimal(params.chargeValue ?: 0), params)

        return debtRecovery
    }

    public DebtRecovery cancelManually(DebtRecovery debtRecovery) {
        cancel(debtRecovery)

        debtRecoveryHistoryService.saveForDebtRecoveryChanged(debtRecovery, "Recuperação cancelada manualmente")

        return debtRecovery
    }

    public DebtRecovery cancel(DebtRecovery debtRecovery) {
        if (!debtRecovery.canManipulate()) throw new BusinessException("A recuperação não se encontra em negociação.")

        debtRecovery.status = DebtRecoveryStatus.CANCELLED
        debtRecovery.save(failOnError: true)

        DebtRecoveryNegotiation negotiation = DebtRecoveryNegotiation.cancellable([debtRecovery: debtRecovery]).get()
        if (negotiation) debtRecoveryNegotiationService.cancel(negotiation)

        asyncActionService.save(AsyncActionType.CANCEL_DEBT_RECOVERY_PHONE_CALL, null, [customerId: debtRecovery.debtorCustomer.id], [allowDuplicatePendingWithSameParameters: true])

        return debtRecovery
    }

    public void renegotiate(DebtRecovery debtRecovery, Map params) {
        validateRenegotiate(debtRecovery, params)

        DebtRecoveryNegotiation negotiation = DebtRecoveryNegotiation.query([debtRecovery: debtRecovery]).get()

        debtRecovery.paymentDunningEnabled = Utils.toBoolean(params.paymentDunningEnabled)
        debtRecovery.status = DebtRecoveryStatus.IN_NEGOTIATION
        debtRecovery.save(failOnError: true)

        if (renegotiationShouldCreateAnotherNegotiation(negotiation)) {
            if (DebtRecoveryNegotiationStatus.getCancellableList().contains(negotiation.status)) debtRecoveryNegotiationService.cancel(negotiation)

            debtRecoveryNegotiationService.save(debtRecovery, debtRecovery.getRemainingValue(), Utils.toBigDecimal(params.chargeValue), params)
        } else {
            debtRecoveryNegotiationService.renegotiate(negotiation, params)
        }

        debtRecoveryHistoryService.saveForDebtRecoveryChanged(debtRecovery, "Recuperação renegociada")
    }

    public DebtRecovery restart(DebtRecovery debtRecoveryToCancel, Map params) {
        validateRestart(debtRecoveryToCancel)

        cancel(debtRecoveryToCancel)

        DebtRecovery debtRecovery = save(debtRecoveryToCancel.debtorCustomer, getCurrentBalanceWithAppropriatedValue(debtRecoveryToCancel.debtorCustomer).abs(), params)

        debtRecoveryHistoryService.saveForRestartDebtRecovery(debtRecoveryToCancel, debtRecovery)

        return debtRecovery
    }

    public DebtRecovery saveForAppropriatedCustomer(Customer customer, BigDecimal chargeValue, Date installmentDueDate) {
        BusinessValidation businessValidation = validateSaveForAppropriatedCustomer(customer)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        BigDecimal absoluteCurrentBalanceWithAppropriatedValue = getCurrentBalanceWithAppropriatedValue(customer).abs()

        BigDecimal bankSlipTotalValue = absoluteCurrentBalanceWithAppropriatedValue + chargeValue
        if (bankSlipTotalValue < DebtRecovery.MINIMUM_BANK_SLIP_VALUE) throw new BusinessException("O valor da recuperação está abaixo do mínimo permitido")

        DebtRecovery debtRecovery = save(customer, absoluteCurrentBalanceWithAppropriatedValue, [chargeValue: chargeValue, installmentDueDate: installmentDueDate])

        debtRecoveryHistoryService.saveForDebtRecoveryChanged(debtRecovery, "Recuperação de cliente com saldo apropriado iniciada")

        return debtRecovery
    }

    public BusinessValidation validateSaveForAppropriatedCustomer(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!CustomerDebtAppropriation.active([customer: customer, exists: true]).get().asBoolean()) {
            businessValidation.addError("debtRecovery.error.customerWithoutActiveDebtAppropriation")
            return businessValidation
        }

        return validateInitiateRecovery(customer)
    }

    public Boolean hasRemainingValueDivergenceWithCustomerBalance(DebtRecovery debtRecovery) {
        BigDecimal debtDivergence = (getCurrentBalanceWithAppropriatedValue(debtRecovery.debtorCustomer) + debtRecovery.getRemainingValue()).abs()
        Boolean hasDivergence = (debtDivergence != 0)

        return hasDivergence
    }

    public void schedulePhoneCall(Customer customer, OutgoingPhoneCallAdapter outgoingPhoneCallAdapter) {
        BusinessValidation validatedBusiness = validateSchedulePhoneCall(customer, outgoingPhoneCallAdapter)
        if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

        outgoingPhoneCallService.save(outgoingPhoneCallAdapter, customer)
    }

    public void cancelRecoveriesForCustomersWithPositiveBalance() {
        Map search = [:]
        search.column = "id"
        search.statusList = [DebtRecoveryStatus.IN_NEGOTIATION, DebtRecoveryStatus.NEGOTIATION_FAILED]
        search."activeCustomerDebtAppropriation[notExists]" = true

        List<Long> debtRecoveryIdList = DebtRecovery.query(search).list()

        for (Long debtRecoveryId in debtRecoveryIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                DebtRecovery debtRecovery = DebtRecovery.get(debtRecoveryId)
                BigDecimal currentBalance = FinancialTransaction.getCustomerBalance(debtRecovery.debtorCustomer)

                if (currentBalance >= 0) {
                    cancel(debtRecovery)
                    debtRecoveryHistoryService.saveForDebtRecoveryChanged(debtRecovery, "Recuperação cancelada devido ao cliente possuir saldo positivo")
                }
            }, [logErrorMessage: "DebtRecoveryService.cancelCustomerWithPositiveBalance >> Falha ao cancelar recuperação de débito [${debtRecoveryId}] de cliente com saldo positivo."])
        }
    }

    public BusinessValidation validateInitiateRecovery(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (UserUtils.hasAsaasEmail(customer.email)) {
            businessValidation.addError("debtRecovery.error.hasAsaasEmail")
            return businessValidation
        }

        Boolean hasPositiveBalance = getCurrentBalanceWithAppropriatedValue(customer) >= 0
        if (hasPositiveBalance) {
            businessValidation.addError("debtRecovery.error.customerHasPositiveBalance")
            return businessValidation
        }

        Boolean hasActiveDebtRecovery = DebtRecovery.active([exists: true, debtorCustomerId: customer.id]).get().asBoolean()
        if (hasActiveDebtRecovery) {
            businessValidation.addError("debtRecovery.error.customerHasActiveRecovery")
            return businessValidation
        }

        return businessValidation
    }

    public BigDecimal calculateChargeValueSuggestion(BigDecimal currentBalanceWithAppropriatedValue) {
        if (currentBalanceWithAppropriatedValue.abs() < DebtRecovery.MINIMUM_BANK_SLIP_VALUE) {
            return DebtRecovery.MINIMUM_BANK_SLIP_VALUE - currentBalanceWithAppropriatedValue.abs()
        }

        return new BigDecimal(0)
    }

    private Boolean renegotiationShouldCreateAnotherNegotiation(DebtRecoveryNegotiation negotiation) {
        if (debtRecoveryNegotiationService.hasRecoveredValueInCurrentNegotiation(negotiation)) return true

        DebtRecoveryNegotiationPayment masterPayment = negotiation.getMasterPayment()
        if (!masterPayment.canBeUpdated()) return true

        return false
    }

    private CustomerAccount saveOrUpdateAsaasCustomerAccountFromCustomer(Customer customer) {
        Map customerAccountParams = customerAccountService.buildCustomerAccountMapFromCustomer(customer)
        customerAccountParams.automaticRoutine = true

        Customer asaasCustomer = Customer.get(grailsApplication.config.asaas.debtRecoveryCustomer.id)

        String hqlQuery = "select id from CustomerAccount ca where ca.provider = :asaasCustomer and ca.customer = :customer and ca.deleted = false"
        customerAccountParams.id = CustomerAccount.executeQuery(hqlQuery, [asaasCustomer: asaasCustomer, customer: customer])[0]
        if (customerAccountParams.id) return customerAccountService.update(asaasCustomer.id, customerAccountParams).customerAccount

        CustomerAccount customerAccount = customerAccountService.save(asaasCustomer, customer, customerAccountParams)
        customerAccountService.createNotificationsForCustomerAccount(customerAccount)

        return customerAccount
    }

    private BusinessValidation validateSchedulePhoneCall(Customer customer, OutgoingPhoneCallAdapter outgoingPhoneCallAdapter) {
        BusinessValidation businessValidation = new BusinessValidation()

        BigDecimal currentBalance = FinancialTransaction.getCustomerBalance(customer)
        if (currentBalance >= 0) {
            businessValidation.addError("debtRecovery.error.customerHasPositiveBalance")
            return businessValidation
        }

        if (!outgoingPhoneCallAdapter.reason) {
            businessValidation.addError("default.null.message", ["motivo"])
            return businessValidation
        }

        if (!PhoneNumberUtils.validatePhone(outgoingPhoneCallAdapter.dialedPhoneNumber)) {
            businessValidation.addError("invalid.phone")
            return businessValidation
        }

        if (outgoingPhoneCallAdapter.asaasAccountManagerId) {
            AccountManager accountManager = AccountManager.read(outgoingPhoneCallAdapter.asaasAccountManagerId)
            if (!accountManager) {
                businessValidation.addError("accountManager.notFound")
                return businessValidation
            }
        }

        if (outgoingPhoneCallAdapter.nextAttemptTime && !outgoingPhoneCallAdapter.nextAttemptDate) {
            businessValidation.addError("default.null.message", ["data da próxima ligação"])
            return businessValidation
        }

        if (outgoingPhoneCallAdapter.nextAttemptDate) {
            Boolean isValidDate = OfficeHoursChecker.isValidHourForOutgoingCalls(outgoingPhoneCallAdapter.nextAttempt)
            if (!isValidDate) {
                businessValidation.addError("invalid.outgoingPhoneCall.attendanceHours")
                return businessValidation
            }
        }

        return businessValidation
    }

    private BusinessValidation validateSave(Customer customer, BigDecimal value) {
        BusinessValidation businessValidation = validateInitiateRecovery(customer)

        if (!businessValidation.isValid()) return businessValidation

        Boolean isValueBiggerThanDebtBalance = getCurrentBalanceWithAppropriatedValue(customer).abs() < value
        if (isValueBiggerThanDebtBalance) {
            businessValidation.addError("debtRecovery.error.valueIsBiggerThanDebtBalance")
        }

        return businessValidation
    }

    private void validateRenegotiate(DebtRecovery debtRecovery, Map params) {
        if (!debtRecovery.canManipulate()) throw new BusinessException("A recuperação não pode ser renegociada.")

        if (debtRecovery.paymentDunningEnabled && !Utils.toBoolean(params.paymentDunningEnabled)) throw new BusinessException("Você não pode desabilitar a opção de envio ao Serasa durante uma renegociação. Reinicie a negativação ou efetue seu cancelamento.")

        Boolean hasNegotiation = DebtRecoveryNegotiation.query([exists: true, debtRecovery: debtRecovery]).get().asBoolean()
        if (!hasNegotiation) throw new BusinessException("Não há nenhuma negociação para ser renegociada.")
    }

    private void validateRestart(DebtRecovery debtRecovery) {
        if (!debtRecovery.canManipulate()) throw new BusinessException("A recuperação não pode ser reiniciada.")

        if (!debtRecovery.getRemainingValue()) throw new BusinessException("A recuperação não pode ser reiniciada pois não apresenta um valor pendente.")

        if (!hasRemainingValueDivergenceWithCustomerBalance(debtRecovery)) throw new BusinessException("A recuperação não pode ser reiniciada pois não há divergência de valores.")
    }

    private BigDecimal getCurrentBalanceWithAppropriatedValue(Customer customer) {
        BigDecimal currentBalance = FinancialTransaction.getCustomerBalance(customer)
        BigDecimal remainingAppropriatedValue = CustomerDebtAppropriation.sumRemainingAppropriatedValue([customer: customer, "status[in]": CustomerDebtAppropriationStatus.listActiveAppropriationStatus()]).get()

        return currentBalance - remainingAppropriatedValue
    }
}
