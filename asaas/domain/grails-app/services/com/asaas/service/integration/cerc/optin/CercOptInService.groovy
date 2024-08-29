package com.asaas.service.integration.cerc.optin

import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.cerc.notification.CercNotification
import com.asaas.domain.integration.cerc.optin.CercOptIn
import com.asaas.integration.cerc.adapter.optin.CercOptInAdapter
import com.asaas.receivableunit.PaymentArrangement
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CercOptInService {

    public List<CercOptIn> list(Customer customer, Integer max, Integer offset, Map filters) {
        Map search = filters ?: [:]
        search.customer = customer
        search.ignoreAsaas = true

        return CercOptIn.query(search).list(max: max, offset: offset)
    }

    public Boolean hasAllOptIn(CercOptInAdapter optInAdapter) {
        List<PaymentArrangement> paymentArrangementList = CercOptIn.query([
            column: "paymentArrangement",
            customerCpfCnpj: optInAdapter.customerCpfCnpj,
            holderCpfCnpj: optInAdapter.holderCpfCnpj,
            requesterCpfCnpj: optInAdapter.requesterCpfCnpj,
            externalIdentifier: optInAdapter.externalIdentifier,
            "paymentArrangement[in]": optInAdapter.paymentArrangementList,
            startDate: optInAdapter.startDate
        ]).list()

        return optInAdapter.paymentArrangementList.every { paymentArrangementList.contains(it) }
    }

    public void save(CercOptInAdapter optInAdapter, CercNotification cercNotification) {
        CercOptIn validatedDomain = validateSave(optInAdapter.customerCpfCnpj, optInAdapter.holderCpfCnpj)
        if (validatedDomain.hasErrors()) throw new ValidationException("CercOptInService.save >> Falha na validação do OPT-In", validatedDomain.errors)

        for (PaymentArrangement paymentArrangement : optInAdapter.paymentArrangementList) {
            if (hasOptIn(optInAdapter, paymentArrangement)) continue

            CercOptIn cercOptIn = new CercOptIn()
            cercOptIn.customerCpfCnpj = optInAdapter.customerCpfCnpj
            cercOptIn.holderCpfCnpj = optInAdapter.holderCpfCnpj
            cercOptIn.requesterCpfCnpj = optInAdapter.requesterCpfCnpj
            cercOptIn.signatureDate = optInAdapter.signatureDate
            cercOptIn.startDate = optInAdapter.startDate
            cercOptIn.dueDate = optInAdapter.dueDate
            cercOptIn.cercNotification = cercNotification
            cercOptIn.paymentArrangement = paymentArrangement
            cercOptIn.externalIdentifier = optInAdapter.externalIdentifier
            cercOptIn.save(failOnError: true)
        }
    }

    private CercOptIn validateSave(String customerCpfCnpj, String holderCpfCnpj) {
        CercOptIn validatedDomain = new CercOptIn()

        if (!customerCpfCnpj && !holderCpfCnpj) DomainUtils.addError(validatedDomain, "É obrigatório informar CPF/CNPJ do cliente ou do titular")

        return validatedDomain
    }

    private Boolean hasOptIn(CercOptInAdapter optInAdapter, PaymentArrangement paymentArrangement) {
        return CercOptIn.query([
            exists: true,
            customerCpfCnpj: optInAdapter.customerCpfCnpj,
            holderCpfCnpj: optInAdapter.holderCpfCnpj,
            requesterCpfCnpj: optInAdapter.requesterCpfCnpj,
            externalIdentifier: optInAdapter.externalIdentifier,
            paymentArrangement: paymentArrangement,
            startDate: optInAdapter.startDate
        ]).get().asBoolean()
    }
}
