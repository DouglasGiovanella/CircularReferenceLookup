package com.asaas.service.api.bradesco

import com.asaas.api.bradesco.ApiBradescoCustomerParser
import com.asaas.criticalaction.CriticalActionType
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.adapter.BradescoCustomerAdapter
import com.asaas.customerreceivableanticipationconfig.adapter.ToggleBlockBillingTypeAdapter
import com.asaas.customerreceivableanticipationconfig.CustomerReceivableAnticipationBillingTypeDisableReason
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerConfig
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.domain.payment.Payment
import com.asaas.exception.ResourceNotFoundException
import com.asaas.partnerapplication.PartnerApplicationName
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class ApiBradescoService {

    def accountActivationRequestService
    def apiAccountApprovalService
	def apiResponseBuilderService
    def apiConfigService
    def customerFeatureService
    def customerInteractionService
    def customerParameterService
    def customerPartnerApplicationService
    def customerReceivableAnticipationConfigService
    def customerService
    def customerStatusService
    def customerUpdateRequestService
    def createCustomerService
    def pixTransactionBankAccountInfoCheckoutLimitService

    public Map saveCustomer(BradescoCustomerAdapter customerAdapter) {
        Customer validatedCustomer = validateSave(customerAdapter)
        if (validatedCustomer.hasErrors()) {
            transactionStatus.setRollbackOnly()
            return apiResponseBuilderService.buildErrorList(validatedCustomer)
        }

        Customer createdCustomer = createCustomerService.save(customerAdapter)
        if (createdCustomer.hasErrors()) {
            transactionStatus.setRollbackOnly()
            return apiResponseBuilderService.buildErrorList(createdCustomer)
        }

        apiConfigService.toggleAutoApproveCreatedAccount(createdCustomer.id, true)

        createdCustomer.refresh()

        customerFeatureService.toggleMultipleBankAccounts(createdCustomer.id, false)

        customerParameterService.save(createdCustomer, CustomerParameterName.ALLOW_DUPLICATED_ACTIVATION_PHONE, true)
        customerParameterService.save(createdCustomer, CustomerParameterName.ALLOW_DUPLICATE_CPF_CNPJ, true)

        def customerUpdateRequest = customerUpdateRequestService.save(createdCustomer.id, customerAdapter.properties)
        if (customerUpdateRequest.hasErrors()) {
            transactionStatus.setRollbackOnly()
            return apiResponseBuilderService.buildErrorList(customerUpdateRequest)
        }

        customerPartnerApplicationService.saveBradesco(createdCustomer)

        String observation = "Ativada devido a auto-ativação para clientes provindos do Bradesco."
        customerStatusService.autoActivate(createdCustomer.id, observation)
        accountActivationRequestService.createAsUsed(createdCustomer)

        Map bankAccountProperties = [bankAccount: customerAdapter.bankAccount.properties]
        createdCustomer = apiAccountApprovalService.approve(createdCustomer, bankAccountProperties)

        customerParameterService.save(createdCustomer, CustomerParameterName.AUTOMATIC_TRANSFER, true)
        customerParameterService.save(createdCustomer, CustomerParameterName.DISABLE_BANK_ACCOUNT_CHANGE, true)
        customerParameterService.save(createdCustomer, CustomerParameterName.CHECKOUT_ONLY_FOR_BANK_ACCOUNT_ALREADY_REGISTERED, true)
        customerParameterService.save(createdCustomer, CustomerParameterName.DISABLE_ASAAS_CARD, true)
        customerParameterService.save(createdCustomer, CustomerParameterName.CANNOT_USE_REFERRAL, true)
        customerParameterService.save(createdCustomer, CustomerParameterName.DISABLE_CHOOSE_CUSTOMER_PLAN, true)

        ToggleBlockBillingTypeAdapter contractualRestrictionAdapter = new ToggleBlockBillingTypeAdapter(createdCustomer,
                                                                                                        false,
                                                                                                        CustomerReceivableAnticipationBillingTypeDisableReason.CONTRACTUAL_RESTRICTION,
                                                                                                        "Antecipação bloqueada por restrição contratual")

        customerReceivableAnticipationConfigService.toggleBlockBankSlipAnticipation(contractualRestrictionAdapter)
        customerReceivableAnticipationConfigService.toggleBlockCreditCardAnticipation(contractualRestrictionAdapter)

        customerFeatureService.toggleBillPayment(createdCustomer.id, false)
        customerFeatureService.toggleAsaasCardElo(createdCustomer.id, false)

        CustomerCriticalActionConfig criticalActionConfig = CustomerCriticalActionConfig.query([customerId: createdCustomer.id]).get()
        criticalActionConfig.transfer = false
        criticalActionConfig.commercialInfoUpdate = false
        criticalActionConfig.save(failOnError: true)
        customerInteractionService.saveCriticalActionDisabledEvent(createdCustomer.id, CriticalActionType.TRANSFER)

        BankAccountInfo bankAccountInfo = BankAccountInfo.findMainAccount(createdCustomer.id)
        final BigDecimal dailyPixCheckoutLimit = 100000.00
        pixTransactionBankAccountInfoCheckoutLimitService.save(bankAccountInfo, dailyPixCheckoutLimit)

        customerService.changeToRegisteredBoleto(createdCustomer.id, Payment.BRADESCO_ONLINE_BOLETO_BANK_ID, "Cliente criado via Integração Bradesco NetEmpresa", false)

        CustomerConfig customerConfig = CustomerConfig.query([customerId: createdCustomer.id]).get()
        customerConfig.maxNegativeBalance = CustomerConfig.BRADESCO_DEFAULT_MAX_NEGATIVE_BALANCE
        customerConfig.save(failOnError: true)

        return apiResponseBuilderService.buildSuccess(ApiBradescoCustomerParser.buildResponseItem(createdCustomer))
    }

    public Map findCustomer(String cpfCnpj) {
        CustomerPartnerApplication customerPartnerApplication = customerPartnerApplicationService.findCustomerPartnerApplication(cpfCnpj, PartnerApplicationName.BRADESCO)
        if (!customerPartnerApplication) throw new ResourceNotFoundException("Nenhuma conta encontrada.")

        return apiResponseBuilderService.buildSuccess(ApiBradescoCustomerParser.buildResponseItem(customerPartnerApplication.customer))
    }

    private Customer validateSave(BradescoCustomerAdapter customerAdapter) {
        Customer customer = new Customer()

        if (!customerAdapter.cpfCnpj) {
            DomainUtils.addError(customer, "O campo cpfCnpj deve ser informado.")
        } else {
            CustomerPartnerApplication customerPartnerApplication = customerPartnerApplicationService.findCustomerPartnerApplication(customerAdapter.cpfCnpj, PartnerApplicationName.BRADESCO)
            if (customerPartnerApplication) {
                DomainUtils.addError(customer, "Cliente com CNPJ ${customerAdapter.cpfCnpj} já tem uma conta Asaas integrada com Bradesco.")
            }
        }

        if (!customerAdapter.name) {
            DomainUtils.addError(customer, "O campo name deve ser informado.")
        }

        return customer
    }

}
