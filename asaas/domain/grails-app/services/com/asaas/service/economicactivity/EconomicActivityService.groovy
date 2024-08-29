package com.asaas.service.economicactivity

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerEconomicActivity
import com.asaas.domain.economicactivity.EconomicActivity
import com.asaas.domain.economicactivity.EconomicActivityClass
import com.asaas.domain.economicactivity.RevenueServiceRegisterEconomicActivity
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.log.AsaasLogger
import com.asaas.revenueserviceregister.adapter.RevenueServiceRegisterEconomicActivityAdapter

import grails.transaction.Transactional

@Transactional
class EconomicActivityService {

    def asaasSegmentioService
    def newChildAccountCreditCardDynamicMccFeeConfigReplicationService

    public List<String> listEconomicActivityNamesByCustomer(Long customerId) {
        Customer customer = Customer.findById(customerId)

        List<String> customerEconomicActivityNames = CustomerEconomicActivity.query([column: "economicActivity.name", customer: customer]).list()

        return customerEconomicActivityNames
    }


    public void associateAllCustomer(Customer customer, List<Map> economicActivities) {
        unassociateAll(customer)

        for (Map economicActivityMap in economicActivities) {
            associateCustomer(customer, economicActivityMap.economicActivity, economicActivityMap.isMainActivity)
        }

        newChildAccountCreditCardDynamicMccFeeConfigReplicationService.saveReplicationIfPossible(customer)
    }

    private void associateCustomer(Customer customer, EconomicActivity economicActivity, Boolean isMainActivity) {
        CustomerEconomicActivity customerEconomicActivity = CustomerEconomicActivity.findOrCreateWhere(economicActivity: economicActivity, customer: customer)
        customerEconomicActivity.isMainActivity = isMainActivity
        customerEconomicActivity.save(flush: true, failOnError: true)
    }

    private void unassociateAll(Customer customer) {
        Customer.executeUpdate("delete CustomerEconomicActivity where customer = :customer", [customer: customer])
    }

    private List<EconomicActivity> findOrSaveList(List<RevenueServiceRegisterEconomicActivityAdapter> economicActivityAdapterList) {
        List<EconomicActivity> economicActivities = []

        for (RevenueServiceRegisterEconomicActivityAdapter economicActivityAdapter : economicActivityAdapterList) {
            if (!economicActivityAdapter.cnaeCode || !economicActivityAdapter.name) continue

            EconomicActivity economicActivity = EconomicActivity.query([cnaeCode: economicActivityAdapter.cnaeCode]).get()

            if (!economicActivity) economicActivity = save(economicActivityAdapter)

            economicActivities.add(economicActivity)
        }

        return economicActivities
    }

    private EconomicActivity save(RevenueServiceRegisterEconomicActivityAdapter economicActivityAdapter) {
        EconomicActivity economicActivity = new EconomicActivity()
        economicActivity.cnaeCode = economicActivityAdapter.cnaeCode
        economicActivity.name = economicActivityAdapter.name

        String classCode = economicActivityAdapter.cnaeCode.take(5)
        EconomicActivityClass economicActivityClass = EconomicActivityClass.query([code: classCode]).get()

        economicActivity.economicActivityClass = economicActivityClass
        economicActivity.save(flush: true, failOnError: true)

        AsaasLogger.warn("EconomicActivity.save -> Atividade Comercial registrada sem o mcc > ID: [${economicActivity.id}] - NOME: [${economicActivity.name}]")

        return economicActivity
    }

    public void saveAndAssociateRevenueServiceRegister(RevenueServiceRegister revenueServiceRegister, List<RevenueServiceRegisterEconomicActivityAdapter> economicActivityAdapterList) {
        List<EconomicActivity> economicActivities = findOrSaveList(economicActivityAdapterList)
        if (!economicActivities) return

        String mainCnaeCode = economicActivityAdapterList.find { RevenueServiceRegisterEconomicActivityAdapter it -> it.isMainActivity }.cnaeCode

        for (EconomicActivity economicActivity in economicActivities) {
            associateRevenueServiceRegister(revenueServiceRegister, economicActivity, (economicActivity.cnaeCode == mainCnaeCode))
        }
    }

    private RevenueServiceRegisterEconomicActivity associateRevenueServiceRegister(RevenueServiceRegister revenueServiceRegister, EconomicActivity economicActivity, Boolean isMainActivity) {
        RevenueServiceRegisterEconomicActivity revenueServiceRegisterEconomicActivity = RevenueServiceRegisterEconomicActivity.query([revenueServiceRegister: revenueServiceRegister, economicActivity: economicActivity]).get()
        if (!revenueServiceRegisterEconomicActivity) {
            revenueServiceRegisterEconomicActivity = new RevenueServiceRegisterEconomicActivity()
            revenueServiceRegisterEconomicActivity.revenueServiceRegister = revenueServiceRegister
            revenueServiceRegisterEconomicActivity.economicActivity = economicActivity
        }
        revenueServiceRegisterEconomicActivity.isMainActivity = isMainActivity
        revenueServiceRegister.addToRevenueServiceRegisterEconomicActivities(revenueServiceRegisterEconomicActivity)

        revenueServiceRegisterEconomicActivity.save(failOnError: true)

        return revenueServiceRegisterEconomicActivity
    }

    public void trackEconomicActivity(Long customerId) {
        String economicActivitiesNames = listEconomicActivityNamesByCustomer(customerId).join(';')
        asaasSegmentioService.identify(customerId, ["economicActivities": economicActivitiesNames])
    }
}
