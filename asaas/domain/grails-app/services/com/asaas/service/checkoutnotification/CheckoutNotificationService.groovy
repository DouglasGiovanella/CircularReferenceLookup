package com.asaas.service.checkoutnotification

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.customer.CustomerTransferConfig
import com.asaas.domain.sms.AsyncSmsType
import com.asaas.domain.sms.SmsPriority
import com.asaas.generatereceipt.PixTransactionGenerateReceiptUrl
import com.asaas.receipt.ReceiptSmsVO
import com.asaas.domain.bill.Bill
import com.asaas.domain.checkoutnotificationconfig.CheckoutNotificationConfig
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.pix.PixTransaction
import com.asaas.log.AsaasLogger
import com.asaas.receipt.ReceiptReceiverType
import com.asaas.receipt.ReceiptEmailVO
import com.asaas.checkoutnotification.vo.CheckoutNotificationVO
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError

import grails.transaction.Transactional

@Transactional
public class CheckoutNotificationService {

    def asaasSegmentioService
    def asyncActionService
    def customerMessageService
    def messageService
    def urlShortenerService
    def asyncSmsMessageService
    def transactionReceiptPixTransactionService

    public CheckoutNotificationConfig saveConfig(CreditTransferRequest creditTransferRequest, CheckoutNotificationVO checkoutNotificationVO) {
        AsaasError asaasError = checkoutNotificationVO.validate()
        if (asaasError) return DomainUtils.addError(new CheckoutNotificationConfig(), asaasError.getMessage())

        CheckoutNotificationConfig checkoutNotificationConfig = buildFromVO(checkoutNotificationVO)
		checkoutNotificationConfig.creditTransferRequest = creditTransferRequest

		return checkoutNotificationConfig.save(failOnError: true)
    }

    public CheckoutNotificationConfig saveConfig(PixTransaction pixTransaction, CheckoutNotificationVO checkoutNotificationVO) {
        AsaasError asaasError = checkoutNotificationVO.validate()
        if (asaasError) return DomainUtils.addError(new CheckoutNotificationConfig(), asaasError.getMessage())

        CheckoutNotificationConfig checkoutNotificationConfig = buildFromVO(checkoutNotificationVO)
        checkoutNotificationConfig.pixTransaction = pixTransaction

        return checkoutNotificationConfig.save(failOnError: true)
    }

    public CheckoutNotificationConfig saveConfig(Bill bill, Map params) {

        CheckoutNotificationConfig checkoutNotificationConfig = build(params)
        checkoutNotificationConfig.bill = bill

		return checkoutNotificationConfig.save(failOnError: true)
    }

    public CheckoutNotificationConfig build(Map params) {
		CheckoutNotificationConfig checkoutNotificationConfig = new CheckoutNotificationConfig()

		checkoutNotificationConfig.phone = params.phone
		checkoutNotificationConfig.email = params.email
		checkoutNotificationConfig.name = params.name
        checkoutNotificationConfig.message = params.message

        return checkoutNotificationConfig
    }

    public CheckoutNotificationConfig buildFromVO(CheckoutNotificationVO checkoutNotificationVO) {
        CheckoutNotificationConfig checkoutNotificationConfig = new CheckoutNotificationConfig()

        checkoutNotificationConfig.phone = checkoutNotificationVO.phone
        checkoutNotificationConfig.email = checkoutNotificationVO.email
        checkoutNotificationConfig.name = checkoutNotificationVO.name
        checkoutNotificationConfig.message = checkoutNotificationVO.message

        return checkoutNotificationConfig
    }

    public void sendReceiptEmail(Bill bill) {
        if (!customerMessageService.canSendMessage(bill.customer)) return

        ReceiptEmailVO receiptEmailVO = new ReceiptEmailVO(bill, ReceiptReceiverType.PROVIDER, null)
        messageService.sendReceipt(receiptEmailVO)

        CheckoutNotificationConfig checkoutNotificationConfig = bill.getCheckoutNotificationConfig()
        if (CheckoutNotificationConfig.canSendEmail(checkoutNotificationConfig)) {
            receiptEmailVO = new ReceiptEmailVO(bill, ReceiptReceiverType.THIRD_PARTY, checkoutNotificationConfig.message)
            messageService.sendReceipt(receiptEmailVO)
            asaasSegmentioService.track(receiptEmailVO.provider.id, "Service :: Bill :: Email recibo pagamento enviado", [bill: bill.id, recipient: receiptEmailVO.recipient])
		}
    }

    public void sendReceiptEmail(CreditTransferRequest creditTransferRequest) {
        if (!customerMessageService.canSendMessage(creditTransferRequest.provider)) return

        ReceiptEmailVO receiptEmailVO = new ReceiptEmailVO(creditTransferRequest, ReceiptReceiverType.PROVIDER, null)
        messageService.sendReceipt(receiptEmailVO)

        CheckoutNotificationConfig checkoutNotificationConfig = CheckoutNotificationConfig.findByCreditTransferRequest(creditTransferRequest)
		if (CheckoutNotificationConfig.canSendEmail(checkoutNotificationConfig)) {
            receiptEmailVO = new ReceiptEmailVO(creditTransferRequest, ReceiptReceiverType.THIRD_PARTY, checkoutNotificationConfig.message)
            messageService.sendReceipt(receiptEmailVO)
            asaasSegmentioService.track(receiptEmailVO.provider.id, "Service :: CreditTransferRequest :: Email recibo transferencia enviado", [creditTransferRequest: creditTransferRequest.id, recipient: receiptEmailVO.recipient])
		}
    }

    public void saveAsyncActionToSendPixTransactionReceipt(PixTransaction pixTransaction) {
        if (!customerMessageService.canSendMessage(pixTransaction.customer)) return

        asyncActionService.save(AsyncActionType.PIX_DEBIT_SEND_RECEIPT, [pixTransactionId: pixTransaction.id])
    }

    public void processSendPixTransactionReceiptAsyncAction() {
        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.PIX_DEBIT_SEND_RECEIPT, 500)

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixTransaction pixTransaction = PixTransaction.read(asyncActionData.pixTransactionId)

                Boolean sendCustomerReceiptDisabled = CustomerTransferConfig.query([column: "sendCustomerReceiptDisabled", customer: pixTransaction.customer]).get().asBoolean()
                sendPixTransactionReceiptEmail(pixTransaction, sendCustomerReceiptDisabled)
                sendPixTransactionReceiptSms(pixTransaction, sendCustomerReceiptDisabled)

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "${this.getClass().getSimpleName()}.processSendPixTransactionReceiptAsyncAction >> Erro ao processar async action. [id: ${asyncActionData.asyncActionId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        }
    }

    public void sendReceiptSms(CreditTransferRequest creditTransferRequest) {
        if (!customerMessageService.canSendMessage(creditTransferRequest.provider)) return

        String transactionReceiptPublicId = creditTransferRequest.getTransactionReceiptPublicId()
        String url

        Utils.withNewTransactionAndRollbackOnError({
            url = urlShortenerService.createShortenedUrl('transactionReceipt', 'show', transactionReceiptPublicId)
        }, [onError: { Exception exception -> throw exception }, ignoreStackTrace: true])

        try {
            CheckoutNotificationConfig checkoutNotificationConfig = CheckoutNotificationConfig.findByCreditTransferRequest(creditTransferRequest)
            if (checkoutNotificationConfig?.phone) {
                ReceiptSmsVO sendReceiptSmsToReceiverVO = new ReceiptSmsVO(creditTransferRequest, ReceiptReceiverType.THIRD_PARTY, url)
                sendAsyncSms(sendReceiptSmsToReceiverVO.getTransferMessage(), checkoutNotificationConfig.phone)
            }
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendReceiptSms >> Erro ao enviar SMS de confirmação da transferência para terceiro [creditTransferRequestId: ${creditTransferRequest.id}]", exception)
        }

        try {
            if (PhoneNumberUtils.validateMobilePhone(creditTransferRequest.provider.mobilePhone)) {
                ReceiptSmsVO sendReceiptSmsToCustomerVO = new ReceiptSmsVO(creditTransferRequest, ReceiptReceiverType.PROVIDER, url)
                sendAsyncSms(sendReceiptSmsToCustomerVO.getTransferMessage(), creditTransferRequest.provider.mobilePhone)
            }
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendReceiptSms >> Erro ao enviar SMS de confirmação da transferência para Cliente [creditTransferRequestId: ${creditTransferRequest.id}]", exception)
		}
    }

    public void sendReceiptSms(Bill bill) {
        if (!customerMessageService.canSendMessage(bill.customer)) return

        String value = FormUtils.formatCurrencyWithoutMonetarySymbol(bill.value)
        String paymentDate = CustomDateUtils.fromDate(bill.paymentDate)
        String transactionReceiptPublicId = bill.getTransactionReceiptPublicId()
        String url

        Utils.withNewTransactionAndRollbackOnError({
            url = urlShortenerService.createShortenedUrl('transactionReceipt', 'show', transactionReceiptPublicId)
        }, [onError: { Exception exception -> throw exception }, ignoreStackTrace: true])

        try {
            if (bill.getCheckoutNotificationConfig()?.phone) {
                List smsTextArgs = [bill.getCheckoutNotificationConfig().name, value, paymentDate, url ]
                String smsText = Utils.getMessageProperty("bill.receipt.sms.message", smsTextArgs)
                sendAsyncSms(smsText, bill.getCheckoutNotificationConfig().phone)
            }
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar SMS de confirmação da pagamento para Terceiro - id: ${bill.id} >> " + exception.getMessage(), exception)
        }

        try {
            if (PhoneNumberUtils.validateMobilePhone(bill.customer.mobilePhone)) {
                List smsTextArgs = [bill.customer.getFirstName(), value, paymentDate, url ]
                String smsText = Utils.getMessageProperty("bill.receipt.sms.message", smsTextArgs)
                sendAsyncSms(smsText, bill.customer.mobilePhone)
            }
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar SMS de confirmação da pagamento para Fornecedor - id: ${bill.id} >> " + exception.getMessage(), exception)
        }
    }

    private void sendPixTransactionReceiptEmail(PixTransaction pixTransaction, Boolean sendCustomerReceiptDisabled) {
        if (!customerMessageService.canSendMessage(pixTransaction.customer)) return

        if (!sendCustomerReceiptDisabled) {
            ReceiptEmailVO receiptCustomerEmailVO = new ReceiptEmailVO(pixTransaction, ReceiptReceiverType.PROVIDER, null)
            messageService.sendReceipt(receiptCustomerEmailVO)
        }

        CheckoutNotificationConfig checkoutNotificationConfig = CheckoutNotificationConfig.findByPixTransaction(pixTransaction)
        if (CheckoutNotificationConfig.canSendEmail(checkoutNotificationConfig)) {
            ReceiptEmailVO receiptThirdPartyEmailVO = new ReceiptEmailVO(pixTransaction, ReceiptReceiverType.THIRD_PARTY, checkoutNotificationConfig.message)
            messageService.sendReceipt(receiptThirdPartyEmailVO)
        }
    }

    private void sendPixTransactionReceiptSms(PixTransaction pixTransaction, Boolean sendCustomerReceiptDisabled) {
        if (!customerMessageService.canSendMessage(pixTransaction.customer)) return

        String transactionReceiptUrl

        Utils.withNewTransactionAndRollbackOnError({
            transactionReceiptUrl = new PixTransactionGenerateReceiptUrl(pixTransaction).generateShortenedUrl()
        }, [onError: { Exception exception -> throw exception }, ignoreStackTrace: true])

        try {
            CheckoutNotificationConfig checkoutNotificationConfig = CheckoutNotificationConfig.findByPixTransaction(pixTransaction)
            if (checkoutNotificationConfig?.phone) {
                ReceiptSmsVO receiptThirdPartySmsVO = new ReceiptSmsVO(pixTransaction, ReceiptReceiverType.THIRD_PARTY, transactionReceiptUrl)
                sendAsyncSms(receiptThirdPartySmsVO.getTransferMessage(), checkoutNotificationConfig.phone)
            }
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendPixTransactionReceiptSms >> Erro ao enviar SMS de confirmação da transferência para Terceiro [pixTransactionId: ${pixTransaction.id}]", exception)
        }

        if (!sendCustomerReceiptDisabled) {
            try {
                if (PhoneNumberUtils.validateMobilePhone(pixTransaction.customer.mobilePhone)) {
                    ReceiptSmsVO receiptCustomerEmailVO = new ReceiptSmsVO(pixTransaction, ReceiptReceiverType.PROVIDER, transactionReceiptUrl)
                    sendAsyncSms(receiptCustomerEmailVO.getTransferMessage(), pixTransaction.customer.mobilePhone)
                }
            } catch (Exception exception) {
                AsaasLogger.error("${this.getClass().getSimpleName()}.sendPixTransactionReceiptSms >> Erro ao enviar SMS de confirmação da transferência para Cliente [pixTransactionId: ${pixTransaction.id}]", exception)
            }
        }
    }

    private void sendAsyncSms(String message, String toPhone) {
        asyncSmsMessageService.save(message, null, toPhone, SmsPriority.LOW, AsyncSmsType.REGULAR)
    }
}
