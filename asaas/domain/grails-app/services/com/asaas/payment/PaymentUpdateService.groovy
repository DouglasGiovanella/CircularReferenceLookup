package com.asaas.payment

import com.asaas.billinginfo.BillingType
import com.asaas.boleto.RegisteredBoletoValidator
import com.asaas.correios.CorreiosDeliveryTime
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.checkoutcallbackconfig.PaymentCheckoutCallbackConfig
import com.asaas.domain.payment.PaymentDiscountConfig
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.payment.PaymentFineConfig
import com.asaas.domain.payment.PaymentInterestConfig
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationPriority
import com.asaas.payment.validator.PaymentValidator
import com.asaas.postalservice.PaymentPostalServiceValidator
import com.asaas.postalservice.PostalServiceStatus
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError

import grails.transaction.Transactional
import org.apache.commons.lang.time.DateUtils

@Transactional
class PaymentUpdateService {

    def asaasSegmentioService
	def bankSlipRegisterNotificationService
	def bankSlipRegisterService
	def boletoBatchFileItemService
	def notificationRequestService
    def notificationDispatcherPaymentNotificationOutboxService
    def paymentAnticipableInfoService
    def paymentCheckoutCallbackConfigService
	def paymentFeeService
	def paymentHistoryService
	def paymentPostalServiceBatchService
	def paymentSplitService
	def paymentDiscountConfigService
    def paymentDunningService
	def paymentFineConfigService
	def paymentInterestConfigService
    def paymentUndefinedBillingTypeConfigService
    def pixAsaasQrCodeService
    def pixQrCodeAsyncActionService
    def pixPaymentInfoService
    def receivableAnticipationValidationService
    def receivableRegistrationEventQueueService
    def receivableHubPaymentOutboxService
    def asaasErpAccountingStatementService
    def paymentPushNotificationRequestAsyncPreProcessingService

	def onError(entity) {
        transactionStatus.setRollbackOnly()
        return entity
    }

    def update(params) {
		Payment payment = params.payment ?: Payment.find(params.id, params.providerId.toLong())

        if (!payment.canManipulate()) return DomainUtils.addError(payment, payment.editDisabledReason)

		List<Map> updatedFields = []

		Boolean billingTypeUpdated = processBillingTypeUpdate(payment, params, updatedFields)
		if (payment.hasErrors()) return onError(payment)

		Boolean valueUpdated = processValueUpdate(payment, params, updatedFields)
		if (payment.hasErrors()) return onError(payment)

        Date previousDueDate = payment.dueDate
        Boolean dueDateUpdated = processDueDayUpdate(payment, params)
        if (payment.hasErrors()) return onError(payment)

		if (params.automaticDueDate && params.sendPaymentByPostalService && payment.customerAccount.city) {
			params.dueDate = getAutomaticDueDateForPostalService(payment)
		}

        dueDateUpdated = processDueDateUpdate(payment, params, updatedFields)
        PaymentValidator.validateDescription(payment, String.valueOf(params.description))
		if (payment.hasErrors()) return onError(payment)

		if (params.containsKey("description")) { payment.description = params.description }

		if (params.containsKey("grossValue") && paymentIsNotReceived(payment)) {
			BigDecimal grossValue = Utils.toBigDecimal(params.grossValue)
			setGrossValueIfEnabled(payment, grossValue)
		}

        if (params.containsKey("softDescriptorText") && payment.provider.getConfig()?.overrideAsaasSoftDescriptor) {
            payment.softDescriptorText = params.softDescriptorText
        }

		if (params.containsKey("externalReference")) { payment.externalReference = params.externalReference }

        if (payment.isDirty() && !payment.canEdit()) {
            payment.discard()
            return DomainUtils.addError(payment, payment.editDisabledReason)
        }

        if (payment.hasErrors()) {
            return onError(payment)
        }

        if (payment.canEdit()) {
            if (payment.postalServiceStatus && !PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(payment)) {
                if (Utils.toBoolean(params.sendPaymentByPostalService) || Utils.toBoolean(params.resendByPostalService)) {
                    DomainUtils.addFieldError(payment, "billingType", "not.allowed.to.postalService")
                    return onError(payment)
                }

                payment.postalServiceStatus = null
            }

            if (Utils.toBoolean(params.cancelSendByPostalService) && payment.postalServiceStatus) {
                if (payment.postalServiceStatus in PostalServiceStatus.notSentYet()) {
                    payment.postalServiceStatus = null
                } else {
                    DomainUtils.addFieldError(payment, "postalServiceStatus", "cannot.cancel.already.sent")
                    return onError(payment)
                }
            } else if (Utils.toBoolean(params.resendByPostalService) && payment.postalServiceStatus in PostalServiceStatus.canResend()) {
                payment.postalServiceStatus = paymentPostalServiceBatchService.getPostalServiceStatusToDeliveryInTime(payment)
            } else if ((Utils.toBoolean(params.sendPaymentByPostalService) || Utils.toBoolean(params.resendByPostalService)) && !payment.postalServiceStatus) {
                payment.postalServiceStatus = paymentPostalServiceBatchService.getPostalServiceStatusToDeliveryInTime(payment)
            }

            if (payment.postalServiceStatus in PostalServiceStatus.notSentYet()) {
                //Se mudou o postalServiceStatus, ou alterou vencimento ou valor e mandou reenviar
                if (payment.getPersistentValue("postalServiceStatus") != payment.postalServiceStatus || (dueDateUpdated || valueUpdated)) {
                    payment.postalServiceStatus = paymentPostalServiceBatchService.getPostalServiceStatusToDeliveryInTime(payment)

                    PaymentPostalServiceValidator postalServiceValidator = new PaymentPostalServiceValidator(payment)

                    if (!postalServiceValidator.isValid()) {
                        if (Utils.toBoolean(params.disablePostalServiceIfNecessary)) {
                            payment.postalServiceStatus = null
                        } else {
                            postalServiceValidator.addErrorsTo(payment)
                            return onError(payment)
                        }
                    }
                }
            }

            if (!payment.isPending() && PostalServiceStatus.notSentYet()) payment.postalServiceStatus = null

            List<AsaasError> asaasErrorList = []
            if (params.discount) {
                asaasErrorList.addAll(paymentDiscountConfigService.validate(payment, params.discount).asaasErrors)
            } else {
                asaasErrorList.addAll(paymentDiscountConfigService.validateObjectValue(payment).asaasErrors)
            }

            if (params.fine) {
                asaasErrorList.addAll(paymentFineConfigService.validate(payment, params.fine).asaasErrors)
            } else {
                asaasErrorList.addAll(paymentFineConfigService.validateObjectValue(payment).asaasErrors)
            }

            asaasErrorList.addAll(paymentInterestConfigService.validate(payment, params.interest).asaasErrors)

            for (AsaasError asaasError : asaasErrorList) {
                DomainUtils.addError(payment, asaasError.getMessage())
            }

            if (payment.hasErrors()) return onError(payment)

            Boolean shouldAnalysisAnticipable = updatedFields.collect { it.fieldName }.intersect(["billingType", "value", "dueDate"])

            if (shouldAnalysisAnticipable) {
                Boolean isBankSlipInstallmentWithMoreThanTwelvePayments = payment.installment && payment.billingType.isBoleto() && payment.installment.installmentCount > 12
                Boolean mustAnalyzeAnticipationSubscription = params.subscriptionBulkSave.asBoolean() && payment.billingType.isEquivalentToAnticipation()
                if (isBankSlipInstallmentWithMoreThanTwelvePayments || mustAnalyzeAnticipationSubscription) {
                    paymentAnticipableInfoService.updateIfNecessary(payment)
                    paymentAnticipableInfoService.sendToAnalysisQueue(payment.id)
                } else {
                    payment.avoidExplicitSave = true
                    receivableAnticipationValidationService.onPaymentChange(payment)
                    payment.avoidExplicitSave = false
                }
            }

            payment.save(flush: true, failOnError: false)
            if (payment.hasErrors()) return onError(payment)

            PaymentDunning activeDunning = PaymentDunning.query(["paymentId": payment, "status[notIn]": PaymentDunningStatus.getRefusedList()]).get()
            if (activeDunning) {
                activeDunning = paymentDunningService.update(activeDunning)
                if (activeDunning.hasErrors()) return DomainUtils.addError(payment, "Uma cobrança em processo de negativação não pode ser atualizada.")
            }

            processPaymentUndefinedBillingTypeConfigRegisterIfNecessary(payment, params.paymentUndefinedBillingTypeConfig, updatedFields)

            PaymentDiscountConfig paymentDiscountConfig = paymentDiscountConfigService.save(payment, params.discount)
            PaymentInterestConfig paymentInterestConfig = paymentInterestConfigService.save(payment, params.interest)
            PaymentFineConfig paymentFineConfig = paymentFineConfigService.save(payment, params.fine)

            if (params.paymentCheckoutCallbackConfig) {
                PaymentCheckoutCallbackConfig paymentCheckoutCallbackConfig = paymentCheckoutCallbackConfigService.save(payment, params.paymentCheckoutCallbackConfig)

                if (paymentCheckoutCallbackConfig.hasErrors()) {
                    DomainUtils.copyAllErrorsFromObject(paymentCheckoutCallbackConfig, payment)
                }
            }

            if (payment.hasErrors()) return onError(payment)

            if (PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(payment) && [billingTypeUpdated, valueUpdated, dueDateUpdated, interestFineDiscountUpdated(paymentInterestConfig, paymentFineConfig, paymentDiscountConfig)].any()) {
                PaymentValidator.validateIfBankSlipCanBeRegistered(payment, payment.boletoBank ?: payment.provider.boletoBank)
                if (payment.hasErrors()) return onError(payment)
            }

            processBankSlipRegisterUpdate(payment, paymentInterestConfig, paymentFineConfig, paymentDiscountConfig, updatedFields)

            pixAsaasQrCodeService.updateValuesIfNecessary(payment)
            pixQrCodeAsyncActionService.updateDynamicQrCodeIfNecessary(payment)
        }

        paymentSplitService.saveSplitInfo(payment, params)

        paymentSplitService.updateSplitTotalValue(payment)

        if (statusHasBeenUpdatedFromOverdueToPending(payment, updatedFields)) {
            notificationRequestService.cancelPaymentUnsentNotifications(payment)
        }

        paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_UPDATED)

        Boolean shouldNotify = valueUpdated || dueDateUpdated
        if (shouldNotify) {
            notificationRequestService.save(payment.customerAccount, NotificationEvent.PAYMENT_UPDATED, payment, NotificationPriority.HIGH)
        }
        notificationDispatcherPaymentNotificationOutboxService.savePaymentDetailsUpdated(payment, shouldNotify, previousDueDate)

        asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment, params.asaasErpAccountingStatementExternalId, false)

        return payment
	}

    public Boolean updatePaymentCreditDateOnHolidayIfExists(Date creditDate) {
        final Integer flushEvery = 200
        final Integer itemsPerThread = 1000
        final Integer maxItemsOnList = 4000
        final Integer businessDaysToSubtract = 1
        final Date newCreditDate = CustomDateUtils.subtractBusinessDays(creditDate, businessDaysToSubtract)
        final List<PaymentStatus> paymentStatusListForUpdate = [
            PaymentStatus.CONFIRMED,
            PaymentStatus.AWAITING_CHARGEBACK_REVERSAL,
            PaymentStatus.CHARGEBACK_DISPUTE,
            PaymentStatus.CHARGEBACK_REQUESTED
        ]

        Map params = [:]
        params.creditDate = creditDate
        params.column = "id"
        params.statusList = paymentStatusListForUpdate
        params.billingType = BillingType.MUNDIPAGG_CIELO
        params.disableSort = true
        List<Long> paymentIdList = Payment.query(params).list(max: maxItemsOnList)

        if (!paymentIdList) return

        ThreadUtils.processWithThreadsOnDemand(paymentIdList, itemsPerThread, { List<Long> threadPaymentIdList ->
            Utils.forEachWithFlushSession(threadPaymentIdList, flushEvery, { Long paymentId ->
                Utils.withNewTransactionAndRollbackOnError({
                    updatePaymentCreditDateOnHoliday(paymentId, newCreditDate, paymentStatusListForUpdate)
                }, [ignoreStackTrace: true, onError: { Exception exception ->
                    AsaasLogger.error("PaymentUpdateService.updatePaymentCreditDateOnHoliday >> Erro ao alterar a data de credito da cobrança no feriado [paymentId: ${paymentId}]", exception)
                }])
            })
        })

        return paymentIdList.size() == maxItemsOnList
    }

    public void processBankSlipRegisterUpdate(Payment payment, PaymentInterestConfig paymentInterestConfig, PaymentFineConfig paymentFineConfig, PaymentDiscountConfig paymentDiscountConfig, List<Map> updatedFields) {
        if (billingTypeHasBeenUpdatedToBoletoOrUndefined(payment, updatedFields)) {
            PaymentBuilder.setBoletoFields(payment, null)
            updateNossoNumero(payment)
            bankSlipRegisterService.registerBankSlip(payment)
        } else if (billingTypeHasBeenUpdatedFromBoletoOrUndefined(payment, updatedFields) && payment.isRegisteredBankSlip()) {
            boletoBatchFileItemService.delete(payment)
        } else if (payment.isRegisteredBankSlip() && interestFineDiscountUpdated(paymentInterestConfig, paymentFineConfig, paymentDiscountConfig) && !payment.isAsaasBoletoBank()) {
            deleteAndRegisterNewBoleto(payment)
        } else if (payment.isRegisteredBankSlip()) {
            updateRegisteredBoleto(updatedFields, payment, paymentInterestConfig, paymentFineConfig, paymentDiscountConfig)
        } else if (PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(payment) && !payment.isRegisteredBankSlip() && bankSlipRegisterService.paymentHasBeenUnregistered(payment) && RegisteredBoletoValidator.validate(payment)) {
            updateNossoNumeroAndRegisterNewBoleto(payment)
        } else if (PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(payment) && !payment.isRegisteredBankSlip()) {
            bankSlipRegisterService.registerBankSlip(payment)
        }

        bankSlipRegisterNotificationService.updateNotificationScheduledDateIfNecessary(payment)
    }

    private void updatePaymentCreditDateOnHoliday(Long paymentId, Date newCreditDate, List<PaymentStatus> paymentStatusListForUpdate) {
        Payment payment = Payment.get(paymentId)

        if (!paymentStatusListForUpdate.contains(payment.status)) {
            AsaasLogger.warn("PaymentUpdateService.updatePaymentCreditDateOnHoliday >> Cobrança ignorada pois o status não permite alteração da data de crédito [paymentId: ${payment.id}, status: ${payment.status}].")
            return
        }

        if (!payment.confirmedDate) {
            AsaasLogger.warn("PaymentUpdateService.updatePaymentCreditDateOnHoliday >> Cobrança sem data de confirmação [paymentId: ${payment.id}].")
            return
        }

        if (!payment.creditDate) {
            AsaasLogger.warn("PaymentUpdateService.updatePaymentCreditDateOnHoliday >> Cobrança sem data de credito [paymentId: ${payment.id}].")
            return
        }

        payment.creditDate = newCreditDate
        payment.save(failOnError: true)

        paymentAnticipableInfoService.updateIfNecessary(payment)

        Map eventData = [paymentId: payment.id]
        receivableRegistrationEventQueueService.save(ReceivableRegistrationEventQueueType.RECREATE_RECEIVABLE_UNIT_ITEM, eventData, null)

        receivableHubPaymentOutboxService.saveConfirmedPaymentUpdated(payment)
    }

	private Boolean processBillingTypeUpdate(Payment payment, params, List<Map> updatedFields) {
		if (!params.containsKey("billingType") || !params.billingType) return false

		if (payment.billingType == BillingType.convert(params.billingType)) return false

		if (payment.isReceivingProcessInitiated()) {
			DomainUtils.addFieldError(payment, "billingType", "update.not.allowed.received")
			return false
		}

		BillingType billingType = BillingType.convert(params.billingType)

		if (!params.installmentUpdate && payment.installment && payment.billingType != billingType) {
			DomainUtils.addFieldError(payment, "billingType", "installment.update.not.allowed")
			return false
		}

		payment.billingType = billingType

		if (!payment.isCreditCard()) payment.billingInfo = null

		updatedFields.add([fieldName: "billingType", oldValue: payment.getPersistentValue("billingType"), newValue: payment.billingType])

        if (payment.value && payment.value > 0) {
            PaymentFeeVO paymentFeeVO = new PaymentFeeVO(payment)
            payment.netValue = paymentFeeService.calculateNetValue(paymentFeeVO)
        }

        pixPaymentInfoService.saveIfNecessary(payment)
        return true
	}

	private Boolean processValueUpdate(Payment payment, params, List<Map> updatedFields) {
		if (!params.containsKey("value") || !params.value) return false

		BigDecimal newValue = Utils.toBigDecimal(params.value)
		if (newValue == payment.value || newValue <= 0) return false

		PaymentValidator.validateIfValueCanBeUpdated(payment, Boolean.valueOf(params.installmentUpdate))
		if (payment.hasErrors()) return

		updatedFields.add([fieldName: "value", oldValue: payment.value, newValue: newValue])

        payment.value = newValue

        if (payment.value && payment.value > 0) {
            PaymentFeeVO paymentFeeVO = new PaymentFeeVO(payment)
            payment.netValue = paymentFeeService.calculateNetValue(paymentFeeVO)
        }

		return true
	}

	private Boolean processDueDateUpdate(Payment payment, params, updatedFields) {
		if (!params.containsKey("dueDate") || !params.dueDate) return false

		Date dueDate = params.dueDate instanceof Date ? params.dueDate : CustomDateUtils.fromString(params.dueDate)
		if (payment.dueDate.compareTo(dueDate) == 0) return false

		PaymentValidator.validateIfDueDateCanBeUpdated(payment, Boolean.valueOf(params.installmentUpdate))
		if (payment.hasErrors()) return false

		payment.ignoreDueDateValidator = params.ignoreDueDateValidator

		payment.dueDate = dueDate

		if (payment.status == PaymentStatus.OVERDUE && payment.dueDate.compareTo(DateUtils.truncate(new Date(), Calendar.DAY_OF_MONTH)) >= 0) {
			payment.status = PaymentStatus.PENDING
            receivableAnticipationValidationService.onPaymentRestore(payment)
		} else if (payment.dueDate.before(new Date().clearTime())) {
			payment.status = PaymentStatus.OVERDUE
		}

        updatedFields.add([fieldName: "status", oldValue: payment.getPersistentValue("status"), newValue: payment.status])

		updatedFields.add([fieldName: "dueDate", oldValue: payment.getPersistentValue("dueDate"), newValue: payment.dueDate])

		return true
	}

	private Boolean processDueDayUpdate(Payment payment, params) {
		if (!params.containsKey("dueDay") || !params.dueDay) return false

		String dueDay = CustomDateUtils.getDayOfDate(payment.dueDate)
		if (Integer.valueOf(dueDay) == Integer.valueOf(params.dueDay)) return false

		Integer newDueDay = Integer.parseInt(params.dueDay)

		Calendar dueDate = CustomDateUtils.getInstanceOfCalendar(payment.dueDate)

		if (newDueDay > dueDate.getActualMaximum(Calendar.DAY_OF_MONTH)) newDueDay = dueDate.getActualMaximum(Calendar.DAY_OF_MONTH)

		dueDate.set(Calendar.DAY_OF_MONTH, newDueDay)

		params.dueDate = dueDate.getTime()

		return true
	}

    private Boolean statusHasBeenUpdatedFromOverdueToPending(Payment payment, List<Map> updatedFields) {
        if (!updatedFields.fieldName.contains("status")) return false

        if ([PaymentStatus.OVERDUE].contains(updatedFields.find{ it.fieldName == "status" }.oldValue) && [PaymentStatus.PENDING].contains(payment.status)) return true

        return false
    }

	private Boolean billingTypeHasBeenUpdatedToBoletoOrUndefined(Payment payment, List<Map> updatedFields) {
		if (!updatedFields.fieldName.contains("billingType")) return false

		if ([BillingType.BOLETO, BillingType.UNDEFINED].contains(updatedFields.find{ it.fieldName == "billingType" }.oldValue)) return false

		if ([BillingType.BOLETO, BillingType.UNDEFINED].contains(payment.billingType)) return true
	}

	private Boolean billingTypeHasBeenUpdatedFromBoletoOrUndefined(Payment payment, List<Map> updatedFields) {
		if (!(updatedFields.fieldName.contains("billingType"))) return false

		if (![BillingType.BOLETO, BillingType.UNDEFINED].contains(updatedFields.find{ it.fieldName == "billingType" }.oldValue)) return false

		if (![BillingType.BOLETO, BillingType.UNDEFINED].contains(payment.billingType)) return true
	}

    private Boolean interestFineDiscountUpdated(PaymentInterestConfig paymentInterestConfig, PaymentFineConfig paymentFineConfig, PaymentDiscountConfig paymentDiscountConfig) {
        if (paymentDiscountConfig?.hasBeenUpdated) return true
        if (paymentFineConfig?.hasBeenUpdated) return true
        if (paymentInterestConfig?.hasBeenUpdated) return true

        return false
    }

	private Date getAutomaticDueDateForPostalService(Payment payment) {
		Calendar dueDate = Calendar.getInstance()

		dueDate.setTime(CorreiosDeliveryTime.calculateEstimatedDeliveryDate(payment.customerAccount.city))

		dueDate.add(Calendar.DAY_OF_MONTH, 5)

		return dueDate.getTime()
	}

	private Boolean paymentIsNotReceived(Payment payment) {
		return (payment.status == PaymentStatus.PENDING || payment.status == PaymentStatus.OVERDUE)
	}

	private void setGrossValueIfEnabled(Payment payment, BigDecimal grossValue) {
		if (payment.customerAccount?.provider?.customerConfig?.grossValueEnabled) {
			payment.grossValue = grossValue
		}
	}

    private void updateRegisteredBoleto(List<Map> updatedFields, Payment payment, PaymentInterestConfig paymentInterestConfig, PaymentFineConfig paymentFineConfig, PaymentDiscountConfig paymentDiscountConfig) {
        if (!(updatedFields.fieldName.contains("value") || updatedFields.fieldName.contains("dueDate") || interestFineDiscountUpdated(paymentInterestConfig, paymentFineConfig, paymentDiscountConfig))) return

        if (!payment.isAsaasBoletoBank() && !BoletoBatchFileItem.existsSentItem(payment)) {
            boletoBatchFileItemService.setPendingItemToReadyForTransmission(payment)
            return
        }

        Boolean hasDeleteSentItem = BoletoBatchFileItem.existsDeleteSentItem(payment, payment.nossoNumero)

        if (payment.isAsaasBoletoBank() && !hasDeleteSentItem) {
            boletoBatchFileItemService.update(payment)
        } else {
            deleteAndRegisterNewBoleto(payment)
        }
    }

	private void deleteAndRegisterNewBoleto(Payment payment) {
		boletoBatchFileItemService.delete(payment)

		updateNossoNumeroAndRegisterNewBoleto(payment)
	}

	public void updateNossoNumeroAndRegisterNewBoleto(Payment payment) {
		updateNossoNumero(payment)

		boletoBatchFileItemService.create(payment)
	}

	public void updateNossoNumero(Payment payment) {
		payment.nossoNumero = PaymentBuilder.buildNossoNumero(payment)

		String nossoNumeroOld = payment.getPersistentValue("nossoNumero")

		payment.save(flush: true, failOnError: true)

		if (nossoNumeroOld) {
            Long boletoBankId = BoletoBatchFileItem.query([column: "boletoBankId", payment: payment, nossoNumero: nossoNumeroOld, sort: "id", order: "desc"]).get()

            paymentHistoryService.save(payment, nossoNumeroOld, boletoBankId)
		}
	}

	public Payment batchUpdate(Customer customer, Map filters, Map newValues) {
		List<Long> idList = Payment.query((filters ?: [:]) + [customerId: customer.id, column: "id"]).list(max: 9999, offset: 0)
        if (!idList) throw new BusinessException("Nenhuma cobrança localizada para efetuar a modificação.")
        if (!filters.isSequencedList && Integer.valueOf(filters.totalCount) != idList.size()) throw new BusinessException("Quantia de cobranças localizadas [${idList.size()}] diferente do previsto [${filters.totalCount}].")

		def parametersMap = [:]
		if (newValues.dueDate) parametersMap.put("dueDate", newValues.dueDate)
		if (newValues.dueDay) parametersMap.put("dueDay", newValues.dueDay)
		if (newValues.value) parametersMap.put("value", newValues.value)
		if (newValues.description) parametersMap.put("description", newValues.description)

        asaasSegmentioService.track(customer.id, "Logged : Acao em lote : modificacao de cobrancas", [paymentCount: idList.size()])

		Payment.withNewTransaction { status ->
			try {
				List<Payment> paymentList = Payment.executeQuery("from Payment p where id in (:idList)", [idList: idList])

				for (Payment payment in paymentList) {
					payment = update([payment: payment] + parametersMap)
					if (payment.hasErrors()) {
						status.setRollbackOnly()
						return payment
					}
				}

				return null
			} catch (Exception e) {
				status.setRollbackOnly()
				throw e
			}
		}
	}

    private processPaymentUndefinedBillingTypeConfigRegisterIfNecessary(Payment payment, Map paymentUndefinedBillingTypeConfigMap, List<Map> updatedFields) {
        if (payment.billingType.isUndefined()) {
            paymentUndefinedBillingTypeConfigService.save(payment, paymentUndefinedBillingTypeConfigMap)
        } else if (isBillingTypeUpdatedFromUndefined(payment, updatedFields)) {
            paymentUndefinedBillingTypeConfigService.deleteIfNecessary(payment.id)
        }
    }

    private Boolean isBillingTypeUpdatedFromUndefined(Payment payment, List<Map> updatedFields) {
        if (!(updatedFields.fieldName.contains("billingType"))) return false

        if (payment.billingType.isUndefined()) return false

        if (BillingType.UNDEFINED == updatedFields.find{ it.fieldName == "billingType" }.oldValue) return true
    }
}
