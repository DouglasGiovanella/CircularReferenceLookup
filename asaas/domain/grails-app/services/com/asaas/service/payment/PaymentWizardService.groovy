package com.asaas.service.payment

import com.asaas.billinginfo.BillingType
import com.asaas.billinginfo.ChargeType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.GroupCustomerAccount
import com.asaas.domain.freepaymentconfig.FreePaymentConfig
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.exception.EmailInUseException
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationType
import com.asaas.payment.vo.WizardNotificationVO
import com.asaas.receipttype.ReceiptType
import com.asaas.utils.AbTestUtils
import com.asaas.utils.Utils
import com.asaas.wizard.receiptdata.PaymentWizardBankSlipAnticipationVO

import grails.transaction.Transactional
import grails.validation.ValidationException

import org.codehaus.groovy.grails.web.servlet.mvc.GrailsHttpSession

@Transactional
class PaymentWizardService {

    def asaasSegmentioService
    def asyncActionService
    def customerAccountGroupService
	def customerAccountService
    def freePaymentConfigCacheService
    def installmentService
    def notificationService
    def paymentDocumentService
    def paymentService
    def paymentWizardReceivableAnticipationService
    def pixAddressKeyService
    def receivableAnticipationService
    def receivableAnticipationValidationCacheService
    def subscriptionService

	public Payment save(Map wizardData, GrailsHttpSession session, Customer customer) throws Exception {
		try {
            Boolean customerAccountAlreadyExists = wizardData["customerData"].id ? true : false
            CustomerAccount customerAccount

            if (customerAccountAlreadyExists) {
                customerAccount = customerAccountService.update(customer.id, wizardData["customerData"]).customerAccount
            } else {
                customerAccount = customerAccountService.save(customer, null, wizardData["customerData"])
            }

            if (wizardData.customerData.containsKey("groupList")) {
                saveCustomerAccountGroup(customer, customerAccount, wizardData.customerData.groupList)
            }

			if (customerAccount.hasErrors()) {
				throw new ValidationException(null, customerAccount.errors)
			}

            WizardNotificationVO wizardNotificationVO = new WizardNotificationVO(customerAccount, customerAccountAlreadyExists, wizardData.sendType, wizardData.notificationsData)
            notificationService.saveWizardNotification(wizardNotificationVO)

			List<Payment> listOfPayment = []
			listOfPayment += savePaymentsAccordingToChargeType(customerAccount, wizardData)

			if (wizardData['paymentDocuments']) {
				paymentDocumentService.save(listOfPayment.sort{it.id}.first(), wizardData['paymentDocuments'])
			}

            BillingType billingType
            if (wizardData["detachedPaymentData"].size() > 0) {
                billingType = BillingType.valueOf(wizardData["detachedPaymentData"].billingType.toString())
            } else if (wizardData["installmentsData"].size() > 0) {
                billingType = BillingType.valueOf(wizardData["installmentsData"].billingType.toString())
            } else if (wizardData["subscriptionData"].size() > 0) {
                billingType = BillingType.valueOf(wizardData["subscriptionData"].billingType.toString())
            }

            Boolean isAutomaticAnticipationConfigActive = receivableAnticipationValidationCacheService.isAutomaticActivated(customer.id)

            if (wizardData.receiptData?.automaticAnticipation) {
                if (billingType.isCreditCard() && !isAutomaticAnticipationConfigActive) {
                    paymentWizardReceivableAnticipationService.saveCreditCardAnticipation(customer, listOfPayment)
                }

                if (billingType.isBoleto()) {
                    PaymentWizardBankSlipAnticipationVO bankSlipAnticipationVO = new PaymentWizardBankSlipAnticipationVO(listOfPayment, wizardData.receiptData.anticipationOptionList)
                    paymentWizardReceivableAnticipationService.saveBankSlipAnticipation(customer, bankSlipAnticipationVO)
                }
            }

			if (session && sendTypeIsPrint(wizardData["sendType"])) {
				session["newBoletoToken"] = listOfPayment.externalToken.join(",")
			}

            if (Boolean.valueOf(wizardData.createPixAddressKey)) {
                createPixAddressKey(customer)
            }

            trackPaymentPersistedSucess(customer, wizardData, wizardNotificationVO.sendTypeList, listOfPayment)

			return Payment.findLastPaymentFrom(customerAccount.id, customer.id)
		} catch (EmailInUseException emailException) {
			throw emailException
		} catch (ValidationException ve) {
			throw ve
		} catch (BusinessException be) {
			throw be
		} catch (Exception e) {
			AsaasLogger.error("PaymentWizardController.save", e)
			throw new RuntimeException(e.getMessage() ?: e.getClass().toString())
		}
	}

    def trackPaymentPersistedSucess(Customer customer, Map wizardData, List<NotificationType> selectedNotificationTypeList, List<Payment> listOfPayment) {
        try {
            def chargeTypeForMap = null
            def billingTypeForMap = null
            def valueForMap = null
            def cycleForMap = null
            def receiptType = null
            def createPaymentDunning = Boolean.valueOf(wizardData["createPaymentDunning"])
            Boolean hasDocument = wizardData["paymentDocuments"].asBoolean()
            Boolean createInvoice = wizardData["invoiceData"].asBoolean()
            Boolean hasIntest = Utils.toBigDecimal(wizardData["interestFine"]?.interest?.value).asBoolean()
            Boolean hasFine = Utils.toBigDecimal(wizardData["interestFine"]?.fine?.value).asBoolean()
            Boolean hasDiscount = Utils.toBigDecimal(wizardData["interestFine"]?.discount?.value).asBoolean()

            if (wizardData["receiptData"]?.automaticAnticipation) {
                receiptType = ReceiptType.ANTICIPATION.toString()
            } else {
                receiptType = ReceiptType.NOT_ANTICIPATION.toString()
            }

            if (wizardData["subscriptionData"]) {
                chargeTypeForMap = ChargeType.RECURRENT.toString()
                billingTypeForMap = wizardData["subscriptionData"].billingType.toString()
                valueForMap = wizardData["subscriptionData"].subscriptionValue
                cycleForMap = wizardData["subscriptionData"].subscriptionCycle
            }
            else if (wizardData["installmentsData"]) {
                chargeTypeForMap = ChargeType.INSTALLMENT.toString()
                billingTypeForMap = wizardData["installmentsData"].billingType.toString()
                valueForMap = wizardData.totalValue
            }
            else {
                chargeTypeForMap = ChargeType.DETACHED.toString()
                billingTypeForMap = wizardData["detachedPaymentData"].billingType.toString()
                valueForMap = wizardData["detachedPaymentData"].value
            }

            Map data = [
                'notificacao' : selectedNotificationTypeList.join(","),
                'cobranca': chargeTypeForMap,
                'pagamento': billingTypeForMap,
                'valor': valueForMap,
                'cycle': cycleForMap,
                'receiptType': receiptType,
                'createPaymentDunning': createPaymentDunning,
                'createInvoice': createInvoice,
                'hasDocument': hasDocument,
                'hasIntest': hasIntest,
                'hasFine': hasFine,
                'hasDiscount': hasDiscount,
                'paymentId' : listOfPayment.first().id
            ]

            asaasSegmentioService.track(customer.id, "wizard__payment_persisted_success", data)

            if (wizardData.undefinedBillingTypeConfig) {
                trackUndefinedBillingTypeConfig(customer, wizardData, listOfPayment.first())
            }
        } catch (Exception e) {
            AsaasLogger.error("PaymentWizard.trackPaymentPersistedSucess >> Falha ao realizar track", e)
        }
    }

    public Integer getFreePaymentsAmountIfHasMinimumForShowValueWithoutFee(Customer customer) {
        Integer freePaymentsAmount = freePaymentConfigCacheService.getFreePaymentsAmount(customer.id)
        if (freePaymentsAmount && freePaymentsAmount >= FreePaymentConfig.MINIMUM_FREE_PAYMENTS_AMOUNT_FOR_SHOW_VALUE_WITHOUT_FEE_ON_WIZARD) return freePaymentsAmount

        return null
    }

	private List<Payment> savePaymentsAccordingToChargeType(CustomerAccount customerAccount, Map wizardData) {
        List<Payment> listOfPayment = []
		if (wizardData["detachedPaymentData"].size() > 0) {
			listOfPayment = [saveDetachedPayment(customerAccount, wizardData, true)]
		}  else if (wizardData["subscriptionData"].size() > 0) {
			listOfPayment = saveSubscriptionPayments(customerAccount, wizardData, true)
		}  else if (wizardData["installmentsData"].size() > 0) {
			listOfPayment = saveInstallmentPayments(customerAccount, wizardData, true)
		}

		return listOfPayment
	}

	private Payment saveDetachedPayment(CustomerAccount customerAccount, Map wizardData, Boolean notifyPaymentCreatedImmediatelyIfEnabled) {
		Map params = wizardData["detachedPaymentData"].clone()
		params.customerAccount = customerAccount
		params.invoice =  wizardData['invoiceData']
		params.customerProduct  = wizardData['customerProductData']
		if (wizardData['interestFine']) {
			params.interest = wizardData['interestFine'].interest
			params.fine = wizardData['interestFine'].fine
			params.discount = wizardData['interestFine'].discount
		}

        params.createPaymentDunning = wizardData['createPaymentDunning']

        params.paymentUndefinedBillingTypeConfig = wizardData['undefinedBillingTypeConfig']

        return paymentService.save(params, true, notifyPaymentCreatedImmediatelyIfEnabled)
	}

	private List<Payment> saveSubscriptionPayments(CustomerAccount customerAccount, Map wizardData, Boolean notifyPaymentCreatedImmediatelyIfEnabled) {
		Map params = wizardData["subscriptionData"]

		params.invoice = wizardData['invoiceData']
		params.customerProduct = wizardData['customerProductData']
        params.notifyPaymentCreatedImmediately = notifyPaymentCreatedImmediatelyIfEnabled

		if (wizardData['interestFine']) {
			params.interest = wizardData['interestFine'].interest
			params.fine = wizardData['interestFine'].fine
			params.discount = wizardData['interestFine'].discount
		}

        params.subscriptionUndefinedBillingTypeConfig = wizardData['undefinedBillingTypeConfig']

		List<Payment> listOfPayments = []
		listOfPayments += subscriptionService.save(customerAccount, params, true).getPayments()

		return listOfPayments
	}

	private List<Payment> saveInstallmentPayments(CustomerAccount customerAccount, Map wizardData, Boolean notifyPaymentCreatedImmediatelyIfEnabled) {
		Map params = prepareInstallmentsData(wizardData["installmentsData"])

        if (wizardData.totalValue) params.totalValue = Utils.toBigDecimal(wizardData.totalValue)

        params.customerAccount = customerAccount
		params.invoice = wizardData['invoiceData']
		params.customerProduct = wizardData['customerProductData']
		if (wizardData['interestFine']) {
			params.interest = wizardData['interestFine'].interest
			params.fine = wizardData['interestFine'].fine
			params.discount = wizardData['interestFine'].discount
		}

        params.paymentUndefinedBillingTypeConfig = wizardData['undefinedBillingTypeConfig']

        Installment installment = installmentService.save(params, notifyPaymentCreatedImmediatelyIfEnabled, true)
		return Payment.query([installmentId: installment.id, sort: "id", order: "asc"]).list()
	}

	private Map prepareInstallmentsData(Map params) {
		return [
			dueDate: params.dueDate,
			value: params.value,
			installmentCount: params.installmentCount,
			billingType: params.billingType as BillingType,
			description: params.description,
			postalService: params.sendPaymentByPostalService.asBoolean()
		]
	}

	private Boolean sendTypeIsPrint(def sendType) {
		if (sendType instanceof String && sendType == "PRINT")
			return true

		if (sendType instanceof List && "PRINT" in sendType)
			return true

		return false
	}

    private void saveCustomerAccountGroup(Customer customer, CustomerAccount customerAccount, List<Map> groupList) {
        List<Long> groupIdListBefore = GroupCustomerAccount.query([column: "group.id", customerAccount: customerAccount]).list()
        List<Long> groupIdListAfter = groupList.collect { Long.valueOf(it.id) }

        List<Long> commonsGroupIdList = groupIdListBefore.intersect(groupIdListAfter)

        List<Long> removedGroupIdList = []
        removedGroupIdList.addAll(groupIdListBefore)
        removedGroupIdList.removeAll(commonsGroupIdList)
        for (Long removedGroupId in removedGroupIdList) {
            customerAccountGroupService.unassociateCustomerAccount(customer, removedGroupId, customerAccount.id)
        }

        List<Long> addedGroupIdList = []
        addedGroupIdList.addAll(groupIdListAfter)
        addedGroupIdList.removeAll(commonsGroupIdList)
        for (Long addedGroupId in addedGroupIdList) {
            String groupName = groupList.find { it.id == addedGroupId }.name
            customerAccountGroupService.associateCustomerAccount(customer, addedGroupId, groupName, customerAccount.id)
        }
    }

    private void createPixAddressKey(Customer customer) {
        if (!pixAddressKeyService.canCreateAutomaticCustomerPixAddressKey(customer)) return

        asyncActionService.saveCreatePixAddressKey(customer)
    }

    private void trackUndefinedBillingTypeConfig(Customer customer, Map wizardData, Payment payment) {
        if (AbTestUtils.hasPaymentWizardUndefinedBillingConfig(customer)) {
            List<String> selectedBillingTypes = []

            Boolean isBankSlipAllowed = Boolean.valueOf(wizardData.undefinedBillingTypeConfig.isBankSlipAllowed)
            Boolean isPixAllowed = Boolean.valueOf(wizardData.undefinedBillingTypeConfig.isPixAllowed)
            Boolean isCreditCardAllowed = Boolean.valueOf(wizardData.undefinedBillingTypeConfig.isCreditCardAllowed)

            if (isPixAllowed && isCreditCardAllowed && !isBankSlipAllowed) {
                selectedBillingTypes.addAll([BillingType.PIX.toString(), BillingType.MUNDIPAGG_CIELO.toString()])
            } else if (isPixAllowed && !isCreditCardAllowed && !isBankSlipAllowed) {
                selectedBillingTypes.add(BillingType.PIX.toString())
            } else if (isPixAllowed && isCreditCardAllowed && isBankSlipAllowed) {
                selectedBillingTypes.addAll([BillingType.PIX.toString(), BillingType.MUNDIPAGG_CIELO.toString(), BillingType.BOLETO.toString()])
            }

            if (!selectedBillingTypes) return

            Map trackObject = [:]

            trackObject.chargeType = payment.chargeType.toString()
            trackObject.billingType = payment.billingType.toString()
            trackObject.value = wizardData.totalValue
            trackObject.selectedBillingTypes = selectedBillingTypes.join(",")

            asaasSegmentioService.track(customer.id, "wizard_undefined_selected_billingtypes", trackObject)
        }
    }
}
