package com.asaas.service.receivableanticipation

import com.asaas.customerreceivableanticipationconfig.repository.CustomerReceivableAnticipationConfigRepository
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.predictionalgorithmlog.PredictionAlgorithmLog
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationNonAutomaticApprovalHistory
import com.asaas.log.AsaasLogger
import com.asaas.receivableanticipation.ReceivableAnticipationAutomaticApprovalReason
import com.asaas.receivableanticipation.ReceivableAnticipationCalculator
import com.asaas.receivableanticipation.ReceivableAnticipationNonAutomaticApprovalReason
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.receivableanticipation.repository.ReceivableAnticipationNonAutomaticApprovalRuleBypassRepository
import com.asaas.receivableanticipation.repository.ReceivableAnticipationRepository
import com.asaas.receivableanticipation.validator.automaticValidation.BaseRule
import com.asaas.receivableanticipation.validator.automaticValidation.ReceivableAnticipationValidationObject
import com.asaas.receivableanticipation.validator.automaticValidation.ReceivableAnticipationValidationVO
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.AnticipationValueRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.BillingTypeIsCreditCardRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.BlockedCreditCardTransactionAttemptRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.BotUsageOnPaymentCampaignRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.BotUsageOnPaymentInvoiceRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.ChargebackInLastMonthRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.ChargebackRatePercentageRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.CompanyTypeRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.ConfirmedValueCompromisedWithCreditCardAnticipationRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.CustomerAnticipatedValuesByCompanyTypeRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.CustomerHasDigitalBankAccountRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.CustomerHasNoMainAccountRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.DistinctCreditCardRateByIpRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.LimitByCompanySizeRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.MEICustomerRequestedAnticipationValueInLastMonthRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.NotDebitedLimitForNewCustomersRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.PastedFieldListRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.PersonTypeRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.ReceivedPaymentsInLastMonthsRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.RequestAndPaidBySameIpRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.RequestAndPaidBySamePersonRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.SameIpSystemUsageRule
import com.asaas.receivableanticipation.validator.automaticValidation.approval.rules.TransactionsWithSameCreditCardInPeriodRule
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional
import org.springframework.transaction.HeuristicCompletionException

@GrailsCompileStatic
@Transactional
class ReceivableAnticipationAutomaticApprovalService {

    ReceivableAnticipationService receivableAnticipationService

    public void processAnticipationsAwaitingAutomaticApproval() {
        final Integer maxItemPerExecution = 100
        List<Long> anticipationIds = ReceivableAnticipationRepository.query([status: ReceivableAnticipationStatus.AWAITING_AUTOMATIC_APPROVAL])
                                                                     .column("id")
                                                                     .disableRequiredFilters()
                                                                     .list(max: maxItemPerExecution) as List<Long>
        for (Long anticipationId : anticipationIds) {
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipation anticipation = ReceivableAnticipationRepository.get(anticipationId)
                List<ReceivableAnticipationNonAutomaticApprovalReason> nonAutomaticApprovalReasonList = ReceivableAnticipationNonAutomaticApprovalRuleBypassRepository.query([customerId: anticipation.customer.id,
                                                                                                                                                                              "expirationDate[ge]": new Date()])
                                                                                                                                                                      .column("reason")
                                                                                                                                                                      .list() as List<ReceivableAnticipationNonAutomaticApprovalReason>
                List<BaseRule> automaticApprovalRulesList = buildAutomaticApprovalRulesList(anticipation)
                ReceivableAnticipationValidationObject validationObject = new ReceivableAnticipationValidationObject(anticipation, nonAutomaticApprovalReasonList as List<Enum>)

                for (BaseRule automaticApprovalRule: automaticApprovalRulesList) {
                    ReceivableAnticipationValidationVO validationResult = automaticApprovalRule.validate(validationObject)
                    if (validationResult) saveLog(anticipation, validationResult.lowEffectiveness, validationResult.reason, validationResult.logParams)
                }

                if (anticipation.hasErrors()) {
                    sendToManualAnalysis(anticipation)
                    return
                }

                approve(anticipation)
            }, [ignoreStackTrace: true,
                onError: { Exception exception ->
                    if (exception instanceof HeuristicCompletionException) {
                        AsaasLogger.warn("ReceivableAnticipationAutomaticApprovalService.processAnticipationsAwaitingAutomaticApproval >> Ocorreu uma exception conhecida durante o processamento da antecipação [anticipationId: ${anticipationId}]", exception)
                        return
                    }

                    if (Utils.isLock(exception)) {
                        AsaasLogger.warn("ReceivableAnticipationAutomaticApprovalService.processAnticipationsAwaitingAutomaticApproval >> Ocorreu um Lock durante o processamento da antecipação [anticipationId: ${anticipationId}]", exception)
                        return
                    }

                    AsaasLogger.error("ReceivableAnticipationAutomaticApprovalService.processAnticipationsAwaitingAutomaticApproval >> Erro ao processar aprovação automática de antecipação [anticipationId: ${anticipationId}]", exception)
                }])
        }
    }

    private void approve(ReceivableAnticipation anticipation) {
        ReceivableAnticipationAutomaticApprovalReason reason = isNewCustomer(anticipation.customer) ? ReceivableAnticipationAutomaticApprovalReason.COMPROMISED_CREDIT_CARD_ANTICIPATION_VALUE_WITHIN_LIMIT_FOR_NEW_CUSTOMERS : ReceivableAnticipationAutomaticApprovalReason.COMPROMISED_CREDIT_CARD_ANTICIPATION_VALUE_WITHIN_CHARGEBACK_AND_TRANSACTIONS_RULES
        saveLog(anticipation, true, reason, null)

        String observation = "Aprovação automática. Motivo: " + Utils.getMessageProperty("${reason.class.simpleName}.${reason.toString()}.observation")
        receivableAnticipationService.approve(anticipation.id, observation)
    }

    private void sendToManualAnalysis(ReceivableAnticipation anticipation) {
        if (!anticipation.status.isAwaitingAutomaticApproval()) throw new RuntimeException("Antecipação ${anticipation.id} não esta aguardando aprovação automática")

        String observation = "Enviado para análise manual. Motivo(s):\n" + anticipation.errors.allErrors.collect { it.defaultMessage }.join("\n")

        receivableAnticipationService.updateStatusToPending(anticipation, observation)
    }

    private Boolean isNewCustomer(Customer customer) {
        Integer daysSinceFirstTransfer = ReceivableAnticipationCalculator.calculateElapsedDaysAfterFirstTransfer(customer)
        return daysSinceFirstTransfer < ReceivableAnticipation.LIMIT_OF_DAYS_TO_AUTO_APPROVE_BASED_ON_COMPROMISED_CREDIT_CARD
    }

    private void saveLog(ReceivableAnticipation anticipation, Boolean lowEffectiveness, Enum reason, List observationParams) {
        Map predictionResponse = [:]
        predictionResponse.predict = lowEffectiveness ? "DO_ANTICIPATE" : "NEED_MANUAL_ANALYSIS"
        predictionResponse.reason = reason
        predictionResponse.observation = Utils.getMessageProperty("${reason.class.simpleName}.${reason.toString()}.observation", observationParams)

        PredictionAlgorithmLog.save(predictionResponse, anticipation)

        if (!lowEffectiveness) {
            DomainUtils.addError(anticipation, predictionResponse.observation.toString())
            saveNonAutomaticalApprovalHistory(anticipation, reason as ReceivableAnticipationNonAutomaticApprovalReason)
        }
    }

    private void saveNonAutomaticalApprovalHistory(ReceivableAnticipation anticipation, ReceivableAnticipationNonAutomaticApprovalReason reason) {
        ReceivableAnticipationNonAutomaticApprovalHistory nonAutomaticApprovalHistory = new ReceivableAnticipationNonAutomaticApprovalHistory()
        nonAutomaticApprovalHistory.anticipation = anticipation
        nonAutomaticApprovalHistory.reason = reason
        nonAutomaticApprovalHistory.save(failOnError: true)
    }

    private List<BaseRule> buildAutomaticApprovalRulesList(ReceivableAnticipation anticipation) {
        CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = CustomerReceivableAnticipationConfigRepository.query([customerId: anticipation.customer.id])
                                                                                                                                  .readOnly()
                                                                                                                                  .get()

        List<BaseRule> baseRuleList = listDefaultAutomaticApprovalRules()

        if (customerReceivableAnticipationConfig.hasCreditCardPercentage()) {
            baseRuleList.addAll(listValidationsWithCreditCardPercentage())
        } else if (isNewCustomer(anticipation.customer)) {
            baseRuleList.addAll(listValidationsForNewUsersWithoutCreditCardPercentage())
        } else {
            baseRuleList.addAll(listValidationsForOldUsersWithoutCreditCardPercentage())
        }

        return baseRuleList
    }

    private List<BaseRule> listDefaultAutomaticApprovalRules() {
        return [
            new BillingTypeIsCreditCardRule(),
            new DistinctCreditCardRateByIpRule(),
            new BlockedCreditCardTransactionAttemptRule(),
            new ReceivedPaymentsInLastMonthsRule(),
            new TransactionsWithSameCreditCardInPeriodRule(),
            new LimitByCompanySizeRule()
        ] as List<BaseRule>
    }

    private List<BaseRule> listValidationsWithCreditCardPercentage() {
        return [
            new CustomerAnticipatedValuesByCompanyTypeRule(),
            new RequestAndPaidBySameIpRule(),
            new SameIpSystemUsageRule(),
            new MEICustomerRequestedAnticipationValueInLastMonthRule(),
            new CompanyTypeRule(),
            new PastedFieldListRule(),
            new BotUsageOnPaymentCampaignRule(),
            new BotUsageOnPaymentInvoiceRule()
        ] as List<BaseRule>
    }

    private List<BaseRule> listValidationsForNewUsersWithoutCreditCardPercentage() {
        return [
            new RequestAndPaidBySamePersonRule(),
            new RequestAndPaidBySameIpRule(),
            new SameIpSystemUsageRule(),
            new PersonTypeRule(),
            new CompanyTypeRule(),
            new CustomerHasNoMainAccountRule(),
            new CustomerHasDigitalBankAccountRule(),
            new NotDebitedLimitForNewCustomersRule()
        ] as List<BaseRule>
    }

    private List<BaseRule> listValidationsForOldUsersWithoutCreditCardPercentage() {
        return [
            new RequestAndPaidBySamePersonRule(),
            new RequestAndPaidBySameIpRule(),
            new SameIpSystemUsageRule(),
            new ChargebackInLastMonthRule(),
            new ChargebackRatePercentageRule(),
            new AnticipationValueRule(),
            new ConfirmedValueCompromisedWithCreditCardAnticipationRule(),
        ] as List<BaseRule>
    }
}
