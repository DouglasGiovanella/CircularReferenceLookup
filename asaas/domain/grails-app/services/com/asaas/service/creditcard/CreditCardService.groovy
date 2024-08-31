package com.asaas.service.creditcard

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.billinginfo.BillingType
import com.asaas.creditcard.CapturedCreditCardTransactionVo
import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardAcquirerRefuseReason
import com.asaas.creditcard.CreditCardBrand
import com.asaas.creditcard.CreditCardGateway
import com.asaas.creditcard.CreditCardPreventAbuseVO
import com.asaas.creditcard.CreditCardTransactionAttemptType
import com.asaas.creditcard.CreditCardTransactionDeviceInfoVO
import com.asaas.creditcard.CreditCardTransactionFraudAnalysisResult
import com.asaas.creditcard.CreditCardTransactionInfoVo
import com.asaas.creditcard.CreditCardTransactionOriginInterface
import com.asaas.creditcard.CreditCardValidator
import com.asaas.creditcard.adapter.CreditCardAuthorizationInfoAdapter
import com.asaas.creditcard.adapter.CreditCardThreeDSecureCaptureAdapter
import com.asaas.creditcard.HolderInfo
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.asaasconfig.AsaasConfig
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.creditcard.CreditCardTransactionOrigin
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerFeature
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.holiday.Holiday
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.CreditCardAuthorizationInfo
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentGatewayInfo
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.exception.UnsupportedCreditCardBrandException
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.MoneyUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import java.security.SecureRandom

@Transactional
class CreditCardService {

	def adyenCreditCardService
    def asaasMoneyService
    def asaasMoneyTransactionInfoService
	def asaasSegmentioService
	def billingInfoService
    def cardAbuseRiskAlertService
    def creditCardAcquirerFeeService
	def creditCardAuthorizationService
    def creditCardAuthorizationInfoService
	def creditCardFraudDetectorService
	def mundiPaggRESTService
    def notificationDispatcherPaymentNotificationOutboxService
	def paymentCreditCardService
    def paymentDiscountConfigService
    def paymentAnticipableInfoService
	def paymentInterestConfigService
    def cieloCreditCardService
    def redeCreditCardService
    def acquiringCreditCardService
    def cyberSourceCreditCardService
    def creditCardPreventAbuseService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def kremlinCreditCardService
    def creditCardTransactionAttemptService
    def grailsApplication

	public Map captureWithBillingInfo(Payment payment, String billingInfoPublicId, Map creditCardTransactionOriginInfo, String clearSaleSessionId) {
		if (!CustomerFeature.canHandleBillingInfoEnabled(payment.provider.id) && !asaasMoneyService.isAsaasMoneyRequest()) {
            AsaasLogger.error("Unauthorized customer [${payment.provider.id}] attempting to handle BillingInfo [${billingInfoPublicId}].")
			return [success: false]
		}

		BillingInfo billingInfo = billingInfoService.findByPublicId(payment.customerAccount, billingInfoPublicId)
		if (!billingInfo) return [success: false]

        return captureWithBillingInfo(payment, billingInfo, creditCardTransactionOriginInfo, clearSaleSessionId)
    }

    public Map captureWithBillingInfo(Payment payment, BillingInfo billingInfo, Map creditCardTransactionOriginInfo, String clearSaleSessionId) {
        return processTransaction(payment, null, billingInfo, creditCardTransactionOriginInfo, clearSaleSessionId, false, false, null)
    }

    public Map captureWithCreditCardInfo(Payment payment, CreditCardTransactionInfoVo creditCardTransactionInfo, Map creditCardTransactionOriginInfo) {
        validadeCreditCard(creditCardTransactionInfo)

        return processTransaction(payment, creditCardTransactionInfo, null, creditCardTransactionOriginInfo, null, false, false, null)
    }

    public Map authorize(Payment payment, BillingInfo billingInfo, Map creditCardTransactionOriginInfo, CreditCardTransactionInfoVo creditCardTransactionInfo) {
        return authorize(payment, billingInfo, creditCardTransactionOriginInfo, creditCardTransactionInfo, false, null)
    }

    public Map authorize(Payment payment, BillingInfo billingInfo, Map creditCardTransactionOriginInfo, CreditCardTransactionInfoVo creditCardTransactionInfo, Boolean withThreeDSecure, CreditCardTransactionDeviceInfoVO deviceInfo) {
        if (withThreeDSecure && !CustomerParameter.getValue(payment.provider, CustomerParameterName.CREDIT_CARD_TRANSACTION_WITH_THREE_D_SECURE_ENABLED)) throw new RuntimeException("Transação com 3DS não habilitada.")

        if (creditCardTransactionInfo) validadeCreditCard(creditCardTransactionInfo)

        return processTransaction(payment, creditCardTransactionInfo, billingInfo, creditCardTransactionOriginInfo, null, true, withThreeDSecure, deviceInfo)
    }

    public Map captureThreeDSecureAuthorization(Payment payment, CreditCardThreeDSecureCaptureAdapter creditCardThreeDSecureCaptureAdapter) {
        Map captureResponse = [success: false]

        if (!creditCardThreeDSecureCaptureAdapter.success) return captureResponse

        payment.lock()

        try {
            CreditCardAuthorizationInfo creditCardAuthorizationInfo = CreditCardAuthorizationInfo.fromPayment(payment, [transactionReference: creditCardThreeDSecureCaptureAdapter.transactionReference]).get()

            if (!creditCardAuthorizationInfo) {
                AsaasLogger.error("CreditCardService.captureThreeDSecureAuthorization >> Não foi possível encontrar a autorização de pagamento. [paymentId: ${payment.id} | transactionReference: ${creditCardThreeDSecureCaptureAdapter.transactionReference}]")

                return captureResponse
            }

            if (!CustomerParameter.getValue(payment.provider, CustomerParameterName.CREDIT_CARD_TRANSACTION_WITH_THREE_D_SECURE_ENABLED)) throw new RuntimeException("Transação com 3DS não habilitada.")

            CreditCardAuthorizationInfoAdapter cardAuthorizationInfoAdapter = new CreditCardAuthorizationInfoAdapter()
            if (!creditCardAuthorizationInfo.transactionIdentifier) cardAuthorizationInfoAdapter.transactionIdentifier = creditCardThreeDSecureCaptureAdapter.transactionIdentifier
            if (!creditCardAuthorizationInfo.authorizationCode) cardAuthorizationInfoAdapter.authorizationCode = creditCardThreeDSecureCaptureAdapter.authorizationCode

            if (cardAuthorizationInfoAdapter.transactionIdentifier || cardAuthorizationInfoAdapter.authorizationCode) creditCardAuthorizationInfoService.update(creditCardAuthorizationInfo, cardAuthorizationInfoAdapter)

            captureResponse = creditCardAuthorizationService.captureAuthorizedPayment(creditCardAuthorizationInfo, true)
            captureResponse.creditCardAuthorizationInfo = creditCardAuthorizationInfo

            return processPaymentCaptureResult(captureResponse, payment, null, null, null, false, null, null, true)
        } catch (BusinessException businessException) {
            throw businessException
        } catch (Exception exception) {
            AsaasLogger.error("CreditCardService.capture3D >> Erro ao realizar captura de cartão com 3DS. [paymentId: ${payment.id}]", exception)
            throw exception
        }
    }

    public Map captureAuthorizedPayment(Payment payment, Map creditCardTransactionOriginInfo) {
        payment.lock()

        try {
            CreditCardAuthorizationInfo creditCardAuthorizationInfo = CreditCardAuthorizationInfo.fromPayment(payment, [:]).get()

            Map captureResponse = creditCardAuthorizationService.captureAuthorizedPayment(creditCardAuthorizationInfo, false)

            return processPaymentCaptureResult(captureResponse, payment, payment.billingInfo, null, creditCardTransactionOriginInfo, false, null, null, false)
        } catch (BusinessException businessException) {
            throw businessException
        } catch (Exception exception) {
            AsaasLogger.error("CreditCardService.captureAuthorizedPayment >> Erro ao realizar captura de cartão com Pre-Auth. [paymentId: ${payment.id}]", exception)
            throw exception
        }
    }

    public Integer buildDaysToReceiveCreditCard(Customer customer) {
        Integer daysToCredit = Utils.toInteger(CustomerParameter.getNumericValue(customer, CustomerParameterName.CUSTOM_CREDIT_CARD_SETTLEMENT_DAYS))
        if (daysToCredit) {
            return daysToCredit
        }

        return grailsApplication.config.payment.creditCard.daysToCredit
    }

    private validadeCreditCard(CreditCardTransactionInfoVo creditCardTransactionInfo) {
        CreditCardValidator creditCardValidator = new CreditCardValidator()

        if (!creditCardValidator.validate(creditCardTransactionInfo.creditCard)) {
            throw new BusinessException(creditCardValidator.errors[0].getMessage())
        }

        if (!creditCardTransactionInfo.bypassClearSale && !creditCardValidator.validateHolderInfo(creditCardTransactionInfo?.holderInfo)) {
            throw new BusinessException(creditCardValidator.errors[0].getMessage())
        }
    }

	private CreditCardTransactionOrigin saveCreditCardTransactionOrigin(Payment payment, Map info) {
		if (!info) return

		try {
            CreditCardTransactionOrigin creditCardTransactionOrigin

            if (payment.installment) {
                creditCardTransactionOrigin = CreditCardTransactionOrigin.query([installment: payment.installment]).get()
			} else {
                creditCardTransactionOrigin = CreditCardTransactionOrigin.query([payment: payment]).get()
			}

            if (!creditCardTransactionOrigin) creditCardTransactionOrigin = new CreditCardTransactionOrigin()

			creditCardTransactionOrigin.originInterface = info.originInterface
			creditCardTransactionOrigin.geolocationInfo = info.geolocationInfo
			creditCardTransactionOrigin.device = info.device
			creditCardTransactionOrigin.remoteIp = info.remoteIp
            creditCardTransactionOrigin.payerRemoteIp = info.payerRemoteIp
			creditCardTransactionOrigin.platform = info.platform

			if (payment.installment) {
				creditCardTransactionOrigin.installment = payment.installment
			} else {
				creditCardTransactionOrigin.payment = payment
			}

			return creditCardTransactionOrigin.save(failOnError: true)
		} catch (Exception exception) {
            AsaasLogger.error("CreditCardService.saveCreditCardTransactionOrigin >> Erro ao salvar origem da transação de cartão de crédito. [paymentId: ${payment?.id}]", exception)
		}
	}

    private Boolean validateBillingInfo(CustomerAccount customerAccount, BillingInfo billingInfo) {
        if (customerAccount != billingInfo.customerAccount) {
            AsaasLogger.warn("BillingInfo [${billingInfo.id}] does not belong to customerAccount [${customerAccount.id}]")
            return false
        }

        if (billingInfo.creditCardInfo.isExpired()) return false

        return true
    }

    private Map processTransaction(Payment payment, CreditCardTransactionInfoVo creditCardTransactionInfo, BillingInfo billingInfo, Map creditCardTransactionOriginInfo, String clearSaleSessionId, Boolean authorizeOnly, Boolean withThreeDSecure, CreditCardTransactionDeviceInfoVO deviceInfo) {
        payment.lock()

        try {
            Map captureResponse = [success: false]

            if (payment.provider.isCreditCardTransactionBlocked()) {
                saveAttemptForCustomerBlockedToNewCreditCardTransactions(payment, creditCardTransactionInfo, billingInfo, creditCardTransactionOriginInfo)
                return captureResponse
            }

			if (billingInfo && !validateBillingInfo(payment.customerAccount, billingInfo)) return captureResponse
            if (!payment.canConfirm()) throw new BusinessException("Cobrança já confirmada.")
            if (payment.deleted) throw new BusinessException("A cobrança está cancelada.")
            if (payment.status.isAuthorized()) throw new UnsupportedOperationException("Captura de cobrança autorizada não suportada.")
            if (payment.hasDunningInProcess()) throw new BusinessException("A cobrança está em processo de negativação e pode ser paga apenas através do boleto.")
            if (payment.provider.boletoIsDisabled()) {
                AsaasLogger.warn("CreditCardService.processTransaction >> Tentativa de pagamento com cartao de credito para cliente com recebimento desabilitado. CustomerId: ${payment.provider.id}")
                throw new BusinessException("Esta cobrança não pode ser paga devido a uma pendência na conta do cliente")
            }

            if (!payment.installment) {
				paymentDiscountConfigService.applyPaymentDiscountIfNecessary(payment)
        		if (!billingInfo) paymentInterestConfigService.applyPaymentInterestIfNecessary(payment)
			}

            CreditCardTransactionFraudAnalysisResult fraudAnalysisResult
            CreditCardTransactionAttemptType creditCardTransactionAttemptType
            CreditCardPreventAbuseVO creditCardPreventAbuseVO
            HolderInfo holderInfo

			if (creditCardTransactionInfo?.creditCard) {
                holderInfo = creditCardTransactionInfo.holderInfo
                creditCardTransactionAttemptType = CreditCardTransactionAttemptType.CREDIT_CARD
                validateCreditCardSimulateNumberError(creditCardTransactionInfo.creditCard)

                creditCardTransactionInfo.payment = payment
                creditCardTransactionInfo.installment = payment.installment

                creditCardPreventAbuseVO = setTransactionAsBlockedIfNecessary(creditCardTransactionInfo.getCustomer(), creditCardTransactionInfo.getCustomerAccount(), creditCardTransactionInfo.creditCard, null, creditCardTransactionAttemptType, holderInfo, creditCardTransactionInfo.remoteIp, creditCardTransactionInfo.payerRemoteIp, creditCardTransactionOriginInfo?.originInterface, payment.value)

                if (!creditCardPreventAbuseVO.blockCreditCardTransaction) {
                    if (authorizeOnly) {
                        captureResponse = creditCardAuthorizationService.authorize(creditCardTransactionInfo.installment, creditCardTransactionInfo.payment, creditCardTransactionInfo.creditCard, null, holderInfo, creditCardTransactionOriginInfo?.originInterface, deviceInfo, withThreeDSecure)
                    } else {
                        captureResponse = authorizeAndCaptureTransaction(creditCardTransactionInfo, creditCardTransactionOriginInfo?.originInterface, null)
                        fraudAnalysisResult = captureResponse.fraudAnalysisResult
                    }
                } else {
                    delayResponseToObfuscateAutomaticBlock()
                }

                asaasSegmentioService.track(creditCardTransactionInfo.payment.provider.id, "Service :: Cartão de Crédito :: ${captureResponse.success ? 'Transação autorizada' : 'Transação negada'}", buildTransactionTrackingMap(creditCardTransactionInfo, fraudAnalysisResult, authorizeOnly))
			} else {
                holderInfo = billingInfo.creditCardHolderInfo ? HolderInfo.buildFromCreditCardHolderInfo(billingInfo.creditCardHolderInfo) : null
                creditCardTransactionAttemptType = CreditCardTransactionAttemptType.TOKENIZED_CREDIT_CARD

                creditCardPreventAbuseVO = setTransactionAsBlockedIfNecessary(payment.provider, payment.customerAccount, null, billingInfo, creditCardTransactionAttemptType, holderInfo, creditCardTransactionOriginInfo?.remoteIp, creditCardTransactionOriginInfo?.payerRemoteIp, creditCardTransactionOriginInfo?.originInterface, payment.value)

                if (!creditCardPreventAbuseVO.blockCreditCardTransaction) {
                    if (payment.provider.isAsaasMoneyProvider() || CustomerParameter.getValue(payment.provider, CustomerParameterName.FORCE_TOKENIZED_CREDIT_CARD_ANALYSIS)) {
                        captureResponse = authorizeAndCaptureTokenizedCardWithAnalisys(billingInfo, payment, creditCardTransactionOriginInfo, clearSaleSessionId)
                        fraudAnalysisResult = captureResponse.fraudAnalysisResult
                    } else {
                        if (authorizeOnly) {
                            captureResponse = creditCardAuthorizationService.authorize(payment.installment, payment, null, billingInfo, holderInfo, creditCardTransactionOriginInfo?.originInterface, deviceInfo, withThreeDSecure)
                        } else {
                            captureResponse = creditCardAuthorizationService.capture(payment.installment, payment, null, billingInfo, holderInfo, creditCardTransactionOriginInfo?.originInterface, true, false)
                        }
                    }
                } else {
                    delayResponseToObfuscateAutomaticBlock()
                }

                asaasSegmentioService.track(payment.provider.id, "Service :: Cartão de Crédito :: ${captureResponse.success ? 'Transação autorizada' : 'Transação negada'}", [paymentId: payment.id, valor: payment.value, tipo: payment.subscriptionPayments ? "Assinatura" : "Avulso", preAuth: authorizeOnly])
			}

            if (!creditCardTransactionAttemptType.isTokenizedCreditCard() || !AsaasEnvironment.isJobServer()) {
                saveCreditCardTransactionAttempt(payment.provider, payment.customerAccount, creditCardTransactionInfo?.creditCard, billingInfo, creditCardPreventAbuseVO, creditCardTransactionAttemptType, captureResponse.success, payment.id, payment.value, creditCardTransactionOriginInfo, fraudAnalysisResult?.clearSaleResponseId, holderInfo, captureResponse.creditCardTransactionLogIdList)
            }

            return processPaymentCaptureResult(captureResponse, payment, billingInfo, creditCardTransactionInfo, creditCardTransactionOriginInfo, authorizeOnly, fraudAnalysisResult, creditCardPreventAbuseVO, withThreeDSecure)
		} catch (BusinessException be) {
            throw be
        } catch (UnsupportedCreditCardBrandException unsupportedCreditCardBrandException) {
            AsaasLogger.warn("CreditCardService.processTransaction >>> Bandeira de cartão de crédito não suportada [paymentId: ${payment.id}]", unsupportedCreditCardBrandException)
            throw unsupportedCreditCardBrandException
        } catch (Exception exception) {
            AsaasLogger.error("CreditCardService.processTransaction >>> Erro ao processar transação de cartão de crédito [paymentId: ${payment.id}]", exception)
            throw exception
		}
	}

    private Map authorizeAndCaptureTokenizedCardWithAnalisys(BillingInfo billingInfo, Payment payment, Map creditCardTransactionOriginInfo, String clearSaleSessionId) {
        CreditCardTransactionInfoVo creditCardTransactionInfoVo = new CreditCardTransactionInfoVo()
        creditCardTransactionInfoVo.payment = payment
        creditCardTransactionInfoVo.installment = payment.installment
        creditCardTransactionInfoVo.creditCard = CreditCard.buildFromCreditCardInfo(billingInfo.creditCardInfo)
        creditCardTransactionInfoVo.holderInfo = HolderInfo.buildFromCreditCardHolderInfo(billingInfo.creditCardHolderInfo)
        creditCardTransactionInfoVo.bypassClearSale = false
        creditCardTransactionInfoVo.remoteIp = creditCardTransactionOriginInfo?.remoteIp
        creditCardTransactionInfoVo.payerRemoteIp = creditCardTransactionOriginInfo?.payerRemoteIp
        creditCardTransactionInfoVo.clearSaleSessionId = clearSaleSessionId

        return authorizeAndCaptureTransaction(creditCardTransactionInfoVo, creditCardTransactionOriginInfo?.originInterface, billingInfo)
    }

    private Map authorizeAndCaptureTransaction(CreditCardTransactionInfoVo creditCardTransactionInfo, CreditCardTransactionOriginInterface creditCardTransactionOriginInterface, BillingInfo billingInfo) {
        CreditCardTransactionFraudAnalysisResult creditCardTransactionFraudAnalysisResult

        if (!(AsaasConfig.getInstance().clearSaleDisabled || creditCardTransactionInfo.bypassClearSale || creditCardTransactionInfo.isClearsaleDisabled() || creditCardTransactionInfo.payment.creditCardRiskValidationNotRequired())) {
            creditCardTransactionFraudAnalysisResult = creditCardFraudDetectorService.doAnalysis(creditCardTransactionInfo, creditCardTransactionOriginInterface)
        }

        if (creditCardTransactionFraudAnalysisResult && !creditCardTransactionFraudAnalysisResult.approved && !creditCardTransactionFraudAnalysisResult.riskAnalysisRequired) {
            return [success: false, fraudAnalysisResult: creditCardTransactionFraudAnalysisResult]
        }

        if (creditCardTransactionInfo.payment.provider.isAsaasMoneyProvider() && asaasMoneyTransactionInfoService.shouldSendToManualAnalysis(creditCardTransactionInfo.payment.provider)) {
            creditCardTransactionFraudAnalysisResult = creditCardTransactionFraudAnalysisResult ?: new CreditCardTransactionFraudAnalysisResult()
            creditCardTransactionFraudAnalysisResult.riskAnalysisRequired = true
        }

        Map captureResponse
        if (billingInfo) {
            captureResponse = creditCardAuthorizationService.capture(creditCardTransactionInfo.installment, creditCardTransactionInfo.payment, null, billingInfo, creditCardTransactionInfo.holderInfo, creditCardTransactionOriginInterface, true, false)
        } else {
            captureResponse = creditCardAuthorizationService.capture(creditCardTransactionInfo.installment, creditCardTransactionInfo.payment, creditCardTransactionInfo.creditCard, null, creditCardTransactionInfo.holderInfo, creditCardTransactionOriginInterface, true, false)
        }

        return captureResponse + [fraudAnalysisResult: creditCardTransactionFraudAnalysisResult]
    }

    private CapturedCreditCardTransactionVo buildTransactionVo(Payment payment, Map captureResponse, BillingInfo billingInfo) {
        CapturedCreditCardTransactionVo capturedCreditCardTransactionVo = new CapturedCreditCardTransactionVo()
        capturedCreditCardTransactionVo.payment = payment
        capturedCreditCardTransactionVo.transactionIdentifier = captureResponse.transactionIdentifier

        if (captureResponse.fraudAnalysisResult) capturedCreditCardTransactionVo.riskAnalysisRequired = captureResponse.fraudAnalysisResult.riskAnalysisRequired

        if (captureResponse.uniqueSequentialNumber) capturedCreditCardTransactionVo.uniqueSequentialNumber = Utils.toLong(captureResponse.uniqueSequentialNumber)

        capturedCreditCardTransactionVo.acquirer = CreditCardAcquirer.parse(captureResponse.acquirer)
        capturedCreditCardTransactionVo.gateway = captureResponse.gateway
        capturedCreditCardTransactionVo.mcc = payment.provider.getMcc() ?: AsaasApplicationHolder.config.asaas.defaultMcc

        if (billingInfo) {
            Integer installmentCount = payment.installment ? payment.installment.getRemainingPaymentsCount() : 1
            capturedCreditCardTransactionVo.acquirerFee = creditCardAcquirerFeeService.findAcquirerFee(capturedCreditCardTransactionVo.acquirer,
                                                                                                       billingInfo.creditCardInfo.brand,
                                                                                                       capturedCreditCardTransactionVo.mcc,
                                                                                                       installmentCount)
            capturedCreditCardTransactionVo.brand = billingInfo.creditCardInfo.brand
        }

        return capturedCreditCardTransactionVo
	}

    private void saveCreditCardTransactionAttempt(Customer customer, CustomerAccount customerAccount, CreditCard creditCard, BillingInfo billingInfo, CreditCardPreventAbuseVO creditCardPreventAbuseVO, CreditCardTransactionAttemptType creditCardTransactionAttemptType, Boolean authorized, Long paymentId, BigDecimal value, Map creditCardTransactionOriginInfo, Long clearSaleResponseId, HolderInfo holderInfo, List<Long> creditCardTransactionLogIdList) {
        Thread.start {
            Utils.withNewTransactionAndRollbackOnError( {
                Map params = [:]
                params.paymentId = paymentId
                params.clearSaleResponseId = clearSaleResponseId
                params.blocked = creditCardPreventAbuseVO.blockCreditCardTransaction
                params.blockReason = creditCardPreventAbuseVO.blockReason
                params.criticalBlock = creditCardPreventAbuseVO.criticalBlock

                if (creditCardTransactionOriginInfo) {
                    params.origin = creditCardTransactionOriginInfo.originInterface
                    params.remoteIp = creditCardTransactionOriginInfo.remoteIp
                    params.payerRemoteIp = creditCardTransactionOriginInfo.payerRemoteIp
                    params.platform = creditCardTransactionOriginInfo.platform
                }

                String creditCardHash
                if (creditCard) {
                    creditCardHash = creditCard.buildHash()
                } else {
                    creditCardHash = CreditCard.buildObfuscatedNumberHash(billingInfo.creditCardInfo.bin, billingInfo.creditCardInfo.lastDigits)
                }

                if (holderInfo) {
                    params.holderCpfCnpj = holderInfo.cpfCnpj
                    params.holderEmail = holderInfo.email
                    params.holderName = holderInfo.name
                }

                params.creditCardTransactionLogIdList = creditCardTransactionLogIdList

                creditCardTransactionAttemptService.save(customer, customerAccount, creditCardTransactionAttemptType, value, authorized, creditCardHash, creditCardPreventAbuseVO.creditCardFullInfoHash, params)
            }, [logErrorMessage: "CreditCardService.saveCreditCardTransactionAttempt >>> Erro ao salvar a transação."] )
        }
    }

	private Map buildTransactionTrackingMap(CreditCardTransactionInfoVo creditCardTransactionInfo, CreditCardTransactionFraudAnalysisResult fraudAnalysisResult, Boolean authorizeOnly) {
		return [paymentId: creditCardTransactionInfo.payment.id,
			    valor: creditCardTransactionInfo.payment.value,
				bandeira: creditCardTransactionInfo.creditCard.brand,
				tipo: creditCardTransactionInfo.payment.subscriptionPayments ? 'Assinatura' : 'Avulso',
				clearSaleScore: fraudAnalysisResult?.clearSaleScore,
				clearSaleStatus: fraudAnalysisResult?.clearSaleStatus,
                preAuth: authorizeOnly
			   ]
	}

    private Map processPaymentCaptureResult(Map captureResponse, Payment payment, BillingInfo billingInfo, CreditCardTransactionInfoVo creditCardTransactionInfo, Map creditCardTransactionOriginInfo, Boolean authorizeOnly, CreditCardTransactionFraudAnalysisResult fraudAnalysisResult, CreditCardPreventAbuseVO creditCardPreventAbuseVO, Boolean withThreeDSecure) {
        if (!captureResponse.success) {
            payment.discard()
            payment.resetValues()
            payment.save(failOnError: true)

            AsaasLogger.info("CreditCardService.processPaymentCaptureResult >> Failure while attempting to capture payment >> ${payment.id}")

            if (CustomerParameter.getValue(payment.provider, CustomerParameterName.ALLOW_CREDIT_CARD_ACQUIRER_REFUSE_REASON) && creditCardPreventAbuseVO?.blockCreditCardTransaction) {
                captureResponse.customerErrorMessage = creditCardPreventAbuseVO.customerErrorMessage ?: "Transação não autorizada. Aguarde para tentar novamente."
            }

            if (CreditCardAcquirerRefuseReason.isNotEnoughBalance(captureResponse.acquirerReturnCode, captureResponse.message)) {
                captureResponse.payerErrorMessage = CreditCardAcquirerRefuseReason.getCreditCardAcquirerRefuseReason(captureResponse.acquirerReturnCode, captureResponse.message)
            }

            if (CustomerParameter.getValue(payment.provider, CustomerParameterName.ENABLE_WEBHOOK_EVENT_FOR_CREDIT_CARD_ACQUIRER_REFUSE_REASON )) {
                String refuseReason

                if (creditCardPreventAbuseVO?.blockCreditCardTransaction) {
                    refuseReason = creditCardPreventAbuseVO.customerErrorMessage ?: "Transação não autorizada. Aguarde para tentar novamente."
                } else {
                    refuseReason = CreditCardAcquirerRefuseReason.getCreditCardAcquirerRefuseReason(captureResponse.acquirerReturnCode, captureResponse.message)
                }

                paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_CREDIT_CARD_CAPTURE_REFUSED, [refuseReason: refuseReason])
            } else {
                paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_CREDIT_CARD_CAPTURE_REFUSED)
            }

            return captureResponse
        }

        if (creditCardTransactionOriginInfo) {
            saveCreditCardTransactionOrigin(payment, creditCardTransactionOriginInfo)
        }

        BillingInfo billingInfoUsedToCapture

        if (withThreeDSecure) {
            if (authorizeOnly) {
                billingInfoUsedToCapture = saveBillingInfoFromTransactionResponseIfNecessary(billingInfo, payment.customerAccount, creditCardTransactionInfo.creditCard, creditCardTransactionInfo.holderInfo, captureResponse)

                if (billingInfoUsedToCapture) {
                    CreditCardAuthorizationInfoAdapter cardAuthorizationInfoAdapter = new CreditCardAuthorizationInfoAdapter()
                    cardAuthorizationInfoAdapter.billingInfoId = billingInfoUsedToCapture.id

                    creditCardAuthorizationInfoService.update(captureResponse.creditCardAuthorizationInfo, cardAuthorizationInfoAdapter)
                } else {
                    AsaasLogger.error("CreditCardService.processPaymentCaptureResult >>> BillingInfo não informado para o pagamento com 3DS [paymentId: ${payment.id}]")
                }

                return captureResponse
            } else {
                billingInfoUsedToCapture = captureResponse.creditCardAuthorizationInfo.billingInfoId ? BillingInfo.get(captureResponse.creditCardAuthorizationInfo.billingInfoId) : null
            }
        } else {
            billingInfoUsedToCapture = saveBillingInfoFromTransactionResponseIfNecessary(billingInfo, payment.customerAccount, creditCardTransactionInfo?.creditCard, creditCardTransactionInfo?.holderInfo, captureResponse)
        }

        if (shouldSaveGatewayInfo(captureResponse.gateway, captureResponse.acquirer) && captureResponse.orderKey) {
            paymentCreditCardService.saveGatewayInfo(payment, captureResponse.gateway, captureResponse.orderKey, captureResponse.transactionKey)
        }

        if (asaasMoneyService.isAsaasMoneyRequest() && creditCardTransactionOriginInfo.asaasMoneyPayerCustomer) {
            asaasMoneyTransactionInfoService.saveCreditCardTransaction(creditCardTransactionOriginInfo.asaasMoneyPayerCustomer, payment)
        }

        CapturedCreditCardTransactionVo capturedCreditCardTransactionVo = buildTransactionVo(payment, captureResponse, billingInfoUsedToCapture)

        if (authorizeOnly) {
            paymentCreditCardService.setCreditCardTransactionAsAuthorized(capturedCreditCardTransactionVo, billingInfoUsedToCapture)
        } else {
            payment.onReceiving = true

            if (fraudAnalysisResult?.riskAnalysisRequired) {
                paymentCreditCardService.setCreditCardCaptureAsAwaitingRiskAnalysis(capturedCreditCardTransactionVo, billingInfoUsedToCapture)
            } else {
                paymentCreditCardService.confirmCreditCardCapture(capturedCreditCardTransactionVo, billingInfoUsedToCapture)
            }
        }

        payment.save(flush: true, failOnError: true)

        return captureResponse
    }

    private void saveAttemptForCustomerBlockedToNewCreditCardTransactions(Payment payment, CreditCardTransactionInfoVo creditCardTransactionInfo, BillingInfo billingInfo, Map creditCardTransactionOriginInfo) {
        if (AsaasEnvironment.isJobServer()) return

        CreditCardPreventAbuseVO creditCardPreventAbuseVO = new CreditCardPreventAbuseVO(creditCardTransactionInfo?.creditCard, billingInfo)
        creditCardPreventAbuseVO.blockCreditCardTransaction = true
        creditCardPreventAbuseVO.blockReason = "Transações bloqueadas para este estabelecimento."

        HolderInfo holderInfo
        CreditCardTransactionAttemptType creditCardTransactionAttemptType
        if (creditCardTransactionInfo?.creditCard) {
            holderInfo = creditCardTransactionInfo.holderInfo
            creditCardTransactionAttemptType = CreditCardTransactionAttemptType.CREDIT_CARD
        } else {
            holderInfo = billingInfo.creditCardHolderInfo ? HolderInfo.buildFromCreditCardHolderInfo(billingInfo.creditCardHolderInfo) : null
            creditCardTransactionAttemptType = CreditCardTransactionAttemptType.TOKENIZED_CREDIT_CARD
        }

        saveCreditCardTransactionAttempt(payment.provider,
            payment.customerAccount,
            creditCardTransactionInfo?.creditCard,
            billingInfo,
            creditCardPreventAbuseVO,
            creditCardTransactionAttemptType,
            false,
            payment.id,
            payment.value,
            creditCardTransactionOriginInfo,
            null,
            holderInfo,
            null)
    }

    public void refund(Long id, Payment paymentToRefund) {
        Payment payment = paymentToRefund ?: Payment.get(id)

        Map transactionInfoMap = CreditCardAuthorizationInfo.query([columnList: ["gateway", "transactionIdentifier", "transactionReference", "amountInCents", "requestKey"], transactionIdentifier: payment.creditCardTid]).get()
        if (!transactionInfoMap) transactionInfoMap = CreditCardTransactionInfo.query([columnList: ["gateway", "transactionIdentifier"], paymentId: payment.id]).get()

        String refundReferenceCode = transactionInfoMap.gateway.isMundipagg() ? PaymentGatewayInfo.query(["column": "mundiPaggOrderKey", "paymentId": payment.id]).get() : transactionInfoMap.requestKey

        Map refundResponse = processRefund(
            payment.provider,
            payment.customerAccount,
            transactionInfoMap.gateway,
            transactionInfoMap.transactionIdentifier,
            transactionInfoMap.transactionReference,
            transactionInfoMap.amountInCents,
            refundReferenceCode
        )

        if (!refundResponse.success) throw new RuntimeException("Erro ao estornar cobrança [${payment.id}]: Falha no gateway: ${refundResponse.message}.")

        AsaasLogger.info("Payment >> ${payment.id} successfully refunded")
    }

	public void refund(Installment installment) throws BusinessException {
		Payment firsCreditCardPayment = installment.listRefundedCreditCardPayments()[0]

        Map creditCardTransactionInfoMap = CreditCardTransactionInfo.query([columnList: ["gateway", "transactionIdentifier"], paymentId: firsCreditCardPayment.id]).get()
		Map refundResponse = [:]
		if (creditCardTransactionInfoMap.gateway?.isAdyen()) {
            refundResponse = adyenCreditCardService.refund(installment.customerAccount.provider, installment.customerAccount, creditCardTransactionInfoMap.transactionIdentifier)
        } else if (creditCardTransactionInfoMap.gateway?.isCybersource()) {
            refundResponse = cyberSourceCreditCardService.refundWithAuthorizationInfo(installment.customerAccount.provider, installment.customerAccount, creditCardTransactionInfoMap.transactionIdentifier)
        } else if (creditCardTransactionInfoMap.gateway?.isCielo()) {
            refundResponse = cieloCreditCardService.refund(creditCardTransactionInfoMap.transactionIdentifier)
		} else if (creditCardTransactionInfoMap.gateway?.isRede()) {
            refundResponse = redeCreditCardService.refund(creditCardTransactionInfoMap.transactionIdentifier)
        } else if (creditCardTransactionInfoMap.gateway?.isAcquiring()) {
            refundResponse = acquiringCreditCardService.refund(creditCardTransactionInfoMap.transactionIdentifier).properties
        } else {
            validateIfAllPaymentsHaveSameGatewayInfo(installment)

            String mundiPaggOrderKey = PaymentGatewayInfo.query(["column": "mundiPaggOrderKey", "paymentId": firsCreditCardPayment.id]).get()
            refundResponse = mundiPaggRESTService.refund(mundiPaggOrderKey)
		}

		if (!refundResponse.success) {
            AsaasLogger.info("Failure while attempting to refund installment >> ${installment.id}. Message >> ${refundResponse.message}")
			throw new BusinessException("Não foi possível estornar este parcelamento. Entre em contato com o Suporte.")
		}
	}

    public Map processRefund(Customer customer, CustomerAccount customerAccount, CreditCardGateway gateway, String transactionIdentifier, String transactionReference, Long amountInCents, String refundReferenceCode) {
        Map refundResponse = [:]

        if (gateway?.isAdyen()) {
            refundResponse = adyenCreditCardService.refund(customer, customerAccount, transactionIdentifier)
        } else if (gateway?.isCybersource()) {
            refundResponse = cyberSourceCreditCardService.refund(customer, customerAccount, refundReferenceCode, amountInCents, transactionReference, transactionIdentifier, null)
        } else if (gateway?.isCielo()) {
            refundResponse = cieloCreditCardService.refund(customer, customerAccount, transactionIdentifier, transactionReference, amountInCents)
        } else if (gateway?.isRede()) {
            refundResponse = redeCreditCardService.refund(customer, customerAccount, transactionIdentifier, amountInCents)
        } else if (gateway?.isAcquiring()) {
            refundResponse = acquiringCreditCardService.refund(customer, customerAccount, transactionIdentifier, amountInCents).properties
        } else {
            refundResponse = mundiPaggRESTService.refund(refundReferenceCode)
        }

        if (!refundResponse.success) AsaasLogger.error("CreditCardService.processRefund >> Falha ao estornar a cobrança [transactionIdentifier: ${transactionIdentifier}]. Mensagem: ${refundResponse.message}")

        return refundResponse
    }

    public Map refund(CreditCardGateway creditCardGateway, String transactionIdentifier, BigDecimal value) {
        Map refundResponseMap

        CreditCardAuthorizationInfo creditCardAuthorizationInfo = CreditCardAuthorizationInfo.query([transactionIdentifier: transactionIdentifier]).get()

        if (!creditCardAuthorizationInfo) {
            AsaasLogger.error("CreditCardService.refund >>> Erro ao estornar a transação: ${transactionIdentifier}: creditCardAuthorizationInfo não encontrado")
            throw new RuntimeException("Não foi possivel efetuar o estorno.")
        }

        validateRefund(creditCardAuthorizationInfo, value)

        switch (creditCardGateway) {
            case CreditCardGateway.ADYEN:
                refundResponseMap = adyenCreditCardService.refund(creditCardAuthorizationInfo.payment.provider, creditCardAuthorizationInfo.payment.customerAccount, transactionIdentifier, MoneyUtils.valueInCents(value))
                break
            case CreditCardGateway.REDE:
                refundResponseMap = redeCreditCardService.refund(creditCardAuthorizationInfo.payment.provider, creditCardAuthorizationInfo.payment.customerAccount, creditCardAuthorizationInfo.transactionIdentifier, MoneyUtils.valueInCents(value))
                break
            case CreditCardGateway.CIELO:
                refundResponseMap = cieloCreditCardService.refund(creditCardAuthorizationInfo.payment.provider, creditCardAuthorizationInfo.payment.customerAccount, creditCardAuthorizationInfo.transactionIdentifier, creditCardAuthorizationInfo.transactionReference, MoneyUtils.valueInCents(value))
                break
            case CreditCardGateway.ACQUIRING:
                refundResponseMap = acquiringCreditCardService.refund(creditCardAuthorizationInfo.payment.provider, creditCardAuthorizationInfo.payment.customerAccount, creditCardAuthorizationInfo.transactionIdentifier, MoneyUtils.valueInCents(value)).properties
                break
            default:
                AsaasLogger.error("CreditCardService.refund >>> ${creditCardGateway.name()} não configurada para estorno com a informação do valor [TID: ${transactionIdentifier}]")
                throw new RuntimeException("Estorno não permitido para esta operação.")
        }

        if (!refundResponseMap.success) {
            AsaasLogger.error("CreditCardService.refund >>> Falha ao estornar a cobrança [TID: ${transactionIdentifier}]")
            throw new RuntimeException("Não foi possivel efetuar o estorno.")
        }

        return refundResponseMap
    }

	private void validateIfAllPaymentsHaveSameGatewayInfo(Installment installment) {
        String orderKey
        String transactionKey

        for (Payment payment : installment.listRefundedCreditCardPayments()) {
            if (payment.billingInfo.creditCardInfo.brand == CreditCardBrand.AMEX) throw new BusinessException("Estorno de cartão Amex deve ser feito direto com a operadora.")
            PaymentGatewayInfo paymentGatewayInfo = PaymentGatewayInfo.query(["paymentId": payment.id]).get()

            if (!orderKey) orderKey = paymentGatewayInfo.mundiPaggOrderKey
            if (!transactionKey) transactionKey = paymentGatewayInfo.mundiPaggTransactionKey

            if (orderKey != paymentGatewayInfo.mundiPaggOrderKey || transactionKey != paymentGatewayInfo.mundiPaggTransactionKey) {
                AsaasLogger.error("Installment [${installment.id}] has more than one mundiPaggOrderKey. Aborting.")
                throw new BusinessException("Não foi possível estornar este parcelamento. Entre em contato com o Suporte.")
            }
        }
    }

	private List<Long> findPaymentsWaitingCapture() {
        final Map defaultQueryParams = [
           billingType: BillingType.MUNDIPAGG_CIELO,
           status: [PaymentStatus.PENDING, PaymentStatus.OVERDUE],
           now: new Date()
        ]

        Calendar maxDueDate = Calendar.getInstance()
        Calendar minDueDate = Calendar.getInstance()
        minDueDate.add(Calendar.DAY_OF_MONTH, -Payment.DAYS_LIMIT_TO_TRY_AUTOMATIC_CREDIT_CARD_CAPTURE)

        Integer queryLimit = 250
        List<Long> paymentIdList = []

        for (Calendar dueDate in minDueDate..maxDueDate) {
            Map queryParams = defaultQueryParams.clone()
            queryParams.dueDate = dueDate.getTime().clearTime()
            queryParams.max = queryLimit

            List<Long> paymentIdAtDueDateList = Payment.executeQuery("select p.id from Payment p join p.subscriptionPayments as sp where p.dueDate = :dueDate and p.billingType = :billingType and p.status in (:status) and p.confirmedDate is null and (p.nextCreditCardCaptureAttempt is null or p.nextCreditCardCaptureAttempt <= :now) and p.deleted = false and sp.subscription.billingInfo is not null", queryParams)
            paymentIdList.addAll(paymentIdAtDueDateList)

            queryLimit -= paymentIdAtDueDateList.size()
            if (queryLimit <= 0) break
        }

        return paymentIdList
	}

	public Boolean capturePendingPayments() {
		List<Long> paymentIdList = findPaymentsWaitingCapture()

        if (!paymentIdList) return false

        final Integer flushEvery = 50
        final Integer numberOfThreads = 2
        Utils.processWithThreads(paymentIdList, numberOfThreads, { List<Long> paymentIdListFromThread ->
            Utils.forEachWithFlushSession(paymentIdListFromThread, flushEvery, { Long paymentId ->
                tryAutomaticPaymentCapture(paymentId)
            })
        })

        return true
	}

	public void tryAutomaticPaymentCapture(Long paymentId) {
		Payment.withNewTransaction{ status ->
			try {
                AsaasLogger.info("Trying capture of Payment [${paymentId}].")

				Payment payment = Payment.get(paymentId)

				BillingInfo billingInfo = payment.subscription?.billingInfo

				Calendar now = CustomDateUtils.getInstanceOfCalendar()
                Integer daysSinceDueDate = CustomDateUtils.calculateDifferenceInDays(payment.dueDate, new Date().clearTime())

				if (payment.provider.boletoIsDisabled() || !billingInfo) {
					now.add(Calendar.DAY_OF_MONTH, 1)
					payment.setNextCreditCardCaptureAttempt(now.getTime())

					payment.save(flush: true, failOnError: true)

					return
				}

				payment.automaticCreditCardCapture = true

				Map captureResponse = captureWithBillingInfo(payment, billingInfo, null, null)

                if (!captureResponse.success) {
					payment.refresh()

					if (payment.isOverdue() && daysSinceDueDate >= Payment.DAYS_LIMIT_TO_TRY_AUTOMATIC_CREDIT_CARD_CAPTURE) {
						payment.subscription.billingInfo = null

                        AsaasLogger.info("Removendo cartão da assinatura >> [${payment.subscription.id}]")

						Payment.query([subscriptionId: payment.subscription.id, status: PaymentStatus.PENDING]).list().each {
                            AsaasLogger.info("Removendo cartão da assinatura >> [${payment.subscription.id}] cobranca >> [${it.id}]")
							it.billingInfo = null
							it.nextCreditCardCaptureAttempt = null
							it.save(failOnError: true)
						}
					} else {
                        Integer hoursToDelayNextAttempt = 6

                        if (billingInfo.creditCardInfo.gateway.isCieloApplicable()) {
                            hoursToDelayNextAttempt = 192
                        } else if (Holiday.isHoliday(now.getTime()) || daysSinceDueDate >= 1) {
                            hoursToDelayNextAttempt = 24
                        }

						now.add(Calendar.HOUR, hoursToDelayNextAttempt)
						payment.setNextCreditCardCaptureAttempt(now.getTime())
					}

					payment.save(flush: true, failOnError: true)
				}
                paymentAnticipableInfoService.updateIfNecessary(payment)
                notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)
			} catch (BusinessException businessException) {
                status.setRollbackOnly()
			} catch (Exception exception) {
                AsaasLogger.error("CreditCardService.tryAutomaticPaymentCapture >> Erro na captura automática da cobrança. [paymentId: ${paymentId}]", exception)
				status.setRollbackOnly()
			}
		}
	}

	public Map tokenizeCreditCard(CustomerAccount customerAccount, CreditCard creditCard, Map creditCardTransactionOriginInfo, HolderInfo holderInfo) {
        validateCreditCardSimulateNumberError(creditCard)
        Map responseMap = [ success: false ]

        CreditCardPreventAbuseVO creditCardPreventAbuseVO = setTransactionAsBlockedIfNecessary(customerAccount.provider, customerAccount, creditCard, null, CreditCardTransactionAttemptType.TOKENIZATION, holderInfo, creditCardTransactionOriginInfo.remoteIp, creditCardTransactionOriginInfo.payerRemoteIp, creditCardTransactionOriginInfo.originInterface, null)

        if (!creditCardPreventAbuseVO.blockCreditCardTransaction) {
            AsaasConfig asaasConfig = AsaasConfig.getInstance()

            creditCard.buildBrand()

            if (CustomerParameter.getValue(customerAccount.provider, CustomerParameterName.CREDIT_CARD_TOKENIZATION_ON_KREMLIN)) {
                responseMap = tokenizeCreditCardWithGateway(customerAccount, creditCard, CreditCardGateway.KREMLIN, false, holderInfo, false)
            } else if (CustomerParameter.getValue(customerAccount.provider, CustomerParameterName.USE_CYBERSOURCE_ON_TOKENIZATION)) {
                responseMap = tokenizeCreditCardWithGateway(customerAccount, creditCard, CreditCardGateway.CYBERSOURCE, false, holderInfo, false)
            } else if (CustomerParameter.getValue(customerAccount.provider, CustomerParameterName.USE_MUNDIPAGG_ON_TOKENIZATION)) {
                responseMap = tokenizeCreditCardWithGateway(customerAccount, creditCard, CreditCardGateway.MUNDIPAGG, false, holderInfo, false)
            } else if (CustomerParameter.getValue(customerAccount.provider, CustomerParameterName.USE_ADYEN_ON_TOKENIZATION)) {
                responseMap = tokenizeCreditCardWithGateway(customerAccount, creditCard, CreditCardGateway.ADYEN, false, holderInfo, false)
            } else if (asaasConfig.creditCardWithKremlinEnabled) {
                responseMap = tokenizeCreditCardWithGateway(customerAccount, creditCard, CreditCardGateway.KREMLIN, false, holderInfo, false)
            } else if (asaasConfig.creditCardWithAdyenEnabled) {
                responseMap = tokenizeCreditCardWithGateway(customerAccount, creditCard, CreditCardGateway.ADYEN, true, holderInfo, false)
            } else if (asaasConfig.creditCardWithCieloEnabled && creditCard.brand.isCieloApplicable()) {
                responseMap = tokenizeCreditCardWithGateway(customerAccount, creditCard, CreditCardGateway.CIELO, true, holderInfo, false)
            } else if (asaasConfig.creditCardWithCyberSourceEnabled) {
                responseMap = tokenizeCreditCardWithGateway(customerAccount, creditCard, CreditCardGateway.CYBERSOURCE, true, holderInfo, false)
            } else {
                responseMap = tokenizeCreditCardWithGateway(customerAccount, creditCard, CreditCardGateway.MUNDIPAGG, true, holderInfo, false)
            }
        } else {
            AsaasLogger.info("CreditCardService.tokenizeCreditCard >>> Transação de tokenização bloqueada na prevenção a abuso [customer: ${customerAccount.provider.id} - customerAccount: ${customerAccount.id}].")
            if (creditCardPreventAbuseVO.customerErrorMessage) responseMap.customerErrorMessage = creditCardPreventAbuseVO.customerErrorMessage
            responseMap.wasBlockedByPreventAbuse = creditCardPreventAbuseVO.blockCreditCardTransaction

            delayResponseToObfuscateAutomaticBlock()
        }

        final BigDecimal tokenizationValue = 0
        saveCreditCardTransactionAttempt(customerAccount.provider, customerAccount, creditCard, null, creditCardPreventAbuseVO, CreditCardTransactionAttemptType.TOKENIZATION, responseMap.success, null, tokenizationValue, creditCardTransactionOriginInfo, null, holderInfo, responseMap.creditCardTransactionLogIdList)

        return responseMap
	}

    public BillingInfo saveBillingInfo(CustomerAccount customerAccount, CreditCard creditCard, Map tokenizeResponse, CreditCardGateway gateway, HolderInfo holderInfo) {
        creditCard.token = tokenizeResponse.instantBuyKey
        creditCard.gateway = gateway
        creditCard.customerToken = tokenizeResponse.customerToken
        String billingInfoPublicId = tokenizeResponse.billingInfoPublicId ?: UUID.randomUUID()

        return billingInfoService.save(BillingType.MUNDIPAGG_CIELO, customerAccount, creditCard, holderInfo, billingInfoPublicId)
    }

    public Map buildTokenResponse(Map tokenizeResponse, BillingInfo billingInfo) {
        return tokenizeResponse + [billingInfo: billingInfo, creditCardToken: billingInfo.publicId]
    }

    private CreditCardPreventAbuseVO setTransactionAsBlockedIfNecessary(Customer customer, CustomerAccount customerAccount, CreditCard creditCard, BillingInfo billingInfo, CreditCardTransactionAttemptType creditCardTransactionAttemptType, HolderInfo holderInfo, String remoteIp, String payerRemoteIp, CreditCardTransactionOriginInterface originInterface, BigDecimal value) {
        CreditCardPreventAbuseVO creditCardPreventAbuseVO = new CreditCardPreventAbuseVO(creditCard, billingInfo)

        if (!customer.isCreditCardTransactionBlocked()) {
            creditCardPreventAbuseVO = creditCardPreventAbuseService.validate(customer, customerAccount, creditCard, billingInfo, creditCardTransactionAttemptType, remoteIp, payerRemoteIp, holderInfo, originInterface, value)

            cardAbuseRiskAlertService.blockCustomerIfNecessary(customer, customerAccount, creditCardPreventAbuseVO, creditCardTransactionAttemptType)
        }

        if (customer.isCreditCardTransactionBlocked()) {
            AsaasLogger.info("Negando transacão via cartao do fornecedor [${customer.id}] >>> Motivo: Transações via cartão temporariamente bloqueadas.")
            creditCardPreventAbuseVO.blockCreditCardTransaction = true
            creditCardPreventAbuseVO.blockReason = "Fornecedor com transações via cartão temporariamente bloqueadas."
        }

        return creditCardPreventAbuseVO
    }

    private void validateCreditCardSimulateNumberError(CreditCard creditCard) {
        if (AsaasEnvironment.isProduction()) return

        List<String> creditCardNumbersToSimulateError = ['5184019740373151', '4916561358240741']

        if (creditCardNumbersToSimulateError.contains(creditCard.number)) {
            throw new BusinessException(Utils.getMessageProperty("creditCard.number.error.simulate"))
        }
    }

    private Map tokenizeCreditCardWithGateway(CustomerAccount customerAccount, CreditCard creditCard, CreditCardGateway gateway, Boolean enableFallback, HolderInfo holderInfo, Boolean isFallback) {
        Map tokenizationResponseMap

        if (gateway.isAdyen()) {
            tokenizationResponseMap = adyenCreditCardService.getInstantBuyKey(creditCard, customerAccount, isFallback)
        } else if (gateway.isCielo()) {
            tokenizationResponseMap = cieloCreditCardService.tokenize(creditCard, customerAccount, isFallback)
        } else if (gateway.isCybersource()) {
            tokenizationResponseMap = cyberSourceCreditCardService.tokenize(creditCard, customerAccount, false, isFallback)
        } else if (gateway.isMundipagg()) {
            validateCreditCardSimulateNumberError(creditCard)

            Payment payment = new Payment(customerAccount: customerAccount, value: 1, dueDate: new Date().clearTime(), softDescriptorText: customerAccount.provider.providerName)

            tokenizationResponseMap = mundiPaggRESTService.getInstantBuyKey(creditCard, payment, isFallback)
            payment.discard()
        } else if (gateway.isKremlin()) {
            tokenizationResponseMap = kremlinCreditCardService.tokenizeValidatedCreditCard(customerAccount, creditCard, null, isFallback)
        }

        if (!tokenizationResponseMap.success) {
            if (!enableFallback) return tokenizationResponseMap
            if (CustomerParameter.getValue(customerAccount.provider, CustomerParameterName.DISABLE_TOKENIZATION_FALLBACK)) return tokenizationResponseMap

            creditCard.buildBrand()
            if (!CreditCard.shouldExecuteFallback(creditCard.brand, creditCard.buildBin(), tokenizationResponseMap.acquirerReturnCode.toString())) return tokenizationResponseMap

            Map fallbackResponse = tokenizeCreditCardFallback(customerAccount, creditCard, gateway, holderInfo)
            if (tokenizationResponseMap.creditCardTransactionLogIdList) fallbackResponse.creditCardTransactionLogIdList.addAll(0, tokenizationResponseMap.creditCardTransactionLogIdList)

            return fallbackResponse
        }

        BillingInfo billingInfo = saveBillingInfo(customerAccount, creditCard, tokenizationResponseMap, gateway, holderInfo)

        if (enableFallback) {
            Map fallbackTokenizationResponseMap = tokenizeCreditCardFallback(customerAccount, creditCard, gateway, holderInfo)

            if (fallbackTokenizationResponseMap.creditCardTransactionLogIdList) tokenizationResponseMap.creditCardTransactionLogIdList.addAll(fallbackTokenizationResponseMap.creditCardTransactionLogIdList)

            if (fallbackTokenizationResponseMap?.success) {
                billingInfo.fallbackBillingInfo = fallbackTokenizationResponseMap.billingInfo
                billingInfo.save(failOnError: true)
            } else {
                AsaasLogger.warn("Não foi possível tokenizar fallback para o BillingInfo [${billingInfo.id}]")
            }
        }

        return buildTokenResponse(tokenizationResponseMap, billingInfo)
    }

    private Map tokenizeCreditCardFallback(CustomerAccount customerAccount, CreditCard creditCard, CreditCardGateway alreadyUsedGateway, HolderInfo holderInfo) {
        AsaasLogger.warn("Utilizando fallback para transação de cartão (tokenização) da ${alreadyUsedGateway.toString()}")

        List<CreditCardGateway> fallbackGatewayList = buildFallbackGatewayList(alreadyUsedGateway, creditCard.brand)
        if (!fallbackGatewayList) return [success: false, creditCardTransactionLogIdList: []]

        return tokenizeCreditCardWithGateway(customerAccount, creditCard, fallbackGatewayList[0], false, holderInfo, true)
    }

    private List<CreditCardGateway> buildFallbackGatewayList(CreditCardGateway alreadyUsedGateway, CreditCardBrand creditCardBrand) {
        List<CreditCardGateway> gatewayListSortedByPriority = [CreditCardGateway.ADYEN, CreditCardGateway.CIELO]

        gatewayListSortedByPriority.removeAll(alreadyUsedGateway)

        AsaasConfig asaasConfig = AsaasConfig.getInstance()
        Boolean adyenEnabled = asaasConfig.creditCardWithAdyenEnabled && creditCardBrand.isAdyenApplicable()
        Boolean cieloEnabled = asaasConfig.creditCardWithCieloEnabled && creditCardBrand.isCieloApplicable()

        if (!adyenEnabled) gatewayListSortedByPriority.removeAll(CreditCardGateway.ADYEN)
        if (!cieloEnabled) gatewayListSortedByPriority.removeAll(CreditCardGateway.CIELO)

        return gatewayListSortedByPriority
    }

    private void delayResponseToObfuscateAutomaticBlock() {
        Integer sleepTime = new SecureRandom().nextInt(2000) + 2000
        Thread.sleep(sleepTime)
    }

    private void validateRefund(CreditCardAuthorizationInfo creditCardAuthorizationInfo, BigDecimal valueToRefund) {
        BigDecimal transactionValue = MoneyUtils.valueFromCents(creditCardAuthorizationInfo.amountInCents.toString())

        if (valueToRefund < transactionValue) {
            if (creditCardAuthorizationInfo.payment.status.isAuthorized()) {
                throw new BusinessException("Para transações pré-autorizadas somente está disponível o estorno total.")
            }

            if (creditCardAuthorizationInfo.dateCreated.clone().clearTime() == new Date().clearTime()) {
                throw new BusinessException("Esta transação só pode ser estornada parcialmente no próximo dia.")
            }
        }
    }

    private Boolean shouldSaveGatewayInfo(CreditCardGateway gateway, CreditCardAcquirer acquirer) {
        if (gateway?.isRede()) return true
        if (gateway?.isMundipagg()) return true
        if (gateway?.isAcquiring() && acquirer?.isRede()) return true

        return false
    }

    private BillingInfo saveBillingInfoFromTransactionResponseIfNecessary(BillingInfo billingInfo, CustomerAccount customerAccount, CreditCard creditCard, HolderInfo holderInfo, Map transactionResponse) {
        if (billingInfo) {
            if (transactionResponse.fallbackBillingInfoHasBeenUsed) return billingInfo.fallbackBillingInfo

            return billingInfo
        }

        creditCard.token = transactionResponse.instantBuyKey
        creditCard.customerToken = transactionResponse.customerToken
        creditCard.tokenizationGateway = transactionResponse.tokenizationGateway
        String billingInfoPublicId = transactionResponse.billingInfoPublicId ?: UUID.randomUUID()

        return billingInfoService.save(BillingType.MUNDIPAGG_CIELO, customerAccount, creditCard, holderInfo, billingInfoPublicId)
    }
}
