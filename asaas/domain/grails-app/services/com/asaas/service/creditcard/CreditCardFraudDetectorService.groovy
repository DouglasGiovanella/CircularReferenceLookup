package com.asaas.service.creditcard

import com.asaas.cardtransactionfraudanalysis.adapter.CardTransactionFraudAnalysisResponseAdapter
import com.asaas.creditcard.CreditCardTransactionFraudAnalysisResult
import com.asaas.creditcard.CreditCardTransactionInfoVo
import com.asaas.creditcard.CreditCardTransactionOriginInterface
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.holiday.Holiday
import com.asaas.domain.payment.ClearsaleResponse
import com.asaas.featureflag.FeatureFlagName
import com.asaas.integration.clearsale.ClearIDInputBuilder
import com.asaas.integration.clearsale.api.SubmitInfoManager
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import java.security.SecureRandom

@Transactional
class CreditCardFraudDetectorService {

    def asaasMoneyService
    def cardTransactionFraudAnalysisService
    def dataRudderManagerService
    def featureFlagCacheService

    public CreditCardTransactionFraudAnalysisResult doAnalysis(CreditCardTransactionInfoVo creditCardTransactionInfo, CreditCardTransactionOriginInterface creditCardTransactionOriginInterface) {
		CreditCardTransactionFraudAnalysisResult creditCardTransactionFraudAnalysisResult = doClearsaleAnalysis(creditCardTransactionInfo, creditCardTransactionOriginInterface)
        if (shouldDoDataRudderAnalysis(creditCardTransactionInfo.getCustomer())) doDataRudderAnalysis(creditCardTransactionInfo)

		if (creditCardTransactionFraudAnalysisResult.approved) return creditCardTransactionFraudAnalysisResult

		if (creditCardTransactionFraudAnalysisResult.error) creditCardTransactionFraudAnalysisResult.approved = true

		return creditCardTransactionFraudAnalysisResult
	}

    private CreditCardTransactionFraudAnalysisResult doClearsaleAnalysis(CreditCardTransactionInfoVo creditCardTransactionInfo, CreditCardTransactionOriginInterface creditCardTransactionOriginInterface) {
		CreditCardTransactionFraudAnalysisResult creditCardTransactionFraudAnalysisResult = new CreditCardTransactionFraudAnalysisResult()

		try {
			CreditCardTransactionFraudAnalysisResult existingCreditCardTransactionFraudAnalysisResult = getSimilarClearsaleResponse(creditCardTransactionInfo, creditCardTransactionOriginInterface)
            if (existingCreditCardTransactionFraudAnalysisResult) return existingCreditCardTransactionFraudAnalysisResult

			SubmitInfoManager apiManager = new SubmitInfoManager(ClearIDInputBuilder.execute(creditCardTransactionInfo))
			apiManager.execute()

			if (apiManager.success) {
                Long customerId = creditCardTransactionInfo.getCustomer().id
                Long paymentId = creditCardTransactionInfo.payment.id
                Long installmentId = creditCardTransactionInfo.installment?.id
                String transactionHash = creditCardTransactionInfo.buildClearSaleHash()
                BigDecimal transactionValue = creditCardTransactionInfo.getTransactionValue()

                Utils.withNewTransactionAndRollbackOnError ({
                    Customer customer = Customer.load(customerId)

					ClearsaleResponse clearsaleResponse = new ClearsaleResponse()
                    clearsaleResponse.installmentId = installmentId
                    clearsaleResponse.paymentId = paymentId
                    clearsaleResponse.transactionId = apiManager.transactionId
                    clearsaleResponse.statusCode = apiManager.statusCode
                    clearsaleResponse.pedidoId = apiManager.pedidoId
                    clearsaleResponse.pedidoScore = Double.valueOf(apiManager.pedidoScore)
                    clearsaleResponse.pedidoStatus = apiManager.pedidoStatus
                    clearsaleResponse.transactionHash = transactionHash
                    clearsaleResponse.transactionValue = transactionValue
                    clearsaleResponse.customer = customer
                    clearsaleResponse.approved = clearsaleResponse.isApproved()
					clearsaleResponse.save(flush: true, failOnError: true)

                    creditCardTransactionFraudAnalysisResult = buildFraudDetectorVo(clearsaleResponse, creditCardTransactionOriginInterface, creditCardTransactionInfo)
				}, [logErrorMessage: "CreditCardFraudDetectorService.doClearsaleAnalysis >>> Erro na gravação da resposta da ClearSale [Response: ${apiManager.apiResponse} | customerId: ${customerId} | paymentId: ${paymentId}]."] )
			} else {
				throw new Exception("Erro na requisição para Clearsale")
			}
		} catch (Exception exception) {
			creditCardTransactionFraudAnalysisResult.error = true
            AsaasLogger.error("Erro na validação da Clearsale. O erro foi tratado e a transação foi aprovada por outro serviço. Payment[${creditCardTransactionInfo.payment.id}] ${creditCardTransactionInfo.installment ? ' | Installment[' + creditCardTransactionInfo.installment?.id + ']' : ''}", exception)
		}

		return creditCardTransactionFraudAnalysisResult
	}

	private CreditCardTransactionFraudAnalysisResult getSimilarClearsaleResponse(CreditCardTransactionInfoVo creditCardTransactionInfoVo, CreditCardTransactionOriginInterface creditCardTransactionOriginInterface) {
		ClearsaleResponse existingClearsaleResponse = ClearsaleResponse.recent([transactionHash: creditCardTransactionInfoVo.buildClearSaleHash(), transactionValue: creditCardTransactionInfoVo.getTransactionValue()]).get()
		if (existingClearsaleResponse) {
			return buildFraudDetectorVo(existingClearsaleResponse, creditCardTransactionOriginInterface, creditCardTransactionInfoVo)
		}

		List<ClearsaleResponse> listOfClearsaleResponse = ClearsaleResponse.recent([transactionHash: creditCardTransactionInfoVo.buildClearSaleHash(), approved: false]).list()
		if (listOfClearsaleResponse.size() >= ClearsaleResponse.MAX_SIMILAR_TRANSACTIONS_WITH_DIFFERENT_VALUE) {
			return buildFraudDetectorVo(listOfClearsaleResponse[0], creditCardTransactionOriginInterface, creditCardTransactionInfoVo)
		}
	}

    private CreditCardTransactionFraudAnalysisResult applyAsaasMoneyRiskAnalysis(CreditCardTransactionFraudAnalysisResult creditCardTransactionFraudAnalysisResult, ClearsaleResponse clearSaleResponse, CreditCardTransactionOriginInterface transactionInterface, CreditCardTransactionInfoVo creditCardTransactionInfoVo) {
        creditCardTransactionFraudAnalysisResult.approved = false
        creditCardTransactionFraudAnalysisResult.riskAnalysisRequired = manualRiskAnalysisEnabled(transactionInterface)

        BigDecimal totalValue
        if (creditCardTransactionInfoVo.installment) {
            totalValue = creditCardTransactionInfoVo.installment.getRemainingValue()
        } else {
            totalValue = creditCardTransactionInfoVo.payment.value
        }

        BigDecimal maxValueToApprove = ClearsaleResponse.MAX_PAYMENT_VALUE_TO_AUTOMATICALLY_APPROVE_ASAAS_MONEY
        if (!creditCardTransactionFraudAnalysisResult.riskAnalysisRequired) maxValueToApprove = ClearsaleResponse.MAX_PAYMENT_VALUE_TO_AUTOMATICALLY_APPROVE_NIGHT_AND_HOLIDAY_ASAAS_MONEY

        if (clearSaleResponse.isApproved() && clearSaleResponse.pedidoScore <= ClearsaleResponse.MAX_SCORE_TO_AUTOMATICALLY_APPROVE_ASAAS_MONEY && totalValue <= maxValueToApprove) {
            creditCardTransactionFraudAnalysisResult.approved = true
            creditCardTransactionFraudAnalysisResult.riskAnalysisRequired = false
        }

        return creditCardTransactionFraudAnalysisResult
    }

    private CreditCardTransactionFraudAnalysisResult applyDefaultRiskAnalysis(CreditCardTransactionFraudAnalysisResult creditCardTransactionFraudAnalysisResult, ClearsaleResponse clearSaleResponse, CreditCardTransactionOriginInterface transactionInterface) {
        if (clearSaleResponse.isApproved()) {
            creditCardTransactionFraudAnalysisResult.approved = true
            return creditCardTransactionFraudAnalysisResult
        }

        if (manualRiskAnalysisEnabled(transactionInterface)) creditCardTransactionFraudAnalysisResult.riskAnalysisRequired = true

        return creditCardTransactionFraudAnalysisResult
    }

	private CreditCardTransactionFraudAnalysisResult buildFraudDetectorVo(ClearsaleResponse clearSaleResponse, CreditCardTransactionOriginInterface transactionInterface, CreditCardTransactionInfoVo creditCardTransactionInfoVo) {
		CreditCardTransactionFraudAnalysisResult creditCardTransactionFraudAnalysisResult = new CreditCardTransactionFraudAnalysisResult()
		creditCardTransactionFraudAnalysisResult.clearSaleScore = clearSaleResponse.pedidoScore
		creditCardTransactionFraudAnalysisResult.clearSaleStatus = clearSaleResponse.pedidoStatus
        creditCardTransactionFraudAnalysisResult.clearSaleResponseId = clearSaleResponse.id
        creditCardTransactionFraudAnalysisResult.riskAnalysisRequired = false
        creditCardTransactionFraudAnalysisResult.approved = false

        if (creditCardTransactionInfoVo.payment.provider.isAsaasMoneyProvider()) {
            return applyAsaasMoneyRiskAnalysis(creditCardTransactionFraudAnalysisResult, clearSaleResponse, transactionInterface, creditCardTransactionInfoVo)
        } else {
            return applyDefaultRiskAnalysis(creditCardTransactionFraudAnalysisResult, clearSaleResponse, transactionInterface)
        }
	}

    private Boolean manualRiskAnalysisEnabled(CreditCardTransactionOriginInterface transactionInterface) {
        Date timeStartLimitToCreateRiskAnalysis = CustomDateUtils.setTime(new Date(), 4, 0, 0)

        if (new Date() < timeStartLimitToCreateRiskAnalysis) return false

        Date timeEndLimitToCreateRiskAnalysis

        if (Holiday.isHoliday(new Date())) {
            timeEndLimitToCreateRiskAnalysis = CustomDateUtils.setTime(new Date(), 17, 50, 0)
        } else {
            timeEndLimitToCreateRiskAnalysis = CustomDateUtils.setTime(new Date(), 21, 50, 0)
        }

        if (new Date() > timeEndLimitToCreateRiskAnalysis) return false

        if (asaasMoneyService.isAsaasMoneyRequest()) return true

        return CreditCardTransactionOriginInterface.eligibleForCreditCardManualRiskAnalysis().contains(transactionInterface)
    }

    private void doDataRudderAnalysis(CreditCardTransactionInfoVo creditCardTransactionInfo) {
        try {
            CardTransactionFraudAnalysisResponseAdapter cardTransactionFraudAnalysisResponseAdapter = dataRudderManagerService.doTransactionFraudAnalysis(creditCardTransactionInfo)

            Utils.withNewTransactionAndRollbackOnError( {
                cardTransactionFraudAnalysisService.save(cardTransactionFraudAnalysisResponseAdapter)
            }, [logErrorMessage: "CreditCardFraudDetectorService.doDataRudderAnalysis >>> Erro na gravação da resposta da Data Rudder [paymentId: ${cardTransactionFraudAnalysisResponseAdapter.paymentId}]."])
        } catch (Exception exception) {
            AsaasLogger.error("CreditCardFraudDetectorService.doDataRudderAnalysis >>> Erro ao processar a analise com a Data Rudder.", exception)
        }
    }

    private Boolean shouldDoDataRudderAnalysis(Customer customer) {
        if (CustomerParameter.getValue(customer, CustomerParameterName.ENABLE_DATA_RUDDER_FRAUD_ANALYSIS_ON_CARD_TRANSACTION)) return true
        if (!featureFlagCacheService.isEnabled(FeatureFlagName.DATA_RUDDER_FRAUD_ANALYSIS)) return false

        Integer percentageTransactionsToAnalyze = 1
        return new SecureRandom().nextInt(100) < percentageTransactionsToAnalyze
    }
}
