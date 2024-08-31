package com.asaas.service.revenueserviceregister

import com.asaas.domain.companypartnerquery.CompanyPartnerQuery
import com.asaas.domain.customer.Customer
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.exception.BusinessException
import com.asaas.integration.heimdall.dto.dataenhancement.get.natural.basicdata.NaturalBasicDataDTO
import com.asaas.integration.heimdall.enums.revenueserviceregister.RevenueServiceRegisterCacheLevel
import com.asaas.log.AsaasLogger
import com.asaas.revenueserviceregister.adapter.RevenueServiceRegisterAdapter
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class RevenueServiceRegisterService {

    def addressService
    def companyPartnerQueryService
    def economicActivityService
    def knowYourCustomerInfoService
    def revenueServiceRegisterManagerService

    public NaturalBasicDataDTO getNaturalData(String cpf) {
        if (!CpfCnpjUtils.isCpf(cpf) || !CpfCnpjUtils.validate(cpf)) throw new ValidationException(Utils.getMessageProperty("revenueServiceRegister.errors.invalidCpf"), null)

        return revenueServiceRegisterManagerService.getNaturalData(cpf)
    }

    public RevenueServiceRegister findNaturalPerson(String cpf, Date birthDate) {
        return findNaturalPerson(cpf, birthDate, RevenueServiceRegisterCacheLevel.LOW)
    }

    public RevenueServiceRegister findNaturalPerson(String cpf, Date birthDate, RevenueServiceRegisterCacheLevel cacheLevel) {
        if (!CpfCnpjUtils.isCpf(cpf) || !CpfCnpjUtils.validate(cpf)) {
            RevenueServiceRegister validatedRevenueServiceRegister = new RevenueServiceRegister()
            DomainUtils.addError(validatedRevenueServiceRegister, "revenueServiceRegister.errors.invalidCpf")
            return validatedRevenueServiceRegister
        }

        RevenueServiceRegister latestNaturalRevenueServiceRegister = RevenueServiceRegister.findLatest(cpf)
        if (latestNaturalRevenueServiceRegister?.hasCache(cacheLevel)) {
            return latestNaturalRevenueServiceRegister
        }

        return find({
            return revenueServiceRegisterManagerService.queryNaturalPerson(cpf, birthDate, cacheLevel)
        })
    }

    public RevenueServiceRegister findLegalPerson(String cnpj) {
        return findLegalPerson(cnpj, RevenueServiceRegisterCacheLevel.LOW)
    }

    public RevenueServiceRegister findLegalPerson(String cnpj, RevenueServiceRegisterCacheLevel cacheLevel) {
        if (!CpfCnpjUtils.isCnpj(cnpj) || !CpfCnpjUtils.validate(cnpj)) {
            RevenueServiceRegister validatedRevenueServiceRegister = new RevenueServiceRegister()
            DomainUtils.addError(validatedRevenueServiceRegister, "revenueServiceRegister.errors.invalidCnpj")
            return validatedRevenueServiceRegister
        }

        RevenueServiceRegister latestLegalRevenueServiceRegister = RevenueServiceRegister.findLatest(cnpj)
        if (latestLegalRevenueServiceRegister?.hasCache(cacheLevel)) {
            return latestLegalRevenueServiceRegister
        }

        return find({
            return revenueServiceRegisterManagerService.queryLegalPerson(cnpj, cacheLevel)
        })
    }

    public void delete(String cpfCnpj) {
        RevenueServiceRegister.query(cpfCnpj: cpfCnpj).list().each {
            it.deleted = true
            it.save(flush: true)
        }
    }

    public RevenueServiceRegisterAdapter findLatest(String cpfCnpj) {
        if (!cpfCnpj) throw new BusinessException("Não foi encontrado um CPF/CNPJ para consultar na receita.")

        if (CpfCnpjUtils.isCnpj(cpfCnpj)) {
            return revenueServiceRegisterManagerService.findLatestLegalPerson(cpfCnpj)
        }

        return revenueServiceRegisterManagerService.findLatestNaturalPerson(cpfCnpj)
    }

    public RevenueServiceRegister findPerson(String cpfCnpj, RevenueServiceRegisterCacheLevel cacheLevel) {
        if (CpfCnpjUtils.isCnpj(cpfCnpj)) {
            return findLegalPerson(cpfCnpj, cacheLevel)
        }
        return findNaturalPerson(cpfCnpj, null, cacheLevel)
    }

    public Boolean isSameOwnership(Customer customer, String cpfCnpj) {
        if (cpfCnpj == customer.cpfCnpj) return true

        if (customer.isNaturalPerson()) return false

        if (customer.getCompanyType().isMEI() && customer.getRevenueServiceRegister()?.getMEIOwnerCpf() == cpfCnpj) return true

        if (CompanyPartnerQuery.isAdminPartner(customer.cpfCnpj, cpfCnpj)) return true

        return false
    }

    private RevenueServiceRegister find(Closure findInManagerService) {
        RevenueServiceRegisterAdapter revenueServiceRegisterAdapter
        try {
            revenueServiceRegisterAdapter = findInManagerService() as RevenueServiceRegisterAdapter
        } catch (BusinessException businessException) {
            RevenueServiceRegister validatedRevenueServiceRegister = new RevenueServiceRegister()
            DomainUtils.addError(validatedRevenueServiceRegister, businessException.message)
            return validatedRevenueServiceRegister
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao consultar dados da receita", exception)
            RevenueServiceRegister validatedRevenueServiceRegister = new RevenueServiceRegister()
            DomainUtils.addError(validatedRevenueServiceRegister, "revenueServiceRegister.errors.unsuccessfulQuery")
            return validatedRevenueServiceRegister
        }

        RevenueServiceRegister revenueServiceRegister = save(revenueServiceRegisterAdapter)

        return revenueServiceRegister
    }

    private RevenueServiceRegister save(RevenueServiceRegisterAdapter revenueServiceRegisterAdapter) {
        RevenueServiceRegister revenueServiceRegister = new RevenueServiceRegister()

        revenueServiceRegister.cnpj = revenueServiceRegisterAdapter.cnpj
        revenueServiceRegister.openDate = revenueServiceRegisterAdapter.openDate
        revenueServiceRegister.corporateName = revenueServiceRegisterAdapter.corporateName
        revenueServiceRegister.tradingName = revenueServiceRegisterAdapter.tradingName
        revenueServiceRegister.registerStatus = revenueServiceRegisterAdapter.registerStatus
        revenueServiceRegister.email = revenueServiceRegisterAdapter.email
        revenueServiceRegister.phone = revenueServiceRegisterAdapter.phone
        revenueServiceRegister.legalNature = revenueServiceRegisterAdapter.legalNature
        revenueServiceRegister.shareCapital = revenueServiceRegisterAdapter.shareCapital
        revenueServiceRegister.name = revenueServiceRegisterAdapter.name
        revenueServiceRegister.birthDate = revenueServiceRegisterAdapter.birthDate
        revenueServiceRegister.isMei = revenueServiceRegisterAdapter.isMei
        revenueServiceRegister.isSimplesNacional = revenueServiceRegisterAdapter.isSimplesNacional

        if (revenueServiceRegisterAdapter.address) {
            revenueServiceRegister.address = addressService.save(revenueServiceRegisterAdapter.address, revenueServiceRegisterAdapter.addressNumber, revenueServiceRegisterAdapter.complement, revenueServiceRegisterAdapter.postalCode, revenueServiceRegisterAdapter.province ?: "", revenueServiceRegisterAdapter.city)
        }

        revenueServiceRegister.save(flush: true, failOnError: true)

        if (CpfCnpjUtils.isCnpj(revenueServiceRegister.cnpj)) {
            economicActivityService.saveAndAssociateRevenueServiceRegister(revenueServiceRegister, revenueServiceRegisterAdapter.revenueServiceRegisterEconomicActivities)
            companyPartnerQueryService.addNewAndDeleteOldInvalid(revenueServiceRegisterAdapter.cnpj, revenueServiceRegisterAdapter.companyPartnerList)
            knowYourCustomerInfoService.saveLegalNamesAsyncActionData(revenueServiceRegister.cnpj, revenueServiceRegister.tradingName, revenueServiceRegister.corporateName)
        }

        return revenueServiceRegister
    }
}
