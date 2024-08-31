package com.asaas.service.pix

import com.asaas.domain.pix.timerecordlog.PixAddressKeyTimeRecordLog
import com.asaas.log.AsaasLogger
import com.asaas.pix.timerecordlog.PixAddressKeyTimeRecordLogType
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class PixAddressKeyOwnershipConfirmationSynchronizationService {

    def pixAddressKeyManagerService

    public void synchronizeYesterdayOwnershipConfirmationTimeRecordLog() {
        List<PixAddressKeyTimeRecordLog> ownershipConfirmationTimeRecordLogList = PixAddressKeyTimeRecordLog.query([type: PixAddressKeyTimeRecordLogType.OWNERSHIP_CONFIRMATION, "conclusionDate[ge]": CustomDateUtils.getYesterday(), "conclusionDate[le]": CustomDateUtils.getYesterday()]).list()
        if (!ownershipConfirmationTimeRecordLogList) {
            AsaasLogger.info("PixAddressKeyOwnershipConfirmationSynchronizationService.synchronizeYesterdayOwnershipConfirmationTimeRecordLog() -> Não existem registros a serem sincronizados.")
            return
        }

        pixAddressKeyManagerService.sendPixAddressKeyTimeRecordLog(ownershipConfirmationTimeRecordLogList)
    }
}
