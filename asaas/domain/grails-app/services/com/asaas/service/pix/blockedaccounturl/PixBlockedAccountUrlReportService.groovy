package com.asaas.service.pix.blockedaccounturl

import com.asaas.domain.file.AsaasFile
import com.asaas.pix.adapter.blockedaccounturl.SendBlockedAccountUrlReportAdapter
import com.asaas.pix.adapter.blockedaccounturl.children.BlockedAccountUrlReportInfoAdapter
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class PixBlockedAccountUrlReportService {

    def messageService
    def reportFileService

    public void sendReportToEmail(SendBlockedAccountUrlReportAdapter reportAdapter) {
        AsaasFile reportFile = buildCsvFile(reportAdapter.reportInfoAdapterList, reportAdapter.reportDate)
        messageService.sendPixBlockedAccountUrlReport(reportFile, reportAdapter.reportDate)
    }

    private AsaasFile buildCsvFile(List<BlockedAccountUrlReportInfoAdapter> reportInfoAdapterList, Date reportDate) {
        final String fileName = "relatorio_contas_irregulares_pix_${CustomDateUtils.fromDate(reportDate, "yyyyMMdd")}"
        final List<String> headerList = ["ID Cliente", "Descrição"]
        List<List> rowList = reportInfoAdapterList.collect { [it.customerId, it.description] }

        return reportFileService.createCsvFile(fileName, headerList, rowList)
    }

}
