package com.asaas.payment

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.asyncaction.AsyncActionType
import com.asaas.boleto.BoletoRegistrationStatus
import com.asaas.boleto.batchfile.BoletoAction
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.bankslip.BankSlipOnlineRegistrationResponse
import com.asaas.domain.bankslip.PaymentBankSlipInfo
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.bb.parsers.BankSlipParser
import com.asaas.integration.bradesco.parsers.BradescoBankSlipParser
import com.asaas.integration.jdnpc.utils.JdNpcUtils
import com.asaas.integration.santander.parsers.SantanderBankSlipParser
import com.asaas.log.AsaasLogger
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class BankSlipOnlineRegistrationService {

	def bancoDoBrasilBankSlipRegisterService
	def santanderBankSlipOnlineRegistrationService
    def bradescoBankSlipOnlineRegistrationService
    def asaasBankSlipRegistrationService
	def paymentUpdateService
    def boletoBatchFileItemService
    def asyncActionService

	public void executeOnlineRegistration(Payment payment, BoletoBatchFileItem boletoBatchFileItem, Map options) {
        if (payment.boletoBank.id == Payment.BB_BOLETO_BANK_ID) {
			executeBancoDoBrasilRegistration(payment, boletoBatchFileItem, options)
		} else if (payment.boletoBank.id == Payment.SANTANDER_ONLINE_BOLETO_BANK_ID) {
			executeSantanderRegistration(boletoBatchFileItem, options)
		} else if (payment.boletoBank.id == Payment.SMARTBANK_BOLETO_BANK_ID) {
            AsaasLogger.warn("BankSlipOnlineRegistrationService.executeOnlineRegistration >>> Tentativa de registro de boleto com BoletoBankId da SmartBank. [PaymentId: ${payment.id}]")
		} else if (payment.boletoBank.id == Payment.BRADESCO_ONLINE_BOLETO_BANK_ID) {
            executeBradescoRegistration(boletoBatchFileItem, options)
        } else if (payment.boletoBank.id == Payment.ASAAS_ONLINE_BOLETO_BANK_ID) {
            executeAsaasRegistration(boletoBatchFileItem, options)
        }
	}

    public void registerPendingBankSlips(Long boletoBankId) {
        if (!AsaasEnvironment.isProduction()) return

        Integer minItemsPerThread = 10
        Integer pendingItemsLimit = 160

        List<Long> pendingItemIds = boletoBatchFileItemService.listPendingValidCreateIdsForOnlineRegistration(boletoBankId, pendingItemsLimit, null)

        ThreadUtils.processWithThreadsOnDemand(pendingItemIds, minItemsPerThread, true,{ List<Long> items ->
            processPendingBankSlipsRegistration(items, boletoBankId)
        })
    }

    public void updatePendingBankSlips(Long boletoBankId) {
        if (!AsaasEnvironment.isProduction()) return

        BoletoBank boletoBank = BoletoBank.read(boletoBankId)

        final Integer itemsPerThread = 10
        final Integer limitOfThreads = 20
        final Integer pendingItemsLimit = 200

        List<Long> pendingItemIds = boletoBatchFileItemService.listPendingValidUpdateIdsForOnlineRegistration(boletoBank, pendingItemsLimit)

        ThreadUtils.dangerousProcessWithThreadsOnDemand(pendingItemIds, itemsPerThread, limitOfThreads, { List<Long> items ->
            processPendingBankSlipsRegistration(items, boletoBank.id)
        })
    }

    public void processBoletoBankMigrationQueue() {
        final Integer maximunNumberOfMigrations = 100
        List<AsyncAction> asyncActionList = AsyncAction.oldestPending([type: AsyncActionType.MIGRATE_PAYMENT_BOLETO_BANK]).list(max: maximunNumberOfMigrations)

        for (AsyncAction asyncAction in asyncActionList) {
            processBoletoBankMigration(asyncAction)
        }
    }

    public void processPendingBankSlipsRegistration(List<Long> pendingItemIds, Long boletoBankId) {
        for (Long pendingItemId in pendingItemIds) {
            Utils.withNewTransactionAndRollbackOnError({
                BoletoBatchFileItem boletoBatchFileItem = BoletoBatchFileItem.get(pendingItemId)

                if (boletoBatchFileItem.payment.deleted) {
                    boletoBatchFileItemService.removeItem(boletoBatchFileItem)
                    return
                }

                executeOnlineRegistration(boletoBatchFileItem.payment, boletoBatchFileItem, [asyncRegistration: true])
            }, [logErrorMessage: "Erro ao registrar boleto pelo webservice [boletoBankId: ${boletoBankId} | BoletoBatchFileItemId: ${pendingItemId}]",
                logLockAsWarning: true])
        }
    }

	private void executeBancoDoBrasilRegistration(Payment payment, BoletoBatchFileItem boletoBatchFileItem, Map options) {
        Boolean asyncRegistration = options.containsKey('asyncRegistration') ? options.asyncRegistration : false
		BankSlipOnlineRegistrationResponse onlineRegistrationResponse = bancoDoBrasilBankSlipRegisterService.processRegistration(boletoBatchFileItem, asyncRegistration)

		if (onlineRegistrationResponse.registrationStatus?.failed()) {

			if (BankSlipParser.isInvalidCpfCnpj(onlineRegistrationResponse.errorCode, onlineRegistrationResponse.returnCode)) {
                if (BankSlipParser.isInvalidCustomerCpfCnpj(onlineRegistrationResponse.errorCode, onlineRegistrationResponse.returnCode) && !payment.provider.boletoBank) {
                    final String customerBoletoBankInfoReason = "CPF/CNPJ do cliente inválido ao registrar boleto via BB."
                    AsaasApplicationHolder.grailsApplication.mainContext.customerService.changeToRegisteredBoleto(payment.provider.id, Payment.BRADESCO_ONLINE_BOLETO_BANK_ID, customerBoletoBankInfoReason, false)
                    AsaasLogger.info(customerBoletoBankInfoReason)
                }

                migrateToAsaasBoletoBankIfPossible(boletoBatchFileItem)
                return
			}

            Boolean keepPendingRegisterOnFailure = BankSlipParser.keepPendingRegisterOnFailure(onlineRegistrationResponse.errorCode, onlineRegistrationResponse.returnCode)
            if (keepPendingRegisterOnFailure) {
                AsaasLogger.error("BankSlipOnlineRegistrationService.executeBancoDoBrasilRegistration >>> Erro ao registrar boleto, registro pendente. [BoletoBatchFileItemId: ${boletoBatchFileItem.id}, ErrorCode: ${onlineRegistrationResponse.errorCode}, Message: ${onlineRegistrationResponse.errorMessage}, ReturnCode: ${onlineRegistrationResponse.returnCode}]")
                throw new BusinessException(Utils.getMessageProperty("bankSlip.register.failed"))
            } else {
                logFailedBankSlipRegister(boletoBatchFileItem, onlineRegistrationResponse)
            }
		}
	}

    private void executeSantanderRegistration(BoletoBatchFileItem boletoBatchFileItem, Map options) {
        Boolean asyncRegistration = options.containsKey('asyncRegistration') ? options.asyncRegistration : false
        BankSlipOnlineRegistrationResponse onlineRegistrationResponse = santanderBankSlipOnlineRegistrationService.processRegistration(boletoBatchFileItem, asyncRegistration)

        if (onlineRegistrationResponse.errorCode == "00489" && onlineRegistrationResponse.errorMessage.equals("CNPJ RAIZ DO PAGADOR NAO PODE SER IGUAL AO DO BENEFICIARIO")) {
            migrateToAsaasBoletoBankIfPossible(boletoBatchFileItem)
            return
        }

        if (onlineRegistrationResponse.registrationStatus?.failed()) {
            Boolean keepPendingRegisterOnFailure = SantanderBankSlipParser.keepPendingRegisterOnFailure(onlineRegistrationResponse.errorCode)
            if (keepPendingRegisterOnFailure) {
                AsaasLogger.error("BankSlipOnlineRegistrationService.executeSantanderRegistration >>> Erro ao registrar boleto, registro pendente. [BoletoBatchFileItemId: ${boletoBatchFileItem.id}, ErrorCode: ${onlineRegistrationResponse.errorCode}, Message: ${onlineRegistrationResponse.errorMessage}, ReturnCode: ${onlineRegistrationResponse.returnCode}]")
                throw new BusinessException(Utils.getMessageProperty("bankSlip.register.failed"))
            } else {
                logFailedBankSlipRegister(boletoBatchFileItem, onlineRegistrationResponse)
            }
        }
    }

    private void executeBradescoRegistration(BoletoBatchFileItem boletoBatchFileItem, Map options) {
        Boolean asyncRegistration = options.containsKey('asyncRegistration') ? options.asyncRegistration : false
        BankSlipOnlineRegistrationResponse onlineRegistrationResponse = bradescoBankSlipOnlineRegistrationService.processRegistration(boletoBatchFileItem, asyncRegistration)

        if (shouldMigrateBradescoToAsaasBoletoBank(onlineRegistrationResponse)) {
            migrateToAsaasBoletoBankIfPossible(boletoBatchFileItem)
            return
        }

        if (onlineRegistrationResponse.registrationStatus?.failed()) {
            if (onlineRegistrationResponse.errorCode == BradescoBankSlipParser.INVALID_POSTAL_CODE_ERROR_CODE && options.retryRegistration) {
                onlineRegistrationResponse = bradescoBankSlipOnlineRegistrationService.processRegistration(boletoBatchFileItem, asyncRegistration)
            }

            if (onlineRegistrationResponse.registrationStatus?.failed()) {
                Boolean keepPendingRegisterOnFailure = BradescoBankSlipParser.keepPendingRegisterOnFailure(onlineRegistrationResponse.errorCode, onlineRegistrationResponse.errorMessage)
                if (keepPendingRegisterOnFailure) {
                    AsaasLogger.error("BankSlipOnlineRegistrationService.executeBradescoRegistration >>> Erro ao registrar boleto, registro pendente. [BoletoBatchFileItemId: ${boletoBatchFileItem.id}, ErrorCode: ${onlineRegistrationResponse.errorCode}, Message: ${onlineRegistrationResponse.errorMessage}, ReturnCode: ${onlineRegistrationResponse.returnCode}]")
                    throw new BusinessException(Utils.getMessageProperty("bankSlip.register.failed"))
                } else {
                    logFailedBankSlipRegister(boletoBatchFileItem, onlineRegistrationResponse)
                }
            }
        }
    }

    private void executeAsaasRegistration(BoletoBatchFileItem boletoBatchFileItem, Map options) {
        if (JdNpcUtils.isUnavailableApiSchedule()) return

        Boolean asyncRegistration = options.containsKey('asyncRegistration') ? options.asyncRegistration : false
        BankSlipOnlineRegistrationResponse onlineRegistrationResponse = asaasBankSlipRegistrationService.doRegistration(boletoBatchFileItem, asyncRegistration)

        if (onlineRegistrationResponse.registrationStatus?.failed()) {
            logFailedBankSlipRegister(boletoBatchFileItem, onlineRegistrationResponse)
        }
    }

    private void migrateToAsaasBoletoBankIfPossible(BoletoBatchFileItem boletoBatchFileItem) {
        Payment payment = boletoBatchFileItem.payment
        if (payment.installment) {
            Installment installment = Installment.read(payment.installment.id)
            for (Payment installmentPayment in installment.payments) {
                if (!installmentPayment.isPaid()) {
                    saveAsaasBoletoBankMigrationAsyncAction(boletoBatchFileItem, installmentPayment.id)
                }
            }
        } else {
            if (!payment.isPaid()) {
                saveAsaasBoletoBankMigrationAsyncAction(boletoBatchFileItem, payment.id)
            }
        }
    }

    private void saveAsaasBoletoBankMigrationAsyncAction(BoletoBatchFileItem currentBoletoBatchFileItem, Long paymentId) {
        final Long boletoBankId = Payment.ASAAS_ONLINE_BOLETO_BANK_ID

        if (currentBoletoBatchFileItem.status.isPending()) boletoBatchFileItemService.setItemAsSent(currentBoletoBatchFileItem)

        Map asyncActionData = [paymentId: paymentId, toBoletoBankId: boletoBankId]
        AsyncActionType asyncActionType = AsyncActionType.MIGRATE_PAYMENT_BOLETO_BANK

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

        asyncActionService.save(asyncActionType, asyncActionData)
    }

    private void processBoletoBankMigration(AsyncAction asyncAction) {
        Boolean hasError = false
        Map asyncActionData = asyncAction.getDataAsMap()

        Utils.withNewTransactionAndRollbackOnError({
            Payment payment = Payment.get(asyncActionData.paymentId)

            if (payment.provider.boletoBank && payment.provider.boletoBank.id != asyncActionData.toBoletoBankId) {
                AsaasLogger.info("Migração de boleto cancelada : AsyncAction [${asyncAction.id}] - Cliente [${payment.provider.id}] possui banco fixo para emissão de boletos - Payment [${payment.id}]")
                asyncActionService.delete(asyncActionData.asyncActionId)
                return
            }

            if (payment.boletoBank.id == asyncActionData.toBoletoBankId || payment.deleted) {
                asyncActionService.delete(asyncActionData.asyncActionId)
                return
            }

            PaymentBankSlipInfo currentPaymentBankSlipInfo = PaymentBankSlipInfo.query([payment: payment]).get()
            if (currentPaymentBankSlipInfo) {
                AsaasLogger.info("Migração de boleto de negativação cancelada : AsyncAction [${asyncAction.id}] - Cliente [${payment.provider.id}] - Payment [${payment.id}]")
                asyncActionService.delete(asyncActionData.asyncActionId)
                payment.updateRegistrationStatus(BoletoRegistrationStatus.FAILED)
                return
            }

            BoletoBatchFileItem currentBoletoBatchFileItem = BoletoBatchFileItem.query([payment: payment, nossoNumero: payment.getCurrentBankSlipNossoNumero(), boletoBankId: payment.boletoBank.id, action: BoletoAction.CREATE]).get()
            BankSlipOnlineRegistrationResponse bankSlipOnlineRegistrationResponse = BankSlipOnlineRegistrationResponse.query([boletoBatchFileItemId: currentBoletoBatchFileItem.id]).get()
            if (bankSlipOnlineRegistrationResponse?.registrationStatus?.successful()) {
                asyncActionService.delete(asyncActionData.asyncActionId)
                return
            }

            BoletoBank toBoletoBank = BoletoBank.get(asyncActionData.toBoletoBankId)

            payment.boletoBank = toBoletoBank
            payment.save(flush: true)

            paymentUpdateService.updateNossoNumeroAndRegisterNewBoleto(payment)
            asyncActionService.delete(asyncActionData.asyncActionId)
        }, [logErrorMessage: "processBoletoBankMigration >> Falha ao processar BoletoBank asyncAction [${asyncActionData.asyncActionId}]", onError: { hasError = true }])

        if (hasError) asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
    }

    private void logFailedBankSlipRegister(BoletoBatchFileItem boletoBatchFileItem, BankSlipOnlineRegistrationResponse onlineRegistrationResponse) {
        Map logData = [:]
        logData.boletoBankId = boletoBatchFileItem.boletoBankId
        logData.boletoBatchFileItemId = boletoBatchFileItem.id
        logData.paymentId = boletoBatchFileItem.paymentId
        logData.errorCode = onlineRegistrationResponse.errorCode
        logData.errorCode = onlineRegistrationResponse.errorMessage
        logData.errorCode = onlineRegistrationResponse.returnCode
        logData.registrationStatus = onlineRegistrationResponse.registrationStatus

        AsaasLogger.warn("BankSlipOnlineRegistrationService.logFailedBankSlipRegister >> Registro pendente por falha. Item: ${logData}")
    }

    private Boolean shouldMigrateBradescoToAsaasBoletoBank(BankSlipOnlineRegistrationResponse onlineRegistrationResponse) {
        if (onlineRegistrationResponse.errorCode.equals("6") && onlineRegistrationResponse.errorMessage.equals("ERRO DE CONSISTENCIA:   SERVICO INDISPONIVEL TABELA NOVA (6)")) {
            return true
        }

        if (onlineRegistrationResponse.errorCode.equals("19") && onlineRegistrationResponse.errorMessage.equals("ERRO DE CONSISTENCIA:   CEP INVALIDO (19)")) {
            return true
        }

        if (onlineRegistrationResponse.errorCode.equals("2") && onlineRegistrationResponse.errorMessage.equals("ERRO DE CONSISTENCIA:   SERVICO TEMPORARIAMENTE NAO DISPONIVEL - 0839 (2)")) {
            return true
        }

        if (onlineRegistrationResponse.errorCode.equals("40") && onlineRegistrationResponse.errorMessage.equals("ERRO DE CONSISTENCIA:   CNPJ/CPF INVALIDO (40)")) {
            return true
        }

        if (onlineRegistrationResponse.errorCode.equals("6") && onlineRegistrationResponse.errorMessage.equals("ERRO DE CONSISTENCIA:   CPF/CNPJ DO PAGADOR DEVE SER DIFERENTE DO BENEFICIARIO (6)")) {
            return true
        }

        return false
    }
}
