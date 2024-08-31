package com.asaas.service.pix.timerecordlog

import com.asaas.domain.pix.timerecordlog.PixAddressKeyTimeRecordLog
import com.asaas.pix.timerecordlog.PixAddressKeyTimeRecordLogType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PixAddressKeyTimeRecordLogService {

    public PixAddressKeyTimeRecordLog saveTimeRecordLogForOwnershipConfirmation(String pixKey, Date startDate) {
        Utils.withSafeException({
            return save(pixKey, PixAddressKeyTimeRecordLogType.OWNERSHIP_CONFIRMATION, startDate, new Date())
        })
    }

    private PixAddressKeyTimeRecordLog save(String pixKey, PixAddressKeyTimeRecordLogType type, Date startDate, Date conclusionDate) {
        PixAddressKeyTimeRecordLog recordLog = new PixAddressKeyTimeRecordLog()
        recordLog.pixKey = pixKey
        recordLog.type = type
        recordLog.startDate = startDate
        recordLog.conclusionDate = conclusionDate
        if (conclusionDate) {
            recordLog.totalSeconds = CustomDateUtils.calculateDifferenceInSeconds(recordLog.startDate, recordLog.conclusionDate)
            recordLog.totalMilliseconds = CustomDateUtils.calculateDifferenceInMilliseconds(recordLog.startDate, recordLog.conclusionDate).toInteger()
        }
        recordLog.save(failOnError: true)
        return recordLog
    }
}
