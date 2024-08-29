package com.asaas.service.transfer

import com.asaas.domain.accountnumber.AccountNumber
import com.asaas.domain.bank.Bank
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.checkoutRiskAnalysis.CheckoutRiskAnalysisRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.paymentserviceprovider.PaymentServiceProvider
import com.asaas.domain.pix.PixTransactionExternalAccount
import com.asaas.domain.transfer.Transfer
import com.asaas.domain.transfer.TransferDestinationBankAccount
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.transfer.TransferDestinationBankAccountType
import com.asaas.transfer.TransferStatus
import com.asaas.transfer.parser.TransferDestinationBankAccountParser
import grails.transaction.Transactional
import org.hibernate.criterion.CriteriaSpecification

@Transactional
class TransferDestinationBankAccountService {

    public TransferDestinationBankAccount saveFromInternalTransfer(Transfer transfer) {
        Customer destinationCustomer = transfer.internalTransfer.destinationCustomer
        AccountNumber accountNumber = destinationCustomer.getAccountNumber()

        TransferDestinationBankAccount transferExternalAccount = new TransferDestinationBankAccount()
        transferExternalAccount.transfer = transfer
        transferExternalAccount.cpfCnpj = destinationCustomer.cpfCnpj
        transferExternalAccount.name = destinationCustomer.getProviderName()
        transferExternalAccount.bank = Bank.query([code: Bank.ASAAS_BANK_CODE]).get()
        transferExternalAccount.agency = accountNumber?.agency
        transferExternalAccount.account = accountNumber?.account
        transferExternalAccount.accountDigit = accountNumber?.accountDigit
        transferExternalAccount.type = TransferDestinationBankAccountType.PAYMENT_ACCOUNT

        return transferExternalAccount.save(failOnError: true)
    }

    public TransferDestinationBankAccount saveFromPixTransaction(Transfer transfer) {
        PixTransactionExternalAccount pixTransactionExternalAccount = transfer.pixTransaction.buildExternalAccount()

        TransferDestinationBankAccount transferExternalAccount = new TransferDestinationBankAccount()
        transferExternalAccount.transfer = transfer
        transferExternalAccount.cpfCnpj = pixTransactionExternalAccount.cpfCnpj
        transferExternalAccount.name = pixTransactionExternalAccount.name
        transferExternalAccount.paymentServiceProvider = PaymentServiceProvider.query([ispb: PixUtils.parseIspb(pixTransactionExternalAccount.ispb)]).get()

        transferExternalAccount.agency = pixTransactionExternalAccount.agency
        transferExternalAccount.account = PixTransactionExternalAccount.removeAccountDigit(pixTransactionExternalAccount.account)
        transferExternalAccount.accountDigit = PixTransactionExternalAccount.retrieveAccountDigit(pixTransactionExternalAccount.account)
        transferExternalAccount.type = TransferDestinationBankAccountParser.parseFromPixAccountType(pixTransactionExternalAccount.accountType)

        return transferExternalAccount.save(failOnError: true)
    }

    public TransferDestinationBankAccount saveFromCreditTransferRequest(Transfer transfer) {
        BankAccountInfo bankAccountInfo = transfer.creditTransferRequest.bankAccountInfo

        TransferDestinationBankAccount transferExternalAccount = new TransferDestinationBankAccount()
        transferExternalAccount.transfer = transfer
        transferExternalAccount.cpfCnpj = bankAccountInfo.cpfCnpj
        transferExternalAccount.name = bankAccountInfo.name
        transferExternalAccount.bank = bankAccountInfo.bank

        transferExternalAccount.agency = bankAccountInfo.agency
        transferExternalAccount.agencyDigit = bankAccountInfo.agencyDigit
        transferExternalAccount.account = bankAccountInfo.account
        transferExternalAccount.accountDigit = bankAccountInfo.accountDigit
        transferExternalAccount.type = TransferDestinationBankAccountParser.parseFromBankAccountType(bankAccountInfo.bankAccountType)

        return transferExternalAccount.save(failOnError: true)
    }

    public List<Map> findTransferDestinationGroupedByBankAccount(CheckoutRiskAnalysisRequest analysisRequest) {
        List<Map> transferDestinationBankAccountList = TransferDestinationBankAccount.createCriteria().list(readOnly: true) {
            resultTransformer(CriteriaSpecification.ALIAS_TO_ENTITY_MAP)

            createAlias("transfer", "transfer")
            createAlias("bank", "bank", CriteriaSpecification.LEFT_JOIN)
            createAlias("paymentServiceProvider", "paymentServiceProvider", CriteriaSpecification.LEFT_JOIN)

            projections {
                property("cpfCnpj", "cpfCnpj")
                property("name", "name")
                property("type", "type")
                property("bank.name", "bankName")
                property("paymentServiceProvider.corporateName", "paymentServiceProviderCorporateName")
                property("account", "account")
                property("accountDigit", "accountDigit")
                property("agency", "agency")
                property("agencyDigit", "agencyDigit")

                groupProperty("id")
                groupProperty("cpfCnpj")
                groupProperty("bank.name")
                groupProperty("type")
                groupProperty("paymentServiceProvider.corporateName")
                groupProperty("account")
                groupProperty("accountDigit")
                groupProperty("agency")
                groupProperty("agencyDigit")
            }

            eq("transfer.customer.id", analysisRequest.customerId)
            eq("transfer.status", TransferStatus.DONE)
            order("id", "desc")
        }

        return transferDestinationBankAccountList
    }
}
