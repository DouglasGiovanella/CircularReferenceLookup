package com.asaas.service.payment

import com.asaas.asyncaction.AsyncActionType
import com.asaas.billinginfo.BillingType
import com.asaas.boleto.BoletoUtils
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardAcquirerOperationEnum
import com.asaas.creditcard.CreditCardBrand
import com.asaas.creditcard.CreditCardGateway
import com.asaas.creditcardacquireroperation.CreditCardAcquirerOperationStatus
import com.asaas.domain.bank.Bank
import com.asaas.domain.billinginfo.CreditCardInfoCde
import com.asaas.domain.creditcard.CreditCardAcquirerOperation
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.creditcard.CreditCardTransactionOrigin
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.debitcard.DebitCardTransactionInfo
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.linhadigitavel.StantardBillLinhaDigitavelValidator
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentBuilder
import com.asaas.payment.PaymentStatus
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.codehaus.groovy.grails.commons.DefaultGrailsDomainClass
import org.codehaus.groovy.grails.orm.hibernate.cfg.NamedCriteriaProxy
import org.hibernate.criterion.CriteriaSpecification

@Transactional
class PaymentAdminService {

    def asyncActionService
    def paymentConfirmRequestService
    def paymentService

    public NamedCriteriaProxy getCriteria(Map search) {
        return new NamedCriteriaProxy(criteriaClosure: paymentListCriteria(search), domainClass: new DefaultGrailsDomainClass(Payment))
    }

    public Map processPaymentReceiving(params) {
        if (CustomDateUtils.fromString(params.paymentDate) > new Date().clearTime()) throw new BusinessException("A data de pagamento não pode ser definida para o futuro!")

        List paymentInfoList = []
        Map paymentInfo = [:]

        Payment payment = Payment.get(params.long("id"))
        try {
            if (!payment.nossoNumero) {
                payment.nossoNumero = PaymentBuilder.buildNossoNumero(payment)
                payment.automaticRoutine = true
                payment.save(failOnError: true)
            }

            paymentInfo.id = payment.id
            paymentInfo.nossoNumero = payment.nossoNumero
            paymentInfo.value = params.value
            paymentInfo.date = params.paymentDate
            paymentInfo.creditDate = CustomDateUtils.fromString(params.paymentDate)

            paymentInfoList.add(paymentInfo)

            Map confirmResponse = paymentConfirmRequestService.receive(BillingType.convert(params.billingType), Bank.findByCode(params.bank), paymentInfoList)
            if (!confirmResponse.success) transactionStatus.setRollbackOnly()
            return confirmResponse
        } catch (Exception e) {
            transactionStatus.setRollbackOnly()
            throw e
        }
    }

    public Map buildCreditCardSettlementValuesInfo(Map search) {
        Map totalizerMap = [:]
        if (!Utils.toBoolean(search.showTotalizers)) return totalizerMap

        if (canShowValueTotalizerInCreditCardReceipt(search)) {
            List<PaymentStatus> statusList = [PaymentStatus.RECEIVED, PaymentStatus.REFUNDED]
            totalizerMap.assasPaidTotalValue = sumPaymentValue(search + ["statusList" : statusList]) ?: 0

            statusList += [PaymentStatus.CONFIRMED, PaymentStatus.CHARGEBACK_REQUESTED]
            totalizerMap.asaasEstimatedTotalValue = sumPaymentValue(search + ["creditDate[isNotNull]" : true, "statusList" : statusList ]) ?: 0
            totalizerMap.acquirerEstimatedTotalValue = sumPaymentValue(search + ["estimatedCreditDate[isNotNull]" : true, "statusList" : statusList]) ?: 0
            totalizerMap.acquirerReceivedTotalValue = sumCreditCardAcqurierOperationValue(search) ?: 0
            return totalizerMap
        } else {
            throw new BusinessException("Para exibir o totalizador, é necessário informar um dos filtros: TID, Nr. Fatura ou data Crédito com intervalo máximo de 1 mês.")
        }
    }

    public Boolean canShowValueTotalizerInCreditCardReceipt(Map params) {
        Map filtersMap = params.findAll { it.value }

        Integer limitDaysToTotalizer = 31
        if (filtersMap.creditDateStart && filtersMap.creditDateFinish) {
            if (CustomDateUtils.calculateDifferenceInDays(CustomDateUtils.fromString(filtersMap.creditDateStart), CustomDateUtils.fromString(filtersMap.creditDateFinish)) <= limitDaysToTotalizer) return true
        }

        List<String> necessaryFiltersToShowTotalizersList = ["creditCardTid", "id"]

        return filtersMap.keySet().any { necessaryFiltersToShowTotalizersList.contains(it) }
    }

    public void saveDeletePendingOrOverduePaymentAsyncAction(Customer customer, Long userId) {
        if (!customer) return

        Map asyncActionData = [customerId: customer.id, userId: userId]
        asyncActionService.save(AsyncActionType.DELETE_PENDING_OR_OVERDUE_PAYMENT, asyncActionData)
    }

    public void deletePendingOrOverduePayment() {
        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.DELETE_PENDING_OR_OVERDUE_PAYMENT, maxItemsPerCycle)
        if (!asyncActionDataList) return

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(asyncActionData.customerId)
                User user = User.read(asyncActionData.userId)
                Boolean allPaymentsWereDeleted = paymentService.deleteAllPaymentsAwaitingConfirmation(customer, user)

                if (allPaymentsWereDeleted) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [
                ignoreStackTrace: true,
                onError: { Exception exception ->
                    AsaasLogger.error("PaymentAdminService.deletePendingOrOverduePayment >> Falha ao deletar cobranças pendentes ou vencidas [${asyncActionData.asyncActionId}]", exception)
                    asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }
            ])
        }
    }

    def list(Integer max, Integer offset, search) {
		return Payment.createCriteria().list([max: max, offset: offset], paymentListCriteria(search))
	}

	def countAdmin(search) {
		return Payment.createCriteria().count(paymentListCriteria(search))
	}

	private Closure paymentListCriteria(Map params) {
        return {
            params.statusList = normalizeStatusFilter(params)

			if (params.providerName || params.providerEmail) {
				createAlias('provider','provider')
			}

            if (params.providerId) {
				eq("provider.id", Utils.toLong(params.providerId))
			}

			if (params.providerEmail) {
				eq("provider.email", params.providerEmail)
			}

			if (params.providerName) {
				or {
					like("provider.name", "%" + params.providerName + "%")
					like("provider.company", "%" + params.providerName + "%")
					like("provider.email", "%" + params.providerName + "%")
				}
			}

            if (params.customerAccountName || params.customerAccountEmail) {
                Map customerAccountSearch = [:]
                if (params.providerId) customerAccountSearch.customerId = Utils.toLong(params.providerId)

                customerAccountSearch.email = params.customerAccountEmail
                customerAccountSearch."name[like%]" = params.customerAccountName

                List customerAccountList = CustomerAccount.query(customerAccountSearch + [column: "id", disableSort: true, deleted: true]).list()

                if (customerAccountList) {
                    'in'("customerAccount.id", customerAccountList)
                } else {
                    eq("id", -1L)
                }
            }

            if (params.containsKey("customerAccountId")) {
                eq("customerAccount.id", Utils.toLong(params.customerAccountId))
            }

			if (params.id) {
				eq("id", Utils.toLong(params.id.trim()))
			}

			if (params.containsKey("receivableUnitId")) {
				exists ReceivableUnitItem.where {
					setAlias("receivableUnitItem")
					eqProperty("receivableUnitItem.payment.id", "this.id")
					eq("deleted", false)
					eq("receivableUnitItem.receivableUnit.id", Utils.toLong(params.receivableUnitId))
				}.id()
			}

			if (params.debitCardTid || params.readyForDebitCardReceiptOnly) {
                exists DebitCardTransactionInfo.where {
                    setAlias("debitCardTransactionInfo")

                    eqProperty("debitCardTransactionInfo.payment.id", "this.id")
                    eq("debitCardTransactionInfo.deleted", false)

                    if (params.debitCardTid) eq("debitCardTransactionInfo.transactionIdentifier", params.debitCardTid)
                    if (params.readyForDebitCardReceiptOnly) isNotNull("debitCardTransactionInfo.transactionIdentifier")
                }.id()
			}

			if (params.creditCardBrand || params.creditCardHash || params.creditCardLastDigits) {
				createAlias('billingInfo','billingInfo')
				createAlias('billingInfo.creditCardInfo','creditCardInfo')
			}

			if (params.nossoNumero) {
                eq("nossoNumero", params.nossoNumero.trim())
			}

			if (params.billingType) {
				and { eq("billingType", BillingType.convert(params.billingType)) }
			}

            if (!Utils.isEmptyOrNull(params.anticipated)) {
               eq("anticipated", Boolean.valueOf(params.anticipated))
            }

            if (Utils.isEmptyOrNull(params.deletedOnly)) {
                eq("deleted", false)
            } else if (Boolean.valueOf(params.deletedOnly)) {
                eq("deleted", true)
            }

            if (params.statusList) {
                or {
                    params.statusList.each { PaymentStatus paymentStatus ->
                        if (!paymentStatus) return

                        if (paymentStatus == PaymentStatus.CHARGEBACK) {
                            and {
                                eq("status", PaymentStatus.REFUNDED)
                                exists CreditCardTransactionInfo.where {
                                    setAlias("creditCardTransactionInfo")
                                    eqProperty("creditCardTransactionInfo.payment.id", "this.id")
                                    eq("creditCardTransactionInfo.chargeback", true)
                                    eq("creditCardTransactionInfo.deleted", false)
                                }.id()
                            }
                            return
                        }

                        if (paymentStatus.isRefunded()) {
                            and {
                                eq("status", PaymentStatus.REFUNDED)
                                notExists CreditCardTransactionInfo.where {
                                    setAlias("creditCardTransactionInfo")
                                    eqProperty("creditCardTransactionInfo.payment.id", "this.id")
                                    eq("creditCardTransactionInfo.chargeback", true)
                                    eq("creditCardTransactionInfo.deleted", false)
                                }.id()
                            }
                            return
                        }

                        eq("status", paymentStatus)
                    }
                }
            }

			if (params.paymentDateStart) {
				and { ge("paymentDate", Date.parse("dd/MM/yyyy", params.paymentDateStart)) }
			}

			if (params.paymentDateFinish) {
				and { le("paymentDate", CustomDateUtils.setTimeToEndOfDay(params.paymentDateFinish)) }
			}

			if (params.paymentDate) {
				and { eq("paymentDate", Date.parse("dd/MM/yyyy", params.paymentDate)) }
			}

			if (params.confirmedDate) {
				and { eq("confirmedDate", Date.parse("dd/MM/yyyy", params.confirmedDate)) }
			}

            if (params.confirmedDateStart) {
                and { ge("confirmedDate", Date.parse("dd/MM/yyyy", params.confirmedDateStart)) }
            }

            if (params.confirmedDateFinish) {
                and { le("confirmedDate", Date.parse("dd/MM/yyyy", params.confirmedDateFinish)) }
            }

            if (params.estimatedCreditDateStart) {
                and { ge("estimatedCreditDate", Date.parse("dd/MM/yyyy", params.estimatedCreditDateStart)) }
            }

            if (params.estimatedCreditDateFinish) {
                and { le("estimatedCreditDate", Date.parse("dd/MM/yyyy", params.estimatedCreditDateFinish)) }
            }

            if (params."estimatedCreditDate[isNotNull]") {
                isNotNull("estimatedCreditDate")
            }

            if (params.creditDateStart) {
                and { ge("creditDate", Date.parse("dd/MM/yyyy", params.creditDateStart)) }
            }

            if (params.creditDateFinish) {
                and { le("creditDate", Date.parse("dd/MM/yyyy", params.creditDateFinish)) }
            }
            if (params."creditDate[isNotNull]") {
                isNotNull("creditDate")
            }

			if (params.'dateCreated[ge]') {
				ge("dateCreated", CustomDateUtils.fromString(params.'dateCreated[ge]'))
			}

			if (params.'dateCreated[le]') {
				le("dateCreated", CustomDateUtils.setTimeToEndOfDay(params.'dateCreated[le]'))
			}

			if (params.'dueDate[ge]') {
				ge("dueDate", CustomDateUtils.fromString(params.'dueDate[ge]'))
			}

			if (params.'dueDate[le]') {
				le("dueDate", CustomDateUtils.setTimeToEndOfDay(params.'dueDate[le]'))
			}

			if (params.creditCardTid) {
				eq("creditCardTid", params.creditCardTid)
			}

            if (params.publicId) {
                eq("publicId", params.publicId)
            }

			if (params.creditCardUsn) {
				and { eq("creditCardUsn", Utils.toLong(params.creditCardUsn)) }
			}

			if (params.readyForCreditCardReceiptOnly) {
				isNotNull("creditCardTid")
			}

			if (params.creditCardBrand) {
				and { eq("creditCardInfo.brand", CreditCardBrand.valueOf(params.creditCardBrand))}
			}

			if (params.creditCardHash) {
                "in"("creditCardInfo.id", CreditCardInfoCde.query([column: "creditCardInfoId", cardHash: params.creditCardHash]).list())
			}

            if (params.creditCardLastDigits) {
                eq("creditCardInfo.lastDigits", params."creditCardLastDigits")
            }

			if (params.'value[le]') {
				and { le("value", params.'value[le]' instanceof BigDecimal ?: Utils.toBigDecimal(params.'value[le]')) }
			}

			if (params.'value[ge]') {
				and { ge("value", params.'value[ge]' instanceof BigDecimal ?: Utils.toBigDecimal(params.'value[ge]')) }
			}

			if (params.identificationField) {
				String nossoNumero = extractNossoNumeroFromIdentificationField(params.identificationField)
                eq("nossoNumero", nossoNumero)

				createAlias('boletoBank', 'boletoBank', CriteriaSpecification.LEFT_JOIN)

				Bank bank = extractBankFromIdentificationField(params.identificationField)
				if (bank.code == SupportedBank.SANTANDER.code()) {
					or {
						isNull("boletoBank")
                        'in'("boletoBank.id", [Payment.SANTANDER_BOLETO_BANK_ID, Payment.SANTANDER_ONLINE_BOLETO_BANK_ID])
					}
				} else if (bank.code == SupportedBank.SICREDI.code()) {
					eq ("boletoBank.id", Payment.SICREDI_BOLETO_BANK_ID)
				} else if (bank.code == SupportedBank.ITAU.code()) {
					eq ("boletoBank.id", Payment.SAFRA_ITAU_BOLETO_BANK_ID)
				}
			}

            if (params.containsKey("device")) {
                List<Map> paymentAndInstallmentIdList = CreditCardTransactionOrigin.query([
                    columnList: ["payment.id", "installment.id"],
                    device: params.device
                ]).list()

                List<Long> installmentIdList = []
                List<Long> paymentIdList = []
                for (Map paymentAndInstallmentId : paymentAndInstallmentIdList) {
                    if (paymentAndInstallmentId."payment.id") {
                        paymentIdList.add(paymentAndInstallmentId."payment.id")
                    } else {
                        installmentIdList.add(paymentAndInstallmentId."installment.id")
                    }
                }

                or {
                    if (paymentIdList) 'in'("id", paymentIdList)
                    if (installmentIdList) 'in'("installment.id", installmentIdList)
                }
            }

            if (joinWithCreditCardTransactionInfo(params)) {
                exists CreditCardTransactionInfo.where {
                    setAlias("creditCardTransactionInfo")

                    eqProperty("creditCardTransactionInfo.payment.id", "this.id")

                    if (params.containsKey("creditCardAcquirer")) {
                        eq("creditCardTransactionInfo.acquirer", CreditCardAcquirer.parse(params.creditCardAcquirer.toString()))
                    }

                    if (params.containsKey("creditCardGateway")) {
                        eq("creditCardTransactionInfo.gateway", CreditCardGateway.valueOf(params.creditCardGateway.toString()))
                    }

                    if (params.acquirerCreditDateStart) {
                        ge("creditCardTransactionInfo.creditDate", Date.parse("dd/MM/yyyy", params.acquirerCreditDateStart))
                    }

                    if (params.acquirerCreditDateFinish) {
                        le("creditCardTransactionInfo.creditDate", Date.parse("dd/MM/yyyy", params.acquirerCreditDateFinish))
                    }

                    if (params."acquirerCreditDate[isNotNull]") {
                        isNotNull("creditCardTransactionInfo.creditDate")
                    }

                    eq("creditCardTransactionInfo.deleted", false)
                }.id()
            }

			if (params.sort) {
				order(params.sort, params.order)
			}
        }
	}

	private extractBankFromIdentificationField(String identificationField) {
		identificationField = Utils.removeNonNumeric(identificationField)

		String bankCode = identificationField.substring(0, 3)

		return Bank.query([code: bankCode]).get()
	}

	private String extractNossoNumeroFromIdentificationField(String identificationField) {
        identificationField = Utils.removeNonNumeric(identificationField)

        StantardBillLinhaDigitavelValidator linhaDigitavelValidator = new StantardBillLinhaDigitavelValidator(identificationField)
        linhaDigitavelValidator.check()

        Map identificationFieldDataMap

        if (identificationField.length() == BoletoUtils.LINHA_DIGITAVEL_LENGTH) {
            identificationFieldDataMap = BoletoUtils.buildLinhaDigitavelDataMap(identificationField)
        } else {
            identificationFieldDataMap = BoletoUtils.buildBarCodeDataMap(identificationField)
        }

        if (identificationFieldDataMap.nossoNumero) return identificationFieldDataMap.nossoNumero

		return "ZZZ"
	}

    private Boolean joinWithCreditCardTransactionInfo(Map search) {
        if (search.containsKey("creditCardAcquirer")) return true
        if (search.containsKey("creditCardGateway")) return true
        if (search.containsKey("acquirerCreditDateStart")) return true
        if (search.containsKey("acquirerCreditDateFinish")) return true
        if (search.containsKey("acquirerCreditDate[isNotNull]")) return true

        return false
    }

    private List<PaymentStatus> normalizeStatusFilter(Map params) {
        List statusList = []

        if (params.status) {
            statusList = params.status.toString().split(",") as List<String>
            params.remove("status")
        }
        if (params.statusList) statusList.addAll(params.statusList)

        return statusList.collect { PaymentStatus.convert(it) }
    }

    private BigDecimal sumPaymentValue(Map params) {
        Closure builtCriteria = paymentListCriteria(params) << {
            projections {
                sqlProjection("sum(this_.value) as total", ["total"], [BIG_DECIMAL])
            }
        }

        return Payment.createCriteria().get(builtCriteria)
    }

    private BigDecimal sumCreditCardAcqurierOperationValue(Map search) {
        Map credCardAcqurierOperationFilter = [:]
        if (search.creditDateStart) credCardAcqurierOperationFilter."dateCreated[ge]" = search.creditDateStart
        if (search.creditDateFinish) credCardAcqurierOperationFilter."dateCreated[le]" = CustomDateUtils.setTimeToEndOfDay(search.creditDateFinish)
        if (search.creditCardTid) credCardAcqurierOperationFilter.transactionIdentifier = search.creditCardTid
        if (search.id) credCardAcqurierOperationFilter.paymentId = Utils.toLong(search.id)

        if (!credCardAcqurierOperationFilter) return BigDecimal.ZERO

        return CreditCardAcquirerOperation.sumValue(credCardAcqurierOperationFilter + ["status[in]": [CreditCardAcquirerOperationStatus.PROCESSED, CreditCardAcquirerOperationStatus.ERROR, CreditCardAcquirerOperationStatus.IGNORED], operation: CreditCardAcquirerOperationEnum.DEPOSIT]).get()
    }
}
