package com.asaas.domain.criticalAction

import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.login.UserKnownDevice
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.exception.BusinessException
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CriticalActionConfigService {

    def asaasSegmentioService
    def customerInteractionService
    def securityEventNotificationService

    public CustomerCriticalActionConfig save(Customer customer) {
        Boolean customerHasCriticalActionConfig = CustomerCriticalActionConfig.query([exists: true, customerId: customer.id]).get().asBoolean()
        if (customerHasCriticalActionConfig) throw new RuntimeException("O cliente [${customer.id}] já possui CriticalActionConfig")

    	CustomerCriticalActionConfig config = new CustomerCriticalActionConfig(customer: customer)
    	config.save(failOnError: true, flush: true)

    	return config
    }

    public Boolean isAllowedToUpdateConfig(Customer customer) {
        if (CustomerPartnerApplication.hasBradesco(customer.id)) return false

        if (!CustomerCriticalActionConfig.query([exists: true, customerId: customer.id]).get().asBoolean()) return false

        return true
    }

    private CriticalAction saveCriticalActionIfNotExists(Customer customer, CriticalActionType type) {
        CriticalAction action = CriticalAction.pendingOrAwaitingAuthorization([customer: customer, type: type]).get()
        if (action) return action

        return CriticalAction.save(customer, type, null)
    }

    private void cancelCriticalActionIfExists(Customer customer, CriticalActionType type) {
        CriticalAction action = CriticalAction.pendingOrAwaitingAuthorization([customer: customer, type: type]).get()
        if (action) action.cancel()
    }

    public CustomerCriticalActionConfig update(Customer customer, Map params, UserKnownDevice userKnownDevice) {
        BusinessValidation businessValidation = validateUpdate(customer, userKnownDevice)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        Map parsedParams = parseUpdateParam(params)

        CustomerCriticalActionConfig customerCriticalActionConfig = CustomerCriticalActionConfig.query([customerId: customer.id]).get()

        saveCriticalActionEnableInteractionIfNecessary(customer, parsedParams)

       if (parsedParams.containsKey("transfer")) {
           if (Boolean.valueOf(parsedParams.transfer)) {
               customerCriticalActionConfig.transfer = true
               customerCriticalActionConfig = setPixTransactionAsNullIfPossible(customerCriticalActionConfig)
               cancelCriticalActionIfExists(customer, CriticalActionType.DISABLE_TRANSFER)
           } else if (customerCriticalActionConfig.transfer) {
               saveCriticalActionIfNotExists(customer, CriticalActionType.DISABLE_TRANSFER)
           }
       }

        if (parsedParams.containsKey("pixTransactionCreditRefund")) {
            if (Boolean.valueOf(parsedParams.pixTransactionCreditRefund)) {
                customerCriticalActionConfig.pixTransactionCreditRefund = true
                customerCriticalActionConfig = setPixTransactionAsNullIfPossible(customerCriticalActionConfig)
                cancelCriticalActionIfExists(customer, CriticalActionType.DISABLE_PIX_TRANSACTION_CREDIT_REFUND)
            } else if (customerCriticalActionConfig.isPixTransactionCreditRefundAuthorizationEnabled()) {
                saveCriticalActionIfNotExists(customer, CriticalActionType.DISABLE_PIX_TRANSACTION_CREDIT_REFUND)
            }
        }

        if (parsedParams.containsKey("bankAccountUpdate")) {
           if (Boolean.valueOf(parsedParams.bankAccountUpdate)) {
               customerCriticalActionConfig.bankAccountUpdate = true
               cancelCriticalActionIfExists(customer, CriticalActionType.DISABLE_BANK_ACCOUNT_UPDATE)
           } else if (customerCriticalActionConfig.bankAccountUpdate) {
               saveCriticalActionIfNotExists(customer, CriticalActionType.DISABLE_BANK_ACCOUNT_UPDATE)
           }
       }

       if (parsedParams.containsKey("userUpdate")) {
           if (Boolean.valueOf(parsedParams.userUpdate)) {
               customerCriticalActionConfig.userUpdate = true
               cancelCriticalActionIfExists(customer, CriticalActionType.DISABLE_USER_UPDATE)
           } else if (customerCriticalActionConfig.userUpdate) {
               saveCriticalActionIfNotExists(customer, CriticalActionType.DISABLE_USER_UPDATE)
           }
       }

       if (parsedParams.containsKey("bill")) {
           if (Boolean.valueOf(parsedParams.bill)) {
               customerCriticalActionConfig.bill = true
               cancelCriticalActionIfExists(customer, CriticalActionType.DISABLE_BILL)
           } else if (customerCriticalActionConfig.bill) {
               saveCriticalActionIfNotExists(customer, CriticalActionType.DISABLE_BILL)
           }
       }

       if (parsedParams.containsKey("commercialInfoUpdate")) {
           if (Boolean.valueOf(parsedParams.commercialInfoUpdate)) {
               customerCriticalActionConfig.commercialInfoUpdate = true
               cancelCriticalActionIfExists(customer, CriticalActionType.DISABLE_COMMERCIAL_INFO_UPDATE)
           } else if (customerCriticalActionConfig.commercialInfoUpdate) {
               saveCriticalActionIfNotExists(customer, CriticalActionType.DISABLE_COMMERCIAL_INFO_UPDATE)
           }
       }

       if (parsedParams.containsKey("asaasCardRecharge")) {
           if (Boolean.valueOf(parsedParams.asaasCardRecharge)) {
               customerCriticalActionConfig.asaasCardRecharge = true
               cancelCriticalActionIfExists(customer, CriticalActionType.DISABLE_ASAAS_CARD_RECHARGE)
           } else if (customerCriticalActionConfig.asaasCardRecharge) {
               saveCriticalActionIfNotExists(customer, CriticalActionType.DISABLE_ASAAS_CARD_RECHARGE)
           }
       }

       if (parsedParams.containsKey("asaasCardStatusManipulation")) {
           if (Boolean.valueOf(parsedParams.asaasCardStatusManipulation)) {
               customerCriticalActionConfig.asaasCardStatusManipulation = true
               cancelCriticalActionIfExists(customer, CriticalActionType.DISABLE_ASAAS_CARD_MANIPULATION)
           } else if (customerCriticalActionConfig.asaasCardStatusManipulation) {
               saveCriticalActionIfNotExists(customer, CriticalActionType.DISABLE_ASAAS_CARD_MANIPULATION)
           }
       }

       if (parsedParams.containsKey("pixTransaction")) {
           if (Boolean.valueOf(parsedParams.pixTransaction)) {
               customerCriticalActionConfig.pixTransaction = customerCriticalActionConfig.transfer ? null : true
               cancelCriticalActionIfExists(customer, CriticalActionType.DISABLE_FOR_PIX_TRANSACTION)
           } else if (customerCriticalActionConfig.pixTransaction) {
               saveCriticalActionIfNotExists(customer, CriticalActionType.DISABLE_FOR_PIX_TRANSACTION)
           }
       }

       if (parsedParams.containsKey("mobilePhoneRecharge")) {
           if (Boolean.valueOf(parsedParams.mobilePhoneRecharge)) {
               customerCriticalActionConfig.mobilePhoneRecharge = true
               cancelCriticalActionIfExists(customer, CriticalActionType.DISABLE_MOBILE_PHONE_RECHARGE)
           } else if (customerCriticalActionConfig.mobilePhoneRecharge) {
               saveCriticalActionIfNotExists(customer, CriticalActionType.DISABLE_MOBILE_PHONE_RECHARGE)
           }
       }

       customerCriticalActionConfig.save(failOnError: true)

       return customerCriticalActionConfig
    }

    private Map parseUpdateParam(Map params) {
        Map parsedParams = params

        if (params.allowDisableCheckoutCriticalAction) return parsedParams

        if (params.containsKey("transfer") && Boolean.valueOf(params.transfer) == false) {
            parsedParams.remove("transfer")
        }

        if (params.containsKey("pixTransaction") && Boolean.valueOf(params.pixTransaction) == false) {
            parsedParams.remove("pixTransaction")
        }

        if (params.containsKey("bill") && Boolean.valueOf(params.bill) == false) {
            parsedParams.remove("bill")
        }

        if (params.containsKey("mobilePhoneRecharge") && Boolean.valueOf(params.mobilePhoneRecharge) == false) {
            parsedParams.remove("mobilePhoneRecharge")
        }

        return parsedParams
    }

    public void onCriticalActionAuthorization(CriticalAction action) {
        CustomerCriticalActionConfig criticalActionConfig = CustomerCriticalActionConfig.query([customerId: action.customerId]).get()

        String criticalActionConfigDisabledMessage = ""

        if (action.type == CriticalActionType.DISABLE_TRANSFER) {
            criticalActionConfig.transfer = false
            criticalActionConfigDisabledMessage = Utils.getMessageProperty("customerCriticalAction.transfer")
        } else if (action.type == CriticalActionType.DISABLE_USER_UPDATE) {
            criticalActionConfig.userUpdate = false
            criticalActionConfigDisabledMessage = Utils.getMessageProperty("customerCriticalAction.userUpdate")
        } else if (action.type == CriticalActionType.DISABLE_BANK_ACCOUNT_UPDATE) {
            criticalActionConfig.bankAccountUpdate = false
            criticalActionConfigDisabledMessage = Utils.getMessageProperty("customerCriticalAction.bankAccountUpdate")
        } else if (action.type == CriticalActionType.DISABLE_BILL) {
            criticalActionConfig.bill = false
            criticalActionConfigDisabledMessage = Utils.getMessageProperty("customerCriticalAction.bill")
        } else if (action.type == CriticalActionType.DISABLE_MOBILE_PHONE_RECHARGE) {
            criticalActionConfig.mobilePhoneRecharge = false
            criticalActionConfigDisabledMessage = Utils.getMessageProperty("customerCriticalAction.mobilePhoneRecharge")
        } else if (action.type == CriticalActionType.DISABLE_COMMERCIAL_INFO_UPDATE) {
            criticalActionConfig.commercialInfoUpdate = false
            criticalActionConfigDisabledMessage = Utils.getMessageProperty("customerCriticalAction.commercialInfoUpdate")
        } else if (action.type == CriticalActionType.DISABLE_ASAAS_CARD_RECHARGE) {
            criticalActionConfig.asaasCardRecharge = false
            criticalActionConfigDisabledMessage = Utils.getMessageProperty("customerCriticalAction.asaasCardRecharge")
        } else if (action.type == CriticalActionType.DISABLE_ASAAS_CARD_MANIPULATION) {
            criticalActionConfig.asaasCardStatusManipulation = false
            criticalActionConfigDisabledMessage = Utils.getMessageProperty("customerCriticalAction.asaasCardStatusManipulation")
        } else if (action.type == CriticalActionType.DISABLE_FOR_PIX_TRANSACTION) {
            criticalActionConfig.pixTransaction = false
            criticalActionConfigDisabledMessage = Utils.getMessageProperty("customerCriticalAction.pixTransaction")
        } else if (action.type == CriticalActionType.DISABLE_PIX_TRANSACTION_CREDIT_REFUND) {
            criticalActionConfig.pixTransactionCreditRefund = false
            criticalActionConfigDisabledMessage = Utils.getMessageProperty("customerCriticalAction.pixTransactionCreditRefund")
        }

        criticalActionConfig = setPixTransactionAsNullIfPossible(criticalActionConfig)
        criticalActionConfig.save(failOnError: true)

        securityEventNotificationService.notifyAndSaveHistoryAboutCriticalActionConfigDisabled(action.group.authorizer, criticalActionConfigDisabledMessage)
    }

    public void disableAll(Customer customer) {
        CustomerCriticalActionConfig criticalActionConfig = CustomerCriticalActionConfig.query([customerId: customer.id]).get()
        toggleAll(criticalActionConfig, false)
    }

    public void enableAll(Customer customer) {
        CustomerCriticalActionConfig criticalActionConfig = CustomerCriticalActionConfig.query([customerId: customer.id]).get()
        toggleAll(criticalActionConfig, true)
    }

    private BusinessValidation validateUpdate(Customer customer, UserKnownDevice userKnownDevice) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (userKnownDevice && !userKnownDevice.trustedToCheckout) {
            asaasSegmentioService.track(userKnownDevice.user.customerId, "device_trustable_validation", [process: "critical_action_config_update"])
            businessValidation.addError("default.notAllowed.message")
            return businessValidation
        }

        if (!isAllowedToUpdateConfig(customer)) {
            businessValidation.addError("default.notAllowed.message")
            return businessValidation
        }

        return businessValidation
    }

    private void toggleAll(CustomerCriticalActionConfig criticalActionConfig, Boolean enable) {
        criticalActionConfig.transfer = enable
        criticalActionConfig.commercialInfoUpdate = enable
        criticalActionConfig.bankAccountUpdate = enable
        criticalActionConfig.userUpdate = enable
        criticalActionConfig.bill = enable
        criticalActionConfig.asaasCardRecharge = enable
        criticalActionConfig.asaasCardStatusManipulation = enable
        criticalActionConfig.pixTransaction = enable
        criticalActionConfig.mobilePhoneRecharge = enable
        criticalActionConfig.save(failOnError: true)
    }

    private void saveCriticalActionEnableInteractionIfNecessary(Customer customer, params) {
        final Map criticalActions = [
                transfer: CriticalActionType.DISABLE_TRANSFER,
                bankAccountUpdate: CriticalActionType.DISABLE_BANK_ACCOUNT_UPDATE,
                userUpdate: CriticalActionType.DISABLE_USER_UPDATE,
                bill: CriticalActionType.DISABLE_BILL,
                commercialInfoUpdate: CriticalActionType.DISABLE_COMMERCIAL_INFO_UPDATE,
                asaasCardRecharge: CriticalActionType.DISABLE_ASAAS_CARD_RECHARGE,
                asaasCardStatusManipulation: CriticalActionType.DISABLE_ASAAS_CARD_MANIPULATION,
                pixTransaction: CriticalActionType.DISABLE_FOR_PIX_TRANSACTION,
                mobilePhoneRecharge: CriticalActionType.DISABLE_MOBILE_PHONE_RECHARGE]

        CustomerCriticalActionConfig customerCriticalActionConfig = CustomerCriticalActionConfig.query([customerId: customer.id]).get()

        params.each { key, value ->
            if (!criticalActions.containsKey(key)) return

            Boolean isCustomerCriticalActionEnabled = customerCriticalActionConfig[key]
            Boolean shouldEnableCriticalAction = Boolean.valueOf(value)

            if (isCustomerCriticalActionEnabled) return
            if (!shouldEnableCriticalAction) return

            customerInteractionService.saveCriticalActionEnableEvent(customer.id, criticalActions[key])
        }
    }

    public CustomerCriticalActionConfig updateByAdmin(Long customerId, List<String> propertiesList, Boolean enabled, CustomerCriticalActionConfig customerCriticalActionConfig) {
        if (propertiesList.contains("transfer")) {
            setPixTransactionAsNullIfPossible(customerCriticalActionConfig)
            customerCriticalActionConfig.transfer = enabled
        }

        if (propertiesList.contains("bankAccountUpdate")) {
            customerCriticalActionConfig.bankAccountUpdate = enabled
        }

        if (propertiesList.contains("userUpdate")) {
            customerCriticalActionConfig.userUpdate = enabled
        }

        if (propertiesList.contains("bill")) {
            customerCriticalActionConfig.bill = enabled
        }

        if (propertiesList.contains("commercialInfoUpdate")) {
            customerCriticalActionConfig.commercialInfoUpdate = enabled
        }

        if (propertiesList.contains("asaasCardRecharge")) {
            customerCriticalActionConfig.asaasCardRecharge = enabled
        }

        if (propertiesList.contains("asaasCardStatusManipulation")) {
            customerCriticalActionConfig.asaasCardStatusManipulation = enabled
        }

        if (propertiesList.contains("pixTransaction")) {
            customerCriticalActionConfig.pixTransaction = enabled
        }

        if (propertiesList.contains("mobilePhoneRecharge")) {
            customerCriticalActionConfig.mobilePhoneRecharge = enabled
        }

        customerCriticalActionConfig.save(failOnError: true)

        Map criticalActionInteraction = [customerId: customerId, description: "Alterando configuração de ações criticas para: [${enabled}] das seguintes ações: ${propertiesList}"]
        customerInteractionService.saveManualInteraction(criticalActionInteraction)

        return customerCriticalActionConfig
    }

    private CustomerCriticalActionConfig setPixTransactionAsNullIfPossible(CustomerCriticalActionConfig customerCriticalActionConfig) {
        if (customerCriticalActionConfig.pixTransaction == null) return customerCriticalActionConfig

        if (customerCriticalActionConfig.transfer == customerCriticalActionConfig.pixTransaction) {
            customerCriticalActionConfig.pixTransaction = null
        }

        return customerCriticalActionConfig
    }
}
