package com.asaas.service.pix

import com.asaas.criticalaction.CriticalActionType
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionExternalAccount
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixCheckoutValidator
import com.asaas.pix.PixTransactionOriginType
import com.asaas.pix.PixTransactionRefusalReason
import com.asaas.pix.PixTransactionType
import com.asaas.pix.adapter.transaction.debitrefund.DebitRefundAdapter
import com.asaas.pix.validator.PixAddressKeyValidator
import com.asaas.pix.vo.transaction.PixAddressKeyDebitVO
import com.asaas.pix.vo.transaction.PixDebitVO
import com.asaas.pix.vo.transaction.PixExternalDebitVO
import com.asaas.pix.vo.transaction.PixManualDebitVO
import com.asaas.pix.vo.transaction.PixQrCodeDebitVO
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.RequestUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PixDebitService {

    def asaasMoneyService
    def bankAccountInfoService
    def criticalActionService
    def pixTransactionService

    public PixTransaction saveExternalDebit(Customer customer, PixExternalDebitVO externalDebitVO) {
        PixTransaction validatedTransaction = PixCheckoutValidator.validateDebit(customer, externalDebitVO)
        if (validatedTransaction.hasErrors()) return validatedTransaction

        return pixTransactionService.save(customer, externalDebitVO)
    }

    public CriticalActionGroup requestManualDebitAuthorizationToken(Customer customer, PixManualDebitVO pixManualDebitVO) {
        String hash = buildCriticalActionHash(customer, pixManualDebitVO)

        String externalAccountInfoName = (pixManualDebitVO.externalAccount.name as String)?.take(20) ?: ""
        String authorizationMessage = "o código para autorizar a transferência no valor de ${FormUtils.formatCurrencyWithMonetarySymbol(BigDecimalUtils.abs(pixManualDebitVO.value))} para ${externalAccountInfoName} é"

        CriticalActionGroup actionGroup = criticalActionService.saveAndSendSynchronous(customer, CriticalActionType.PIX_SAVE_MANUAL_DEBIT, hash, authorizationMessage)
        return actionGroup
    }

    public PixTransaction saveManualDebit(Customer customer, PixManualDebitVO pixManualDebitVO, Map tokenParams) {
        PixTransaction validatedToken = validateToken(customer, pixManualDebitVO, CriticalActionType.PIX_SAVE_MANUAL_DEBIT, tokenParams)
        if (validatedToken.hasErrors()) return validatedToken

        PixTransaction validatedTransaction = PixCheckoutValidator.validateDebit(customer, pixManualDebitVO)
        if (validatedTransaction.hasErrors()) return validatedTransaction

        PixTransaction pixTransaction = pixTransactionService.save(customer, pixManualDebitVO)

        if (pixTransaction && pixManualDebitVO.saveBankAccount) {
            PixTransactionExternalAccount pixTransactionExternalAccount = PixTransactionExternalAccount.read(pixTransaction.externalAccount.id)
            bankAccountInfoService.createAsyncFromPixTransactionWithNewTransactionIfPossible(pixTransaction.type, pixTransaction.customer, pixTransactionExternalAccount, null)
        }

        return pixTransaction
    }

    public CriticalActionGroup requestQrCodeDebitAuthorizationToken(Customer customer, PixQrCodeDebitVO pixQrCodeDebitVO) {
        String hash = buildCriticalActionHash(customer, pixQrCodeDebitVO)

        String qrCodeReceiverName = (pixQrCodeDebitVO.externalAccount.name as String)?.take(20) ?: ""
        String authorizationMessage = "o código para autorizar a transferência no valor de ${FormUtils.formatCurrencyWithMonetarySymbol(BigDecimalUtils.abs(pixQrCodeDebitVO.value))} para ${qrCodeReceiverName} é"

        return criticalActionService.saveAndSendSynchronous(customer, CriticalActionType.PIX_SAVE_QR_CODE_DEBIT, hash, authorizationMessage)
    }

    public PixTransaction saveQrCodeDebit(Customer customer, PixQrCodeDebitVO pixQrCodeDebitVO, Map tokenParams) {
        PixTransaction validatedToken = validateToken(customer, pixQrCodeDebitVO, CriticalActionType.PIX_SAVE_QR_CODE_DEBIT, tokenParams)
        if (validatedToken.hasErrors()) return validatedToken

        PixTransaction validatedTransaction = PixCheckoutValidator.validateQrCodeDebit(customer, pixQrCodeDebitVO)
        if (validatedTransaction.hasErrors()) return validatedTransaction

        return pixTransactionService.save(customer, pixQrCodeDebitVO)
    }

    public CriticalActionGroup requestAddressKeyDebitAuthorizationToken(Customer customer, PixAddressKeyDebitVO pixAddressKeyDebitVO) {
        String hash = buildCriticalActionHash(customer, pixAddressKeyDebitVO)
        String authorizationMessage = "o código para autorizar a transferência no valor de ${FormUtils.formatCurrencyWithMonetarySymbol(BigDecimalUtils.abs(pixAddressKeyDebitVO.value))} para chave Pix: ${pixAddressKeyDebitVO.pixKey} é"
        return criticalActionService.saveAndSendSynchronous(customer, CriticalActionType.PIX_SAVE_ADDRESS_KEY_DEBIT, hash, authorizationMessage)
    }

    public PixTransaction saveAddressKeyDebit(Customer customer, PixAddressKeyDebitVO pixAddressKeyDebitVO, Map tokenParams) {
        BusinessValidation pixAddressKeyBusinessValidation = PixAddressKeyValidator.validate(pixAddressKeyDebitVO.pixKey, pixAddressKeyDebitVO.pixKeyType)
        if (!pixAddressKeyBusinessValidation.isValid()) return DomainUtils.addError(new PixTransaction(), pixAddressKeyBusinessValidation.getFirstErrorMessage())

        PixTransaction validatedToken = validateToken(customer, pixAddressKeyDebitVO, CriticalActionType.PIX_SAVE_ADDRESS_KEY_DEBIT, tokenParams)
        if (validatedToken.hasErrors()) return validatedToken

        PixTransaction validatedTransaction = PixCheckoutValidator.validateDebit(customer, pixAddressKeyDebitVO)
        if (validatedTransaction.hasErrors()) return validatedTransaction

        PixTransaction pixTransaction = pixTransactionService.save(customer, pixAddressKeyDebitVO)

        if (pixTransaction && pixAddressKeyDebitVO.saveBankAccount) {
            PixTransactionExternalAccount pixTransactionExternalAccount = PixTransactionExternalAccount.read(pixTransaction.externalAccount.id)
            bankAccountInfoService.createAsyncFromPixTransactionWithNewTransactionIfPossible(pixTransaction.type, pixTransaction.customer, pixTransactionExternalAccount, pixTransaction.getDestinationKeyIfPossible())
        }

        return pixTransaction
    }

    private PixTransaction validateToken(Customer customer, PixDebitVO pixDebitVO, CriticalActionType type, Map tokenParams) {
        PixTransaction validatedToken = new PixTransaction()

        if (!pixDebitVO.authorizeSynchronous) return validatedToken
        if (pixDebitVO instanceof PixAddressKeyDebitVO && pixDebitVO.isRecurringTransfer) return validatedToken
        if (pixDebitVO instanceof PixManualDebitVO && pixDebitVO.isRecurringTransfer) return validatedToken

        CustomerCriticalActionConfig customerCriticalActionConfig = CustomerCriticalActionConfig.query([customerId: customer.id]).get()
        Boolean isTokenRequired = customerCriticalActionConfig?.isPixTransactionAuthorizationEnabled()
        if (!isTokenRequired) return validatedToken

        BusinessValidation businessValidation = criticalActionService.authorizeSynchronous(customer, Utils.toLong(tokenParams.groupId), tokenParams.token, type, null, buildCriticalActionHash(customer, pixDebitVO))
        if (!businessValidation.isValid()) DomainUtils.addError(validatedToken, businessValidation.getFirstErrorMessage())
        return validatedToken
    }

    public String buildCriticalActionHash(Customer customer, PixDebitVO pixDebitVO) {
        String operation = ""
        operation += customer.id.toString()
        operation += pixDebitVO.externalAccount.ispb.toString()
        operation += pixDebitVO.externalAccount.cpfCnpj.toString()
        operation += pixDebitVO.externalAccount.accountType.toString()
        operation += pixDebitVO.externalAccount.account.toString()
        operation += pixDebitVO.externalAccount.agency.toString()
        operation += pixDebitVO.externalAccount.pixKey.toString()
        operation += pixDebitVO.scheduledDate.toString()
        operation += pixDebitVO.value.toString()

        if (pixDebitVO.originType.isQrCode()) {
            operation += pixDebitVO.conciliationIdentifier.toString()
            operation += pixDebitVO.externalQrCodeInfo.originalValue.toString()
            operation += pixDebitVO.externalQrCodeInfo.interestValue.toString()
            operation += pixDebitVO.externalQrCodeInfo.fineValue.toString()
            operation += pixDebitVO.externalQrCodeInfo.discountValue.toString()
            if (pixDebitVO.cashValueFinality) {
                operation += pixDebitVO.cashValueFinality.toString()
                operation += pixDebitVO.cashValue.toString()
            }
        }

        if (!operation) throw new RuntimeException("Operação não suportada!")
        return operation.encodeAsMD5()
    }

    public PixTransaction refund(DebitRefundAdapter refundInfo) {
        PixTransaction validatedRefund = validateRefund(refundInfo)
        if (validatedRefund.hasErrors()) return validatedRefund

        return pixTransactionService.refundDebit(refundInfo.pixDebitTransaction, refundInfo)
    }

    public PixTransaction validateRefund(DebitRefundAdapter refundInfo) {
        Boolean withoutDebitEndToEndIdentifier = (!refundInfo.debitEndToEndIdentifier)
        if (withoutDebitEndToEndIdentifier) return refuseRefund(refundInfo.properties, PixTransactionRefusalReason.DENIED, "Identificador da transação não foi informado.")

        Boolean invalidDebitEndToEndIdentifier = (refundInfo.pixDebitTransaction.endToEndIdentifier != refundInfo.debitEndToEndIdentifier)
        if (invalidDebitEndToEndIdentifier) return refuseRefund(refundInfo.properties, PixTransactionRefusalReason.DENIED, "Transação de origem não foi encontrada.")

        Boolean canBeRefunded = (refundInfo.pixDebitTransaction.canBeRefunded().isValid() && refundInfo.pixDebitTransaction.type.isDebit())
        if (!canBeRefunded) return refuseRefund(refundInfo.properties, PixTransactionRefusalReason.DENIED, "Transação de origem não está confirmada.")

        BigDecimal totalCompromisedValue = refundInfo.value.abs() + (refundInfo.pixDebitTransaction.refundedValue ?: 0)
        if (totalCompromisedValue > refundInfo.pixDebitTransaction.value.abs()) return refuseRefund(refundInfo.properties, PixTransactionRefusalReason.DENIED, "Valor total estornado maior do que o valor da transação.")

        if (refundInfo.externalIdentifier) {
            Boolean externalIdentifierAlreadyUsed = PixTransaction.query([exists: true, type: PixTransactionType.DEBIT_REFUND, externalIdentifier: refundInfo.externalIdentifier]).get().asBoolean()
            if (externalIdentifierAlreadyUsed) return refuseRefund(refundInfo.properties, PixTransactionRefusalReason.DENIED, "Identificador externo já utilizado.")
        }

        return new PixTransaction()
    }

    public Boolean isAsynchronousCheckout(PixDebitVO pixDebitVO) {
        if (PixUtils.isScheduledTransaction(pixDebitVO.scheduledDate)) return false
        if (!PixTransactionOriginType.listSupportsAsynchronousCheckout().contains(pixDebitVO.originType)) return false
        if (pixDebitVO.originType.isQrCode() && asaasMoneyService.isAsaasMoneyRequest()) return false

        return CustomerParameter.getValue(pixDebitVO.customer, CustomerParameterName.PIX_ASYNC_CHECKOUT).asBoolean()
    }

    private PixTransaction refuseRefund(Map refundInfo, PixTransactionRefusalReason refusalReason, String reasonDescription) {
        AsaasLogger.info("PixDebitService -> ${reasonDescription} [DebitRefundAdapter: [debitEndToEndIdentifier: ${refundInfo.debitEndToEndIdentifier}, refundEndToEndIdentifier: ${refundInfo.refundEndToEndIdentifier}]]")

        PixTransaction validatedTransaction = new PixTransaction()
        validatedTransaction.refusalReason = refusalReason
        DomainUtils.addError(validatedTransaction, Utils.getMessageProperty("PixTransactionRefusalReason.${refusalReason}", [reasonDescription]))

        return validatedTransaction
    }
}
