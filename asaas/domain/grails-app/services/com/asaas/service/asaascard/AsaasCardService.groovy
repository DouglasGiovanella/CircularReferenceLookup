package com.asaas.service.asaascard

import com.asaas.asaascard.AsaasCardBrand
import com.asaas.asaascard.AsaasCardStatus
import com.asaas.asaascard.AsaasCardType
import com.asaas.asaascard.adapter.AsaasCardDeliveryTrackInfoAdapter
import com.asaas.asaascard.validator.AsaasCardCustomerRequirementsValidator
import com.asaas.criticalaction.CriticalActionType
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascard.AsaasCardAgreement
import com.asaas.domain.asaascard.AsaasCardBalanceRefund
import com.asaas.domain.asaascard.AsaasCardHolder
import com.asaas.domain.chargedfee.ChargedFee
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.productpromotion.ProductPromotion
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.adapter.asaascard.AsaasCardAdapter
import com.asaas.integration.bifrost.adapter.asaascard.AsaasCardListAdapter
import com.asaas.integration.bifrost.adapter.asaascard.AsaasCardPermissionsAdapter
import com.asaas.integration.bifrost.adapter.asaascardbill.AsaasCardAccountDisableRestrictionsAdapter
import com.asaas.integration.bifrost.adapter.cardfinancialtransaction.AsaasCardFinancialTransactionListAdapter
import com.asaas.integration.bifrost.adapter.creditcardeventhistory.AsaasCreditCardEventHistoryListAdapter
import com.asaas.integration.bifrost.vo.CardConfigVO
import com.asaas.log.AsaasLogger
import com.asaas.product.ProductName
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

import java.text.Normalizer

@Transactional
class AsaasCardService {

    def asaasCardAgreementService
    def asaasCardBalanceRefundService
    def asaasCardCustomerMessageService
    def asaasCardHolderService
    def asaasCardNotificationService
    def asaasCardRechargeService
    def asyncActionService
    def beamerService
    def bifrostAccountManagerService
    def bifrostCardService
    def bifrostCustomerCreditLimitManagerService
    def chargedFeeService
    def criticalActionService
    def customerAlertNotificationService
    def customerFeatureService
    def hubspotEventService
    def productPromotionService

    public Boolean hasProcessedAsaasCardsAwaitingForCustomerCheckoutApproval(Customer customer) {
        if (!AsaasCard.awaitingActivation([customer: customer, exists: true]).get()) return false
        if (AsaasCardCustomerRequirementsValidator.validateCheckout(customer).isValid()) return false

        return true
    }

    public AsaasCard updateName(Long id, Long customerId, String name) {
        AsaasCard asaasCard = AsaasCard.find(id, customerId)

        asaasCard.name = name
        asaasCard.save(failOnError: true)

        return asaasCard
    }

    public AsaasCard updateStatus(Long id, AsaasCardStatus status, Date deliveredDate) {
        AsaasCard asaasCard = AsaasCard.get(id)
        if (asaasCard.status == status) return asaasCard

        asaasCard.status = status
        if (deliveredDate) asaasCard.deliveredDate = deliveredDate
        asaasCard.save(failOnError: true)

        if (!asaasCard.status.isDelivered()) return asaasCard

        if (CustomDateUtils.isToday(asaasCard.deliveredDate)) asaasCardNotificationService.notifyDeliveredCard(asaasCard)

        Boolean hasProductPromotion = ProductPromotion.query([customer: asaasCard.customer, productName: ProductName.ASAAS_CARD_ACTIVATE, exists: true]).get().asBoolean()
        if (!hasProductPromotion) productPromotionService.save(asaasCard.customer, ProductName.ASAAS_CARD_ACTIVATE)

        return asaasCard
    }

    public String getFourthLine(Customer customer) {
        if (!customer.isLegalPerson()) return

        String fourthLine

        RevenueServiceRegister revenueServiceRegister = customer.getRevenueServiceRegister()

        if (revenueServiceRegister?.tradingName) {
            fourthLine = revenueServiceRegister.tradingName
        } else if (revenueServiceRegister?.corporateName) {
            fourthLine = revenueServiceRegister.corporateName
        } else {
            fourthLine = customer.getProviderName()
        }

        return formatTextForFourthLine(fourthLine)
    }

    public AsaasCard save(Customer customer, Map params) {
        params = parseSaveParams(customer, params)
        AsaasCardType type = AsaasCardType.convert(params.type)
        AsaasCardBrand brand = AsaasCardBrand.valueOf(params.brand.toString())
        BigDecimal requestFee = getRequestFee(customer, type, brand)

        AsaasCard asaasCard = validateRequestAsaasCard(customer, params, requestFee)
        if (asaasCard.hasErrors()) return onError(asaasCard)

        if (type.isCredit()) {
            String hash = buildRequestCardCriticalActionHash(customer)
            BusinessValidation businessValidation = criticalActionService.authorizeSynchronousWithNewTransaction(customer.id, params.groupId, params.token, CriticalActionType.ASAAS_CREDIT_CARD_REQUEST, hash)
            if (!businessValidation.isValid()) {
                asaasCard = DomainUtils.addError(asaasCard, businessValidation.getFirstErrorMessage())
                return onError(asaasCard)
            }
        }

        AsaasCardHolder cardHolder = asaasCardHolderService.save(customer, params)
        if (cardHolder.hasErrors()) {
            asaasCard = DomainUtils.copyAllErrorsFromObject(cardHolder, asaasCard)
            return onError(asaasCard)
        }

        asaasCard = saveAsaasCard(cardHolder, customer, params.cardName, type, brand)

        if (requestFee > 0) chargedFeeService.saveAsaasCardRequestFee(customer, asaasCard, requestFee)

        bifrostCardService.save(asaasCard)

        if (asaasCard.type.isCreditEnabled()) {
            if (asaasCard.brand.isElo()) {
                productPromotionService.deleteIfNecessary(asaasCard.customer, ProductName.REQUEST_ASAAS_CREDIT_CARD)
                beamerService.createOrUpdateUser(customer.id, [hasAvailableAsaasCreditCardRequest: false])
            }

            AsaasCardAgreement agreement = asaasCardAgreementService.saveCreditCardAgreementIfNecessary(customer, asaasCard.brand, params, UserUtils.getCurrentUser())
            if (agreement.hasErrors()) {
                AsaasLogger.error("AsaasCardService.save() -> Erro ao salvar o aceite de contrato do cartão pós-pago. [customerId: ${customer.id}, asaasCardId: ${asaasCard.id}]")
            }
        }
        asaasCardCustomerMessageService.notifyAsaasCardRequested(asaasCard)

        return asaasCard
    }

    public AsaasCardListAdapter listExternalCards(Long customerId, Map filters) {
        AsaasCardListAdapter asaasCardListAdapter = bifrostCardService.list(customerId, filters)
        for (AsaasCardAdapter asaasCardAdapter : asaasCardListAdapter.asaasCardList) {
            asaasCardAdapter.asaasCard = synchronizeEloCardInfo(AsaasCard.find(asaasCardAdapter.asaasCardId, customerId), asaasCardAdapter)
        }

        return asaasCardListAdapter
    }

    public AsaasCardAdapter findWithExternalCardSync(Long id, Long customerId) {
        AsaasCard asaasCard = AsaasCard.find(id, customerId)

        return syncWithExternalCard(asaasCard)
    }

    public AsaasCard activate(Long id, Long customerId, Map params) {
        AsaasCard asaasCard = AsaasCard.find(id, customerId)

        asaasCard = validateActivation(asaasCard, params)
        if (asaasCard.hasErrors()) return asaasCard

        asaasCard = activatePaysmartElo(asaasCard, params)
        if (!asaasCard.hasErrors()) {
            asaasCardNotificationService.notifyAsaasCardActivated(asaasCard)
            productPromotionService.deleteIfNecessary(asaasCard.customer, ProductName.ASAAS_CARD_ACTIVATE)
            if (asaasCard.type.isCredit()) beamerService.createOrUpdateUser(customerId, [hasActivatedAsaasCreditCard: true])
            asaasCardRechargeService.releaseRechargesWaitingCardActivation(asaasCard)
        }

        return asaasCard
    }

    public AsaasCard customerReissue(AsaasCard asaasCard, Map params, Customer customer) {
        AsaasCardPermissionsAdapter permissions = getPermissions(asaasCard)
        if (permissions.reissue.disabled) throw new BusinessException(permissions.reissue.disabledReason)

        String hash = buildReissueCriticalActionHash(asaasCard)
        BusinessValidation businessValidation = criticalActionService.authorizeSynchronousWithNewTransaction(customer.id, Utils.toLong(params.groupId), params.token, CriticalActionType.ASAAS_CARD_REISSUE, hash)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        AsaasCardHolder cardHolder = asaasCardHolderService.updateForReissue(customer, asaasCard.holder.id, params)
        if (cardHolder.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(cardHolder))

        return reissue(asaasCard, customer, cardHolder, params.cardName)
    }

    public AsaasCard adminReissue(AsaasCard asaasCard, Map params, Customer customer) {
        BusinessValidation reissueBusinessValidation =  asaasCard.canAdminReissue()
        if (!reissueBusinessValidation.isValid()) throw new BusinessException(reissueBusinessValidation.getFirstErrorMessage())

        AsaasCardHolder cardHolder = asaasCardHolderService.updateAddress(customer, asaasCard.holder.id, params)
        if (cardHolder.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(cardHolder))

        return reissue(asaasCard, customer, cardHolder, params.cardName)
    }

    public CriticalActionGroup saveReissueCriticalActionGroup(AsaasCard asaasCard) {
        String authorizationMessage = "o código para solicitar segunda via do cartão ${asaasCard.getFormattedName()} é"
        String hash = buildReissueCriticalActionHash(asaasCard)

        return criticalActionService.saveAndSendSynchronous(asaasCard.customer, CriticalActionType.ASAAS_CARD_REISSUE, hash, authorizationMessage)
    }

    public CriticalActionGroup saveChangeCreditEnabledLimitCriticalActionGroup(AsaasCard asaasCard) {
        String authorizationMessage = "o código para alteração de limite do seu cartão ${asaasCard.getFormattedName()} é"
        String hash = buildChangeCreditEnabledLimitCriticalActionHash(asaasCard)

        return criticalActionService.saveAndSendSynchronous(asaasCard.customer, CriticalActionType.ASAAS_CARD_CHANGE_CREDIT_ENABLED_LIMIT, hash, authorizationMessage)
    }

    public CriticalActionGroup saveCreditCardRequestCriticalActionGroup(Customer customer) {
        String authorizationMessage = "o código para solicitar seu cartão é"
        String hash = buildRequestCardCriticalActionHash(customer)

        return criticalActionService.saveAndSendSynchronous(customer, CriticalActionType.ASAAS_CREDIT_CARD_REQUEST, hash, authorizationMessage)
    }

    public void setAsCancelled(AsaasCard asaasCard) {
        asaasCard.status = AsaasCardStatus.CANCELLED
        asaasCard.save(failOnError: true)

        if (asaasCard.type.isCredit()) beamerService.createOrUpdateUser(asaasCard.customer.id, [hasActivatedAsaasCreditCard: false])
    }

    public AsaasCardFinancialTransactionListAdapter listAsaasCardFinancialTransaction(Long id, Long customerId, Map search) {
        AsaasCard asaasCard = AsaasCard.find(id, customerId)

        if (!asaasCard.status.hasBeenActivated() && !isReissue(asaasCard.id) || !asaasCard.brand.isElo()) return null

        return bifrostCardService.listAsaasCardFinancialTransaction(asaasCard, search)
    }

    public List<Long> listReissuedCardIds(Long currentAsaasCardId) {
        List<Long> asaasCardIdList = []

        Long reissuedAsaasCardId = AsaasCard.withReissue([column: "id", reissuedCardId: currentAsaasCardId]).get()

        while (reissuedAsaasCardId) {
            asaasCardIdList.add(reissuedAsaasCardId)

            reissuedAsaasCardId = AsaasCard.withReissue([column: "id", reissuedCardId: reissuedAsaasCardId]).get()
        }

        return asaasCardIdList
    }

    public AsaasCard block(Long id, Long customerId, Boolean isUnlockable) {
        AsaasCard asaasCard = AsaasCard.find(id, customerId)

        AsaasCardPermissionsAdapter permissions = getPermissions(asaasCard)
        if (permissions.block.disabled) throw new BusinessException(permissions.block.disabledReason)

        bifrostCardService.block(asaasCard, isUnlockable)

        return updateToBlocked(asaasCard)
    }

    public AsaasCard unblock(Long id, Long customerId, Map params) {
        AsaasCard asaasCard = AsaasCard.find(id, customerId)

        AsaasCardPermissionsAdapter permissions = getPermissions(asaasCard)
        if (permissions.unblock.disabled) throw new BusinessException(permissions.unblock.disabledReason)

        asaasCard = bifrostCardService.unblock(asaasCard, Utils.toLong(params.groupId), params.token, Utils.toBoolean(params.bypassCriticalActionValidation))

        if (asaasCard.hasErrors()) return asaasCard

        asaasCard.status = AsaasCardStatus.ACTIVE
        asaasCard.save(failOnError: true)
        return asaasCard
    }

    public AsaasCard onError(AsaasCard asaasCard) {
        transactionStatus.setRollbackOnly()
        return asaasCard
    }

    public AsaasCard changePin(Long asaasCardId, Long customerId, Map params) {
        AsaasCard asaasCard = AsaasCard.find(asaasCardId, customerId)

        BusinessValidation validatedBusiness = asaasCard.canChangePin()
        if (!validatedBusiness.isValid()) {
            DomainUtils.addError(asaasCard, validatedBusiness.asaasErrors[0].getMessage())
            return asaasCard
        }

        return bifrostCardService.changePin(asaasCard, Utils.toLong(params.groupId), params.token, params.pin)
    }

    public Boolean isReissue(Long asaasCardId) {
        return AsaasCard.query([reissuedCardId: asaasCardId, includeWithReissue: true, exists: true]).get().asBoolean()
    }

    public List<Map> getDeliveryTrackInfo(AsaasCard asaasCard) {
        if (!asaasCard.brand.isElo()) return null
        if (!asaasCard.status.isPending() && !asaasCard.status.isInTransit() && !asaasCard.status.isDelivered()) return null

        List<Map> deliveryTrackInfoList = bifrostCardService.getDeliveryTrackInfo(asaasCard)
        return deliveryTrackInfoList
    }

    public AsaasCardDeliveryTrackInfoAdapter buildDeliveryTrackInfoIfPossible(AsaasCardAdapter asaasCardAdapter) {
        AsaasCard asaasCard = asaasCardAdapter.asaasCard
        if (!asaasCard.brand.isElo()) return null
        if (!asaasCard.status.isActivationEnabled()) return null

        return new AsaasCardDeliveryTrackInfoAdapter(asaasCardAdapter)
    }

    public void enableEloIfNecessary(Customer customer) {
        if (customer.asaasCardEloEnabled()) return

        if (CustomerParameter.getValue(customer, CustomerParameterName.ASAAS_CARD_ELO_DISABLED)) return

        AsaasLogger.info("AsaasCardService.enableEloIfNecessary() -> Habilitando cartão Elo para o cliente ${customer.id}")

        customerFeatureService.toggleAsaasCardElo(customer.id, true)
    }

    public AsaasCard refundBalance(Long id, Long customerId) {
        AsaasCard asaasCard = AsaasCard.find(id, customerId)

        BusinessValidation validatedBusiness = asaasCard.canRefundBalance()
        if (!validatedBusiness.isValid()) {
            DomainUtils.addError(asaasCard, validatedBusiness.getFirstErrorMessage())
            return asaasCard
        }

        AsaasCardBalanceRefund asaasCardBalanceRefund = asaasCardBalanceRefundService.refundBalanceToAsaasAccount(asaasCard)

        return asaasCardBalanceRefund.asaasCard
    }


    public BigDecimal getRequestFee(Customer customer, AsaasCardType type, AsaasCardBrand brand) {
        if (brand.isMasterCard()) return getMastercardRequestFee(customer)
        if (type.isCredit()) return AsaasCard.ELO_CREDIT_REQUEST_FEE

        Integer cardLimit
        if (customer.isLegalPerson()) {
            cardLimit = (type.isDebit() ? AsaasCard.TOTAL_FREE_DEBIT_ELO_CARDS : AsaasCard.TOTAL_FREE_PREPAID_ELO_CARDS_LEGAL_PERSON)
        } else {
            Boolean hasFreeCardRequest = CustomerParameter.getValue(customer, CustomerParameterName.FIRST_ASAAS_CARD_REQUEST_FREE_OF_CHARGE)
            if (!hasFreeCardRequest) return ChargedFee.ELO_CARD_REQUEST_FEE
            cardLimit = (type.isDebit() ? AsaasCard.TOTAL_FREE_DEBIT_ELO_CARDS : AsaasCard.TOTAL_FREE_PREPAID_ELO_CARDS_NATURAL_PERSON)
        }

        Integer requestedTypeCardListCount = AsaasCard.query([customer: customer, type: type]).count()

        if (requestedTypeCardListCount >= cardLimit) return ChargedFee.ELO_CARD_REQUEST_FEE

        return 0.00
    }

    public AsaasCard updateDebitCardToCreditCard(Long customerId, User user, Map params) {
        AsaasCard asaasCard = AsaasCard.query([customerId: customerId, type: AsaasCardType.DEBIT, brand: AsaasCardBrand.ELO, status: AsaasCardStatus.ACTIVE]).get()
        if (!asaasCard.type.isDebit()) {
            DomainUtils.addError(asaasCard, "O tipo de cartão não permite essa alteração.")
            return asaasCard
        }

        if (!productPromotionService.isEligibleForMigrateAsaasCardToCredit(asaasCard.customer)) {
            DomainUtils.addError(asaasCard, "Não é possível converter o cartão para crédito.")
            return asaasCard
        }

        asaasCard = bifrostCardService.validateToken(asaasCard, Utils.toLong(params.groupId), params.token, CriticalActionType.MIGRATE_ASAAS_CARD_TO_CREDIT)
        if (asaasCard.hasErrors()) return asaasCard

        AsaasCardAgreement agreement = asaasCardAgreementService.saveCreditCardAgreementIfNecessary(asaasCard.customer, asaasCard.brand, params, user)
        if (agreement.hasErrors()) return DomainUtils.copyAllErrorsFromObject(agreement, asaasCard)

        bifrostCardService.updateDebitCardToCreditCard(asaasCard)
        updateType(asaasCard, AsaasCardType.CREDIT)
        productPromotionService.deleteIfNecessary(asaasCard.customer, ProductName.MIGRATE_ASAAS_CARD_TO_CREDIT)
        beamerService.createOrUpdateUser(customerId, [hasAvailableAsaasCreditCardRequest: false, hasActivatedAsaasCreditCard: true])
        asaasCardNotificationService.notifyAsaasCardAutomaticDebitActivated(asaasCard.customer, AsaasCard.DEFAULT_BILL_DUE_DAY)

        return asaasCard
    }

    public void updateType(AsaasCard asaasCard, AsaasCardType type) {
        asaasCard.type = type
        asaasCard.save(failOnError: true)
    }

    public AsaasCard updateToBlocked(AsaasCard asaasCard) {
        asaasCard.status = AsaasCardStatus.BLOCKED
        asaasCard.save(failOnError: true)

        if (asaasCard.type.isPrepaid()) asaasCardRechargeService.cancelIfPossible(asaasCard)

        return asaasCard
    }

    public void unblockAsaasCardsBlockedByNegativeBalance() {
        List<Map> asyncActionList = asyncActionService.listUnblockAsaasCreditCardIfNecessary(500)

        Utils.forEachWithFlushSession(asyncActionList, 50, { Map asyncAction ->
            Utils.withNewTransactionAndRollbackOnError({
                AsaasCard asaasCard = AsaasCard.get(asyncAction.asaasCardId)
                AsaasCardAdapter asaasCardAdapter = syncWithExternalCard(asaasCard)

                if (asaasCard.status.isBlocked() && asaasCardAdapter.blockStatus?.isNegativeBalanceBlock()) {
                    unblock(asaasCard.id, asaasCard.customer.id, [bypassCriticalActionValidation: true])
                } else if (asaasCard.status.isActivationEnabled() && !asaasCardAdapter.unlockable) {
                    bifrostCardService.updateUnlockable(asaasCard, true)
                }

                asyncActionService.delete(asyncAction.asyncActionId)
                asaasCardNotificationService.notifyAsaasCardUnblockedAfterBalanceAcquittance(asaasCard)
            }, [logErrorMessage: "AsaasCardService.unblockAsaasCardsBlockedByNegativeBalance >> Ocorreu um erro ao desbloquear cartão para o item [ID: ${asyncAction.asyncActionId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncAction.asyncActionId) }
            ])
        })
    }

    public void cancelAsaasCardsOfCancelledAccounts() {
        List<Map> asyncActionList = asyncActionService.listCancelAllAsaasCards()

        Utils.forEachWithFlushSession(asyncActionList, 50, { Map asyncAction ->
            Utils.withNewTransactionAndRollbackOnError({
                cancelAllCards(Customer.read(Utils.toLong(asyncAction.customerId)))

                asyncActionService.delete(Utils.toLong(asyncAction.asyncActionId))
            }, [logErrorMessage: "AsaasCardService.cancelAsaasCardsOfCancelledAccounts >> Ocorreu um erro ao cancelar cartões para o item [ID: ${asyncAction.asyncActionId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(Utils.toLong(asyncAction.asyncActionId)) }
            ])
        })
    }

    public AsaasCreditCardEventHistoryListAdapter listCreditCardEventHistories(Long asaasCardId, Long customerId, Map filter) {
        AsaasCard asaasCard = AsaasCard.find(asaasCardId, customerId)

        return bifrostCardService.listCreditCardEventHistories(asaasCard, filter)
    }

    public BigDecimal getCustomerCreditLimit(Long customerId) {
        return bifrostCustomerCreditLimitManagerService.get(customerId)
    }

    public void cancelAllCards(Customer customer) {
        AsaasCardAccountDisableRestrictionsAdapter asaasCardAccountDisableRestrictions = bifrostAccountManagerService.validateToDisable(customer.id)
        if (asaasCardAccountDisableRestrictions.hasUnpaidBills) AsaasLogger.warn("O cliente desabilitado possui faturas pendentes a serem pagas: ${customer.id}")
        if (asaasCardAccountDisableRestrictions.hasAvailableCredit) AsaasLogger.warn("O cliente desabilitado possui crédito disponível em fatura positiva: ${customer.id}")

        List<Long> cardList = AsaasCard.query([customer: customer, "status[in]": [AsaasCardStatus.BLOCKED, AsaasCardStatus.ACTIVE] + AsaasCardStatus.getActivableList(), column: "id"]).list()

        for (Long asaasCardId : cardList) {
            Utils.withNewTransactionAndRollbackOnError ( {
                AsaasCard asaasCard = AsaasCard.get(asaasCardId)

                if (asaasCard.status.isActivationEnabled()) {
                    bifrostCardService.updateUnlockable(asaasCard, false)
                } else {
                    cancel(asaasCard)
                }
            }, [logErrorMessage: "AsaasCardService.cancelAllCards >>> Erro ao cancelar o cartão [asaasCardId: ${asaasCardId}]"] )
        }
    }

    public AsaasCard cancel(AsaasCard asaasCard) {
        if (!asaasCard.status.isError()) bifrostCardService.cancel(asaasCard)

        setAsCancelled(asaasCard)

        if (asaasCard.type.isCredit()) enableCreditCardRequest(asaasCard.customer)
        if (asaasCard.type.isPrepaid()) asaasCardRechargeService.cancelIfPossible(asaasCard)

        return asaasCard
    }

    public void enableCreditCardRequest(Customer customer) {
        Boolean hasDebitCard = AsaasCard.query([exists: true, customer: customer, type: AsaasCardType.DEBIT]).get().asBoolean()

        ProductName productName = ProductName.REQUEST_ASAAS_CREDIT_CARD
        if (hasDebitCard) productName = ProductName.MIGRATE_ASAAS_CARD_TO_CREDIT

        Boolean alreadyHasProductPromotion = ProductPromotion.query([exists: true, customer: customer, productName: productName]).get()
        if (!alreadyHasProductPromotion) {
            productPromotionService.save(customer, productName)
            beamerService.createOrUpdateUser(customer.id, [hasAvailableAsaasCreditCardRequest: true])
        }
    }

    public void blockAllCards(Customer customer) {
        List<Long> cardList = AsaasCard.query([customer: customer, "status[in]": [AsaasCardStatus.ACTIVE, AsaasCardStatus.BLOCKED] + AsaasCardStatus.getActivableList(), column: "id"]).list()

        for (Long asaasCardId : cardList) {
            Utils.withNewTransactionAndRollbackOnError ( {
                AsaasCard asaasCard = AsaasCard.get(asaasCardId)

                if (asaasCard.status.isActivationEnabled() || asaasCard.status.isBlocked()) {
                    bifrostCardService.updateUnlockable(asaasCard, false)
                } else {
                    asaasCard = block(asaasCard.id, customer.id, false)

                    if (asaasCard.hasErrors()) throw new BusinessException(asaasCard.errors.allErrors.first())
                }
            }, [logErrorMessage: "AsaasCardService.blockAllCards >>> Erro ao bloquear o cartão [asaasCardId: ${asaasCardId}]"] )
        }
    }

    public void updateAllToUnlockable(Customer customer) {
        List<AsaasCard> cardList = AsaasCard.query([customer: customer, "status[in]": [AsaasCardStatus.BLOCKED] + AsaasCardStatus.getActivableList()]).list()

        for (AsaasCard asaasCard : cardList) {
            bifrostCardService.updateUnlockable(asaasCard, true)
        }
    }

    public void updateConfig(AsaasCard asaasCard, Map configMap) {
        AsaasCardPermissionsAdapter permissions = getPermissions(asaasCard)
        if (permissions.config.disabled) throw new BusinessException("Cartão não permite configuração [asaasCardId: ${asaasCard.id}].")
        if (configMap.automaticBillPaymentEnabled != null) throw new BusinessException("Configuração de débito automático de fatura de cartão não suportada.")

        if (configMap.enabledLimit) {
            if (permissions.changeEnabledLimit.disabled) throw new BusinessException(permissions.changeEnabledLimit.disabledReason)

            String hash = buildChangeCreditEnabledLimitCriticalActionHash(asaasCard)
            BusinessValidation businessValidation = criticalActionService.authorizeSynchronousWithNewTransaction(asaasCard.customer.id, Utils.toLong(configMap.groupId), configMap.token, CriticalActionType.ASAAS_CARD_CHANGE_CREDIT_ENABLED_LIMIT, hash)
            if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())
        }

        CardConfigVO cardConfigVO = new CardConfigVO()
        cardConfigVO.automaticBillPaymentEnabled = configMap.automaticBillPaymentEnabled == null ? null : Utils.toBoolean(configMap.automaticBillPaymentEnabled)
        cardConfigVO.contactlessEnabled = configMap.contactlessEnabled == null ? null : Utils.toBoolean(configMap.contactlessEnabled)
        cardConfigVO.enabledLimit = Utils.toBigDecimal(configMap.enabledLimit)

        bifrostCardService.config(asaasCard, cardConfigVO)

        if (cardConfigVO.enabledLimit) {
            customerAlertNotificationService.notifyAsaasCardCreditEnabledLimitChanged(asaasCard.customer)
        }
    }

    public AsaasCard activatePaysmartElo(AsaasCard asaasCard, Map params) {
        AsaasCard activatedAsaasCard = bifrostCardService.activate(asaasCard, Utils.toLong(params.groupId), params.token, params.pin.toString(), params.lastDigits)
        if (activatedAsaasCard.hasErrors()) return activatedAsaasCard

        activatedAsaasCard.status = AsaasCardStatus.ACTIVE
        activatedAsaasCard.activatedAt = new Date()
        activatedAsaasCard.save(failOnError: true)

        return activatedAsaasCard
    }

    public AsaasCardAdapter getExternalCardInfo(AsaasCard asaasCard) {
        return bifrostCardService.get(asaasCard)
    }

    public AsaasCardPermissionsAdapter getPermissions(AsaasCard asaasCard) {
        return bifrostCardService.getPermissions(asaasCard)
    }

    private AsaasCard reissue(AsaasCard asaasCard, Customer customer, AsaasCardHolder cardHolder, String cardName) {
        AsaasCard newAsaasCard = saveAsaasCard(cardHolder, customer, cardName, asaasCard.type, asaasCard.brand)
        setAsCancelled(asaasCard)
        asaasCard.reissuedCard = newAsaasCard
        asaasCard.save(failOnError: true)

        bifrostCardService.reissue(asaasCard, newAsaasCard)

        return newAsaasCard
    }

    private AsaasCard saveAsaasCard(AsaasCardHolder cardHolder, Customer customer, String cardName, AsaasCardType type, AsaasCardBrand brand) {
        AsaasCard asaasCard = new AsaasCard()
        asaasCard.creator = UserUtils.getCurrentUser()
        asaasCard.holder = cardHolder
        asaasCard.customer = customer
        asaasCard.name = cardName
        asaasCard.status = AsaasCardStatus.PENDING
        asaasCard.publicId = AsaasCard.buildPublicId()
        asaasCard.type = type
        asaasCard.brand = brand
        if (customer.isLegalPerson()) asaasCard.fourthLine = getFourthLine(customer)
        asaasCard.save(failOnError: true)

        hubspotEventService.trackCustomerHasRequestedEloCard(customer)

        return asaasCard
    }

    private AsaasCard validateRequestAsaasCard(Customer customer, Map params, BigDecimal cardRequestFee) {
        AsaasCard asaasCard = new AsaasCard()

        if (!AsaasCardCustomerRequirementsValidator.customerStatusEnablesActivation(customer).isValid()) {
            DomainUtils.addError(asaasCard, "Não é possível solicitar o cartão sem a ativação da conta")
            return asaasCard
        }

        AsaasCardBrand asaasCardBrand = AsaasCardBrand.valueOf(params.brand.toString())
        AsaasCardType asaasCardType = AsaasCardType.convert(params.type)

        BusinessValidation validatedBusiness = AsaasCardCustomerRequirementsValidator.validate(customer, asaasCardType, cardRequestFee, asaasCardBrand)
        if (!validatedBusiness.isValid()) return DomainUtils.addError(asaasCard, validatedBusiness.getFirstErrorMessage())

        if (asaasCardBrand.isElo()) {
            if (asaasCardType.isDebit() || asaasCardType.isCredit()) {
                AsaasCardType type = AsaasCard.query([column: "type", customer: customer, "status[ne]": AsaasCardStatus.CANCELLED, "type[in]": [AsaasCardType.DEBIT, AsaasCardType.CREDIT]]).get()
                if (type) {
                    DomainUtils.addError(asaasCard, "Não é possível solicitar o cartão pois você já possui um cartão de ${type.isDebit() ? "débito" : "crédito"}.")
                    return asaasCard
                }
            }

            if (asaasCardType.isCredit() && !productPromotionService.isEligibleForRequestAsaasCreditCard(customer)) {
                DomainUtils.addError(asaasCard, "Não é possível solicitar um cartão de crédito.")
                return asaasCard
            }
        }

        AsaasCardHolder cardHolder = asaasCardHolderService.validateRequestParams(params)
        if (cardHolder.hasErrors()) {
            asaasCard = DomainUtils.copyAllErrorsFromObject(cardHolder, asaasCard)
            return asaasCard
        }

        return asaasCard
    }

    private String formatTextForFourthLine(String fourthLineText) {
        if (!fourthLineText) return null

        String textWithoutSpecialChars = Normalizer.normalize(fourthLineText, Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", "").replaceAll("[^\\w^\\s]", "")
        textWithoutSpecialChars = textWithoutSpecialChars.trim().toUpperCase()

        if (textWithoutSpecialChars.size() <= AsaasCard.FOURTH_LINE_MAX_SIZE) return textWithoutSpecialChars

        String truncatedAtMaxSize = textWithoutSpecialChars.take(AsaasCard.FOURTH_LINE_MAX_SIZE).trim()
        Boolean firstCharAfterTruncateIsBlank = textWithoutSpecialChars[AsaasCard.FOURTH_LINE_MAX_SIZE].isAllWhitespace()

        if (firstCharAfterTruncateIsBlank || !truncatedAtMaxSize.contains(" ")) return truncatedAtMaxSize

        String truncatedAtFirstLetterOfLastWord = textWithoutSpecialChars.take(AsaasCard.FOURTH_LINE_MAX_SIZE).trim().replaceAll("\\B\\S*\$", "")

        return truncatedAtFirstLetterOfLastWord
    }

    private Map parseSaveParams(Customer customer, Map params) {
        if (params.birthDate && params.birthDate instanceof String) params.birthDate = CustomDateUtils.fromString(params.birthDate, 'dd/MM/yy', false)
        if (params.postalCode) params.postalCode = Utils.removeNonNumeric(params.postalCode)
        if (params.cpfCnpj) params.cpfCnpj = Utils.removeNonNumeric(params.cpfCnpj)
        if (params.mobilePhone) params.mobilePhone = Utils.removeNonNumeric(params.mobilePhone)
        if (params.groupId) params.groupId = Utils.toLong(params.groupId)

        asaasCardHolderService.setAddressFromPostalCode(params)

        Map holderInfo = asaasCardHolderService.getHolderInfoIfNecessary(customer)
        if (holderInfo) {
            Boolean shouldFillPersonalInfo =  (!params.name && !params.cpfCnpj && !params.birthDate)
            if (shouldFillPersonalInfo) {
                params.name = holderInfo.name
                params.cpfCnpj = holderInfo.cpfCnpj
                params.birthDate = holderInfo.birthDate
            }
            if (!params.mobilePhone) params.mobilePhone = holderInfo.mobilePhone
            if (!params.email) params.email = holderInfo.email
        }

        return params
    }

    private AsaasCardAdapter syncWithExternalCard(AsaasCard asaasCard) {
        AsaasCardAdapter asaasCardAdapter = getExternalCardInfo(asaasCard)
        asaasCardAdapter.asaasCard = synchronizeEloCardInfo(asaasCard, asaasCardAdapter)

        return asaasCardAdapter
    }

    private AsaasCard synchronizeEloCardInfo(AsaasCard asaasCard, AsaasCardAdapter asaasCardAdapter) {
        asaasCard.status = asaasCardAdapter.status
        if (asaasCard.type.isPrepaid()) asaasCard.cachedBalance = asaasCardAdapter.cachedBalance

        if (asaasCardAdapter.embossingName) asaasCard.embossingName = asaasCardAdapter.embossingName

        if (asaasCard.status.isActive()) {
            if (asaasCardAdapter.dueDate) asaasCard.dueDate = asaasCardAdapter.dueDate
            if (asaasCardAdapter.maskedNumber) {
                asaasCard.firstDigits = StringUtils.removeWhitespaces(asaasCardAdapter.maskedNumber)[0..5]
                asaasCard.lastDigits = asaasCardAdapter.maskedNumber[-4..-1]
            }
        }

        asaasCard.save(failOnError: true)
        return asaasCard
    }

    private AsaasCard validateActivation(AsaasCard asaasCard, Map params) {
        BusinessValidation validatedBusiness = asaasCard.canActivate()
        if (!validatedBusiness.isValid()) {
            DomainUtils.addError(asaasCard, validatedBusiness.asaasErrors[0].getMessage())
            return asaasCard
        }

        if (!params.lastDigits || params.lastDigits.toString().length() != 4) {
            DomainUtils.addError(asaasCard, "Informe os últimos 4 dígitos do cartão.")
            return asaasCard
        }

        if (!params.token) {
            Boolean criticalActionConfigAsaasCardStatusManipulation = CustomerCriticalActionConfig.query([column: "asaasCardStatusManipulation", customerId: asaasCard.customerId]).get()
            if (criticalActionConfigAsaasCardStatusManipulation) {
                DomainUtils.addError(asaasCard, "É necessário informar o código de ativação.")
                return asaasCard
            }
        }

        asaasCard = validatePin(asaasCard, params)
        if (asaasCard.hasErrors()) return asaasCard

        return asaasCard
    }

    private AsaasCard validatePin(AsaasCard asaasCard, Map params) {
        if (!params.pin) {
            DomainUtils.addError(asaasCard, "É necessário informar uma senha para o cartão.")
            return asaasCard
        }

        if (params.pin.toString().length() != 4 || Utils.removeNonNumeric(params.pin.toString()).length() != params.pin.toString().length()) {
            DomainUtils.addError(asaasCard, "A senha informada é inválida.")
            return asaasCard
        }

        if (params.pin != params.pinConfirmation) {
            DomainUtils.addError(asaasCard, "A confirmação de senha é inválida. Informe uma senha igual para os dois campos.")
            return asaasCard
        }

        return asaasCard
    }

    private String buildReissueCriticalActionHash(AsaasCard asaasCard) {
        String operation = ""
        operation += asaasCard.id.toString()
        operation += asaasCard.customer.id.toString()

        if (!operation) throw new RuntimeException("Operação não suportada!")
        return operation.encodeAsMD5()
    }

    private String buildChangeCreditEnabledLimitCriticalActionHash(AsaasCard asaasCard) {
        String operation = ""
        operation += asaasCard.id.toString()
        operation += asaasCard.customer.id.toString()

        if (!operation) throw new RuntimeException("Operação não suportada!")
        return operation.encodeAsMD5()
    }

    private String buildRequestCardCriticalActionHash(Customer customer) {
        String operation = customer.id.toString()

        if (!operation) throw new RuntimeException("Operação não suportada!")
        return operation.encodeAsMD5()
    }

    private BigDecimal getMastercardRequestFee(Customer customer) {
        Integer masterCardCount = AsaasCard.query([customer: customer, brand: AsaasCardBrand.MASTERCARD]).count()

        if (customer.isLegalPerson()) {
            if (masterCardCount >= AsaasCard.TOTAL_FREE_CARDS_FOR_LEGAL_PERSON) return ChargedFee.ELO_CARD_REQUEST_FEE
        } else {
            Boolean hasFirstRequestFreeOfCharge = CustomerParameter.getValue(customer, CustomerParameterName.FIRST_ASAAS_CARD_REQUEST_FREE_OF_CHARGE)
            Integer activeEloCardCount = AsaasCard.query([customer: customer, brand: AsaasCardBrand.ELO, status: AsaasCardStatus.ACTIVE]).count()

            final Boolean isExceededSameQuantityOfEloCardsRequested = activeEloCardCount <= masterCardCount
            final Boolean doesntHaveFirstCardFreeOrAlreadyRequested = !hasFirstRequestFreeOfCharge || masterCardCount > 0
            if (isExceededSameQuantityOfEloCardsRequested && doesntHaveFirstCardFreeOrAlreadyRequested) return ChargedFee.ELO_CARD_REQUEST_FEE
        }

        return 0
    }
}
