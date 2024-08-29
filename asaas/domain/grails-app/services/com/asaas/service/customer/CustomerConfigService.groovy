package com.asaas.service.customer

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerConfig
import com.asaas.exception.BusinessException
import com.asaas.notification.NotificationMessageType
import com.asaas.utils.CustomDateUtils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional
import grails.util.Environment

@Transactional
class CustomerConfigService {

    def cardAbuseRiskAlertService
    def customerAdminService
    def customerInteractionService
    def customerMessageService
    def notificationDispatcherCustomerOutboxService
    def notificationService
    def paymentFloatService

	public CustomerConfig save(Customer customer) {
		CustomerConfig customerConfig = CustomerConfig.findOrCreateByCustomer(customer)
		customerConfig.save(flush: true, failOnError: true)

		if (Environment.getCurrent().equals(Environment.PRODUCTION)) {
			customerConfig.boletoBlockDate = new Date()
		}

		return customerConfig
	}

	public void extendBoletoBlockDate(Customer customer, Integer daysToExtend, Boolean saveCustomerInteraction) {
        BusinessValidation businessValidation = customerAdminService.validateIfCanUnblockOrExtendBoleto(customer)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

		CustomerConfig customerConfig = customer.customerConfig

		Calendar blockDate = CustomDateUtils.getInstanceOfCalendar()

		if (blockDate.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY) {
			daysToExtend = daysToExtend + 2
		} else if (blockDate.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) {
			daysToExtend = daysToExtend + 2
		} else if (blockDate.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
			daysToExtend = daysToExtend + 1
		}

		blockDate.add(Calendar.DAY_OF_MONTH, daysToExtend)

		customerConfig.boletoBlockDate = blockDate.getTime()

		customerConfig.save(failOnError: true)

		if(saveCustomerInteraction) {
			customerInteractionService.saveExtendBoletoBlockDate(customer.id, daysToExtend)
		}
	}

	public void blockBoleto(Long customerId) {
		CustomerConfig customerConfig = CustomerConfig.findByCustomer(Customer.get(customerId))

		customerConfig.boletoBlockDate = new Date()
		customerConfig.save(failOnError: true)

		customerInteractionService.saveBoletoBlock(customerId)
	}

    public void unblockBoleto(Customer customer) {
        BusinessValidation businessValidation = customerAdminService.validateIfCanUnblockOrExtendBoleto(customer)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        CustomerConfig customerConfig = CustomerConfig.findByCustomer(customer)

        customerConfig.boletoBlockDate = null
        customerConfig.save(failOnError: true)

        customerInteractionService.saveBoletoUnblock(customer)
    }

	public void enableOrDisableGrossValue(Customer customer, Boolean enabled) {
		CustomerConfig customerConfig = CustomerConfig.findByCustomer(customer)

		customerConfig.grossValueEnabled = enabled
		customerConfig.save(failOnError: true)
	}

    public void changeNotificationMessageType(Customer customer, NotificationMessageType notificationMessageType) {
        customer.customerConfig.notificationMessageType = notificationMessageType
        customer.customerConfig.save(failOnError: true)

        notificationService.updateTemplatesAccordingToTheNotificationMessageType(customer, notificationMessageType)
        notificationDispatcherCustomerOutboxService.onCustomerUpdated(customer)
    }

	public CustomerConfig toggleTransferValidationBypass(Long customerId, Boolean allow) {
		Customer customer = Customer.get(customerId)

		CustomerConfig customerConfig = customer.getConfig()
		customerConfig.transferValidationBypassAllowed = allow
		customerConfig.save(flush: false, failOnError: false)

		if (customerConfig.hasErrors()) return customerConfig

		customerInteractionService.saveToggleTransferValidationBypass(customer, customerConfig.transferValidationBypassAllowed)

		return customerConfig
	}

    public void setImmediateCheckout(Long customerId) {
		Customer customer = Customer.get(customerId)
		CustomerConfig customerConfig = customer.getConfig()

		if (customerConfig.immediateCheckout) return

		customerConfig.immediateCheckout = true
        customerConfig.paymentFloat = CustomerConfig.DEFAULT_PAYMENT_FLOAT

		if (customer.accountOwner) {
			CustomerConfig accountOwnerConfig = customer.accountOwner.getConfig()
			if (accountOwnerConfig.immediateCheckout) {
				customerConfig.paymentFloat = accountOwnerConfig.paymentFloat
			}
		}

		customerConfig.save(failOnError: true)
    }

    public void updatePaymentFloat(Long customerId, Integer paymentFloat, String reason) {
        if (!reason) throw new BusinessException("É necessário informar o motivo da alteração.")

		Customer customer = Customer.get(customerId)
		CustomerConfig customerConfig = customer.getConfig()

		if (customerConfig.paymentFloat == paymentFloat) return
        validateUpdatePaymentFloat(customer, paymentFloat)

		Integer previousPaymentFloat = customerConfig.paymentFloat

		customerConfig.paymentFloat = paymentFloat
		customerConfig.save(failOnError: true)

        paymentFloatService.updateCreditDateForNotCreditedPayments(customer)

        Boolean paymentFloatHasBeenIncreased = customerConfig.paymentFloat > previousPaymentFloat
        customerMessageService.notifyPaymentFloatUpdate(customer, paymentFloatHasBeenIncreased)

        String description = "Alteração do tempo para disponibilização do saldo de cobranças confirmadas de ${previousPaymentFloat} para ${customerConfig.paymentFloat} dias úteis. ${reason}"
        customerInteractionService.save(customer, description)

		List<Customer> childAccountsList = Customer.childAccounts(customer, [:]).list()

		for (Customer childAccount : childAccountsList) {
            updatePaymentFloat(childAccount.id, paymentFloat, "Alteração via conta pai.")
		}
    }

    private void validateUpdatePaymentFloat(Customer customer, BigDecimal paymentFloat) {
		CustomerConfig customerConfig = customer.getConfig()

        if (paymentFloat == null) {
            throw new BusinessException("Informe uma quantidade de dias válida.")
        }

		if (paymentFloat > 30) {
            throw new BusinessException("A quantidade de dias máxima é 30.")
        }
    }

    public CustomerConfig saveToggleClearSale(Long customerId, Boolean disable, Boolean fromAccountOwner) {
        Customer customer = Customer.get(customerId)

        CustomerConfig customerConfig = customer.customerConfig
        customerConfig.clearSaleDisabled = disable
        customerConfig.save(flush: false, failOnError: false)

        if (customerConfig.hasErrors()) return customerConfig

        customerInteractionService.saveClearSaleToggled(customerId, disable, fromAccountOwner)

        return customerConfig
    }

    public void saveOverrideAsaasSoftDescriptor(Long customerId, Boolean enabled, Boolean fromAccountOwner) {
        Customer customer = Customer.get(customerId)

		CustomerConfig customerConfig = customer.customerConfig
		customerConfig.overrideAsaasSoftDescriptor = enabled
		customerConfig.save(failOnError: true)

        customerInteractionService.saveUpdateSoftDescriptor(customer, enabled, fromAccountOwner)
    }

    public CustomerConfig updateCardTransactionBlockExpirationDate(Customer customer, String reason, Date blockExpirationDate, Boolean block) {
        CustomerConfig customerConfig = customer.getConfig()

        validateCardTransactionBlockExpirationDate(customerConfig.creditCardTransactionReleaseDate, blockExpirationDate, block)

        if (block) cardAbuseRiskAlertService.saveIfNecessary(customer, block, blockExpirationDate)

        customerConfig.creditCardTransactionReleaseDate = blockExpirationDate
        customerConfig.save(failOnError: false)

        customerInteractionService.saveCardTransactionBlockInteraction(customer, reason, block, blockExpirationDate)

        return customerConfig
    }

    public void updateNotificationDisabled(Customer customer, Boolean disabled) {
        CustomerConfig customerConfig = CustomerConfig.query([customerId: customer.id]).get()

        customerConfig.notificationDisabled = disabled
        customerConfig.save(failOnError: true)

        notificationDispatcherCustomerOutboxService.onCustomerUpdated(customer)
    }

    private void validateCardTransactionBlockExpirationDate(Date currentBlockDate, Date newBlockDate, Boolean block) {
        if (block) {
            if (newBlockDate == currentBlockDate) throw new BusinessException("O período de bloqueio informado é igual ao período atualmente cadastrado: ${currentBlockDate}")

            if (newBlockDate <= new Date()) throw new BusinessException("Período de bloqueio inválido")
        } else {
            if (!currentBlockDate || currentBlockDate <= new Date()) throw new BusinessException("O cliente não possuí um período de bloqueio ativo no momento")

            if (newBlockDate) throw new BusinessException("Para desbloquear o cliente não informe data de bloqueio")
        }
    }
}
