package com.asaas.service.paymentsplit

import com.asaas.domain.customer.Customer
import com.asaas.log.AsaasLogger
import com.asaas.paymentsplit.PaymentSplitListType
import com.asaas.paymentsplit.PaymentSplitListVO
import com.asaas.paymentsplit.repository.PaymentSplitRepository
import com.mysql.jdbc.exceptions.MySQLTimeoutException
import grails.compiler.GrailsCompileStatic
import grails.orm.PagedResultList
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class PaymentSplitListService {

    public PaymentSplitListVO listWithTimeout(Customer customer, PaymentSplitListType listType, Map search, Integer max, Integer page, Integer offset) {
        try {
            if (listType.isReceiver()) {
                search.destinationCustomer = customer
            } else {
                search.originCustomer = customer
            }

            List<Map> sortListForForceIndex = []
            sortListForForceIndex.add([column: "id", order: "desc"])
            sortListForForceIndex.add([column: "paymentConfirmedDate", order: "desc"])
            PagedResultList paymentSplitList = PaymentSplitRepository.query(search).readOnly().sort(sortListForForceIndex).list(max: max, offset: offset)

            return new PaymentSplitListVO(paymentSplitList, customer, listType, page)
        } catch (MySQLTimeoutException mySQLTimeoutException) {
            AsaasLogger.warn("PaymentSplitListService.listWithTimeout >> Timeout na listagem de splits. Params [${search}]", mySQLTimeoutException)

            throw mySQLTimeoutException
        }
    }
}
