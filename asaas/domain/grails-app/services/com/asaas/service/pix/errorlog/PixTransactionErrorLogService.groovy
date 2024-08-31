package com.asaas.service.pix.errorlog

import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.errorlog.PixTransactionErrorLog
import com.asaas.pix.PixTransactionErrorLogType

import grails.transaction.Transactional

@Transactional
class PixTransactionErrorLogService {

    public PixTransactionErrorLog saveConnectionErrorOnRequest(PixTransaction pixTransaction) {
        PixTransactionErrorLog errorLog = new PixTransactionErrorLog()
        errorLog.pixTransaction = pixTransaction
        errorLog.type = PixTransactionErrorLogType.CONNECTION_ERROR_ON_REQUEST
        errorLog.save(failOnError: true)
        return errorLog
    }

}
