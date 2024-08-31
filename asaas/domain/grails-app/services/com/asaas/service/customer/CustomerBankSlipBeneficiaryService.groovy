package com.asaas.service.customer

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.AsyncActionType
import com.asaas.customer.CustomerBankSlipBeneficiaryStatus
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBankSlipBeneficiary
import com.asaas.domain.payment.Payment
import com.asaas.integration.jdnpc.utils.JdNpcUtils
import com.asaas.integration.jdpsti.adapter.BeneficiaryResponseAdapter
import com.asaas.integration.jdpsti.adapter.BeneficiaryStatusResponseAdapter
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerBankSlipBeneficiaryService {

    def asyncActionService
    def jdPstiBeneficiaryManagerService

    public void registerBeneficiaryIfNecessary(Long boletoBankId, Customer customer) {
        if (customer.cpfCnpj) {
            if (boletoBankId == Payment.ASAAS_ONLINE_BOLETO_BANK_ID) {
                processAsaasBeneficiaryRegister(boletoBankId, customer)
            }
        }
    }

    public void processAsaasBeneficiaryRegisterWaitingResponse() {
        if (JdNpcUtils.isUnavailableApiSchedule()) return

        final Integer max = 100

        for (Map asyncActionData : asyncActionService.listPendingBeneficiaryRegisterWaitingConfirmation(max)) {
            Utils.withNewTransactionAndRollbackOnError({
                BeneficiaryResponseAdapter beneficiaryResponseAdapter = jdPstiBeneficiaryManagerService.processCipRequestStatusUpdate(asyncActionData.partnerControlNumber, asyncActionData.requestId)

                if (beneficiaryResponseAdapter.beneficiaryStatus && !beneficiaryResponseAdapter.beneficiaryStatus.isPending()) {
                    CustomerBankSlipBeneficiary bankSlipBeneficiary = CustomerBankSlipBeneficiary.query([customerId: Utils.toLong(asyncActionData.customerId), boletoBankId: Payment.ASAAS_ONLINE_BOLETO_BANK_ID]).get()
                    bankSlipBeneficiary.status = beneficiaryResponseAdapter.beneficiaryStatus
                    bankSlipBeneficiary.save(failOnError: true)

                    if (beneficiaryResponseAdapter.beneficiaryStatus.isFailedOrRejected()) {
                        AsaasLogger.error("CustomerBankSlipBeneficiaryService.processAsaasBeneficiaryRegisterWaitingResponse >> Falha ao atualizar status de beneficiário : Customer [${asyncActionData.customerId}] : [${beneficiaryResponseAdapter.errorData}]")
                    }
                    asyncActionService.delete(asyncActionData.asyncActionId)
                } else {
                    AsyncActionStatus asyncActionStatus = asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId).status
                    if (asyncActionStatus.isCancelled() && asyncActionData.attempt > AsyncActionType.BENEFICIARY_REGISTER_WAITING_CONFIRMATION.maxAttempts) {
                        AsaasLogger.warn("CustomerBankSlipBeneficiaryService.processAsaasBeneficiaryRegisterWaitingResponse >>> Processamento assíncrono de status de beneficiário foi cancelado por ultrapassar o máximo de tentativas. [asyncActionData: ${asyncActionData}]")
                    }
                }
            }, [logErrorMessage: "CustomerBankSlipBeneficiaryService.processAsaasBeneficiaryRegisterWaitingResponse >> Erro ao processar AsyncAction [${asyncActionData.asyncActionId}]", onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public void processAsaasBeneficiaryRemovalWaitingResponse() {
        if (JdNpcUtils.isUnavailableApiSchedule()) return

        final Integer max = 100

        for (Map asyncActionData : asyncActionService.listPendingBeneficiaryRemovalWaitingConfirmation(max)) {
            Utils.withNewTransactionAndRollbackOnError({
                BeneficiaryResponseAdapter beneficiaryResponseAdapter = jdPstiBeneficiaryManagerService.processCipRequestStatusUpdate(asyncActionData.partnerControlNumber, asyncActionData.requestId)

                beneficiaryResponseAdapter.beneficiaryStatus = CustomerBankSlipBeneficiaryStatus.APPROVED
                if (beneficiaryResponseAdapter.beneficiaryStatus && !beneficiaryResponseAdapter.beneficiaryStatus.isPending()) {
                    if (beneficiaryResponseAdapter.beneficiaryStatus.isFailedOrRejected()) {
                        AsaasLogger.error("CustomerBankSlipBeneficiaryService.processAsaasBeneficiaryRemovalWaitingResponse >> Falha ao remover conta de beneficiário : Customer [${asyncActionData.customerId}] : [${beneficiaryResponseAdapter.errorData}]")
                    } else {
                        if (beneficiaryResponseAdapter.beneficiaryStatus.isApproved()) removeBeneficiary(asyncActionData.customerId, Payment.ASAAS_ONLINE_BOLETO_BANK_ID)
                    }
                    asyncActionService.delete(asyncActionData.asyncActionId)
                } else {
                    AsyncActionStatus asyncActionStatus = asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId).status
                    if (asyncActionStatus.isCancelled()) {
                        AsaasLogger.error("CustomerBankSlipBeneficiaryService.processAsaasBeneficiaryRemovalWaitingResponse >>> Processamento assíncrono de status de remoção de beneficiário foi cancelado por ultrapassar o máximo de tentativas. [asyncActionId: ${asyncActionData.asyncActionId}]")
                    }
                }
            }, [logErrorMessage: "CustomerBankSlipBeneficiaryService.processAsaasBeneficiaryRemovalWaitingResponse >> Erro ao processar AsyncAction [${asyncActionData.asyncActionId}]", onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public void removeAsaasBeneficiaryAccount(Long boletoBankId, Customer customer) {
        Boolean existsBeneficiaryWithCpfCnpj = CustomerBankSlipBeneficiary.query([exists: true, cpfCnpj: customer.cpfCnpj, boletoBankId: boletoBankId, "customer[ne]": customer, "status[notIn]": [CustomerBankSlipBeneficiaryStatus.FAILED, CustomerBankSlipBeneficiaryStatus.REJECTED]]).get().asBoolean()

        BeneficiaryResponseAdapter registerResponse
        if (existsBeneficiaryWithCpfCnpj) {
            registerResponse = jdPstiBeneficiaryManagerService.registerOrUpdate(customer, true, true)
        } else {
            registerResponse = jdPstiBeneficiaryManagerService.remove(customer)
            if (registerResponse.beneficiaryStatus.isApproved()) removeBeneficiary(customer.id, boletoBankId)
        }

        if (registerResponse.beneficiaryStatus.isFailedOrRejected()) {
            AsaasLogger.error("CustomerBankSlipBeneficiaryService.removeAsaasBeneficiaryAccount >> Falha ao remover conta de beneficiário : Customer [${customer.id}] : [${registerResponse.errorData}]")
        }
    }

    public CustomerBankSlipBeneficiary processAsaasBeneficiaryRegister(Long boletoBankId, Customer customer) {
        CustomerBankSlipBeneficiary customerBankSlipBeneficiary = CustomerBankSlipBeneficiary.query([customer: customer, boletoBankId: boletoBankId]).get()

        if (ignoreBeneficiaryRegister(customerBankSlipBeneficiary)) return customerBankSlipBeneficiary

        if (!customerBankSlipBeneficiary) {
            customerBankSlipBeneficiary = saveBeneficiary(boletoBankId, customer)
        }

        if (customerBankSlipBeneficiary.cpfCnpj != customer.cpfCnpj) {
            updateCpfCnpj(customerBankSlipBeneficiary, customer.cpfCnpj)
        }

        Boolean existsBeneficiaryWithCpfCnpj = CustomerBankSlipBeneficiary.query([exists: true, cpfCnpj: customer.cpfCnpj, boletoBankId: boletoBankId, "customer[ne]": customer, "status[notIn]": [CustomerBankSlipBeneficiaryStatus.FAILED, CustomerBankSlipBeneficiaryStatus.REJECTED]]).get().asBoolean()

        BeneficiaryResponseAdapter registerResponse = jdPstiBeneficiaryManagerService.registerOrUpdate(customer, existsBeneficiaryWithCpfCnpj, false)

        if (registerResponse) {
            customerBankSlipBeneficiary = updateRegistrationInfo(customerBankSlipBeneficiary, registerResponse.beneficiaryStatus, registerResponse.externalIdentifier, registerResponse.errorData)

            if (registerResponse.beneficiaryStatus.isFailedOrRejected()) {
                AsaasLogger.error("CustomerBankSlipBeneficiaryService.processAsaasBeneficiaryRegister >> Falha ao cadastrar beneficiário : Customer [${customer.id}] : [${registerResponse.errorData}]")
            }
        }

        return customerBankSlipBeneficiary
    }

    public void createAsyncBeneficiaryRegistrationIfNecessary(Long boletoBankId, Customer customer) {
        if (!boletoBankId) return
        if (!customer.getAccountNumber()) return
        if (!customer.hadGeneralApproval()) return

        CustomerBankSlipBeneficiary customerBankSlipBeneficiary = CustomerBankSlipBeneficiary.query([customerId: customer.id, boletoBankId: boletoBankId]).get()
        if (ignoreBeneficiaryRegister(customerBankSlipBeneficiary)) return

        asyncActionService.savePendingBeneficiaryRegister(boletoBankId, customer.id)
    }

    public void processPendingBeneficiaryRegisters() {
        if (JdNpcUtils.isUnavailableApiSchedule()) return

        final Integer max = 50
        for (Map asyncActionData : asyncActionService.listPendingBeneficiaryRegister(max)) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(asyncActionData.customerId)
                registerBeneficiaryIfNecessary(asyncActionData.boletoBankId, customer)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "CustomerBankSlipBeneficiaryService.processPendingBeneficiaryRegisters >> Erro ao processar AsyncAction [${asyncActionData.asyncActionId}]", onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public BeneficiaryStatusResponseAdapter getStatusFromCipAndUpdateBeneficiary(Customer customer, Long boletoBankId) {
        BeneficiaryStatusResponseAdapter beneficiaryStatusResponseAdapter = jdPstiBeneficiaryManagerService.getBeneficiaryStatus(customer)

        if (beneficiaryStatusResponseAdapter.status) {
            List<CustomerBankSlipBeneficiary> customerBankSlipBeneficiaryList = CustomerBankSlipBeneficiary.query([cpfCnpj: customer.cpfCnpj, boletoBankId: boletoBankId, "status[notIn]": [CustomerBankSlipBeneficiaryStatus.FAILED, CustomerBankSlipBeneficiaryStatus.REJECTED], "withDifferentCipStatus": true, "cipStatus": beneficiaryStatusResponseAdapter.status, "lastCipStatusDate": beneficiaryStatusResponseAdapter.lastStatusDate]).list()

            for (CustomerBankSlipBeneficiary customerBankSlipBeneficiary : customerBankSlipBeneficiaryList) {
                customerBankSlipBeneficiary.cipStatus = beneficiaryStatusResponseAdapter.status
                customerBankSlipBeneficiary.lastCipStatusDate = beneficiaryStatusResponseAdapter.lastStatusDate
                customerBankSlipBeneficiary.save(failOnError: true)

                if (customerBankSlipBeneficiary.hasErrors()) {
                    AsaasLogger.error("CustomerBankSlipBeneficiaryService.getStatusFromCipAndUpdateBeneficiary >> Falha ao atualizar status CIP do beneficiário : Customer [${customer.id}] : [${customerBankSlipBeneficiary.errors.allErrors[0]}]")
                }
            }
        }

        return beneficiaryStatusResponseAdapter
    }

    private boolean ignoreBeneficiaryRegister(CustomerBankSlipBeneficiary customerBankSlipBeneficiary) {
        if (!customerBankSlipBeneficiary) return false
        if (customerBankSlipBeneficiary.status.isFailedOrRejected()) return false
        if (customerBankSlipBeneficiary.cpfCnpj != customerBankSlipBeneficiary.customer.cpfCnpj) return false

        return true
    }

    private void removeBeneficiary(Long customerId, Long boletoBankId) {
        CustomerBankSlipBeneficiary customerBankSlipBeneficiary = CustomerBankSlipBeneficiary.query([customerId: customerId, boletoBankId: boletoBankId]).get()
        customerBankSlipBeneficiary.deleted = true
        customerBankSlipBeneficiary.save(flush: true, failOnError: true)
    }

    private CustomerBankSlipBeneficiary saveBeneficiary(Long boletoBankId, Customer customer) {
        CustomerBankSlipBeneficiary customerBankSlipBeneficiary = new CustomerBankSlipBeneficiary()
        customerBankSlipBeneficiary.customer = customer
        customerBankSlipBeneficiary.cpfCnpj = customer.cpfCnpj
        customerBankSlipBeneficiary.boletoBank = BoletoBank.read(boletoBankId)
        customerBankSlipBeneficiary.status = CustomerBankSlipBeneficiaryStatus.PENDING

        customerBankSlipBeneficiary.save(failOnError: true, deepValidate: false)

        return customerBankSlipBeneficiary
    }

    private CustomerBankSlipBeneficiary updateRegistrationInfo(CustomerBankSlipBeneficiary customerBankSlipBeneficiary, CustomerBankSlipBeneficiaryStatus status, String externalIdentifier, String errorMessage) {
        customerBankSlipBeneficiary.status = status
        customerBankSlipBeneficiary.externalIdentifier = externalIdentifier
        customerBankSlipBeneficiary.errorMessage = Utils.truncateString(errorMessage, 255)

        customerBankSlipBeneficiary.save(failOnError: true, deepValidate: false)

        return customerBankSlipBeneficiary
    }

    private CustomerBankSlipBeneficiary updateCpfCnpj(CustomerBankSlipBeneficiary customerBankSlipBeneficiary, String customerCpfCnpj) {
        customerBankSlipBeneficiary.cpfCnpj = customerCpfCnpj
        customerBankSlipBeneficiary.save(failOnError: true)

        return customerBankSlipBeneficiary
    }
}
