package com.asaas.service.intercom

import com.asaas.domain.customer.Customer
import com.asaas.domain.customerstage.CustomerStage
import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.intercom.IntercomUtils
import com.asaas.integration.intercom.adapter.IntercomCustomerAttendanceDataAdapter
import com.asaas.integration.intercom.adapter.IntercomCustomerWhatsappAttendanceAdapter
import com.asaas.log.AsaasLogger
import com.asaas.stage.StageCode
import com.asaas.utils.Utils

import grails.transaction.Transactional

import static grails.async.Promises.task

@Transactional
class IntercomService {

    def customerAttendanceService

    public void updateCurrentUserCustomAttributes(Long userId) {
        if (!AsaasEnvironment.isProduction()) return

        task {
            try {
                Utils.withNewTransactionAndRollbackOnError({
                    User user = User.get(userId)
                    Customer customer = user.customer
                    IntercomCustomerAttendanceDataAdapter intercomCustomerAttendanceDataAdapter = generateIntercomCustomerAttendanceData(customer)

                    if (!intercomCustomerAttendanceDataAdapter) return

                    IntercomUtils.updateUserCustomAttributes(customer, intercomCustomerAttendanceDataAdapter)
                }, [onError: { Exception exception -> AsaasLogger.error("Problema ao tentar atualizar informacoes de usuario no Intercom. UserId [${userId}]", exception)}])
            } catch (Exception exception) {
                AsaasLogger.error("Problema ao tentar atualizar informacoes de usuario no Intercom. UserId [${userId}]", exception)
            }
        }
    }

    public IntercomCustomerAttendanceDataAdapter generateIntercomCustomerAttendanceData(Customer customer) {
        try {
            if (!AsaasEnvironment.isProduction()) return

            if (!canCustomerBeRegisteredOnIntercom(customer)) return

            IntercomCustomerAttendanceDataAdapter intercomCustomerAttendanceDataAdapter = new IntercomCustomerAttendanceDataAdapter(customer)

            return intercomCustomerAttendanceDataAdapter
        } catch (Exception exception) {
            AsaasLogger.error("Problema ao tentar gerar informacoes de usuario do Intercom. Customer [${customer.id}]", exception)
            return null
        }
    }

    public Boolean canCustomerBeRegisteredOnIntercom(Customer customer) {
        Map search = [
            customerId: customer.id,
            exists: true,
            "stageCode[in]": [StageCode.ACTIVATED, StageCode.CONVERTED]
        ]

        return CustomerStage.query(search).get().asBoolean()
    }

    public void includeUserOnTag(Long customerId, String tagName) {
        if (!AsaasEnvironment.isProduction()) return

        task {
            try {
                Customer customer = Customer.read(customerId)
                IntercomUtils.includeUserOnTag(customer, tagName)
            } catch (Exception exception) {
                AsaasLogger.error("IntercomService.includeUserOnTag >> Erro ao incluir usuário [${customerId}] na TAG [${tagName}] do Intercom", exception)
            }
        }
    }

    public IntercomCustomerWhatsappAttendanceAdapter buildWhatsAppCustomerAttendanceData(String cpfCnpj) {
        Customer customer = customerAttendanceService.findNotDisabledCustomer(cpfCnpj, null)
        if (!customer) return null

        IntercomCustomerWhatsappAttendanceAdapter intercomCustomerWhatsappAttendanceAdapter = new IntercomCustomerWhatsappAttendanceAdapter(customer)

        return intercomCustomerWhatsappAttendanceAdapter
    }
}
