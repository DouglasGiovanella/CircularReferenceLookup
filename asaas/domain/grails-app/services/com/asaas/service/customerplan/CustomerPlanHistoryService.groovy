package com.asaas.service.customerplan

import com.asaas.domain.customerplan.CustomerPlan
import com.asaas.domain.customerplan.CustomerPlanHistory
import grails.transaction.Transactional

@Transactional
class CustomerPlanHistoryService {

    public CustomerPlanHistory create(CustomerPlan customerPlan) {
        CustomerPlanHistory customerPlanHistory = new CustomerPlanHistory()
        customerPlanHistory.customerPlan = customerPlan
        customerPlanHistory.plan = customerPlan.plan
        customerPlanHistory.active = customerPlan.active
        customerPlanHistory.value = customerPlan.value
        customerPlanHistory.endDate = customerPlan.endDate

        customerPlanHistory.save(failOnError: true)

        return customerPlanHistory
    }
}
