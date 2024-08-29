package com.asaas.service.creditbureaureport

import com.asaas.converter.HtmlToPdfConverter
import com.asaas.creditbureaureport.adapter.CreditBureauReportInfoAdapter
import com.asaas.customer.PersonType
import com.asaas.domain.creditbureaureport.CreditBureauReport
import com.asaas.domain.creditbureaureport.CreditBureauReportAgreement
import com.asaas.domain.creditbureaureport.CreditBureauReportInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.integration.creditbureaureport.enums.ResponseCode
import com.asaas.log.AsaasLogger
import com.asaas.referral.ReferralStatus
import com.asaas.referral.ReferralValidationOrigin
import com.asaas.state.State
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.RequestUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class CreditBureauReportService {

    def chargedFeeService
    def creditBureauReportAgreementService
    def creditBureauReportCustomerCostService
    def creditBureauReportInfoService
    def creditBureauReportSerasaQueryManagerService
    def creditBureauReportSerasaCustomerAccessionManagerService
    def customerMessageService
    def grailsLinkGenerator
    def groovyPageRenderer
    def originRequesterInfoService
    def referralService

    public CreditBureauReport save(Customer customer, User user, Map params) {
        Boolean isFirstCreditBureauReport = !CreditBureauReport.customerHasCreditBureauReport(customer)
        PersonType personType = PersonType.convert(params.personType)

        CustomerAccount customerAccount
        if (params.customerAccountId) {
            customerAccount = CustomerAccount.find(params.customerAccountId, customer.id)

            params.cpfCnpj = customerAccount.cpfCnpj
            params.state = params.state ?: customerAccount.getCityNameAndState().state
        }

        CreditBureauReport validatedCreditBureauReport = validateSave(customer, personType, params)
        if (validatedCreditBureauReport.hasErrors()) {
            return validatedCreditBureauReport
        }

        if (!CreditBureauReportAgreement.customerHasCurrentAgreementVersion(customer)) {
            CreditBureauReportAgreement creditBureauReportAgreement = saveAgreement(customer, user, params.remoteIp, params.userAgent, params.headers, Boolean.valueOf(params.acceptAgreement), params.terms)
            if (creditBureauReportAgreement.hasErrors()) {
                return DomainUtils.copyAllErrorsFromObject(creditBureauReportAgreement, new CreditBureauReport())
            }
        }

        CreditBureauReport creditBureauReport = new CreditBureauReport()
        creditBureauReport.customer = customer
        creditBureauReport.customerAccount = customerAccount
        creditBureauReport.cpfCnpj = params.cpfCnpj
        creditBureauReport.state = params.state
        creditBureauReport.publicId = UUID.randomUUID()
        creditBureauReport.cost = creditBureauReportCustomerCostService.calculateCost(customer, personType)

        creditBureauReport.save(failOnError: false)
        if (creditBureauReport.hasErrors()) return creditBureauReport

        chargedFeeService.saveCreditBureauReportFee(customer, creditBureauReport, personType)

        CreditBureauReportInfoAdapter creditBureauReportInfoAdapter
        CreditBureauReportInfo creditBureauReportInfo
        try {
            creditBureauReportInfoAdapter = creditBureauReportSerasaQueryManagerService.processQuery(creditBureauReport, personType, isFirstCreditBureauReport)

            creditBureauReportInfo = creditBureauReportInfoService.save(creditBureauReport, creditBureauReportInfoAdapter)
        } catch (BusinessException businessException) {
            transactionStatus.setRollbackOnly()
            DomainUtils.addError(creditBureauReport, businessException.message)
            return creditBureauReport
        } catch (Exception exception) {
            transactionStatus.setRollbackOnly()
            AsaasLogger.error("Erro ao consultar serasa para cliente ${customer.id}", exception)
            DomainUtils.addError(creditBureauReport, Utils.getMessageProperty("unknow.error"))
            return creditBureauReport
        }

        try {
            customerMessageService.sendCreditBureauReport(creditBureauReport, creditBureauReportInfo, buildCreditBureauReportFile(creditBureauReport))
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email com PDF da consulta serasa para cliente ${customer.id}", exception)
        }

        originRequesterInfoService.save(creditBureauReport)

        referralService.saveUpdateToValidatedReferralStatus(creditBureauReport.customerId, ReferralValidationOrigin.PRODUCT_USAGE)

        return creditBureauReport
    }

    public BusinessValidation canRequestCreditBureauReport(Customer customer, PersonType personType) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (customer.isNaturalPerson()) {
            businessValidation.addError("creditBureauReport.denied.legalPerson")
            return businessValidation
        }

        if (!customer.cpfCnpj || customer.isNotFullyApproved()) {
            businessValidation.addError("creditBureauReport.denied.isNotFullyApproved")
            return businessValidation
        }

        if (!customer.hasSufficientBalance(CreditBureauReport.getCustomerRequiredBalanceToCreditBureauReport(customer, personType))) {
            businessValidation.addError("creditBureauReport.creditBureauReportModal.hasInsufficientBalance")
            return businessValidation
        }

        try {
            Map validatedCustomerCreditBureauReportOnSerasa = creditBureauReportSerasaCustomerAccessionManagerService.validate(customer)
            if (validatedCustomerCreditBureauReportOnSerasa.success) return businessValidation

            businessValidation.addError(validatedCustomerCreditBureauReportOnSerasa.message ?: "unknow.error")
            if (!validatedCustomerCreditBureauReportOnSerasa.partnerCodeDescription) return businessValidation

            ResponseCode responseCode = ResponseCode.findByDescription(validatedCustomerCreditBureauReportOnSerasa.partnerCodeDescription)
            if (responseCode?.isIrregularCnpjStatusOnFederalRevenueDatabase()) return businessValidation

            AsaasLogger.error("Erro ao consultar serasa para cliente ${ customer.id } | ${ validatedCustomerCreditBureauReportOnSerasa.partnerCodeDescription }")
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao consultar serasa para cliente ${ customer.id }", exception)
            businessValidation.addError("unknow.error")
        }

        return businessValidation
    }

    public BusinessValidation validateIfCanBeDisplayed(User user) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (user.customer.isNaturalPerson()) {
            businessValidation.addError("creditBureauReport.denied.legalPerson")
            return businessValidation
        }

        if (!user.hasFinancialModulePermission()) {
            businessValidation.addError("default.notAllowed.message")
        }

        return businessValidation
    }

    public BusinessValidation validateIfCanBeRequested(CustomerAccount customerAccount) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (customerAccount.deleted) {
            businessValidation.addError("creditBureauReport.denied.customerAccount.deleted")
            return businessValidation
        }

        if (!customerAccount.cpfCnpj) {
            businessValidation.addError("creditBureauReport.denied.withoutCpfCnpj")
        }

        return businessValidation
    }

    public byte[] buildCreditBureauReportFile(CreditBureauReport creditBureauReport) {
        BusinessValidation canDownloadReportValidation = creditBureauReport.canDownloadReport()
        if (!canDownloadReportValidation.isValid()) throw new BusinessException(canDownloadReportValidation.getFirstErrorMessage())

        String urlImageAsaasLogo = grailsLinkGenerator.link(controller: 'static', action: 'images', id: 'creditBureauReport/asaas-logo-credit-bureau-report-pdf.svg', absolute: true)
        String urlImageSerasaLogo = grailsLinkGenerator.link(controller: 'static', action: 'images', id: 'creditBureauReport/serasa-experian-logo.png', absolute: true)

        String htmlString = groovyPageRenderer.render(template: "/creditBureauReport/templates/pdf/report",
            model: [remoteIp: RequestUtils.getRemoteIp(),
                    creditBureauReport: creditBureauReport,
                    customer: creditBureauReport.customer,
                    personType: CpfCnpjUtils.isCpf(creditBureauReport.cpfCnpj) ? PersonType.FISICA : PersonType.JURIDICA,
                    urlImageAsaasLogo: urlImageAsaasLogo,
                    urlImageSerasaLogo: urlImageSerasaLogo]).decodeHTML()
        return HtmlToPdfConverter.convert(htmlString)
    }

    private CreditBureauReport validateSave(Customer customer, PersonType personType, Map params) {
        CreditBureauReport validatedCreditBureauReport = new CreditBureauReport()

        if (!params.cpfCnpj) {
            if (params.customerAccountId) {
                DomainUtils.addError(validatedCreditBureauReport, "O pagador informado não possui CPF/CNPJ cadastrado.")
            } else {
                DomainUtils.addError(validatedCreditBureauReport, "Informe o CPF/CNPJ ou pagador que deseja consultar.")
            }
            return validatedCreditBureauReport
        }

        if (!CpfCnpjUtils.validate(params.cpfCnpj)) {
            DomainUtils.addError(validatedCreditBureauReport, "O CPF/CNPJ informado é inválido.")
            return validatedCreditBureauReport
        }

        if (!params.state) {
            if (params.customerAccountId) {
                DomainUtils.addError(validatedCreditBureauReport, "O pagador informado não possui uma cidade cadastrada. Informe manualmente ou atualize o cadastro do pagador.")
            } else {
                DomainUtils.addError(validatedCreditBureauReport, "Informe o estado em que deseja realizar a consulta desse CPF/CNPJ.")
            }

            return validatedCreditBureauReport
        }

        if (!State.validate(params.state)) {
            DomainUtils.addError(validatedCreditBureauReport, "O estado informado não é válido.")
            return validatedCreditBureauReport
        }

        BusinessValidation canRequestCreditBureauReportValidation = canRequestCreditBureauReport(customer, personType)
        if (!canRequestCreditBureauReportValidation.isValid()) return DomainUtils.addError(validatedCreditBureauReport, canRequestCreditBureauReportValidation.getFirstErrorMessage())

        return validatedCreditBureauReport
    }

    private CreditBureauReportAgreement saveAgreement(Customer customer, User user, String remoteIp, String userAgent, String headers, Boolean acceptAgreement, String terms) {
        CreditBureauReportAgreement creditBureauReportAgreement = new CreditBureauReportAgreement()

        if (!acceptAgreement || !terms) {
            DomainUtils.addError(creditBureauReportAgreement, "Os termos do aditivo não foram assinados.")
            return creditBureauReportAgreement
        }

        return creditBureauReportAgreementService.save(customer, user, remoteIp, userAgent, headers, terms)
    }
}
