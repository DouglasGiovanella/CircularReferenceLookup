package com.asaas.service.payment

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.correios.CorreiosDeliveryTime
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.postalservice.PaymentPostalServiceBatch
import com.asaas.domain.postalservice.PaymentPostalServiceBatchItem
import com.asaas.log.AsaasLogger
import com.asaas.postalservice.PaymentPostalServiceFee
import com.asaas.postalservice.PaymentPostalServiceValidator
import com.asaas.postalservice.PostalServiceBatchStatus
import com.asaas.postalservice.PostalServiceStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional
import grails.util.Environment
import org.apache.commons.lang.RandomStringUtils
import org.apache.commons.lang.StringUtils
import org.springframework.util.StopWatch

@Transactional
class PaymentPostalServiceBatchService {

    def correiosBatchFileService
    def financialTransactionService
	def messageService
    def promotionalCodeService
	def timelineEventService

	public void createForAllCustomersAndSendToCorreios(Boolean sendToFtp) {
		if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return

        StopWatch stopWatch = new StopWatch("createForAllCustomersAndSendToCorreios stop watch")
        stopWatch.start("createForAllCustomers")
        createForAllCustomers()
        stopWatch.stop()

		if (sendToFtp) {
            stopWatch.start("createCorreiosBatchFileAndSendToFtp")
            createCorreiosBatchFileAndSendToFtp()
            stopWatch.stop()
        }

        AsaasLogger.info(stopWatch.prettyPrint())
    }

    public void createCorreiosBatchFileAndSendToFtp() {
    	if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return

		PaymentPostalServiceBatch.withNewTransaction { transaction ->
			try {
				List<Long> batchIdList = PaymentPostalServiceBatch.query([column: "id", status: PostalServiceBatchStatus.PENDING]).list()

				correiosBatchFileService.save(batchIdList)
				updateStatus(batchIdList, PostalServiceBatchStatus.PROCESSED)
			} catch(Exception exception) {
				transaction.setRollbackOnly()
				AsaasLogger.error("PaymentPostalServiceBatchService.createCorreiosBatchFileAndSendToFtp() >> Falha ao gerar remessa dos Correios.", exception)
				throw exception
			}
		}

		correiosBatchFileService.sendFileToCorreiosFtp()
    }

	private void createForAllCustomers() {
		for (Long customerId in Customer.listWithPaymentsAwaitingToBeProcessed()) {
			save(customerId, false)
		}
	}

	public Long save(Long customerId, Boolean ignoreEstimatedDate) {
		Customer.withNewTransaction { status ->
			try {
				Customer customer = Customer.get(customerId)

				PaymentPostalServiceValidator validator = new PaymentPostalServiceValidator(customer, Payment.list(customer, PostalServiceStatus.AWAITING_TO_BE_PROCESSED), ignoreEstimatedDate)
				validator.isPostalServiceBatchProcessing = true
				validator.processListOfPayments()

				PaymentPostalServiceBatch batch
				if (validator.paymentsWithoutInconsistencies.size() > 0) {
					batch = new PaymentPostalServiceBatch(customer: customer)

					associateBatchWithItems(batch, validator.paymentsWithoutInconsistencies)

                    batch.fee = batch.items.size() * PaymentPostalServiceFee.getFee(customer)

					batch.save(flush: true, failOnError: true)

					setReturnCode(batch)

                    BigDecimal feeWithDiscountApplied = promotionalCodeService.consumeFeeDiscountBalance(customer, batch.fee, batch)
                    batch.fee = feeWithDiscountApplied
                    batch.save(failOnError: true)

                    financialTransactionService.savePaymentPostalServiceBatchCreated(batch)

					for (Payment payment : batch.items.collect{ it.payment }) {
						payment.postalServiceStatus = PostalServiceStatus.PROCESSED
						Date estimatedDeliveryDate = CorreiosDeliveryTime.calculateEstimatedDeliveryDate(payment.customerAccount.city, true)
						timelineEventService.createPaymentPostalServiceSentEvent(payment, estimatedDeliveryDate)
					}

					messageService.notifyBatchFileCreated(batch)
				}

				updatePaymentsToInconsistenciesStatus(validator.paymentsWithInconsistencies.each{ it }.payment)

				sendInconsistenciesReportForCustomer(customer, validator.paymentsWithInconsistencies, validator.paymentsWithoutInconsistencies, false)
				sendSucessReportForCustomer(customer, validator.paymentsWithoutInconsistencies)

				return batch?.id
			} catch(Exception exception) {
				status.setRollbackOnly()
                AsaasLogger.error("PaymentPostalServiceBatchService.save() >> Falha ao processar pagamentos a serem enviados via Correios. Fornecedor = " + customerId, exception)

				return null
			}
		}
	}

	private void associateBatchWithItems(PaymentPostalServiceBatch batch, List<Payment> payments) {
		for (Payment payment : payments) {
			PaymentPostalServiceBatchItem item = new PaymentPostalServiceBatchItem(paymentPostalServiceBatch: batch, payment: payment)

			batch.addToItems(item)
		}
	}

	public void sendReport() {
		if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return

		for (Long id : Customer.listWithPaymentsAwaitingToBeProcessed()) {
			Customer.withNewTransaction { status ->
				try {
					Customer customer = Customer.get(id)

					PaymentPostalServiceValidator validator = new PaymentPostalServiceValidator(customer, Payment.list(customer, PostalServiceStatus.AWAITING_TO_BE_PROCESSED), false)
					validator.processListOfPayments()

					sendInconsistenciesReportForCustomer(customer, validator.paymentsWithInconsistencies, validator.paymentsWithoutInconsistencies, true)
				} catch(Exception exception) {
                    AsaasLogger.error("PaymentPostalServiceBatchService.sendReport() >> Falha ao gerar relatório para o customerId: ${id}", exception)
					status.setRollbackOnly()
				}
			}
		}
	}

	private void updatePaymentsToInconsistenciesStatus(List<Payment> payments) {
		if (payments.size() == 0) return

		Payment.executeUpdate("update Payment p set p.postalServiceStatus = :status, lastUpdated = :lastUpdated where p in (:payments)", [status: PostalServiceStatus.WITH_INCONSISTENCIES, payments: payments, lastUpdated: new Date()])
	}

	private void setReturnCode(PaymentPostalServiceBatch batch) {
		for (item in batch.items) {
			item.returnCode = generateReturnCode(item.id)
			item.save(failOnError: true)
		}
	}

	private String generateReturnCode(Long sequencialNumber) {
		String returnCode = AsaasApplicationHolder.config.asaas.correios.codigoCartaComercial + AsaasApplicationHolder.config.asaas.correios.codigoContrato + new Date().format("ddMMyy") + StringUtils.leftPad(sequencialNumber.toString(), 7, "0") + RandomStringUtils.randomNumeric(11)

		if (!returnCodeAlreadyExists(returnCode)) {
			return generateReturnCode()
		} else {
			return returnCode
		}
	}

	private Boolean returnCodeAlreadyExists(String returnCode) {
		return PaymentPostalServiceBatchItem.executeQuery("select count(id) from PaymentPostalServiceBatchItem where returnCode = :returnCode and deleted = false", [returnCode: returnCode]).size() > 0
	}

	private void sendInconsistenciesReportForCustomer(Customer customer, List<Map> paymentsAndReasons, List<Payment> paymentsWithoutInconsistencies, Boolean isBeforeSend) {
		if (paymentsAndReasons.size() == 0) return

		messageService.notifyProviderAboutPostalServiceBatchInconsistencies(customer, paymentsAndReasons, paymentsWithoutInconsistencies.size(), isBeforeSend)
	}

	private void sendSucessReportForCustomer(Customer customer, List<Payment> payments) {
		if (payments.size() == 0) return

		messageService.notifyProviderAboutPostalServiceBatchSuccess(customer, payments)
	}

	public void updateStatus(List<Long> paymentPostalServiceBatchIdList, PostalServiceBatchStatus status) {
		if (paymentPostalServiceBatchIdList.size() == 0) return

		PaymentPostalServiceBatch.executeUpdate("update PaymentPostalServiceBatch set status = :status, version = version + 1 where id in (:ids)", [status: status, ids: paymentPostalServiceBatchIdList])
	}

	public void processPaymentsToBeSent() {
    	if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return

		List<Long> paymentIds = Payment.listWithPendingPostalServiceStatus()

    	if (paymentIds.size() == 0) return

        AsaasLogger.info("PaymentPostalServiceBatchService.processPaymentsToBeSent() >> ${paymentIds.size()} Installment payments found to process and send to Correios")

		for (Long id: paymentIds) {
	    	Payment.withNewTransaction { status ->
				try {
	    			Payment payment = Payment.get(id)

	    			PostalServiceStatus postalServiceStatus = getPostalServiceStatusToDeliveryInTime(payment, true)

	    			if (postalServiceStatus != payment.postalServiceStatus) {
	    				Payment.executeUpdate("update Payment p set p.postalServiceStatus = :postalServiceStatus, lastUpdated = :lastUpdated where p = :payment", [postalServiceStatus: postalServiceStatus, payment: payment, lastUpdated: new Date()])
	    			}
		    	} catch (Exception exception) {
                    status.setRollbackOnly()
                    AsaasLogger.error("PaymentPostalServiceBatchService.processPaymentsToBeSent() >> Falha ao processar cobrança pendente de envio dos Correios PaymentId [${id}].", exception)
				}
		    }
		}
    }

	public validateMandatoryParams(entity, Map params) {
		if (!params.containsKey("billingType") || !params.billingType) {
			DomainUtils.addError(entity, "A forma de pagamento deve ser informada.")
		}

		if ((!params.containsKey("dueDate") || !params.dueDate) && (!params.containsKey("nextDueDate") || !params.nextDueDate) && (!params.containsKey("expirationDay") || !params.expirationDay)) {
			DomainUtils.addError(entity, "A data de vencimento da cobrança deve ser informada.")
		}

		return entity
	}

	public PostalServiceStatus getPostalServiceStatusToDeliveryInTime(Payment payment) {
		return getPostalServiceStatusToDeliveryInTime(payment, false)
	}

	public PostalServiceStatus getPostalServiceStatusToDeliveryInTime(Payment payment, Boolean isPostalServiceBatchProcessing) {
        Integer numberOfDaysToDueDate = CustomDateUtils.calculateDifferenceInDays(new Date(), payment.dueDate)
        Integer estimatedDaysToDelivery = CustomDateUtils.calculateDifferenceInDays(new Date(), CorreiosDeliveryTime.calculateEstimatedDeliveryDate(payment.customerAccount.city, isPostalServiceBatchProcessing))

       	if ((numberOfDaysToDueDate - 7) <= 15 || (numberOfDaysToDueDate - 7) <= estimatedDaysToDelivery) {
            return PostalServiceStatus.AWAITING_TO_BE_PROCESSED
        }

        return PostalServiceStatus.PENDING
    }
}
