package com.asaas.service.accountbalancereport

import com.asaas.asyncaction.AsyncActionType
import com.asaas.converter.HtmlToPdfConverter
import com.asaas.domain.bank.Bank
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.exception.BusinessException
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.FileUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional

class AccountBalanceReportService {

    public static final Integer RELEASE_YEAR = 2022

    def asyncActionService
    def fileService
    def groovyPageRenderer
    def messageService

    public void requestReportFile(Customer customer, Integer year) {
        validateReportRequest(customer, year)

        AsyncActionType type = AsyncActionType.SEND_ACCOUNT_BALANCE_REPORT_THROUGH_EMAIL
        Map asyncActionData = [customerId: customer.id, year: year]

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, type)) return

        asyncActionService.save(type, asyncActionData)
    }

    public void sendEmailReport() {
        final Integer limit = 100
        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.SEND_ACCOUNT_BALANCE_REPORT_THROUGH_EMAIL, limit)

        if (!asyncActionDataList) return

        Utils.forEachWithFlushSession(asyncActionDataList, 50, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError({
                Long customerId = asyncActionData.customerId
                Integer year = asyncActionData.year.toInteger()
                Customer customer = Customer.read(customerId)

                byte[] reportFileBytes = buildReportFile(customer, year)
                File reportFile = FileUtils.buildFileFromBytes(reportFileBytes)
                AsaasFile asaasFile = fileService.createFile(customer, reportFile, "InformeDeSaldoEmConta${year}.pdf")

                messageService.sendAccountBalanceReport(customer, year, asaasFile.publicId)

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "AccountBalanceReportService.sendEmailReport >> Erro no envio do e-mail de Informe de Saldo em Conta. AsyncActionId: ${asyncActionData.asyncActionId}",
            onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        })
    }

    public byte[] buildReportFile(Customer customer, Integer year) {
        final Integer lastMonthOfYear = 12

        validateReportRequest(customer, year)

        Date yearLastDay = CustomDateUtils.getLastDayOfMonth(CustomDateUtils.fromString("${lastMonthOfYear}${year}", "MMyyyy"))
        BigDecimal yearTotalBalance = calculateTotalBalance(customer, yearLastDay)

        Integer previousYear = year-1
        Date previousYearLastDay = CustomDateUtils.getLastDayOfMonth(CustomDateUtils.fromString("${lastMonthOfYear}${previousYear}", "MMyyyy"))
        BigDecimal previousYearTotalBalance = calculateTotalBalance(customer, previousYearLastDay)

        String htmlString = groovyPageRenderer.render(template: "/finance/templates/report/accountBalanceReport",
                model: [
                    selectedYear: year,
                    selectedYearLastDay: yearLastDay,
                    selectedYearTotalBalance: yearTotalBalance,
                    previousYear: previousYear,
                    previousYearLastDay: previousYearLastDay,
                    previousYearTotalBalance: previousYearTotalBalance,
                    customer: customer,
                    asaasBankName: Bank.ASAAS_BANK_NAME,
                    accountNumber: customer.getAccountNumber()]).decodeHTML()

        return HtmlToPdfConverter.convert(htmlString)
    }

    private BigDecimal calculateTotalBalance(Customer customer, Date transactionDate) {
        BigDecimal totalBalance = FinancialTransaction.sumValue([provider: customer, "transactionDate[le]": CustomDateUtils.setTimeToEndOfDay(transactionDate)]).get()

        return totalBalance
    }

    private void validateReportRequest(Customer customer, Integer year) {
        if (year < AccountBalanceReportService.RELEASE_YEAR || year >= CustomDateUtils.getYear(new Date())) throw new BusinessException("Ano inválido!")
        if (!customer.isNaturalPerson()) throw new BusinessException("Apenas pessoas físicas podem emitir o relatório de informe de saldo em conta!")
    }
}
