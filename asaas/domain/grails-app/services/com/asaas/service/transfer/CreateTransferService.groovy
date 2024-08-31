package com.asaas.service.transfer

import com.asaas.checkout.CheckoutValidator
import com.asaas.checkout.CustomerCheckoutFee
import com.asaas.credittransferrequest.CreditTransferRequestCheckoutValidator
import com.asaas.credittransferrequest.TransferFeeReason
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.bankaccountinfo.BankAccountInfoPixKey
import com.asaas.domain.companypartnerquery.CompanyPartnerQuery
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimit
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.domain.transfer.Transfer
import com.asaas.domain.transfer.TransferType
import com.asaas.pix.PixCheckoutValidator
import com.asaas.pix.PixTransactionOriginType
import com.asaas.pix.vo.transaction.PixAddressKeyDebitVO
import com.asaas.pix.vo.transaction.PixManualDebitVO
import com.asaas.transfer.TransferUtils
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError

import grails.transaction.Transactional

@Transactional
class CreateTransferService {

    def creditTransferRequestService
    def pixDebitService

    public Map resolveTransferType(BankAccountInfo bankAccountInfo, BigDecimal value) {
        Boolean canUsePix = new CheckoutValidator(bankAccountInfo.customer).customerCanUsePix()
        if (!canUsePix) {
            return [
                transferType: TransferType.TED,
                reason: "Essa é sua primeira transferência. Ela precisa obrigatoriamente ser realizada para uma conta de sua titularidade."
            ]
        }

        Boolean bankAccountAllowsPix = bankAccountInfo.financialInstitution.isPixActiveParticipant()
        if (!bankAccountAllowsPix) {
            return [
                transferType: TransferType.TED,
                reason: "A instituição de destino não é participante do Pix."
            ]
        }

        Boolean toSameOwnership = bankAccountInfo.cpfCnpj == bankAccountInfo.customer.cpfCnpj
        PixTransactionBankAccountInfoCheckoutLimit pixTransactionBankAccountInfoCheckoutLimit = PixTransactionBankAccountInfoCheckoutLimit.query([bankAccountInfoId: bankAccountInfo.id]).get()

        Boolean invalidPixCheckout
        if (pixTransactionBankAccountInfoCheckoutLimit) {
            invalidPixCheckout = PixCheckoutValidator.validateCheckoutWithBankAccountInfoCheckoutLimit(
                bankAccountInfo.customer,
                Utils.toBigDecimal(value),
                PixTransactionOriginType.MANUAL,
                null,
                false,
                toSameOwnership,
                pixTransactionBankAccountInfoCheckoutLimit
            ).asBoolean()
        } else {
            invalidPixCheckout = PixCheckoutValidator.validateCheckoutLimit(
                bankAccountInfo.customer,
                Utils.toBigDecimal(value),
                PixTransactionOriginType.MANUAL,
                null,
                false,
                toSameOwnership
            ).asBoolean()
        }

        if (invalidPixCheckout) {
            return [
                transferType: TransferType.TED,
                reason: "Você já ultrapassou o limite Pix da sua conta."
            ]
        }
        return [transferType: TransferType.PIX]
    }

    public Transfer save(BankAccountInfo bankAccountInfo, BigDecimal value, TransferType transferType, Map options) {
        if (transferType.isPix()) {
            BankAccountInfoPixKey bankAccountInfoPixKey = bankAccountInfo.getPixKey()
            Customer customer = bankAccountInfo.customer

            PixTransaction pixTransaction
            if (bankAccountInfoPixKey) {
                pixTransaction = pixDebitService.saveAddressKeyDebit(customer, new PixAddressKeyDebitVO(bankAccountInfoPixKey, value, options), [:])
            } else {
                pixTransaction = pixDebitService.saveManualDebit(customer, new PixManualDebitVO(bankAccountInfo, value, options), [:])
            }

            if (pixTransaction.hasErrors()) return DomainUtils.copyAllErrorsFromObject(pixTransaction, new Transfer())
            return Transfer.query([pixTransaction: pixTransaction]).get()
        } else {
            CreditTransferRequest creditTransferRequest = creditTransferRequestService.save(bankAccountInfo.customer, bankAccountInfo.id, value, options)

            if (creditTransferRequest.hasErrors()) return DomainUtils.copyAllErrorsFromObject(creditTransferRequest, new Transfer())
            return creditTransferRequest.transfer
        }
    }

    public Transfer validate(Customer customer, Long bankAccountId, BigDecimal value, TransferType transferType, Date scheduledDate) throws Exception {
        Transfer validatedTransfer = new Transfer()

        BankAccountInfo bankAccountInfo = BankAccountInfo.find(Utils.toLong(bankAccountId), customer.id)

        Boolean bankAccountSupportsTransferType = bankAccountSupportsTransferType(bankAccountInfo, transferType)
        if (!bankAccountSupportsTransferType) return DomainUtils.addError(validatedTransfer, "A instituição de destino não permite o tipo de transferência selecionada.")

        if (transferType.isPix()) {
            BankAccountInfoPixKey bankAccountInfoPixKey = bankAccountInfo.getPixKey()

            PixTransaction validatedTransaction
            if (bankAccountInfoPixKey) {
                PixAddressKeyDebitVO pixAddressKeyDebitVO = new PixAddressKeyDebitVO(bankAccountInfoPixKey, value, [scheduledDate: scheduledDate])
                validatedTransaction = PixCheckoutValidator.validateDebit(customer, pixAddressKeyDebitVO)
            } else {
                PixManualDebitVO pixManualDebitVO = new PixManualDebitVO(bankAccountInfo, value, [scheduledDate: scheduledDate])
                validatedTransaction = PixCheckoutValidator.validateDebit(customer, pixManualDebitVO)
            }
            if (validatedTransaction.hasErrors()) return DomainUtils.copyAllErrorsFromObject(validatedTransaction, validatedTransfer)
            return validatedTransfer
        }

        AsaasError asaasError = creditTransferRequestService.getDenialReasonIfExists(customer, bankAccountId, value, [scheduledDate: scheduledDate])
        if (asaasError) return DomainUtils.addError(validatedTransfer, asaasError.getMessage())
        return validatedTransfer
    }

    public Transfer validateValue(Customer customer, Long bankAccountId, BigDecimal value, TransferType transferType, Map params) throws Exception {
        Transfer validatedTransfer = new Transfer()

        BankAccountInfo bankAccountInfo = BankAccountInfo.find(Utils.toLong(bankAccountId), customer.id)
        if (transferType.isPix()) {
            BankAccountInfoPixKey bankAccountInfoPixKey = bankAccountInfo.getPixKey()

            AsaasError asaasError
            if (bankAccountInfoPixKey) {
                PixAddressKeyDebitVO pixAddressKeyDebitVO = new PixAddressKeyDebitVO(bankAccountInfoPixKey, value, params)
                asaasError = PixCheckoutValidator.validateValue(customer, pixAddressKeyDebitVO)
            } else {
                PixManualDebitVO pixManualDebitVO = new PixManualDebitVO(bankAccountInfo, value, params)
                asaasError = PixCheckoutValidator.validateValue(customer, pixManualDebitVO)
            }

            if (asaasError) return DomainUtils.addError(validatedTransfer, asaasError.getMessage())
            return validatedTransfer
        }

        AsaasError asaasError = CreditTransferRequestCheckoutValidator.validateValue(customer, bankAccountInfo, value, [:])
        if (asaasError) return DomainUtils.addError(validatedTransfer, asaasError.getMessage())
        return validatedTransfer
    }

    public Date calculateEstimatedDebitDate(Date requestDate, Integer estimatedDaysForConfirmation) {
        if (!requestDate) return null

        return CustomDateUtils.addBusinessDays(requestDate, estimatedDaysForConfirmation).clearTime()
    }

    public Map calculateTransferFeeAndReason(Customer customer, BankAccountInfo bankAccountInfo, BigDecimal value, TransferType transferType, Date scheduledDate) {
        if (TransferUtils.isScheduledTransfer(scheduledDate)) return [fee: 0, reason: null]

        if (transferType.isPix()) {
            TransferFeeReason transferFeeReason = getPixDebitFeeReasonIfExists(customer, bankAccountInfo, value)
            return [fee: transferFeeReason ? CustomerFee.calculatePixDebitFee(customer) : 0, reason: transferFeeReason]
        }

        TransferFeeReason transferFeeReason = getCreditTransferRequestFeeReasonIfExists(customer, bankAccountInfo, value)
        return [fee: transferFeeReason ? CustomerFee.calculateTransferFeeValue(customer) : 0, reason: transferFeeReason]
    }

    public Integer getEstimatedDaysForDebit(TransferType transferType) {
        if (transferType.isTed()) {
            Boolean canExecuteTransferToday = CustomDateUtils.getInstanceOfCalendar().get(Calendar.HOUR_OF_DAY) < CreditTransferRequest.getLimitHourToExecuteTransferToday()
            return canExecuteTransferToday ? 0 : 1
        } else {
            return 0
        }
    }

    public Integer getEstimatedDaysForScheduledDebit(Date scheduledDate) {
        return CustomDateUtils.calculateDifferenceInDays(new Date(), scheduledDate)
    }

    private TransferFeeReason getPixDebitFeeReasonIfExists(Customer customer, BankAccountInfo bankAccountInfo, BigDecimal value) {
        if (CustomerCheckoutFee.shouldChargePixDebitFee(customer, PixTransactionOriginType.MANUAL, bankAccountInfo.cpfCnpj == bankAccountInfo.customer.cpfCnpj, BigDecimalUtils.abs(value))) {
            return TransferFeeReason.MONTHLY_TRANSFERS_WITHOUT_CHARGE_FEE_REACHED
        }
        return null
    }

    private TransferFeeReason getCreditTransferRequestFeeReasonIfExists(Customer customer, BankAccountInfo bankAccountInfo, BigDecimal value) {
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
        } else if (!bankAccountInfo.mainAccount) {
            return TransferFeeReason.NOT_MAIN_ACCOUNT
        }

        return null
    }

    private Boolean bankAccountSupportsTransferType(BankAccountInfo bankAccountInfo, TransferType transferType) {
        if (transferType.isPix()) {
            return bankAccountInfo.bankAccountSupportsPix()
        } else {
            return bankAccountInfo.bankAccountSupportsTed()
        }
    }
}
