package com.asaas.service.integration.sinqia

import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.integration.sinqia.SinqiaFinancialStatement
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.integration.sinqia.enums.SinqiaMovementType
import com.asaas.integration.sinqia.enums.SinqiaResponsibilityCenter
import com.asaas.integration.sinqia.enums.SinqiaSyncStatus
import com.asaas.integration.sinqia.enums.SinqiaTransactionType
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class SinqiaFinancialStatementService {

    def grailsApplication

    public void createFromFinancialStatement(Date startDate, Date endDate) {
        if (!startDate || !endDate) throw new Exception("Os parâmetros [startDate, endDate] são obrigatórios para a consulta e criação dos registros.")

        List<Long> financialStatementIdList = FinancialStatement.query([column: "id",
                                                                        "statementDate[ge]": startDate,
                                                                        "statementDate[le]": endDate,
                                                                        "sinqiaFinancialStatement[notExists]": true,
                                                                        "value[isNotNull]": true,
                                                                        "financialStatementType[ne]": FinancialStatementType.PIX_DIRECT_CUSTOMER_REVENUE_TEST]).list()
        if (!financialStatementIdList) return

        for (Long id : financialStatementIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                save(FinancialStatement.get(id))
            }, [logErrorMessage: "SinqiaFinancialStatementService.createFromFinancialStatement >> Erro ao salvar SinqiaFinancialStatement para o FinancialStatement nr. [${id}]"])
        }
    }

    public void setAsDone(SinqiaFinancialStatement sinqiaFinancialStatement, String externalIdentifier) {
        if (!externalIdentifier) throw new RuntimeException("Identificador externo não pode ser nulo")

        sinqiaFinancialStatement.syncStatus = SinqiaSyncStatus.DONE
        sinqiaFinancialStatement.externalIdentifier = externalIdentifier
        sinqiaFinancialStatement.save(failOnError: true)
    }

    public void setAsErrorWithNewTransaction(Long sinqiaFinancialStatementId) {
        Utils.withNewTransactionAndRollbackOnError({
            SinqiaFinancialStatement sinqiaFinancialStatement = SinqiaFinancialStatement.get(sinqiaFinancialStatementId)
            sinqiaFinancialStatement.syncStatus = SinqiaSyncStatus.ERROR
            sinqiaFinancialStatement.save(failOnError: true)
        }, [logErrorMessage: "SinqiaFinancialStatementService.setAsErrorWithNewTransaction >> Falha ao marcar o registro [${sinqiaFinancialStatementId}] como erro de sincronia"])
    }

    private SinqiaFinancialStatement save(FinancialStatement financialStatement) {
        SinqiaFinancialStatement sinqiaFinancialStatement = new SinqiaFinancialStatement()
        sinqiaFinancialStatement.agencyId = getAgencyId(financialStatement.financialStatementType)
        sinqiaFinancialStatement.customerId = getCustomerId(financialStatement.financialStatementType)
        sinqiaFinancialStatement.description = financialStatement.description
        sinqiaFinancialStatement.financialStatement = financialStatement
        sinqiaFinancialStatement.institutionId = getInstitutionId(financialStatement.financialStatementType)

        sinqiaFinancialStatement.movementDate = financialStatement.statementDate
        sinqiaFinancialStatement.competenceDate = financialStatement.statementDate
        sinqiaFinancialStatement.provisionDate = financialStatement.provisionDate ?: financialStatement.statementDate

        sinqiaFinancialStatement.movementType = getMovementType(financialStatement.financialStatementType)
        sinqiaFinancialStatement.transactionTypeId = getTransactionTypeId(financialStatement.financialStatementType)
        sinqiaFinancialStatement.responsibilityCenterId = getResponsibilityCenterId(financialStatement.financialStatementType, sinqiaFinancialStatement.transactionTypeId)

        Map settlementBankAccountInfo = getSettlementBankAccountInfo(financialStatement)
        sinqiaFinancialStatement.settlementBankId = settlementBankAccountInfo.id
        sinqiaFinancialStatement.settlementAgencyId = settlementBankAccountInfo.agencyId
        sinqiaFinancialStatement.settlementBankAccountId = settlementBankAccountInfo.bankAccountId

        sinqiaFinancialStatement.value = financialStatement.value.abs()

        return sinqiaFinancialStatement.save(failOnError: true)
    }

    private Map getSettlementBankAccountInfo(FinancialStatement financialStatement) {
        if (financialStatement.financialStatementType.isBillExpense() && !financialStatement.bank) return grailsApplication.config.sinqia.api.settlementBankAccount.celcoin

        if (financialStatement.financialStatementType.isRefundBillPaymentExpense() && !financialStatement.bank) return grailsApplication.config.sinqia.api.settlementBankAccount.celcoin

        if (financialStatement.financialStatementType.isAsaasCardTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitoryElo

        if (financialStatement.financialStatementType.isChargebackTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitoryChargebackBlock

        if (financialStatement.financialStatementType.useSinqiaStandardTransitoryBankAccount()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitory

        if (financialStatement.financialStatementType.isExternalSettlementTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitoryExternalSettlement

        if (financialStatement.financialStatementType.isContractualEffectSettlementTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitoryExternalSettlement

        if (financialStatement.financialStatementType.isJudicialProcessBalanceBlockTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitoryJudicialProcessBalanceBlock

        if (financialStatement.financialStatementType.isScdReceivablesFinancingSettlement()) return grailsApplication.config.sinqia.api.settlementBankAccount.scdSettlementOperation

        if (financialStatement.financialStatementType.isScdTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.scdTransitory

        if (financialStatement.financialStatementType.isManualBalanceBlockTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitoryManualBalanceBlock

        if (financialStatement.financialStatementType.isCustomerRevenueTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitoryCustomerRevenue

        if (financialStatement.financialStatementType.useInternalTransferAccount()) return grailsApplication.config.sinqia.api.settlementBankAccount.internalTransfer

        if (financialStatement.financialStatementType.isCustomerLossProvision()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitoryLossProvision

        if (financialStatement.financialStatementType.isScdLossProvision()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitoryLossProvision

        if (financialStatement.financialStatementType.isBillPaymentTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitoryBillPayment

        if (financialStatement.financialStatementType.isBacenReservesTransferSystemOperation()) return grailsApplication.config.sinqia.api.settlementBankAccount.bacenReservesTransferSystem

        if (financialStatement.financialStatementType.isRefundRequestDebitTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitoryRefundRequestDebit

        if (financialStatement.financialStatementType.isRefundRequestFeeDebitTransitory() || financialStatement.financialStatementType.isTransferFeeTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitoryRefundRequestFeeDebit

        if (financialStatement.financialStatementType.isTransferTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitoryTransfer

        if (financialStatement.financialStatementType.isAdyenCreditCardToReceiveTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.adyenCreditCardToReceiveTransitory

        if (financialStatement.financialStatementType.isAdyenCreditCardToPayTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.adyenCreditCardToPayTransitory

        if (financialStatement.financialStatementType.isAdyenCreditCardFeeTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.adyenCreditCardFeeTransitory

        if (financialStatement.financialStatementType.isAdyenCreditCardTransactionFeeTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.adyenCreditCardTransactionFeeTransitory

        if (financialStatement.financialStatementType.isCieloCreditCardToReceiveTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.cieloCreditCardToReceiveTransitory

        if (financialStatement.financialStatementType.isCieloCreditCardToPayTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.cieloCreditCardToPayTransitory

        if (financialStatement.financialStatementType.isCieloCreditCardFeeTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.cieloCreditCardFeeTransitory

        if (financialStatement.financialStatementType.isCieloCreditCardTransactionFeeTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.cieloCreditCardTransactionFeeTransitory

        if (financialStatement.financialStatementType.isRedeCreditCardToReceiveTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.redeCreditCardToReceiveTransitory

        if (financialStatement.financialStatementType.isRedeCreditCardToPayTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.redeCreditCardToPayTransitory

        if (financialStatement.financialStatementType.isRedeCreditCardFeeTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.redeCreditCardFeeTransitory

        if (financialStatement.financialStatementType.isRedeCreditCardTransactionFeeTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.redeCreditCardTransactionFeeTransitory

        if (financialStatement.financialStatementType.isPixTransaction()) {
            if (financialStatement.financialStatementType.isPixCashInRiskTransitory()) return grailsApplication.config.sinqia.api.settlementBankAccount.transitoryPixCashInRisk

            if (financialStatement.financialStatementType.isPixDirect()) {
                return grailsApplication.config.sinqia.api.settlementBankAccount.pixDirect
            } else {
                return grailsApplication.config.sinqia.api.settlementBankAccount.pixIndirect
            }
        }

        if (financialStatement.financialStatementType.isMobilePhoneRechargeExpense()) return grailsApplication.config.sinqia.api.settlementBankAccount.celcoin

        if (financialStatement.financialStatementType.isRefundMobilePhoneRechargeExpense()) return grailsApplication.config.sinqia.api.settlementBankAccount.celcoin

        return grailsApplication.config.sinqia.api.settlementBankAccount."${financialStatement.bank.code}"
    }

    private SinqiaMovementType getMovementType(FinancialStatementType financialStatementType) {
        if (financialStatementType.isRevenue()) return SinqiaMovementType.RECEIPT

        return SinqiaMovementType.PAYMENT
    }

    private String getTransactionTypeId(FinancialStatementType financialStatementType) {
        SinqiaTransactionType transactionType = SinqiaTransactionType.convert(financialStatementType)

        return transactionType.getTransactionId()
    }

    private String getCustomerId(FinancialStatementType financialStatementType) {
        SinqiaTransactionType transactionType = SinqiaTransactionType.convert(financialStatementType)

        if (transactionType.isSerasaTransaction()) return SinqiaFinancialStatement.SERASA_CUSTOMER_ID

        if (transactionType == SinqiaTransactionType.IR_TAX_ASAAS_BALANCE_PROVISION_DEBIT) return SinqiaFinancialStatement.REVENUE_SERVICE_CUSTOMER_ID

        if (transactionType == SinqiaTransactionType.ISS_TAX_ASAAS_BALANCE_PROVISION_DEBIT) return SinqiaFinancialStatement.MUNICIPAL_SERVICE_CUSTOMER_ID

        if (financialStatementType.isAdyenStatement()) return SinqiaFinancialStatement.ADYEN_BALANCE_CUSTOMER_ID

        if (financialStatementType.isCieloStatement()) return SinqiaFinancialStatement.CIELO_BALANCE_CUSTOMER_ID

        if (financialStatementType.isRedeStatement()) return SinqiaFinancialStatement.REDE_BALANCE_CUSTOMER_ID

        if (financialStatementType.isAsaasBalance()) return SinqiaFinancialStatement.ASAAS_BALANCE_CUSTOMER_ID

        return SinqiaFinancialStatement.CUSTOMER_BALANCE_CUSTOMER_ID
    }

    private String getResponsibilityCenterId(FinancialStatementType financialStatementType, String transactionTypeId) {
        SinqiaResponsibilityCenter responsibilityCenter = SinqiaResponsibilityCenter.convert(financialStatementType)

        return responsibilityCenter.getResponsibilityCenterId(transactionTypeId)
    }

    private String getInstitutionId(FinancialStatementType financialStatementType) {
        if (financialStatementType.isScd()) return SinqiaFinancialStatement.SCD_INSTITUTION_ID

        return SinqiaFinancialStatement.ASAAS_INSTITUTION_ID
    }

    private String getAgencyId(FinancialStatementType financialStatementType) {
        if (financialStatementType.isScd()) return SinqiaFinancialStatement.SCD_AGENCY_ID

        return SinqiaFinancialStatement.ASAAS_AGENCY_ID
    }
}
