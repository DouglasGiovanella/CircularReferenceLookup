package com.asaas.service.receivableanticipationpartner

import com.asaas.billinginfo.BillingType
import com.asaas.billinginfo.ChargeType
import com.asaas.customeraccountstatistic.CustomerAccountStatisticName
import com.asaas.customerdocument.CustomerDocumentStatus
import com.asaas.customerdocument.CustomerDocumentType
import com.asaas.customerdocument.adapter.CustomerDocumentAdapter
import com.asaas.customerdocument.adapter.CustomerDocumentFileVersionAdapter
import com.asaas.customerstatistic.CustomerStatisticName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerStatistic
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.customeraccount.CustomerAccountStatistic
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.receivableanticipation.ReceivableAnticipationAsaasConfig
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisition
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerConfig
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerConfigDocument
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisitionStatus
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerConfigStatus
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerDocumentType
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationPartnerConfigService {

    def customerDocumentProxyService
    def receivableAnticipationValidationCacheService

    public ReceivableAnticipationPartner getReceivableAnticipationPartner(Customer customer, ChargeType chargeType, String customerAccountCpfCnpj, BillingType billingType, BigDecimal valueToBeAnticipated) {
        if (!ReceivableAnticipationAsaasConfig.getInstance().anticipationPartnerEnabled) return ReceivableAnticipationPartner.ASAAS

        if (billingType.isCreditCard()) return ReceivableAnticipationPartner.OCEAN

        ReceivableAnticipationPartnerConfig partnerConfig = ReceivableAnticipationPartnerConfig.query([customer: customer]).get()
        if (!partnerConfig) partnerConfig = save(customer)

        if (partnerConfig.vortxStatus.isDisabled()) return ReceivableAnticipationPartner.ASAAS
        if (customerAnticipationValueAboveAvailablePartnerLimit(customer, valueToBeAnticipated)) return ReceivableAnticipationPartner.ASAAS
        if (customerAccountCpfCnpjAnticipationValueAboveAvailablePartnerLimit(customerAccountCpfCnpj, valueToBeAnticipated)) return ReceivableAnticipationPartner.ASAAS

        if (billingType.isBoletoOrPix()) return ReceivableAnticipationPartner.VORTX
        if (!partnerConfig.creditCardEnabled) return ReceivableAnticipationPartner.ASAAS

        CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
        if (chargeType.isDetached() && customerReceivableAnticipationConfig.creditCardDetachedDailyFee == 0) return ReceivableAnticipationPartner.ASAAS
        if (chargeType.isInstallment() && customerReceivableAnticipationConfig.creditCardInstallmentDailyFee == 0) return ReceivableAnticipationPartner.ASAAS

        return ReceivableAnticipationPartner.VORTX
    }

    public ReceivableAnticipationPartnerConfig save(Customer customer) {
        ReceivableAnticipationPartnerConfig partnerConfig = ReceivableAnticipationPartnerConfig.query([customer: customer]).get()
        if (!partnerConfig) partnerConfig = new ReceivableAnticipationPartnerConfig(customer: customer)

        if (partnerConfig.vortxStatus?.isEnabled()) return partnerConfig

        partnerConfig.vortxStatus = receivableAnticipationValidationCacheService.anyAgreementVersionHasBeenSigned(customer.id)
            ? ReceivableAnticipationPartnerConfigStatus.PENDING
            : ReceivableAnticipationPartnerConfigStatus.AWAITING_CONTRACT_AGREEMENT

        partnerConfig.creditCardEnabled = true
        partnerConfig.oceanRegistrationEnabled = true
        partnerConfig.save(flush: true, failOnError: true)

        savePartnerConfigDocumentList(partnerConfig)

        return partnerConfig
    }

    public void setAsApproved(ReceivableAnticipationPartnerConfig partnerConfig) {
        partnerConfig.vortxStatus = ReceivableAnticipationPartnerConfigStatus.APPROVED
        partnerConfig.save(failOnError: true)
    }

    public void setAsDisabled(ReceivableAnticipationPartnerConfig partnerConfig) {
        partnerConfig.vortxStatus = ReceivableAnticipationPartnerConfigStatus.DISABLED
        partnerConfig.save(failOnError: true)
    }

    public void setPartnerConfigStatusAsPendingIfNecessary(CustomerUpdateRequest customerUpdateRequest) {
        Boolean cpfCnpjHasChanged = (customerUpdateRequest.provider.cpfCnpj != customerUpdateRequest.cpfCnpj)
        if (!cpfCnpjHasChanged) return

        ReceivableAnticipationPartnerConfig partnerConfig = ReceivableAnticipationPartnerConfig.processed([customer: customerUpdateRequest.provider]).get()
        if (!partnerConfig) return

        partnerConfig.vortxStatus = ReceivableAnticipationPartnerConfigStatus.PENDING
        partnerConfig.save(failOnError: true)
    }

    public void processPartnerContractAgreement(Customer customer) {
        ReceivableAnticipationPartnerConfig partnerConfig = ReceivableAnticipationPartnerConfig.query([customer: customer, vortxStatus: ReceivableAnticipationPartnerConfigStatus.AWAITING_CONTRACT_AGREEMENT]).get()

        if (!partnerConfig) return

        partnerConfig.vortxStatus = ReceivableAnticipationPartnerConfigStatus.PENDING
        partnerConfig.save(failOnError: true)
    }

    private Boolean customerAnticipationValueAboveAvailablePartnerLimit(Customer customer, BigDecimal valueToBeAnticipated) {
        BigDecimal customerCompromisedLimitWithPartner = getCustomerCompromisedLimitWithPartner(customer)

        ReceivableAnticipationAsaasConfig receivableAnticipationConfig = ReceivableAnticipationAsaasConfig.getInstance()
        BigDecimal bookValue = calculateBookValue(customerCompromisedLimitWithPartner, valueToBeAnticipated, receivableAnticipationConfig.fidcPdd)

        return bookValue >= receivableAnticipationConfig.fidcMaxCompromisedLimitPerCustomer
    }

    private BigDecimal getCustomerCompromisedLimitWithPartner(Customer customer) {
        List<ReceivableAnticipationPartnerAcquisitionStatus> partnerAcquisitionStatusList = ReceivableAnticipationPartnerAcquisitionStatus.getNotCompromisedStatusList()

        BigDecimal anticipationTotalValue = ReceivableAnticipationPartnerAcquisition.sumTotalValue([customer: customer, "status[notIn]": partnerAcquisitionStatusList]).get()
        BigDecimal settlementPaidValue = CustomerStatistic.getBigDecimalValue(customer, CustomerStatisticName.TOTAL_VALUE_DEBITED_WITH_ANTICIPATION_PARTNER)

        return anticipationTotalValue - settlementPaidValue
    }

    private Boolean customerAccountCpfCnpjAnticipationValueAboveAvailablePartnerLimit(String customerAccountCpfCnpj, BigDecimal valueToBeAnticipated) {
        if (!customerAccountCpfCnpj) return true

        BigDecimal customerAccountCompromisedLimitWithPartner = getCustomerAccountCompromisedLimitWithPartner(customerAccountCpfCnpj)

        ReceivableAnticipationAsaasConfig receivableAnticipationConfig = ReceivableAnticipationAsaasConfig.getInstance()
        BigDecimal bookValue = calculateBookValue(customerAccountCompromisedLimitWithPartner, valueToBeAnticipated, receivableAnticipationConfig.fidcPdd)

        return bookValue >= receivableAnticipationConfig.fidcMaxCompromisedLimitPerCustomerAccountCpfCnpj
    }

    private BigDecimal getCustomerAccountCompromisedLimitWithPartner(String customerAccountCpfCnpj) {
        List<ReceivableAnticipationPartnerAcquisitionStatus> partnerAcquisitionStatusList = ReceivableAnticipationPartnerAcquisitionStatus.getNotCompromisedStatusList()

        BigDecimal anticipationTotalValue = ReceivableAnticipationPartnerAcquisition.sumTotalValue([customerAccountCpfCnpj: customerAccountCpfCnpj, "status[notIn]": partnerAcquisitionStatusList]).get()
        BigDecimal settlementPaidValue = CustomerAccountStatistic.getBigDecimalValue(customerAccountCpfCnpj, CustomerAccountStatisticName.TOTAL_VALUE_DEBITED_WITH_ANTICIPATION_PARTNER)

        return anticipationTotalValue - settlementPaidValue
    }

    private BigDecimal calculateBookValue(BigDecimal compromisedValue, BigDecimal valueToBeAnticipated, BigDecimal fidcPdd) {
        return (compromisedValue + valueToBeAnticipated) - fidcPdd
    }

    private void savePartnerConfigDocumentList(ReceivableAnticipationPartnerConfig partnerConfig) {
        Map searchParams = [
            status: CustomerDocumentStatus.APPROVED,
            typeList: CustomerDocumentType.requiredForReceivableAnticipationWithPartnerList()]
        List<CustomerDocumentAdapter> customerDocumentAdapterList = customerDocumentProxyService.list(partnerConfig.customer.id, searchParams)
        if (!customerDocumentAdapterList) return

        for (CustomerDocumentAdapter customerDocumentAdapter : customerDocumentAdapterList) {
            List<CustomerDocumentFileVersionAdapter> customerDocumentFileVersionAdapterList = customerDocumentAdapter.customerDocumentFileList.collect { it.lastCustomerDocumentFileVersion }
            if (!customerDocumentFileVersionAdapterList) continue

            for (CustomerDocumentFileVersionAdapter customerDocumentFileVersionAdapter : customerDocumentFileVersionAdapterList) {
                ReceivableAnticipationPartnerConfigDocument partnerConfigDocument = new ReceivableAnticipationPartnerConfigDocument()
                partnerConfigDocument.partnerConfig = partnerConfig
                partnerConfigDocument.externalFileId = customerDocumentFileVersionAdapter.fileId
                partnerConfigDocument.type = ReceivableAnticipationPartnerDocumentType.parseCustomerDocumentType(customerDocumentAdapter.type)
                partnerConfigDocument.save(failOnError: true)
            }
        }
    }
}
