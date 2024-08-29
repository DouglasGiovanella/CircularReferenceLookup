package com.asaas.service.referral

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerStatus
import com.asaas.domain.Referral
import com.asaas.domain.creditbureaureport.CreditBureauReport
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.promotionalcode.PromotionalCode
import com.asaas.domain.promotionalcode.PromotionalCodeGroup
import com.asaas.domain.stage.Stage
import com.asaas.exception.SmsFailException
import com.asaas.log.AsaasLogger
import com.asaas.product.ProductName
import com.asaas.referral.ReferralStatus
import com.asaas.referral.ReferralType
import com.asaas.referral.ReferralValidationOrigin
import com.asaas.referral.vo.ReferralPanelVO
import com.asaas.referral.vo.ReferralPromotionBVO
import com.asaas.referral.vo.ReferralPromotionTableBVO
import com.asaas.stage.StageCode
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.utils.AbTestUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.hibernate.SQLQuery

@Transactional
class ReferralService {

    private static final Integer FLUSH_EVERY = 50

    private static final Integer MAX_ITEMS_PER_CYCLE = 400

    def asyncActionService
	def asaasSegmentioService
	def bankSlipFeeService
    def customerMessageService
    def creditCardFeeConfigService
	def grailsApplication
	def messageService
	def mobilePushNotificationService
	def promotionalCodeService
    def productPromotionService
    def promotionalCodeGroupService
	def sessionFactory
    def smsSenderService
	def treasureDataService
    def unsubscribeService

    public Integer getMaxInvitationForCustomer(Customer customer) {
        if (customer.isNotFullyApproved()) return Referral.MAX_INVITATIONS_FOR_NOT_APPROVED_CUSTOMER
        return Referral.MONTHLY_INVITATION_LIMIT
    }

    public void saveUpdateToValidatedReferralStatus(Long invitedCustomerId, ReferralValidationOrigin validationOrigin) {
        Boolean hasReferralWithRegisteredStatus = Referral.query([exists: true, invitedCustomerId: invitedCustomerId, status: ReferralStatus.REGISTERED]).get().asBoolean()
        if (!hasReferralWithRegisteredStatus) return

        Map asyncActionData = [invitedCustomerId: invitedCustomerId, referralStatus: ReferralStatus.VALIDATED, referralValidationOrigin: validationOrigin]
        Boolean hasAsyncActionPending = asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.UPDATE_REFERRAL_STATUS)

        if (!hasAsyncActionPending) asyncActionService.save(AsyncActionType.UPDATE_REFERRAL_STATUS, asyncActionData)
    }

    public void processPendingUpdateReferralStatus() {
        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.UPDATE_REFERRAL_STATUS, MAX_ITEMS_PER_CYCLE)

        Utils.forEachWithFlushSession(asyncActionDataList, FLUSH_EVERY, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer invitedCustomer = Customer.read(asyncActionData.invitedCustomerId)
                ReferralStatus referralStatus = ReferralStatus.convert(asyncActionData.referralStatus)
                ReferralValidationOrigin referralValidationOrigin = ReferralValidationOrigin.convert(asyncActionData.referralValidationOrigin)

                switch (referralStatus) {
                    case ReferralStatus.VALIDATED:
                        updateReferralStatusToValidatedIfExists(invitedCustomer, referralValidationOrigin)
                        break
                    default:
                        throw new UnsupportedOperationException("ReferralService.processPendingUpdateReferralStatus >> Status do referral não suportado. [referralStatus: ${referralStatus}]")
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [
                logErrorMessage: "ReferralService.processPendingUpdateReferralStatus >> Erro ao atualizar o status do referral. [asyncActionId: ${asyncActionData.asyncActionId}, customerId: ${asyncActionData.invitedCustomerId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }
            ])
        })
    }

    public ReferralPromotionBVO buildReferralPromotionBVO() {
        BigDecimal firstNaturalPersonIndicationDiscountValue = promotionalCodeGroupService.getPromotionalCodeGroupValue(grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedFirstNaturalPerson.name)
        BigDecimal secondNaturalPersonIndicationDiscountValue = promotionalCodeGroupService.getPromotionalCodeGroupValue(grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedSecondNaturalPerson.name)
        BigDecimal overTwoNaturalPersonIndicationDiscountValue = promotionalCodeGroupService.getPromotionalCodeGroupValue(grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedOverTwoNaturalPerson.name)
        BigDecimal firstLegalPersonIndicationDiscountValue = promotionalCodeGroupService.getPromotionalCodeGroupValue(grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedFirstLegalPerson.name)
        BigDecimal secondLegalPersonIndicationDiscountValue = promotionalCodeGroupService.getPromotionalCodeGroupValue(grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedSecondLegalPerson.name)
        BigDecimal overTwoLegalPersonIndicationDiscountValue = promotionalCodeGroupService.getPromotionalCodeGroupValue(grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedOverTwoLegalPerson.name)

        return ReferralPromotionBVO.build(
            firstNaturalPersonIndicationDiscountValue,
            secondNaturalPersonIndicationDiscountValue,
            overTwoNaturalPersonIndicationDiscountValue,
            firstLegalPersonIndicationDiscountValue,
            secondLegalPersonIndicationDiscountValue,
            overTwoLegalPersonIndicationDiscountValue
        )
    }

    public ReferralPanelVO buildReferralPanelVO(Long invitedByCustomerId) {
        Integer totalPending = Referral.countNotValidatedInvitations(invitedByCustomerId)
        Integer totalValidateNaturalPerson = PromotionalCode.getTotalValidatedReferralPromotion(invitedByCustomerId, PromotionalCodeGroup.getNaturalPersonReferralPromotionCodeGroupName())
        Integer totalValidateLegalPerson = PromotionalCode.getTotalValidatedReferralPromotion(invitedByCustomerId, PromotionalCodeGroup.getLegalPersonReferralPromotionCodeGroupName())

        return ReferralPanelVO.build(totalPending, totalValidateNaturalPerson, totalValidateLegalPerson)
    }

    public List<ReferralPromotionTableBVO> buildReferralPromotionTableBVOList(List<Referral> referralList) {
        List<ReferralPromotionTableBVO> referralPromotionTableVOList = []

        for (Referral referral : referralList) {
            Map promotionalCodeData = PromotionalCode.query([columnList: ["discountValue", "promotionalCodeGroup.name"], referralId: referral.id]).get()

            if (!promotionalCodeData) {
                referralPromotionTableVOList.add(buildReferralPromotionTableVOWithoutPromotionalCodeData(referral))
                continue
            }

            ReferralType referralType = PromotionalCodeGroup.getReferralPromotionType(promotionalCodeData."promotionalCodeGroup.name")
            ReferralPromotionTableBVO promotionVO = ReferralPromotionTableBVO.build(referral, promotionalCodeData.discountValue, referralType)
            referralPromotionTableVOList.add(promotionVO)
        }

        return referralPromotionTableVOList
    }

    public String buildReferralUrl(Customer customer) {
        return "${grailsApplication.config.grails.serverURL}/r/${customer.publicId}"
    }

	public Referral updateReferralStatusToValidatedIfExists(Customer customer, ReferralValidationOrigin validationOrigin) {
		try {
            Referral referral = Referral.query([invitedCustomer: customer, statusList: [ReferralStatus.REGISTERED]]).get()
            if (!referral) return null

            if (!validationOrigin) {
                AsaasLogger.error("ReferralService.updateReferralStatusToValidatedIfExists >> Erro ao verificar origem da validação. [customerId: ${customer.id}] [referralId: ${referral.id}]")
            }

            if (!canUpdateStatusToValidated(customer, validationOrigin)) return null

			referral.status = ReferralStatus.VALIDATED
			referral.save(failOnError: true)

			if (referral.invitedByCustomer) {
				mobilePushNotificationService.notifyInvitedFriendFirstProductUse(referral)

                if (AbTestUtils.hasReferralPromotionVariantB(referral.invitedByCustomer)) {
                    promotionalCodeService.createPromotionalCodeForReferralPromoterIfPossible(referral)
                } else {
                    bankSlipFeeService.incrementDiscountExpiration(referral.invitedByCustomer, referral)
                    creditCardFeeConfigService.incrementDiscountExpiration(referral.invitedByCustomer)
                }
			} else if (referral.accountManager) {
				treasureDataService.track(referral.accountManager.user?.customer, TreasureDataEventType.ACCOUNT_MANAGER_INVITE_CONVERTED, [referralId: referral.id])
			}

			return referral
		} catch (Exception e) {
            AsaasLogger.error("Erro ao atualizar para ${ ReferralStatus.VALIDATED } o referral do cliente ${ customer.id }", e)
			return null
		}
	}

	public void setInvitationAsViewed(Referral referral) {
		try {
			referral.status = ReferralStatus.VIEWED
			referral.save()

			trackInvitationViewing(referral)
		} catch (Exception exception) {
            AsaasLogger.error("ReferralService.setInvitationAsViewed >> Erro ao atualizar para ${ ReferralStatus.VIEWED } o referral do cliente ${ referral.id }", exception)
		}
	}

	public void updateReferralStatusToRegisteredIfExists(Customer customer, String referralToken) {
        Referral referral

        if (referralToken) {
            referral = Referral.findWaitingRegistration(referralToken)
        } else {
            referral = Referral.findOldestWaitingRegistrationByEmail(customer.email)
        }

        if (!referral) return

        referral.invitedCustomer = customer
        referral.status = ReferralStatus.REGISTERED

        referral.save(flush: true, failOnError: true)

        promotionalCodeService.createPromotionalCodeForInvited(referral)

        if (referral.invitedByCustomer) {
            messageService.notifyIndicatorAboutReferralRegistration(referral)
            mobilePushNotificationService.notifyInvitedFriendRegistered(referral)
        }
	}

	public void trackFacebookInvitationViewed(Customer customer, Boolean fromFacebookPost) {
		try {
			asaasSegmentioService.track(customer.id, "referral_public_link", [action: 'viewed', providerEmail: customer.email, fromFacebookPost: fromFacebookPost])
		} catch (Exception exception) {
			AsaasLogger.error("ReferralService.trackFacebookInvitationViewed >> Erro ao rastrear a visualização do convite do Facebook [${customer.id}]", exception)
		}
	}

	public void trackInvitationEmailView(Long referralId) {
		Referral referral = Referral.get(referralId)

		asaasSegmentioService.track(referral.getIdForTrack(), "Public :: Referral :: Email Convite visualizado", buildDefaultParamsToTrack(referral))
	}

	public void inviteAgain(Referral referral) throws SmsFailException {
        if (referral.unsubscribed) return

        asaasSegmentioService.track(referral.getIdForTrack(), "Logged :: Referral :: Convite reenviado", buildDefaultParamsToTrack(referral))

		notifyInvitationCreated(referral)
	}

    public Referral unsubscribe(String token) {
        try {
            Referral referral = Referral.findWaitingRegistration(token?.toUpperCase())

            if (!referral) return null

            referral.unsubscribed = true
            referral.save(failOnError: true)

            unsubscribeService.executeReferralExternalUnsubscribeWithNewThread(referral.invitedEmail)

            return referral
        } catch (Exception e) {
            AsaasLogger.error("Erro ao cancelar a inscrição do referral [${token}]", e)
            return null
        }
    }

    public Integer getCustomerAccountInvitationLimit(Customer customer) {
        Integer canBeInvitedCount = CustomerAccount.canBeInvitedCount(customer)
        Integer remainingReferralCount = getRemainingReferralCount(customer)

        if (remainingReferralCount > canBeInvitedCount) {
            return canBeInvitedCount
        } else {
            return remainingReferralCount
        }
    }

    public Integer getRemainingReferralCount(Customer customer) {
        if (customer.isNotFullyApproved()) {
            return Referral.MAX_INVITATIONS_FOR_NOT_APPROVED_CUSTOMER - Referral.query([invitedByCustomer: customer]).count()
        } else {
            return Referral.MONTHLY_INVITATION_LIMIT - Referral.query([invitedByCustomer: customer, invitationMonth: CustomDateUtils.getFirstDayOfCurrentMonth()]).count()
        }
    }

    public Boolean saveCustomersInvitationAwardEmail() {
        List<Long> customerIdList = listCategorizedCustomersToBoostReferralAward()
        AsaasLogger.info("ReferralService.saveCustomersInvitationAwardEmail >> [${customerIdList.size()}] clientes sendo processados.")

        Utils.forEachWithFlushSession(customerIdList, FLUSH_EVERY, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.get(customerId)

                productPromotionService.save(customer, ProductName.BOOST_REFERRAL_PROGRAM)
                asyncActionService.saveSendReferralAwardInvitation(customerId, customer.email)
            }, [logErrorMessage: "ReferralService.saveCustomersInvitationAwardEmail >> Erro ao salvar o cliente na fila do email de impulsionamento do premio de referral. CustomerId [${customerId}]"])
        })

        return !customerIdList.isEmpty()
    }

    public List<Map> sendCustomersInvitationAwardEmail() {
        List<Map> asyncActionDataList = asyncActionService.listSendReferralAwardInvitation(MAX_ITEMS_PER_CYCLE)

        Utils.forEachWithFlushSession(asyncActionDataList, FLUSH_EVERY, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError({
                customerMessageService.sendReferralAwardInvitation(asyncActionData)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [
                logErrorMessage: "ReferralService.sendCustomersInvitationAwardEmail - Erro ao enviar o email de impulsionamento do premio de referral. CustomerId -> [${asyncActionData.customerId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }
            ])
        })

        return asyncActionDataList
    }

    public void notifyInvitationCreated(Referral referral) {
        if (referral.invitedPhone) {
            try {
                String message = referral.buildInvitationSms(referral.getInvitedByName())
                smsSenderService.send(message, referral.invitedPhone, true, [:])
            } catch (SmsFailException e) {
                DomainUtils.addError(referral, "O número do celular informado é inválido.")
            } catch (Exception exception) {
                DomainUtils.addError(referral, "Ocorreu um erro desconhecido ao enviar SMS.")
                AsaasLogger.error("ReferralService.notifyInvitationCreated >> Erro ao enviar SMS de convite para o referral [${referral.id}]", exception)
            }
        }

        if (referral.invitedEmail) messageService.sendReferralInvitation(referral)
    }

    private ReferralPromotionTableBVO buildReferralPromotionTableVOWithoutPromotionalCodeData(Referral referral) {
        if (!referral.invitedCustomer) {
            return ReferralPromotionTableBVO.build(referral, null, null)
        }

        ReferralType referralType
        if (referral.invitedCustomer.isNaturalPerson()) {
            referralType = ReferralType.NATURAL_PERSON
        } else if (referral.invitedCustomer.isLegalPerson()) {
            referralType = ReferralType.LEGAL_PERSON
        } else {
            referralType = null
        }

        return ReferralPromotionTableBVO.build(referral, null, referralType)
    }

    private void trackInvitationViewing(Referral referral) {
        try {
            asaasSegmentioService.track(referral.getIdForTrack(), "Public :: Referral :: Convite visualizado", buildDefaultParamsToTrack(referral))
        } catch (Exception exception) {
            AsaasLogger.error("ReferralService.trackInvitationViewing >> Erro ao rastrear a visualização do convite [${referral.id}]", exception)
        }
    }

    private Map buildDefaultParamsToTrack(Referral referral) {
        Map params = [referral: referral.token,
                      invitedEmail: referral.invitedEmail,
                      campaignName: referral.campaignName]

        if (referral.invitedName) params.invitedName = referral.invitedName

        if (referral.invitedByCustomer) params.providerEmail =  referral.invitedByCustomer.email
        else if (referral.accountManager) params.accountManager = referral.accountManager.email

        return params
    }

    private Boolean canUpdateStatusToValidated(Customer customer, ReferralValidationOrigin validationOrigin) {
        switch (validationOrigin) {
            case ReferralValidationOrigin.PRODUCT_USAGE:
                return customer.customerRegisterStatus.generalApproval.isApproved()
            case ReferralValidationOrigin.ACCOUNT_APPROVAL:
                return customerAlreadyUsedProduct(customer)
            default:
                return customerAlreadyUsedProductAndApproved(customer)
        }
    }

    private Boolean customerAlreadyUsedProduct(Customer customer) {
        if (customer.hasCreatedPayments()) return true

        if (CreditBureauReport.customerHasCreditBureauReport(customer)) return true

        if (grailsApplication.mainContext.customerInvoiceService.hasCreatedInvoice(customer)) return true

        return false
    }

    private Boolean customerAlreadyUsedProductAndApproved(Customer customer) {
        if (!customer.customerRegisterStatus.generalApproval.isApproved()) return false

        return customerAlreadyUsedProduct(customer)
    }

    private List<Long> listCategorizedCustomersToBoostReferralAward() {
        StringBuilder builder = new StringBuilder()
        builder.append("SELECT customer.id AS customerId FROM customer_stage customerStage")
        builder.append(" INNER JOIN customer ON customerStage.customer_id = customerStage.customer_id AND customerStage.id = customer.current_customer_stage_id")
        builder.append(" WHERE customerStage.deleted = false")
        builder.append(" AND customerStage.date_created > :startDate")
        builder.append(" AND customerStage.stage_id IN (:stageIdList)")
        builder.append(" AND customer.deleted = false")
        builder.append(" AND customer.account_owner_id IS null")
        builder.append(" AND NOT (customer.status IN (:customerStatus))")
        builder.append(" AND NOT EXISTS (SELECT 1 FROM product_promotion productPromotion")
        builder.append("                 WHERE productPromotion.customer_id = customer.id")
        builder.append("                 AND productPromotion.product_name = :productName)")
        builder.append(" AND NOT EXISTS (SELECT 1 FROM referral")
        builder.append("                 WHERE referral.invited_by_customer_id = customer.id)")
        builder.append(" AND NOT EXISTS (SELECT 1 FROM customer_parameter customerParameter")
        builder.append("                 WHERE customerParameter.customer_id = customer.id")
        builder.append("                 AND customerParameter.name IN (:customerParameterList)")
        builder.append("                 AND customerParameter.value = true)")
        builder.append("LIMIT :maxItems")

        Date startDate = CustomDateUtils.sumDays(new Date(), -3)
        List<Long> stageIdList = Stage.query([column: "id", "stageCode[in]": [StageCode.ACTIVATED, StageCode.CONVERTED, StageCode.RECOVERED, StageCode.RETAINED]]).list()

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(builder.toString())
        query.setDate("startDate", startDate)
        query.setParameterList("stageIdList", stageIdList)
        query.setParameterList("customerStatus", CustomerStatus.inactive().collect { it.toString() })
        query.setString("productName", ProductName.BOOST_REFERRAL_PROGRAM.toString())
        query.setParameterList("customerParameterList", [CustomerParameterName.CANNOT_USE_REFERRAL.toString(), CustomerParameterName.WHITE_LABEL.toString()])
        query.setInteger("maxItems", MAX_ITEMS_PER_CYCLE)
        return query.list().collect( { Utils.toLong(it) } )
    }
}
