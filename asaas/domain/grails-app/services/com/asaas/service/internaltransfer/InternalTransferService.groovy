package com.asaas.service.internaltransfer

import com.asaas.authorizationrequest.AuthorizationRequestActionType
import com.asaas.authorizationrequest.AuthorizationRequestType
import com.asaas.checkout.CheckoutValidator
import com.asaas.criticalaction.CriticalActionType
import com.asaas.customer.CustomerOwnedByAsaasValidator
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.asaasmoney.AsaasMoneyTransactionInfo
import com.asaas.domain.bill.Bill
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.debtrecovery.DebtRecoveryNegotiationPayment
import com.asaas.domain.internalloan.InternalLoan
import com.asaas.domain.internalloan.InternalLoanPayment
import com.asaas.domain.internaltransfer.InternalTransfer
import com.asaas.domain.mobilephonerecharge.MobilePhoneRecharge
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.split.PaymentRefundSplit
import com.asaas.domain.split.PaymentSplit
import com.asaas.domain.transfer.Transfer
import com.asaas.domain.wallet.Wallet
import com.asaas.exception.BusinessException
import com.asaas.internaltransfer.InternalTransferStatus
import com.asaas.internaltransfer.InternalTransferType
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class InternalTransferService {

    def authorizationRequestService
    def customerExternalAuthorizationRequestCreateService
    def customerStageService
    def financialTransactionService
    def lastCheckoutInfoService
    def messageService
    def receivableAnticipationPartnerSettlementService
    def transactionReceiptService
    def transferService

    def onError(entity) {
        transactionStatus.setRollbackOnly()
        return entity
    }

    public InternalTransfer save(InternalLoan internalLoan, BigDecimal value) {
        if (CustomerOwnedByAsaasValidator.isInternalSystemAccount(internalLoan.guarantor)) throw new BusinessException(Utils.getMessageProperty("transfer.denied.asaasAccount.checkout.disabled"))

        lastCheckoutInfoService.save(internalLoan.guarantor)

        InternalTransfer internalTransfer = create(internalLoan.guarantor, internalLoan.debtor, value, InternalTransferType.INTERNAL_LOAN)
        internalTransfer.save(flush: true, failOnError: false)
        if (internalTransfer.hasErrors()) throw new BusinessException(Utils.getMessageProperty(internalTransfer.errors.allErrors[0]))

        String debitDescription = "Transferência enviada para regularização de saldo da conta Asaas ${internalTransfer.destinationCustomer.getProviderName()} - ${CpfCnpjUtils.formatCpfCnpj(internalTransfer.destinationCustomer.cpfCnpj)}"
        financialTransactionService.saveInternalTransferDebit(internalTransfer, debitDescription)

        String creditDescription = "Transferência recebida de regularização de saldo da conta Asaas ${internalTransfer.customer.getProviderName()} - ${CpfCnpjUtils.formatCpfCnpj(internalTransfer.customer.cpfCnpj)}"
        executeInternalTransfer(internalTransfer, creditDescription)

        return internalTransfer
    }

    public InternalTransfer save(InternalLoanPayment internalLoanPayment, BigDecimal value) {
        if (CustomerOwnedByAsaasValidator.isInternalSystemAccount(internalLoanPayment.debtor)) throw new BusinessException(Utils.getMessageProperty("transfer.denied.asaasAccount.checkout.disabled"))

        lastCheckoutInfoService.save(internalLoanPayment.debtor)

        InternalTransfer internalTransfer = create(internalLoanPayment.debtor, internalLoanPayment.creditor, value, InternalTransferType.INTERNAL_LOAN_PAYMENT)
        internalTransfer.save(flush: true, failOnError: false)
        if (internalTransfer.hasErrors()) throw new BusinessException(Utils.getMessageProperty(internalTransfer.errors.allErrors[0]))

        String debitDescription = "Transferência enviada para devolução do valor emprestado da conta Asaas ${internalTransfer.destinationCustomer.getProviderName()} - ${CpfCnpjUtils.formatCpfCnpj(internalTransfer.destinationCustomer.cpfCnpj)} para regularização de saldo"
        financialTransactionService.saveInternalTransferDebit(internalTransfer, debitDescription)

        String creditDescription = "Transferência recebida para regularização de saldo da conta Asaas ${internalTransfer.customer.getProviderName()} - ${CpfCnpjUtils.formatCpfCnpj(internalTransfer.customer.cpfCnpj)}"
        executeInternalTransfer(internalTransfer, creditDescription)

        return internalTransfer
    }

    public InternalTransfer save(PaymentSplit paymentSplit) {
        if (CustomerOwnedByAsaasValidator.isInternalSystemAccount(paymentSplit.payment.getProvider())) throw new BusinessException(Utils.getMessageProperty("transfer.denied.asaasAccount.checkout.disabled"))

    	InternalTransfer internalTransfer = create(paymentSplit.payment.getProvider(), paymentSplit.wallet.destinationCustomer, paymentSplit.totalValue, InternalTransferType.SPLIT)

        internalTransfer.paymentSplit = paymentSplit

        internalTransfer.save(flush: true, failOnError: true)

        financialTransactionService.saveInternalTransferDebit(internalTransfer, buildDebitDescriptionForSplit(internalTransfer))

        executeInternalTransfer(internalTransfer, buildCreditDescriptionForSplit(internalTransfer))

        return internalTransfer
    }

    public InternalTransfer save(Customer customer, String destinationWalletPublicId, BigDecimal value) {
        if (CustomerOwnedByAsaasValidator.isInternalSystemAccount(customer)) throw new BusinessException(Utils.getMessageProperty("transfer.denied.asaasAccount.checkout.disabled"))

        Wallet destinationWallet = Wallet.findByPublicId(destinationWalletPublicId)
        Customer destinationCustomer = destinationWallet.destinationCustomer

        InternalTransfer validatedDestinationCustomer = validateDestinationCustomer(destinationCustomer)
        if (validatedDestinationCustomer.hasErrors()) return validatedDestinationCustomer

        InternalTransfer validatedInternalTransferForThirdPartyAccount = validateInternalTransferForThirdPartyAccount(customer, destinationCustomer)
        if (validatedInternalTransferForThirdPartyAccount.hasErrors()) return validatedInternalTransferForThirdPartyAccount

        InternalTransfer validatedInternalTransfer = validateSaveForCheckout(new CheckoutValidator(customer), value)
        if (validatedInternalTransfer.hasErrors()) return validatedInternalTransfer

        lastCheckoutInfoService.save(customer)

        InternalTransfer internalTransfer = create(customer, destinationCustomer, value, InternalTransferType.ASAAS_ACCOUNT)

        AuthorizationRequestType authorizationRequestType = authorizationRequestService.findAuthorizationRequestType(customer, AuthorizationRequestActionType.INTERNAL_TRANSFER_SAVE)

        if (authorizationRequestType.isExternalAuthorization()) {
            internalTransfer.status = InternalTransferStatus.AWAITING_EXTERNAL_AUTHORIZATION
        }

        internalTransfer.destinationWallet = destinationWallet
        internalTransfer.save(failOnError: false)

        if (internalTransfer.hasErrors()) return internalTransfer

        String debitDescription = "Transferência para a conta Asaas ${internalTransfer.destinationCustomer.getProviderName()}"
        financialTransactionService.saveInternalTransferDebit(internalTransfer, debitDescription)
        Transfer transfer = transferService.save(customer, internalTransfer)

        if (authorizationRequestType.isExternalAuthorization()) {
            customerExternalAuthorizationRequestCreateService.saveForTransfer(transfer)
        } else if (authorizationRequestType.isCriticalAction()) {
            CriticalAction.save(customer, CriticalActionType.INTERNAL_TRANSFER, internalTransfer)
            internalTransfer.awaitingCriticalActionAuthorization = true
            internalTransfer.save(failOnError: false)

            if (internalTransfer.hasErrors()) return onError(internalTransfer)
        } else if (authorizationRequestType.isNone()) {
            String creditDescription = "Transferência da conta Asaas ${internalTransfer.customer.getProviderName()}"
            executeInternalTransfer(internalTransfer, creditDescription)
        }

        return internalTransfer
    }

    public InternalTransfer saveAsaasMoneyDiscountInternalTransfer(domainInstance, Customer asaasMoneyMainAccountCustomer, Customer payerCustomer, BigDecimal value) {
        BigDecimal maxDiscountValue = BigDecimalUtils.calculateValueFromPercentage(domainInstance.value, AsaasMoneyTransactionInfo.MAX_DISCOUNT_PERCENTAGE_OVER_VALUE)

        if (value > maxDiscountValue) throw new BusinessException("O valor do desconto não pode ser superior a ${AsaasMoneyTransactionInfo.MAX_DISCOUNT_PERCENTAGE_OVER_VALUE}% do valor da transação")

        InternalTransfer internalTransfer = create(asaasMoneyMainAccountCustomer, payerCustomer, value, InternalTransferType.ASAAS_MONEY_DISCOUNT)
        internalTransfer.save(failOnError: true)

        String debitDescription = "Transferência de crédito de desconto para pré pagamento "

        if (domainInstance instanceof Bill) {
            debitDescription += "de conta ${Boolean.valueOf(domainInstance.description) ? ':' + domainInstance.description : ''}"
        } else if (domainInstance instanceof MobilePhoneRecharge) {
            debitDescription += "de recarga para o celular ${PhoneNumberUtils.formatPhoneNumber(domainInstance.phoneNumber)} (${domainInstance.operatorName})"
        } else if (domainInstance instanceof PixTransaction) {
            debitDescription += "de cobrança Pix"
        } else {
            throw new RuntimeException("internalTransferService.saveAsaasMoneyDiscountInternalTransfer: Objeto [${domainInstance.class.simpleName}] não suportado.")
        }

        financialTransactionService.saveInternalTransferDebit(internalTransfer, debitDescription)
        transferService.save(asaasMoneyMainAccountCustomer, internalTransfer)

        return internalTransfer
    }

    public void executeAsaasMoneyDiscountInternalTransfer(InternalTransfer internalTransfer) {
        String creditDescription = "Crédito recebido referente a desconto Asaas Money"
        executeInternalTransfer(internalTransfer, creditDescription)
    }

    public InternalTransfer reverseAsaasMoneyDiscountInternalTransfer(AsaasMoneyTransactionInfo asaasMoneyTransactionInfo) {
        InternalTransfer reversedTransfer = asaasMoneyTransactionInfo.discountInternalTransfer

        if (reversedTransfer.status.isPending()) return cancel(reversedTransfer)

        InternalTransfer reversalTransfer = create(reversedTransfer.destinationCustomer, reversedTransfer.customer, reversedTransfer.value, InternalTransferType.ASAAS_MONEY_DISCOUNT_REVERSAL)
        reversalTransfer.reversedTransfer = reversedTransfer
        reversalTransfer.save(failOnError: true)

        String creditDescription = "Estorno da transferência de crédito de desconto para pré pagamento "

        if (asaasMoneyTransactionInfo.type.isBill()) {
            creditDescription += "de conta ${Boolean.valueOf(asaasMoneyTransactionInfo.destinationBill.description) ? ':' + asaasMoneyTransactionInfo.destinationBill.description : ''}"
        } else if (asaasMoneyTransactionInfo.type.isMobilePhoneRecharge()) {
            creditDescription += "de recarga para o celular ${PhoneNumberUtils.formatPhoneNumber(asaasMoneyTransactionInfo.destinationMobilePhoneRecharge.phoneNumber)} (${asaasMoneyTransactionInfo.destinationMobilePhoneRecharge.operatorName})"
        } else if (asaasMoneyTransactionInfo.type.isPixTransaction()) {
            creditDescription += "de cobrança Pix"
        }

        String debitDescription = "Estorno do crédito recebido referente a desconto Asaas Money"

        financialTransactionService.saveInternalTransferDebit(reversalTransfer, debitDescription)
        transferService.save(reversedTransfer.destinationCustomer, reversalTransfer)

        executeInternalTransfer(reversalTransfer, creditDescription)

        return reversalTransfer
    }

    public InternalTransfer saveAsaasMoneyBackingInternalTransfer(domainInstance, Customer asaasMoneyMainAccountCustomer, Customer payerCustomer, BigDecimal value) {
        InternalTransfer internalTransfer = create(asaasMoneyMainAccountCustomer, payerCustomer, value, InternalTransferType.ASAAS_MONEY)
        internalTransfer.save(failOnError: true)

        String debitDescription = ""
        if (domainInstance instanceof Bill) {
            debitDescription = "Transferência para pré pagamento de conta: ${domainInstance.description ? domainInstance.description : ''} ${domainInstance.customer.buildTradingName()}"
        } else if (domainInstance instanceof MobilePhoneRecharge) {
            debitDescription = "Transferência para pré pagamento de recarga para o celular ${PhoneNumberUtils.formatPhoneNumber(domainInstance.phoneNumber)}"
        } else if (domainInstance instanceof PixTransaction) {
            debitDescription = "Transferência para pré pagamento de cobrança Pix"
        } else {
            throw new RuntimeException("saveAsaasMoneyBackingInternalTransfer: Objeto [${domainInstance.class.simpleName}] não suportado.")
        }

        financialTransactionService.saveInternalTransferDebit(internalTransfer, debitDescription)
        transferService.save(asaasMoneyMainAccountCustomer, internalTransfer)

        return internalTransfer
    }

    public void executeAsaasMoneyBackingInternalTransfer(AsaasMoneyTransactionInfo asaasMoneyTransactionInfo) {
        String creditDescription = "Saldo gerado via cartão de crédito - "
        if (asaasMoneyTransactionInfo.type.isBill()) {
            creditDescription += "Pagamento de conta "
        } else  if (asaasMoneyTransactionInfo.type.isMobilePhoneRecharge()) {
            creditDescription += "Recarga para o celular "
        }else if (asaasMoneyTransactionInfo.type.isPixTransaction()) {
            creditDescription += "Pagamento via PIX "
        } else {
            throw new NotImplementedException("Tipo de transferência não implementado")
        }
        creditDescription += "ASAAS MONEY"

        executeInternalTransfer(asaasMoneyTransactionInfo.backingInternalTransfer, creditDescription)
    }

    public InternalTransfer reverseAsaasMoneyBackingInternalTransfer(AsaasMoneyTransactionInfo asaasMoneyTransactionInfo) {
        InternalTransfer reversedTransfer = asaasMoneyTransactionInfo.backingInternalTransfer

        if (reversedTransfer.status.isPending()) return cancel(reversedTransfer)

        InternalTransfer reversalTransfer = create(reversedTransfer.destinationCustomer, reversedTransfer.customer, reversedTransfer.value, InternalTransferType.ASAAS_MONEY_REVERSAL)
        reversalTransfer.reversedTransfer = reversedTransfer
        reversalTransfer.save(failOnError: true)

        String creditDescription = ""
        if (asaasMoneyTransactionInfo.type.isBill()) {
            creditDescription = "Estorno da transferência para pré pagamento de conta: ${asaasMoneyTransactionInfo.destinationBill.description ? asaasMoneyTransactionInfo.destinationBill.description : ''} ${asaasMoneyTransactionInfo.destinationBill.customer.buildTradingName()}"
        } else if (asaasMoneyTransactionInfo.type.isMobilePhoneRecharge()) {
            creditDescription = "Estorno de recarga para o celular ${PhoneNumberUtils.formatPhoneNumber(asaasMoneyTransactionInfo.destinationMobilePhoneRecharge.phoneNumber)}"
        } else if (asaasMoneyTransactionInfo.type.isPixTransaction()) {
            creditDescription += "Estorno de cobrança Pix"
        }

        String debitDescription = "Estorno de saldo gerado via cartão de crédito - "
        if (asaasMoneyTransactionInfo.type.isBill()) {
            debitDescription += "Pagamento de conta "
        } else if (asaasMoneyTransactionInfo.type.isMobilePhoneRecharge()) {
            debitDescription += "Recarga para o celular "
        }  else if (asaasMoneyTransactionInfo.type.isPixTransaction()) {
            debitDescription += "Pagamento via PIX "
        } else {
            throw new NotImplementedException("Tipo de estorno não implementado")
        }
        debitDescription += "ASAAS MONEY"

        financialTransactionService.saveInternalTransferDebit(reversalTransfer, debitDescription)
        transferService.save(reversedTransfer.destinationCustomer, reversalTransfer)

        executeInternalTransfer(reversalTransfer, creditDescription)

        return reversalTransfer
    }

    public InternalTransfer saveDebtRecoveryNegotiationInternalTransfer(DebtRecoveryNegotiationPayment negotiationPayment) {
        lastCheckoutInfoService.save(negotiationPayment.payment.provider)

        CheckoutValidator checkoutValidator = new CheckoutValidator(negotiationPayment.payment.provider)
        checkoutValidator.bypassSufficientBalance = true
        InternalTransfer validatedInternalTransfer = validateSaveForCheckout(checkoutValidator, negotiationPayment.netValue)
        if (validatedInternalTransfer.hasErrors()) return validatedInternalTransfer

        InternalTransfer internalTransfer = create(negotiationPayment.payment.provider, negotiationPayment.debtRecovery.debtorCustomer, negotiationPayment.netValue, InternalTransferType.ASAAS_ACCOUNT)
        internalTransfer.save(flush: true, failOnError: false)
        if (internalTransfer.hasErrors()) return internalTransfer

        financialTransactionService.saveDebtRecoveryInternalTransferDebit(internalTransfer)
        transferService.save(negotiationPayment.payment.provider, internalTransfer)

        String creditDescription = "Transferência da conta Asaas ${internalTransfer.customer.getProviderName()}"
        executeInternalTransfer(internalTransfer, creditDescription)
        return internalTransfer
    }

    private InternalTransfer validateDestinationCustomer(Customer destinationCustomer) {
        InternalTransfer validatedDestinationCustomer = new InternalTransfer()

        if (destinationCustomer.status.isInactive()) {
            DomainUtils.addError(validatedDestinationCustomer, Utils.getMessageProperty("transfer.denied.destinationCustomer.inactive"))
        }

        if (CustomerParameter.getValue(destinationCustomer, CustomerParameterName.CHECKOUT_DISABLED)) {
            DomainUtils.addError(validatedDestinationCustomer, Utils.getMessageProperty("transfer.denied.checkout.disabled"))
        }

        if (!destinationCustomer.customerRegisterStatus.generalApproval.isApproved()) {
            DomainUtils.addError(validatedDestinationCustomer, Utils.getMessageProperty("transfer.denied.destinationCustomer.register.general.pending"))
        }

        return validatedDestinationCustomer
    }

    private InternalTransfer validateSaveForCheckout(CheckoutValidator checkoutValidator, BigDecimal value) {
        checkoutValidator.isInternalTransfer = true

        InternalTransfer validatedInternalTransfer = new InternalTransfer()

        List<AsaasError> asaasErrorList = checkoutValidator.validate(value)
        if (asaasErrorList) {
            for (AsaasError asaasError in asaasErrorList) {
                asaasError.code = "transfer.${asaasError.code}"
                DomainUtils.addError(validatedInternalTransfer, asaasError.getMessage())
            }
        }

        return validatedInternalTransfer
    }

    public InternalTransfer cancel(InternalTransfer internalTransfer) {
        BusinessValidation canBeCancelled = internalTransfer.canBeCancelled()
        if (!canBeCancelled.isValid()) throw new BusinessException(canBeCancelled.getFirstErrorMessage())

        setAsCancelled(internalTransfer)

        financialTransactionService.reverseInternalTransferDebit(internalTransfer, null)

        CriticalAction.deleteNotAuthorized(internalTransfer)
    }

    public void onCriticalActionCancellation(CriticalAction criticalAction) {
        InternalTransfer internalTransfer = criticalAction.internalTransfer
        cancel(internalTransfer)
    }

    public void onCriticalActionAuthorization(CriticalAction criticalAction) {
        InternalTransfer internalTransfer = criticalAction.internalTransfer
        internalTransfer.awaitingCriticalActionAuthorization = false

        internalTransfer.save(failOnError: true)

        String creditDescription = "Transferência da conta Asaas ${internalTransfer.customer.getProviderName()}"
        executeInternalTransfer(internalTransfer, creditDescription)
    }

    public void onExternalAuthorizationApproved(InternalTransfer internalTransfer) {
        if (internalTransfer.status != InternalTransferStatus.AWAITING_EXTERNAL_AUTHORIZATION) {
            throw new RuntimeException("InternalTransferService.onExternalAuthorizationApproved > Transação [${internalTransfer.id}] não está aguardando autorização externa.")
        }

        String creditDescription = "Transferência da conta Asaas ${internalTransfer.customer.getProviderName()}"
        executeInternalTransfer(internalTransfer, creditDescription)
    }

    public void onExternalAuthorizationRefused(InternalTransfer internalTransfer) {
        if (internalTransfer.status != InternalTransferStatus.AWAITING_EXTERNAL_AUTHORIZATION) {
            throw new RuntimeException("InternalTransferService.onExternalAuthorizationRefused > Transação [${internalTransfer.id}] não está aguardando autorização externa.")
        }

        cancel(internalTransfer)
    }

    public BusinessValidation validateExternalAuthorizationTransferStatus(InternalTransfer internalTransfer) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (internalTransfer.status.isCancelled()) businessValidation.addError("customerExternalAuthorization.transfer.internalTransfer.status.isCancelled")

        return businessValidation
    }

    private void executeInternalTransfer(InternalTransfer internalTransfer, String description = null) {
        financialTransactionService.saveInternalTransferCredit(internalTransfer, null, description)
        setAsDone(internalTransfer)
        if (!internalTransfer.type.isSplit()) transactionReceiptService.save(internalTransfer)

        receivableAnticipationPartnerSettlementService.debitOverdueSettlements(internalTransfer.destinationCustomer, internalTransfer)
    }

    public InternalTransfer refundSplit(PaymentSplit paymentSplit) {
        InternalTransfer internalTransfer = InternalTransfer.query([paymentSplit: paymentSplit, type: InternalTransferType.SPLIT]).get()
        if (!internalTransfer) return

        BigDecimal alreadyReversedValue = InternalTransfer.sumValueAbs([paymentSplit: paymentSplit, type: InternalTransferType.SPLIT_REVERSAL]).get()
        BigDecimal remainingValue = internalTransfer.value - alreadyReversedValue

        if (remainingValue <= 0) return

        InternalTransfer reversalTransfer = create(internalTransfer.destinationCustomer, internalTransfer.customer, remainingValue, InternalTransferType.SPLIT_REVERSAL)
        reversalTransfer.reversedTransfer = internalTransfer
        reversalTransfer.paymentSplit = paymentSplit
        reversalTransfer.save(failOnError: true)

        financialTransactionService.saveInternalTransferDebit(reversalTransfer, buildDebitDescriptionForSplit(reversalTransfer))

        financialTransactionService.saveInternalTransferCredit(reversalTransfer, null, buildCreditDescriptionForSplit(reversalTransfer))
        setAsDone(reversalTransfer)

        return reversalTransfer
    }

    public InternalTransfer executePaymentRefundSplit(PaymentRefundSplit paymentRefundSplit) {
        InternalTransfer internalTransfer = InternalTransfer.query([paymentSplit: paymentRefundSplit.paymentSplit, type: InternalTransferType.SPLIT]).get()
        if (!internalTransfer) return

        InternalTransfer reversalTransfer = create(paymentRefundSplit.paymentSplit.wallet.destinationCustomer, internalTransfer.customer, paymentRefundSplit.value, InternalTransferType.SPLIT_REVERSAL)
        reversalTransfer.reversedTransfer = internalTransfer
        reversalTransfer.paymentSplit = paymentRefundSplit.paymentSplit
        reversalTransfer.save(failOnError: true)

        financialTransactionService.saveInternalTransferDebit(reversalTransfer, buildDebitDescriptionForSplit(reversalTransfer))

        financialTransactionService.saveInternalTransferCredit(reversalTransfer, null, buildCreditDescriptionForSplit(reversalTransfer))
        setAsDone(reversalTransfer)

        return reversalTransfer
    }

    private InternalTransfer create(Customer customer, Customer destinationCustomer, BigDecimal value, InternalTransferType type) {
        InternalTransfer internalTransfer = new InternalTransfer()

        internalTransfer.customer = customer
        internalTransfer.destinationCustomer = destinationCustomer
        internalTransfer.value = value
        internalTransfer.type = type
        internalTransfer.status = InternalTransferStatus.PENDING

        return internalTransfer
    }

    private void setAsCancelled(InternalTransfer internalTransfer) {
        internalTransfer.awaitingCriticalActionAuthorization = false
        internalTransfer.status = InternalTransferStatus.CANCELLED
        internalTransfer.save(failOnError: true)

        if (internalTransfer.type == InternalTransferType.ASAAS_ACCOUNT) transferService.setAsCancelled(internalTransfer.getTransfer())
    }

    private void setAsDone(InternalTransfer internalTransfer) {
        internalTransfer.status = InternalTransferStatus.DONE
        internalTransfer.transferDate = new Date().clearTime()
        internalTransfer.save(failOnError: true)

        if (internalTransfer.type == InternalTransferType.ASAAS_ACCOUNT) transferService.setAsDone(internalTransfer.getTransfer(), internalTransfer.transferDate)

        customerStageService.processCashInReceived(internalTransfer.destinationCustomer)
    }

    private InternalTransfer validateInternalTransferForThirdPartyAccount(Customer originCustomer, Customer destinationCustomer) {
        InternalTransfer validatedInternalTransfer = new InternalTransfer()

        Boolean originIsAccountOwner = originCustomer.accountOwner == null
        Boolean destinationIsAccountOwner = destinationCustomer.accountOwner == null

        Boolean isFromAccountOwnerToThirdPartyAccountOwner = originIsAccountOwner && destinationIsAccountOwner
        Boolean isFromAccountOwnerToThirdPartyChildAccount = originIsAccountOwner && !destinationIsAccountOwner && originCustomer.id != destinationCustomer.accountOwner.id
        Boolean isFromChildAccountToThirdPartyAccountOwner = !originIsAccountOwner && destinationIsAccountOwner && originCustomer.accountOwner.id != destinationCustomer.id
        Boolean isFromChildAccountToThirdPartyChildAccount = !originIsAccountOwner && !destinationIsAccountOwner && originCustomer.accountOwner.id != destinationCustomer.accountOwner.id

        if (!isFromAccountOwnerToThirdPartyAccountOwner && !isFromAccountOwnerToThirdPartyChildAccount && !isFromChildAccountToThirdPartyAccountOwner && !isFromChildAccountToThirdPartyChildAccount) {
            return validatedInternalTransfer
        }

        if (!CustomerParameter.getValue(originCustomer, CustomerParameterName.ALLOW_INTERNAL_TRANSFERS_TO_THIRD_PARTY)) {
            DomainUtils.addError(validatedInternalTransfer, Utils.getMessageProperty("transfer.denied.internalThirdParty.disabled"))
        }

        return validatedInternalTransfer
    }

    private String buildCreditDescriptionForSplit(InternalTransfer internalTransfer) {
        if (internalTransfer.reversedTransfer) {
            return "Estorno da comissão transferida para o parceiro ${internalTransfer.reversedTransfer.destinationCustomer.getProviderName()} - fatura nr. ${internalTransfer.reversedTransfer.paymentSplit.payment.getInvoiceNumber()}"
        }

        return "Comissão recebida do parceiro ${internalTransfer.customer.getProviderName()} - fatura nr. ${internalTransfer.paymentSplit.payment.getInvoiceNumber()}"
    }

    private String buildDebitDescriptionForSplit(InternalTransfer internalTransfer) {
        if (internalTransfer.reversedTransfer) {
            return "Estorno da comissão recebida do parceiro ${internalTransfer.reversedTransfer.customer.getProviderName()} - fatura nr. ${internalTransfer.paymentSplit.payment.getInvoiceNumber()}"
        }

        return "Comissão transferida para o parceiro ${internalTransfer.destinationCustomer.getProviderName()} - fatura nr. ${internalTransfer.paymentSplit.payment.getInvoiceNumber()}"
    }
}
