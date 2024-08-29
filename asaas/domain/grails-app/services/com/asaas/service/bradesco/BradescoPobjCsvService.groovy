package com.asaas.service.bradesco

import au.com.bytecode.opencsv.CSVWriter
import com.asaas.billinginfo.BillingType
import com.asaas.boleto.BoletoRegistrationStatus
import com.asaas.boleto.batchfile.BoletoAction
import com.asaas.boleto.batchfile.BoletoBatchFileStatus
import com.asaas.domain.file.AsaasFile
import com.asaas.integration.bradesco.dto.BradescoPobjItemDTO
import com.asaas.integration.bradesco.dto.BradescoPobjPaymentMethod
import com.asaas.partnerapplication.PartnerApplicationName
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.ZipFileUtils
import grails.transaction.Transactional
import org.hibernate.SQLQuery
import org.hibernate.transform.AliasToEntityMapResultTransformer

import java.nio.charset.StandardCharsets

@Transactional
class BradescoPobjCsvService {

    def fileService

    def grailsApplication

    def bradescoMessageService

    def sessionFactory

    public void buildAndSendFile(Date baseDate) {
        List<BradescoPobjItemDTO> buildDailyPobjItems = buildPobjItems(baseDate)
        if (!buildDailyPobjItems) return

        String pobjContent = createCsv(buildDailyPobjItems)

        String fileName = "emissoes${baseDate.format('yyyyMMdd')}"

        AsaasFile file = fileService.createFile("${fileName}.csv", pobjContent, StandardCharsets.UTF_8.toString())
        byte[] zippedContent = ZipFileUtils.buildZip([file], grailsApplication.config.asaas.bradesco.pobj.password)

        String subject = "Emissões Asaas de ${baseDate.format('dd/MM/yyyy')}"
        bradescoMessageService.sendPobjCsv(subject, "${fileName}.zip", zippedContent)
    }

    private List<BradescoPobjItemDTO> buildPobjItems(Date baseDate) {
        List<BradescoPobjItemDTO> bradescoPobjCsvDTOList = []

        bradescoPobjCsvDTOList.addAll(buildBoletoCreatedItems(baseDate))
        bradescoPobjCsvDTOList.addAll(buildPaidByPixItems(baseDate))
        bradescoPobjCsvDTOList.addAll(buildPaidByCreditCardItems(baseDate))

        return bradescoPobjCsvDTOList.sort { it.cpfCnpj }
    }

    private List<BradescoPobjItemDTO> buildBoletoCreatedItems(Date baseDate) {
        SQLQuery sqlQuery = sessionFactory.currentSession.createSQLQuery('''
SELECT c.cpf_cnpj, c.id as customer_id, COUNT(DISTINCT p.id) as count
FROM customer_partner_application AS partner
INNER JOIN payment AS p ON p.provider_id = partner.customer_id
INNER JOIN boleto_batch_file_item AS b ON b.payment_id = p.id
INNER JOIN bank_slip_online_registration_response AS resp ON resp.boleto_batch_file_item_id = b.id
INNER JOIN customer AS c ON c.id = partner.customer_id
WHERE partner.deleted = :deleted
AND partner.partner_application_name = :partnerApplicationName
AND p.deleted = :deleted
AND b.deleted = :deleted
AND b.`status` = :status
AND b.`action` = :action
AND b.last_updated >= :startDate
AND b.last_updated <= :endDate
AND resp.deleted = :deleted
AND resp.registration_status = :registrationStatus
AND c.deleted = :deleted
GROUP BY c.cpf_cnpj, c.id
''')

        sqlQuery.setBoolean('deleted', false)
        sqlQuery.setString('partnerApplicationName', PartnerApplicationName.BRADESCO.toString())
        sqlQuery.setString('status', BoletoBatchFileStatus.SENT.toString())
        sqlQuery.setString('action', BoletoAction.CREATE.toString())
        sqlQuery.setTimestamp('startDate', baseDate.clearTime())
        sqlQuery.setTimestamp('endDate', CustomDateUtils.setTimeToEndOfDay(baseDate))
        sqlQuery.setString('registrationStatus', BoletoRegistrationStatus.SUCCESSFUL.toString())
        sqlQuery.setResultTransformer(AliasToEntityMapResultTransformer.INSTANCE)

        List<BradescoPobjItemDTO> bradescoPobjCsvDTOList = sqlQuery.list().collect { Map row ->
            new BradescoPobjItemDTO(row.cpf_cnpj, row.customer_id as Long, row.count as Long, baseDate, BradescoPobjPaymentMethod.BOLETO)
        }

        return bradescoPobjCsvDTOList
    }

    private List<BradescoPobjItemDTO> buildPaidByPixItems(Date baseDate) {
        return fillFromPayment(baseDate, BillingType.PIX, BradescoPobjPaymentMethod.PIX)
    }

    private List<BradescoPobjItemDTO> buildPaidByCreditCardItems(Date baseDate) {
        return fillFromPayment(baseDate, BillingType.MUNDIPAGG_CIELO, BradescoPobjPaymentMethod.CREDIT_CARD)
    }

    private List<BradescoPobjItemDTO> fillFromPayment(Date baseDate, BillingType billingType, BradescoPobjPaymentMethod paymentMethod) {
        SQLQuery sqlQuery = sessionFactory.currentSession.createSQLQuery('''
SELECT c.cpf_cnpj, c.id as customer_id, COUNT(p.id) as count
FROM customer_partner_application AS partner
INNER JOIN payment p on p.provider_id = partner.customer_id
INNER JOIN customer AS c ON c.id = partner.customer_id
WHERE partner.deleted = :deleted
AND partner.partner_application_name = :partnerApplicationName
AND p.deleted = :deleted
AND p.payment_date >= :startDate
AND p.payment_date <= :endDate
AND p.billing_type = :billingType
AND c.deleted = :deleted
GROUP BY c.cpf_cnpj, c.id
''')

        sqlQuery.setBoolean('deleted', false)
        sqlQuery.setString('partnerApplicationName', PartnerApplicationName.BRADESCO.toString())
        sqlQuery.setTimestamp('startDate', baseDate.clearTime())
        sqlQuery.setTimestamp('endDate', CustomDateUtils.setTimeToEndOfDay(baseDate))
        sqlQuery.setString('billingType', billingType.toString())
        sqlQuery.setResultTransformer(AliasToEntityMapResultTransformer.INSTANCE)

        List<BradescoPobjItemDTO> bradescoPobjCsvDTOList = sqlQuery.list().collect { Map row ->
            new BradescoPobjItemDTO(row.cpf_cnpj, row.customer_id as Long, row.count as Long, baseDate, paymentMethod)
        }

        return bradescoPobjCsvDTOList
    }

    private String createCsv(List<BradescoPobjItemDTO> items) {
        StringWriter stringWriter = new StringWriter()
        CSVWriter writer = new CSVWriter(stringWriter, ';' as char)
        try {
            String[] header = ['CNPJ', 'agenciaBradesco', 'contaBradesco', 'digitoVerificadorContaBradesco', 'qtdEmissoes', 'dataEmissoes', 'meioDePagamento']

            writer.writeNext(header)

            for (BradescoPobjItemDTO dto : items) {
                List<String> content = []
                content.add(dto.cpfCnpj)
                content.add(dto.agency)
                content.add(dto.accountNumber)
                content.add(dto.digit)
                content.add(dto.count.toString())
                content.add(dto.date.format('yyyyMMdd'))
                content.add(dto.paymentMethod.getCode())
                writer.writeNext(content as String[])
            }

            return stringWriter.toString()
        } catch (Throwable exception) {
            throw exception
        } finally {
            writer.flush()
            stringWriter.flush()

            writer.close()
            stringWriter.close()
        }
    }
}
