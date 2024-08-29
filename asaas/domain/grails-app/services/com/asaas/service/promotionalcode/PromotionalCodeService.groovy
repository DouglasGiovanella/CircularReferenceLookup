package com.asaas.service.promotionalcode

import com.asaas.benefit.enums.BenefitStatus
import com.asaas.benefit.repository.BenefitItemRepository
import com.asaas.benefit.repository.BenefitRepository
import com.asaas.customer.customerbenefit.adapter.CreateCustomerBenefitAdapter
import com.asaas.domain.Referral
import com.asaas.domain.benefit.Benefit
import com.asaas.domain.benefit.BenefitItem
import com.asaas.domain.bill.Bill
import com.asaas.domain.chargedfee.ChargedFee
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.payment.Payment
import com.asaas.domain.postalservice.PaymentPostalServiceBatch
import com.asaas.domain.productpromotion.ProductPromotion
import com.asaas.domain.promotionalcode.PromotionalCode
import com.asaas.domain.promotionalcode.PromotionalCodeGroup
import com.asaas.domain.promotionalcode.PromotionalCodeUse
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.refundrequest.RefundRequest
import com.asaas.exception.BusinessException
import com.asaas.exception.InvalidPromotionalCodeException
import com.asaas.log.AsaasLogger
import com.asaas.promotionalcode.PromotionalCodeType
import com.asaas.promotionalcode.adapter.PromotionalCodeAdapter
import com.asaas.promotionalcodegroup.cache.PromotionalCodeGroupCacheVO
import com.asaas.referral.adapter.SaveReferralAdapter
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

import org.apache.commons.lang.RandomStringUtils

@Transactional
class PromotionalCodeService {

    def asaasSegmentioService
    def customerBenefitService
	def customerInteractionService
    def createReferralService
	def grailsApplication
    def promotionalCodeGroupService
    def messageService

	public PromotionalCode findByCode(String code) {
        return PromotionalCode.availableCodeToBeUsed(code, [:]).get()
	}

	public Boolean validateFeeDiscountPromotionalCode(String code) {
        return PromotionalCode.availableCodeToBeUsed(code, [exists: true, promotionalCodeType: PromotionalCodeType.FEE_DISCOUNT,  "customer[isNull]": true]).get().asBoolean()
	}

	public PromotionalCode findDetachedPromotionalCodeByCode(String code) {
        return PromotionalCode.availableCodeToBeUsed(code, ["customer[isNull]": true]).get()
	}

    public List<Map> getFeeDiscountValueAvailableWithEndDate(Long customerId) {
        return PromotionalCode.getFeeDiscountValueAvailableWithEndDate(customerId).list()
    }

	public List<PromotionalCode> findWithAvailableFeeDiscount(Customer customer) {
        return PromotionalCode.availableFeeDiscount(customer, [:]).list()
	}

	public BigDecimal getAllAvailableFeeDiscountValue(Customer customer) {
		List<PromotionalCode> listOfPromotionalCode = findWithAvailableFeeDiscount(customer)
		if (!listOfPromotionalCode) return 0

		return listOfPromotionalCode*.getAvailableDiscountValue().sum()
	}

    public void cancelPromotionalCodeUse(ReceivableAnticipation receivableAnticipation) {
        List<PromotionalCodeUse> promotionalCodeUseList = PromotionalCodeUse.query([consumerObject: receivableAnticipation]).list()
        for (PromotionalCodeUse promotionalCodeUse in promotionalCodeUseList) {
            promotionalCodeUse.deleted = true
            promotionalCodeUse.promotionalCode.discountValueUsed = promotionalCodeUse.promotionalCode.discountValueUsed - promotionalCodeUse.value
        }
    }

    public PromotionalCode createPromotionalCodeForInvited(Referral referral) {
		PromotionalCode promotionalCode = buildReferralPromotionalCode(referral.invitedCustomer, referral, PromotionalCodeType.FEE_DISCOUNT, BigDecimal.valueOf(grailsApplication.config.asaas.referral.promotionalCode.discountValue.invited))

		referral.addToPromotionalCodes(promotionalCode)

		return promotionalCode
    }

    public void createPromotionalCodeForReferralPromoterIfPossible(Referral referral) {
        PromotionalCodeGroupCacheVO promotionalCodeGroupCacheVO = promotionalCodeGroupService.getReferralPromotionPromotionalCodeGroup(referral.invitedByCustomer.id, referral.invitedCustomer.isNaturalPerson())

        BigDecimal promotionalFeeDiscountValue = calculatePromotionalCodeDiscountValueReferralPromotion(referral.invitedByCustomer.id, promotionalCodeGroupCacheVO.discountValue)

        if (!promotionalFeeDiscountValue) return

        Integer freePaymentAmount = 0
        Date promotionalCodeEndDate = CustomDateUtils.sumMonths(new Date(), PromotionalCode.REFERRAL_PROMOTION_MONTHS_TO_EXPIRE)

        PromotionalCodeAdapter createPromotionalCodeAdapter = PromotionalCodeAdapter
            .build(promotionalCodeGroupCacheVO, PromotionalCodeType.FEE_DISCOUNT, referral)
            .setFreePaymentAmount(freePaymentAmount)
            .setDiscountValue(promotionalFeeDiscountValue)
            .setEndDate(promotionalCodeEndDate)

        save(createPromotionalCodeAdapter, true)
    }

    public void createPromotionalCodeEntryPromotionForCustomer(Long customerId) {
        PromotionalCodeGroup promotionalCodeGroup = PromotionalCodeGroup.query([id: PromotionalCodeGroup.ENTRY_PROMOTION_PROMOTIONAL_CODE_GROUP_ID, valid: true]).get()
        Date promotionalCodeEndDate = CustomDateUtils.sumDays(new Date(), PromotionalCode.NEW_ENTRY_PROMOTION_DAYS_TO_EXPIRE)

        PromotionalCodeAdapter createPromotionalCodeAdapter = PromotionalCodeAdapter
            .build(promotionalCodeGroup, PromotionalCodeType.FEE_DISCOUNT)
            .setCustomerId(customerId)
            .setFreePaymentAmount(0)
            .setEndDate(promotionalCodeEndDate)

        save(createPromotionalCodeAdapter, true)
    }

	public void consumeFreePaymentPromotionalCode(Payment payment) {
        if (payment.getAsaasValue() == 0) return

        Customer customer = payment.provider
        PromotionalCode promotionalCode = PromotionalCode.listValidFreePaymentPromotionalCode(customer, [:]).get()

		if (!promotionalCode) {
            AsaasLogger.error("[PromotionalCodeService - consumeFreePaymentPromotionalCode] Cliente não possui código promocional para ser consumido. Customer-> [${payment.provider.id}]")
			throw new RuntimeException("Cliente não possui código promocional para ser consumido.")
		}

        if (promotionalCode.promotionalCodeGroup?.id == PromotionalCodeGroup.DOUBLE_PROMOTION_PROMOTIONAL_CODE_GROUP_ID && !payment.billingType.isBoletoOrPix()) {
            return
        }

        promotionalCode.freePaymentsUsed++

        savePromotionalCodeUse(promotionalCode, payment.getAsaasValue(), payment)

        if (promotionalCode.promotionalCodeGroup?.id == PromotionalCodeGroup.BLACK_FRIDAY_PROMOTIONAL_CODE_GROUP_ID) {
            payment.netValue = payment.value
            return
        }

        if (payment.isCreditCard() || payment.isDebitCard()) {
            BigDecimal discountAppliedForCard = BigDecimalUtils.min(payment.getAsaasValue(), PromotionalCode.MAXIMUM_FREE_PAYMENT_CARD_TRANSACTION_DISCOUNT)
            payment.netValue = payment.value - (payment.getAsaasValue() - discountAppliedForCard)
            return
        }

        payment.netValue = payment.value
	}

	public void consumeFeeDiscountPromotionalCodeAndSetNetValue(Payment payment) {
		if (payment.getAsaasValue() == 0) return

		BigDecimal feeWithDiscountApplied = consumeFeeDiscountBalance(payment.provider, payment.getAsaasValue(), payment)

		payment.netValue = payment.value - feeWithDiscountApplied
	}

	public PromotionalCode consumeFeeDiscountPromotionalCodeAndSetNetValue(CreditTransferRequest transfer) {
		if (transfer.transferFee == 0) return

		PromotionalCode promotionalCode = findWithAvailableFeeDiscount(transfer.provider)[0]

		if (!promotionalCode) return

		if (transfer.transferFee <= promotionalCode.getAvailableDiscountValue()) {
			transfer.addToPromotionalCodeUses(savePromotionalCodeUse(promotionalCode, transfer.transferFee, transfer))
			transfer.transferFee = 0
		}

		return promotionalCode
	}

	public Boolean hasValidFreePaymentPromotionalCode(Customer customer) {
        return PromotionalCode.listValidFreePaymentPromotionalCode(customer, [exists: true]).get().asBoolean()
	}

    public Boolean customerHasValidFeeDiscount(Customer customer) {
        return PromotionalCode.availableFeeDiscount(customer, [:]).get().asBoolean()
    }

	public void associateToCustomer(String code, Customer customer) throws InvalidPromotionalCodeException {
		PromotionalCode promotionalCode = findDetachedPromotionalCodeByCode(code)

		if (!promotionalCode || !validateIfGroupCanBeAssociatedToCustomer(customer, promotionalCode.promotionalCodeGroup)) throw new InvalidPromotionalCodeException("Código promocional inválido.", code)

		if (promotionalCode.isGoldTicket() && !validateIfGoldTicketCanBeAssociatedToCustomer(customer)) throw new InvalidPromotionalCodeException("Não é possível associar esse código promocional à sua conta.", code)

		if (promotionalCode.owner) {
            SaveReferralAdapter adapter = SaveReferralAdapter.buildFromPromotionalCode(promotionalCode, customer)

            Referral referral = createReferralService.save(adapter)

            trackReferralCreatedFromGoldenTicket(referral)
            messageService.notifyIndicatorAboutReferralRegistration(referral)
		}

		promotionalCode.customer = customer
		promotionalCode.save(flush: true, failOnError: true)
	}

    public PromotionalCode save(PromotionalCodeAdapter adapter) throws Exception {
        save(adapter, false)
    }

    public PromotionalCode save(PromotionalCodeAdapter adapter, Boolean shouldSaveCustomerBenefit) throws Exception {
        Customer customer = Customer.get(adapter.customerId)
        PromotionalCodeGroup promotionalCodeGroup = PromotionalCodeGroup.get(adapter.promotionalCodeGroupId)

        PromotionalCode promotionalCode = validateSave(customer, promotionalCodeGroup, adapter)

        if (promotionalCode.hasErrors()) return promotionalCode

        String code = buildCode()

        promotionalCode = new PromotionalCode()
        promotionalCode.promotionalCodeGroup = promotionalCodeGroup
        promotionalCode.customer = customer
        promotionalCode.promotionalCodeType = adapter.type
        promotionalCode.freePaymentAmount = adapter.freePaymentAmount
        promotionalCode.discountValue = adapter.discountValue
        promotionalCode.endDate = adapter.endDate
        promotionalCode.referral = adapter.referral
        promotionalCode.code = code
        promotionalCode.save(failOnError: true)

        if (customer) {
            customerInteractionService.savePromotionalCodeGenerated(
                customer,
                promotionalCode.code,
                adapter.reason,
                adapter.type,
                adapter.discountValue,
                adapter.freePaymentAmount)
        }

        if (shouldSaveCustomerBenefit) createCustomerBenefitFromPromotionalCode(customer, promotionalCode)

        return promotionalCode
    }

    public void saveGoldenTicket(PromotionalCodeAdapter adapter, Integer amountToGenerate) {
        PromotionalCode validatedPromotionalCode = validateGoldenTicketAmount(amountToGenerate)
        if (validatedPromotionalCode.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(validatedPromotionalCode))

        Integer goldTicketCount = 0
        while (goldTicketCount < amountToGenerate) {
            PromotionalCode promotionalCode = save(adapter)

            if (promotionalCode.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(promotionalCode))

            goldTicketCount++
        }
    }

	public List<PromotionalCode> listCustomerPromotionalCodeExceptDoublePromotion(Customer customer) {
        PromotionalCodeGroup promotionalCodeGroup = PromotionalCodeGroup.findByName(grailsApplication.config.asaas.doublePromotion.promotionalCodeGroup.name)

        Map params = [:]
        params.customer = customer
        params."promotionalCodeType[in]" = [PromotionalCodeType.FEE_DISCOUNT, PromotionalCodeType.FREE_PAYMENT]
        if (promotionalCodeGroup) params."promotionalCodeGroup[ne]" = promotionalCodeGroup
        params.valid = true

        return PromotionalCode.query(params).list()
	}

	public void consumeFeeDiscountPromotionalCode(Bill bill) {
		if (Bill.getOriginalFee(bill.linhaDigitavel) <= bill.fee) return

		consumeFeeDiscountBalance(bill.customer, Bill.getOriginalFee(bill.linhaDigitavel), bill)
	}

	public BigDecimal consumeFeeDiscountBalance(Customer customer, BigDecimal valueToConsume, domainInstance) {
		List<PromotionalCode> promotionalCodeList = findWithAvailableFeeDiscount(customer)

		if (!promotionalCodeList) return valueToConsume

		for (PromotionalCode promotionalCode : promotionalCodeList) {
			if (valueToConsume == 0) break

			if (promotionalCode.getAvailableDiscountValue() >= valueToConsume) {
				savePromotionalCodeUse(promotionalCode, valueToConsume, domainInstance)
				valueToConsume = 0
			} else {
				valueToConsume -= promotionalCode.getAvailableDiscountValue()
				savePromotionalCodeUse(promotionalCode, promotionalCode.getAvailableDiscountValue(), domainInstance)
			}
		}

		return valueToConsume
	}

    public List<PromotionalCodeUse> reverseDiscountValueUsed(domainInstance) {
        List<PromotionalCodeUse> promotionalCodesUsedList = PromotionalCodeUse.query([consumerObject: domainInstance]).list()

        for (PromotionalCodeUse promotionalCodeUse in promotionalCodesUsedList) {
            PromotionalCode promotionalCode = promotionalCodeUse.promotionalCode
            promotionalCode.discountValueUsed -= promotionalCodeUse.value
            promotionalCode.save(failOnError: true)

            promotionalCodeUse.deleted = true
            promotionalCodeUse.save(failOnError: true)
        }

        return promotionalCodesUsedList
    }

    public List<Map> getFreePaymentNumbers(Customer customer) {
        List<Map> responseMapList = []

        List<Map> promotionalCodeFreePaymentValuesList = PromotionalCode.getPromotionalCodeFreePaymentValues(customer.id).list()
        if (!promotionalCodeFreePaymentValuesList) return responseMapList

        for (Map promotionalCodeFreePaymentValues : promotionalCodeFreePaymentValuesList) {
            if (!promotionalCodeFreePaymentValues.sumFreePaymentAmount) continue

            Map responseMap = [:]
            responseMap.id = promotionalCodeFreePaymentValues.promotionalCodeGroupId
            responseMap.freePaymentRemaining = promotionalCodeFreePaymentValues.sumFreePaymentRemaining
            responseMap.freePaymentAmount = promotionalCodeFreePaymentValues.sumFreePaymentAmount

            responseMapList.add(responseMap)
        }

        return responseMapList
    }

    public BigDecimal getPromotionalBalance(Long customerId) {
        return PromotionalCode.getPromotionalFeeDiscountBalance(customerId, [:]).get()
    }

    public List<Map> getValidFreePayments(Long customerId) {
        List<Map> responseMapList = []

        List<Map> promotionalCodeFreePaymentValuesList = PromotionalCode.getValidFreePayments(customerId).list()
        if (!promotionalCodeFreePaymentValuesList) return responseMapList

        for (Map promotionalCodeFreePaymentValues : promotionalCodeFreePaymentValuesList) {
            if (!promotionalCodeFreePaymentValues.freePaymentRemaining) continue

            Map responseMap = [:]
            responseMap.freePaymentRemaining = promotionalCodeFreePaymentValues.freePaymentRemaining
            responseMap.endDate = promotionalCodeFreePaymentValues.endDate
            responseMap.promotionalCodeGroupId = promotionalCodeFreePaymentValues.promotionalCodeGroupId

            responseMapList.add(responseMap)
        }

        return responseMapList
    }

    public void createOrUpdatePromotionalCodeFreePixOrBankSlipPaymentFromDoublePromotionV2(Long customerId) {
        Integer freePixOrBankSlipPaymentAmount = 1
        PromotionalCodeGroup promotionalCodeGroup = PromotionalCodeGroup.findByName(grailsApplication.config.asaas.doublePromotion.promotionalCodeGroup.name)
        PromotionalCode promotionalCode = PromotionalCode.availableDoublePromotion(customerId, promotionalCodeGroup).get()

        if (promotionalCode) {
            promotionalCode.freePaymentAmount += freePixOrBankSlipPaymentAmount
            promotionalCode.save(flush: true, failOnError: true)
        } else {
            Date activatedPromotionDate = ProductPromotion.getActivatedDoublePromotion(customerId, [column: "dateCreated"]).get()
            Date promotionEndDate = CustomDateUtils.sumDays(activatedPromotionDate, ProductPromotion.DAYS_TO_EXPIRE_DOUBLE_PROMOTION_V2_AFTER_ACTIVATION)

            PromotionalCodeAdapter createPromotionalCodeAdapter = PromotionalCodeAdapter
                .build(promotionalCodeGroup, PromotionalCodeType.FREE_PAYMENT)
                .setCustomerId(customerId)
                .setFreePaymentAmount(freePixOrBankSlipPaymentAmount)
                .setEndDate(promotionEndDate)

            save(createPromotionalCodeAdapter, false)
        }
    }

    private void createCustomerBenefitFromPromotionalCode(Customer customer, PromotionalCode promotionalCode) {
        String benefitName = Benefit.getBenefitNameBasedOnPromotionalCodeGroupName(promotionalCode.promotionalCodeGroup.name)
        Benefit benefit = BenefitRepository.query(name: benefitName).get()

        BigDecimal benefitValue = benefit.type.isPromotionalCredit() ? promotionalCode.discountValue : promotionalCode.freePaymentAmount

        Map params = [
            customer: customer,
            benefit: benefit,
            activationDate: promotionalCode.dateCreated,
            expirationDate: promotionalCode.endDate ?: null,
            status: BenefitStatus.ACTIVE,
            customerBenefitItemsList: buildCustomerBenefitItemsList(benefit, benefitValue)
        ]

        CreateCustomerBenefitAdapter adapter = CreateCustomerBenefitAdapter.build(params)
        customerBenefitService.save(adapter)
    }

    private List<Map> buildCustomerBenefitItemsList(Benefit benefit, BigDecimal value) {
        BenefitItem benefitItem = BenefitItemRepository.query(benefitId: benefit.id).get()
        List<Map> customerBenefitItemsList = [
            [
                benefitItem: benefitItem,
                value: value ?: benefitItem.defaultValue
            ]
        ]

        return customerBenefitItemsList
    }

    private PromotionalCode validateGoldenTicketAmount(Integer amountToGenerate) {
        PromotionalCode promotionalCode = new PromotionalCode()

        if (amountToGenerate == null) {
            DomainUtils.addError(promotionalCode, "A quantidade de Golden Tickets a serem gerados é obrigatória.")
        }

        if (amountToGenerate <= 0) {
            DomainUtils.addError(promotionalCode, "A quantidade de Golden Tickets a serem gerados deve ser maior que zero.")
        }

        return promotionalCode
    }

    private PromotionalCode validateSave(Customer customer, PromotionalCodeGroup promotionalCodeGroup, PromotionalCodeAdapter adapter) {
        PromotionalCode promotionalCode = new PromotionalCode()

        if (!customer && adapter.promotionalCodeGroupId != PromotionalCodeGroup.GOLDEN_TICKET_PROMOTIONAL_CODE_GROUP_ID) {
            DomainUtils.addError(promotionalCode, "O cliente deve ser informado.")
        }

        if (!promotionalCodeGroup) {
            DomainUtils.addError(promotionalCode, "O grupo do código promocional deve ser informado.")
        }

        if (!adapter.type.isFreePayment()) {
            if (adapter.discountValue == null) {
                DomainUtils.addError(promotionalCode, "O valor deve ser informado.")
            } else if (adapter.discountValue <= 0) {
                DomainUtils.addError(promotionalCode, "O valor deve ser maior que zero.")
            }
        }

        if (adapter.type.isFreePayment()) {
            if (adapter.freePaymentAmount == null) {
                DomainUtils.addError(promotionalCode, "A quantidade de cobranças grátis deve ser informada.")
            } else if (adapter.freePaymentAmount <= 0) {
                DomainUtils.addError(promotionalCode, "A quantidade de cobranças grátis deve ser maior que zero.")
            }
        }

        if (!adapter.reason) {
            DomainUtils.addError(promotionalCode, "O motivo deve ser informado.")
        }

        return promotionalCode
    }

    private PromotionalCode buildReferralPromotionalCode(Customer customer, Referral referral, PromotionalCodeType type, BigDecimal discountValue) {
        PromotionalCode promotionalCode = new PromotionalCode(customer: customer, referral: referral, promotionalCodeType: type, discountValue: discountValue, code: buildCode())
        promotionalCode.promotionalCodeGroup = PromotionalCodeGroup.findByName(grailsApplication.config.asaas.referral.promotionalCodeGroup.name)

        promotionalCode.save(flush: true, failOnError: true)

        createCustomerBenefitFromPromotionalCode(customer, promotionalCode)

        return promotionalCode
    }

    private PromotionalCodeUse savePromotionalCodeUse(PromotionalCode promotionalCode, BigDecimal value, Payment payment) {
        return savePromotionalCodeUse(promotionalCode, value, payment, null, null, null, null, null, null, null)
    }

    private PromotionalCodeUse savePromotionalCodeUse(PromotionalCode promotionalCode, BigDecimal value, CreditTransferRequest transfer) {
        return savePromotionalCodeUse(promotionalCode, value, null, transfer, null, null, null, null, null, null)
    }

    private PromotionalCodeUse savePromotionalCodeUse(PromotionalCode promotionalCode, BigDecimal value, Bill bill) {
        return savePromotionalCodeUse(promotionalCode, value, null, null, null, bill, null, null, null, null)
    }

    private PromotionalCodeUse savePromotionalCodeUse(PromotionalCode promotionalCode, BigDecimal value, Invoice invoice) {
        return savePromotionalCodeUse(promotionalCode, value, null, null, null, null, invoice, null, null, null)
    }

    private PromotionalCodeUse savePromotionalCodeUse(PromotionalCode promotionalCode, BigDecimal value, RefundRequest refundRequest) {
       return savePromotionalCodeUse(promotionalCode, value, null, null, null, null, null, refundRequest, null, null)
    }

    private PromotionalCodeUse savePromotionalCodeUse(PromotionalCode promotionalCode, BigDecimal value, ChargedFee chargedFee) {
        return savePromotionalCodeUse(promotionalCode, value, null, null, null, null, null, null, chargedFee, null)
    }

    private PromotionalCodeUse savePromotionalCodeUse(PromotionalCode promotionalCode, BigDecimal value, PaymentPostalServiceBatch paymentPostalServiceBatch) {
        return savePromotionalCodeUse(promotionalCode, value, null, null, null, null, null, null, null, paymentPostalServiceBatch)
    }

    private PromotionalCodeUse savePromotionalCodeUse(PromotionalCode promotionalCode, BigDecimal value, ReceivableAnticipation receivableAnticipation) {
        return savePromotionalCodeUse(promotionalCode, value, null, null, receivableAnticipation, null, null, null, null, null)
    }

    private PromotionalCodeUse savePromotionalCodeUse(PromotionalCode promotionalCode, BigDecimal value, Payment payment, CreditTransferRequest transfer, ReceivableAnticipation receivableAnticipation, Bill bill, Invoice invoice, RefundRequest refundRequest, ChargedFee chargedFee, PaymentPostalServiceBatch paymentPostalServiceBatch) {
        promotionalCode.discountValueUsed = (promotionalCode.discountValueUsed ?: 0) + value

        customerBenefitService.updateStatusToUsedIfNecessary(promotionalCode)

        PromotionalCodeUse promotionalCodeUse = new PromotionalCodeUse(promotionalCode: promotionalCode, value: value, payment: payment, transfer: transfer, receivableAnticipation: receivableAnticipation, bill: bill, invoice: invoice, refundRequest: refundRequest, chargedFee: chargedFee, paymentPostalServiceBatch: paymentPostalServiceBatch)
        return promotionalCodeUse.save(failOnError: true)
    }

    private String buildCode() {
        String code = RandomStringUtils.randomAlphanumeric(6).toUpperCase()

        if (codeAlreadyInUse(code)) {
            return buildCode()
        } else {
            return code
        }
    }

    private Boolean codeAlreadyInUse(String code) {
        Integer count = PromotionalCode.countByCode(code)

        return count > 0
    }

    private Boolean validateIfGroupCanBeAssociatedToCustomer(Customer customer, PromotionalCodeGroup group) {
        if (!group || group.multipleCodesAllowed) return true

        Boolean customerAlreadyAssociatedToPromotion = PromotionalCode.query([exists: true, customer: customer, promotionalCodeGroup: group]).get().asBoolean()

        if (customerAlreadyAssociatedToPromotion) return false

        return true
    }

    private Boolean validateIfGoldTicketCanBeAssociatedToCustomer(Customer customer) {
        def paymentService = grailsApplication.mainContext.paymentService

        if (paymentService.hasAtLeastOnePaymentConfirmedOrReceived(customer) || BankSlipFee.findBankSlipFeeForCustomer(customer)?.calculateFixedFeeValue() != BankSlipFee.DISCOUNT_BANK_SLIP_FEE) return false

        return true
    }

    private BigDecimal calculatePromotionalCodeDiscountValueReferralPromotion(Long invitedByCustomerId, BigDecimal promotionalFeeDiscountValue) {
        BigDecimal discountSumValueForCurrentMonth = PromotionalCode.getReferralPromotionFeeDiscountSumValueForCurrentMonth(invitedByCustomerId).get()

        BigDecimal availableDiscountValueForMonth = PromotionalCode.REFERRAL_PROMOTION_MAXIMUM_FEE_DISCOUNT_VALUE_PER_MONTH - discountSumValueForCurrentMonth
        Boolean hasAvailableDiscountValueForMonth = availableDiscountValueForMonth > BigDecimal.ZERO
        if (!hasAvailableDiscountValueForMonth) {
            AsaasLogger.info("PromotionalCodeService.calculatePromotionalCodeDiscountValueReferralPromotion >> Cliente atingiu o limite de desconto promocional por mês. Customer: [${invitedByCustomerId}], discountSumValueForCurrentMonth: [${discountSumValueForCurrentMonth}], promotionalFeeDiscountValue: [${promotionalFeeDiscountValue}]")
            return null
        }

        if (availableDiscountValueForMonth < promotionalFeeDiscountValue) return availableDiscountValueForMonth

        return promotionalFeeDiscountValue
    }

    private void trackReferralCreatedFromGoldenTicket(Referral referral) {
        try {
            Map data = [providerEmail: referral.invitedCustomer.email, referral: referral.token, campaignName: referral.campaignName]

            asaasSegmentioService.track(referral.getIdForTrack(), "Logged :: Promotional Code :: Indicado usou GoldTicket", data)
            asaasSegmentioService.track(referral.invitedCustomer.id, "Logged :: Promotional Code :: Indicado entrou pelo GoldTicket", data)
        } catch (Exception exception) {
            AsaasLogger.error("PromotionalCodeService.trackReferralCreatedFromGoldenTicket >> Erro ao rastrear a criação do referral a partir de um Golden Ticket. referralId: [${referral?.id}]", exception)
        }
    }
}
