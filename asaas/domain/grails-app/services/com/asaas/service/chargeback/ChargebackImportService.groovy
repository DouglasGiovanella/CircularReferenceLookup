package com.asaas.service.chargeback

import com.asaas.billinginfo.BillingType
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.importdata.chargeback.AdyenChargebackExcelImporter
import com.asaas.importdata.chargeback.ChargebackExcelImporter
import com.asaas.importdata.chargeback.CieloChargebackExcelImporter
import com.asaas.importdata.chargeback.RedeChargebackExcelImporter
import com.asaas.payment.PaymentStatus
import grails.transaction.Transactional
import grails.validation.ValidationException
import org.apache.commons.lang.NotImplementedException
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class ChargebackImportService {

    def chargebackService

    public Map processFile(CommonsMultipartFile file, CreditCardAcquirer creditCardAcquirer) {
        if (file.empty) throw new BusinessException("Nenhum arquivo foi selecionado.")

        ChargebackExcelImporter excelImporter
        if (creditCardAcquirer.isCielo()) {
            excelImporter = new CieloChargebackExcelImporter(file)
        } else if (creditCardAcquirer.isAdyen()) {
            excelImporter = new AdyenChargebackExcelImporter(file)
        }  else if (creditCardAcquirer.isRede()) {
            excelImporter = new RedeChargebackExcelImporter(file)
        } else {
            throw new NotImplementedException("Arquivo da adquirente [${creditCardAcquirer}] não suportado")
        }

        Map importInfo = [
            totalImported: 0,
            itemWithChargebackAlreadyCreatedList: [],
            itemWithPaymentNotFoundList: [],
            itemWithReasonNotFoundList: []
        ]

        excelImporter.setFlushAndClearEvery(50).forEachRow { Map item ->
            if (!item) return

            Map parsedItem = parseItem(item, excelImporter)

            Boolean itemHasChargebackAlready = Chargeback.query([exists: true, creditCardTid: item.transactionIdentifier]).get().asBoolean()
            if (itemHasChargebackAlready) {
                importInfo.itemWithChargebackAlreadyCreatedList += parsedItem
                return
            }

            if (!parsedItem.payment) {
                importInfo.itemWithPaymentNotFoundList += parsedItem
                return
            }

            if (!parsedItem.reason) {
                importInfo.itemWithReasonNotFoundList += parsedItem
                return
            }

            saveItem(parsedItem)
            importInfo.totalImported++
        }

        return importInfo
    }

    private void saveItem(Map item) {
        Chargeback chargeback
        if (item.payment.installment) {
            chargeback = chargebackService.save(null, item.payment.installment.id, item.reason, [originDisputeDate: item.originDisputeDate])
        } else {
            chargeback = chargebackService.save(item.payment.id, null, item.reason, [originDisputeDate: item.originDisputeDate])
        }

        if (chargeback.hasErrors()) throw new ValidationException("Erro ao salvar o chargeback com TID: [${item.transactionIdentifier}]" , chargeback.errors)
    }

    private Map parseItem(Map item, ChargebackExcelImporter excelImporter) {
        Map parsedItem = excelImporter.parseItem(item)

        Payment payment = Payment.query([
            creditCardTid: item.transactionIdentifier,
            billingType: BillingType.MUNDIPAGG_CIELO,
            statusList: [PaymentStatus.CONFIRMED, PaymentStatus.RECEIVED]
        ]).get()

        return parsedItem + [transactionIdentifier : item.transactionIdentifier, payment: payment]
    }
}
