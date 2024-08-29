package com.asaas.service.debit

import com.asaas.customer.CustomerParameterName
import com.asaas.debit.DebitType
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.auditlog.AuditLogEvent
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.debit.Debit
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.Payment
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class DebitService {

    def financialTransactionService

    def messageService

    def financialStatementService

    def customerInteractionService

    public Debit save(Customer customer, BigDecimal value, DebitType type, String description, Map additionalParams) {
        BusinessValidation validatedBusiness = validateSave(customer, value, type, description, additionalParams)
        if (!validatedBusiness.isValid()) return DomainUtils.addError(new Debit(), validatedBusiness.getFirstErrorMessage())

        Debit debit = new Debit()
        debit.customer = customer
        debit.value = value
        debit.type = type
        debit.description = description
        debit.debitDate = additionalParams?.debitDate ?: new Date()
        debit.done = false

        if (additionalParams?.domainObject) {
            switch (additionalParams.domainObject) {
                case AsaasCard:
                    debit.asaasCard = additionalParams.domainObject as AsaasCard
                    break
                default:
                    throw new NotImplementedException("Objeto do tipo [${additionalParams.domainObject.class.simpleName}] não implementado")
            }
        }

        debit.save(flush: true, failOnError: true)

        debitFromCustomerFinancialTransactionIfNecessary(debit)

        if (DebitType.listTypesToSaveInteraction().contains(type)) {
            String interactionDescription = "Lançamento de débito de ${FormUtils.formatCurrencyWithMonetarySymbol(value)} referente a ${Utils.getEnumLabel(type)}.\nDescrição: ${description}."
            customerInteractionService.save(customer, interactionDescription)
        }

        return debit
    }

    public void saveAllPaymentCreationFeesFromDate(Date filterDate) {
        List<Long> customerParametersWithPaymentCreationFeeIds = CustomerParameter.query([column: 'id', name: CustomerParameterName.PAYMENT_CREATION_FEE]).list()

        for (Long customerParameterId : customerParametersWithPaymentCreationFeeIds) {
            Utils.withNewTransactionAndRollbackOnError({
                CustomerParameter customerParameter = CustomerParameter.get(customerParameterId)
                Customer customer = customerParameter.customer
                BigDecimal paymentFee = customerParameter.numericValue

                if (!paymentFee || paymentFee <= 0) return

                Long countPaymentsToDebit = Payment.query(['duplicatedPayment[isNull]': true, deleted: true, customer: customer, "dateCreated[ge]": filterDate, "dateCreated[le]": filterDate]).count()

                if (!countPaymentsToDebit) return

                Date today = new Date()
                Boolean isDebitAlreadyCreated = Debit.query([exists: true, customer: customer, type: DebitType.PAYMENT_CREATION_FEE, "dateCreated[ge]": today.clearTime(), dateCreatedFinish: today]).get().asBoolean()
                if (isDebitAlreadyCreated) return

                AsaasLogger.info("Lancando debito de taxa de emissao de cobrancas para o fornecedor [${customer.id}]")
                String description = "Taxa de emissão de ${countPaymentsToDebit} ${countPaymentsToDebit > 1 ? 'cobranças' : 'cobrança'} em ${CustomDateUtils.fromDate(filterDate)}"
                BigDecimal totalFee = (paymentFee * countPaymentsToDebit)
                save(customer, totalFee, DebitType.PAYMENT_CREATION_FEE, description, null)

            }, [logErrorMessage: "Erro ao lançar taxa de criação de pagamento para customerParameterId: ${customerParameterId}"])
        }
    }

    public void saveAllPaymentUpdateFeesFromDate(Date filterDate) {
        List<Long> customerParametersWithPaymentAlterationFeeIds = CustomerParameter.query([column: 'id', name: CustomerParameterName.PAYMENT_CREATION_FEE]).list()

        for (Long customerParameterId : customerParametersWithPaymentAlterationFeeIds) {
            Utils.withNewTransactionAndRollbackOnError({
                CustomerParameter customerParameter = CustomerParameter.get(customerParameterId)
                Customer customer = customerParameter.customer
                BigDecimal paymentFee = customerParameter.numericValue

                if (!paymentFee || paymentFee <= 0) return

                List<Long> updatedPaymentIdList = Payment.query([column: 'id', 'duplicatedPayment[isNull]': true, deleted: true, customer: customer, "lastUpdated[ge]": filterDate, "lastUpdated[le]": filterDate]).list()

                if (!updatedPaymentIdList) return

                Date today = new Date()
                Boolean isDebitAlreadyCreated = Debit.query([exists: true, customer: customer, type: DebitType.PAYMENT_UPDATE_FEE, "dateCreated[ge]": today.clearTime(), dateCreatedFinish: today]).get().asBoolean()
                if (isDebitAlreadyCreated) return

                Long countPaymentsToDebit = AuditLogEvent.query([className: "Payment", propertyName: "dueDate", eventName: "UPDATE", persistedObjectIdList: updatedPaymentIdList, "dateCreated[ge]": filterDate, "dateCreated[le]": filterDate]).count()

                if (!countPaymentsToDebit) return

                AsaasLogger.info("Lancando debito de taxa de alteração de cobrancas para o fornecedor [${customer.id}]")
                String description = "Taxa de alteração de ${countPaymentsToDebit} ${countPaymentsToDebit > 1 ? 'cobranças' : 'cobrança'} em ${CustomDateUtils.fromDate(filterDate)}"
                BigDecimal totalFee = (paymentFee * countPaymentsToDebit)
                save(customer, totalFee, DebitType.PAYMENT_UPDATE_FEE, description, null)

            }, [logErrorMessage: "Erro ao lançar taxa de alteração de pagamento para customerParameterId: ${customerParameterId}"])
        }
    }

    public FinancialTransaction reverseBalanceBlock(Long customerId) {
        Debit debit = Debit.getBlockedBalance(customerId)
        return reverseDebit(debit.id)
    }

    public Debit reverse(Long id) {
        return reverseDebit(id).debit
    }

    private FinancialTransaction reverseDebit(Long debitId) {
        Debit debit = deleteDebitAndCreditConsume(debitId)

        FinancialTransaction financialTransaction = financialTransactionService.reverseDebit(debit)

        return financialTransaction
    }

    private Debit deleteDebitAndCreditConsume(Long debitId) {
        Debit debit = Debit.get(debitId)

        debit.deleted = true
        debit.save(flush: true, failOnError:true)

        return debit
    }

    private void debitFromCustomerFinancialTransactionIfNecessary(Debit debit) {
        if (debit.debitDate > new Date().clearTime()) return
        if (debit.done) throw new RuntimeException("O débito [${debit.id}] já foi realizado")

        FinancialTransactionType financialTransactionType = DebitType.getFinancialTransactionType(debit.type)
        financialTransactionService.saveDebit(debit, null, financialTransactionType)
        debit.done = true
        debit.debitDate = new Date()
        debit.save(failOnError: true)
    }

    private BusinessValidation validateSave(Customer customer, BigDecimal value, DebitType type, String description, Map additionalParams) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!customer) validatedBusiness.addError("default.null.message", ["cliente"])

        if (!value) validatedBusiness.addError("default.null.message", ["valor"])

        if (value < 0) validatedBusiness.addError("default.error.minValue", ["valor", "R\$ 0,00"])

        if (!type) validatedBusiness.addError("default.null.message", ["tipo de débito"])

        if (description.trim().length() < Debit.MINIMUM_RELEVANT_DESCRIPTION_LENGTH) validatedBusiness.addError("default.error.minFieldLength", ["descrição", Debit.MINIMUM_RELEVANT_DESCRIPTION_LENGTH])

        if (additionalParams?.debitDate && additionalParams?.debitDate < new Date().clearTime()) validatedBusiness.addError("default.error.afterNow", ["data de débito"])

        return validatedBusiness
    }
}
