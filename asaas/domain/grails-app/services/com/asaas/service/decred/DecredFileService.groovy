package com.asaas.service.decred

import com.asaas.billinginfo.BillingType
import com.asaas.customer.PersonType
import com.asaas.domain.file.AsaasFile
import com.asaas.log.AsaasLogger
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.apache.commons.lang.StringUtils

@Transactional
class DecredFileService {

    def databricksService
    def fileService
    def sessionFactory

    public AsaasFile build(Date dateStart, Date dateEnd) {
        try {
            String header = buildHeader(dateStart)
            Integer rowCount = 3

            Map cardTransactionList = getCardTransactionList(dateStart, dateEnd)
            List<Map> naturalPersonCardTransactionList = cardTransactionList.naturalPersonCardTransactionList
            List<Map> legalPersonCardTransactionList = cardTransactionList.legalPersonCardTransactionList
            legalPersonCardTransactionList = includeCnpjAndReorderCardTransactionList(legalPersonCardTransactionList)

            String naturalPersonCardTransactionBody = buildCardTransactionsBody(PersonType.FISICA, naturalPersonCardTransactionList, rowCount)
            rowCount += naturalPersonCardTransactionList.size()

            String legalPersonCardTransactionBody = buildCardTransactionsBody(PersonType.JURIDICA, legalPersonCardTransactionList, rowCount)
            rowCount += legalPersonCardTransactionList.size()

            Map receivedPaymentsList = getReceivedPaymentsList(dateStart, dateEnd)
            List<Map> naturalPersonReceivedPaymentsList = receivedPaymentsList.naturalPersonReceivedPaymentsList
            List<Map> legalPersonReceivedPaymentsList = receivedPaymentsList.legalPersonReceivedPaymentsList

            String naturalPersonReceivedPaymentsBody = buildReceivedPaymentsBody(PersonType.FISICA, naturalPersonReceivedPaymentsList, rowCount)
            rowCount += naturalPersonReceivedPaymentsList.size()

            String legalPersonReceivedPaymentsBody = buildReceivedPaymentsBody(PersonType.JURIDICA, legalPersonReceivedPaymentsList, rowCount)
            rowCount += legalPersonReceivedPaymentsList.size()

            String footer = buildFooter(dateStart, rowCount)

            String fileContent = header + naturalPersonCardTransactionBody + legalPersonCardTransactionBody + naturalPersonReceivedPaymentsBody + legalPersonReceivedPaymentsBody + footer
            AsaasFile asaasFile = fileService.createFile("decred.txt", fileContent)

            AsaasLogger.info("Arquivo DECRED gerado. Id: ${asaasFile.id}")

            return asaasFile
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao gerar arquivo DECRED", exception)
            throw exception
        }
    }

    private Map getCardTransactionList(Date dateStart, Date dateEnd) {
        final Long naturalPersonMonthValue = 5000
        final Long legalPersonMonthValue = 10000
        final Long minMonthValue = Math.min(naturalPersonMonthValue, legalPersonMonthValue)

        String sql = """
            SELECT c.cpf_cnpj,
               MONTH(t.date_created) as month,
               c.id,
               sum(t.value) as value
            FROM asaas_card_transaction t
            INNER JOIN asaas_card ac ON ac.id = t.asaas_card_id
            INNER JOIN customer c ON c.id  = ac.customer_id
            LEFT JOIN customer_update_request cup ON cup.provider_id = c.id
                                                and cup.person_type <> c.person_type
                                                and cup.date_created >= :dateStart
                                                and cup.date_created  < :dateEnd
            WHERE t.date_created >= :dateStart
                    and t.date_created < :dateEnd
                    and cup.id is null
                    and (t.type = 'PURCHASE' or t.type = 'WITHDRAWAL')
                    and t.id not in (SELECT t2.transaction_origin_id
                                     FROM asaas_card_transaction t2
                                     WHERE t2.date_created >= :dateStart
                                            and t2.date_created < :dateEnd
                                            and t2.type not in ('PURCHASE', 'WITHDRAWAL'))
            GROUP BY 1,2,3
            HAVING value >= :monthValue
            ORDER BY 1,2,3
        """

        def query = sessionFactory.currentSession.createSQLQuery(sql)
        query.setDate("dateStart", dateStart)
        query.setTimestamp("dateEnd", dateEnd)
        query.setLong("monthValue", minMonthValue)

        List itemList = query.list().collect { [cpfCnpj: it[0], month: it[1], customerId: it[2],  value: it[3]] }

        List naturalPersonCardTransactionList = []
        List legalPersonCardTransactionList = []

        for (def item : itemList) {
            if (CpfCnpjUtils.isCpf(item.cpfCnpj) && item.value >= naturalPersonMonthValue) {
                naturalPersonCardTransactionList.add(item)
            } else if (item.value >= legalPersonMonthValue) {
                legalPersonCardTransactionList.add(item)
            }
        }

        return [naturalPersonCardTransactionList: naturalPersonCardTransactionList, legalPersonCardTransactionList: legalPersonCardTransactionList]
    }

    private Map getReceivedPaymentsList(Date dateStart, Date dateEnd) {
        final Long naturalPersonMonthValue = 5000
        final Long legalPersonMonthValue = 10000

        String sql = """
            select c.cpf_cnpj,
                DATE_FORMAT(p.confirmed_date, 'MM'),
                SUM(p.value)
            from payment p
            join customer c on p.provider_id = c.id
            where p.billing_type = :billingType
                    and p.status IN ('CONFIRMED', 'RECEIVED', 'CHARGEBACK_REQUESTED', 'CHARGEBACK_DISPUTE', 'AWAITING_CHARGEBACK_REVERSAL')
                    and p.confirmed_date >= :dateStart
                    and p.confirmed_date < :dateEnd
                    and (p.refunded_date IS NULL OR p.refunded_date >= :dateEnd)
                    and c.cpf_cnpj IS NOT NULL
            group by 1 , 2
            having SUM(p.value) >= :monthValue
            order by 1 , 2
        """

        Map queryParams = [:]
        queryParams.dateStart = dateStart
        queryParams.dateEnd = dateEnd
        queryParams.billingType = BillingType.MUNDIPAGG_CIELO.toString()
        queryParams.monthValue = Math.min(naturalPersonMonthValue, legalPersonMonthValue)

        List itemList = databricksService.runQuery(sql, queryParams).collect { [cpfCnpj: it[0], month: it[1], value: it[2]] }

        List naturalPersonReceivedPaymentsList = []
        List legalPersonReceivedPaymentsList = []

        for (def item : itemList) {
            if (CpfCnpjUtils.isCpf(item.cpfCnpj) && item.value >= naturalPersonMonthValue) {
                naturalPersonReceivedPaymentsList.add(item)
            } else if (item.value >= legalPersonMonthValue) {
                legalPersonReceivedPaymentsList.add(item)
            }
        }

        return [naturalPersonReceivedPaymentsList: naturalPersonReceivedPaymentsList, legalPersonReceivedPaymentsList: legalPersonReceivedPaymentsList]
    }

    private String buildCardTransactionsBody(PersonType personType, List<Map> itemList, Long indexStart) {
        StringBuilder body = new StringBuilder()
        Integer index = indexStart

        for (Map item : itemList) {
            index += 1
            body.append(buildNumber(index.toString(), 8))
            body.append(personType.isFisica() ? "R04" : "R05")
            body.append(buildString(Utils.removeNonNumeric(item.cpfCnpj), personType.isFisica() ? 11 : 14))
            body.append(buildNumber(item.month.toString(), 2))
            body.append(buildString(item.customerId.toString(), 60))
            body.append(buildNumber(Utils.removeNonNumeric(BigDecimalUtils.roundHalfUp(item.value).toString()), 17))
            if (personType.isFisica()) body.append(buildString(" ", 3))
            body.append("\r\n")
        }
        return body.toString()
    }

    private String buildReceivedPaymentsBody(PersonType personType, List<Map> itemList, Long indexStart) {
        StringBuilder body = new StringBuilder()
        Integer index = indexStart

        for (Map item : itemList) {
            index += 1

            body.append(buildNumber(index.toString(), 8)) // Sequencial
            body.append(personType.isFisica() ? "R06" : "R07") // Tipo do registro - R06 pessoa física / R07 pessoa jurídica
            body.append(buildString(Utils.removeNonNumeric(item.cpfCnpj), personType.isFisica() ? 11 : 14)) // CPF / CNPJ
            body.append(buildNumber(item.month.toString(), 2)) // Mês do Repasse
            body.append(buildNumber(Utils.removeNonNumeric(BigDecimalUtils.roundHalfUp(item.value).toString()), 17)) // Valor do Repasse
            body.append(buildString(" ", personType.isFisica() ? 63 : 60)) // Reservado
            body.append("\r\n")
        }

        return body.toString()
    }

    private String buildHeader(Date dateStart) {
        StringBuilder header = new StringBuilder()
        Long index = 0

        // R01
        index += 1
        header.append(buildNumber(index.toString(), 8)) // Sequencial
        header.append(buildString("R01", 3)) // Tipo registro
        header.append(buildString("19540550000121", 14)) // CNPJ do Declarante
        header.append(buildString(buildPeriod(dateStart), 5)) // Semestre e Ano-Calendário
        header.append(buildNumber("0", 1)) // Tipo da Declaração
        header.append(buildNumber("3", 1)) // Tipo do Declarante
        header.append(buildString("SC", 2)) // UF
        header.append(buildString("ASAAS GESTAO FINANCEIRA SA", 60)) // Nome do Declarante
        header.append(buildString("DECRED", 6)) // Nome do Arquivo / Constante
        header.append(buildString(" ", 4)) // Reservado
        header.append("\r\n")

        // R02
        index += 1
        header.append(buildNumber(index.toString(), 8)) // Sequencial
        header.append(buildString("R02", 3)) // Tipo do registro
        header.append(buildString("PIERO BITENCOURT CONTEZINI", 60)) // Nome completo do Representante Legal
        header.append(buildString("00740472992", 11)) // CPF
        header.append(buildNumber("47", 4)) // DDD
        header.append(buildNumber("34652062", 9)) // Telefone
        header.append(buildNumber("0", 5)) // Ramal - Zero se ausente
        header.append(buildString(" ", 4)) // Reservado
        header.append("\r\n")

        // R03
        index += 1
        header.append(buildNumber(index.toString(), 8)) // Sequencial
        header.append(buildString("R03", 3)) // Tipo do registro
        header.append(buildString("ISRAEL DIAS GUERRA", 60)) // Nome completo do responsável pelo preenchimento da declaração
        header.append(buildString("35231523801", 11)) // CPF
        header.append(buildNumber("47", 4)) // DDD
        header.append(buildNumber("38010919", 9)) // Telefone
        header.append(buildNumber("0", 5)) // Ramal - Zero se ausente
        header.append(buildString(" ", 4)) // Reservado
        header.append("\r\n")

        return header.toString()
    }

    private String buildFooter(Date dateStart, Long indexStart) {
        Long index = indexStart + 1

        StringBuilder header = new StringBuilder()

        header.append(buildString("T9", 2)) // Tipo registro
        header.append(buildNumber(index.toString(), 8)) // Sequencial
        header.append(buildString(buildPeriod(dateStart), 5)) // Semestre e Ano-Calendário
        header.append(buildString(" ", 89)) // Reservado



        return header.toString()
    }

    private String buildString(String rawString, Integer size) {
        rawString = rawString.take(size)

        return StringUtils.rightPad(rawString, size, " ")
    }

    private String buildNumber(String rawString, Integer size) {
        rawString = rawString.take(size)

        return StringUtils.leftPad(rawString, size, "0")
    }

    private String buildPeriod(Date date) {
        String semester = CustomDateUtils.getMonth(date) <= 6 ? 1 : 2
        String year = CustomDateUtils.getYear(date)

        return "${semester}${year}"
    }

    private List<Map> includeCnpjAndReorderCardTransactionList(List<Map> legalPersonCardTransactionList) {
        legalPersonCardTransactionList.add([cpfCnpj: "53449323000123", month: 2, customerId: 1326881, value: Utils.toBigDecimal(14430.00)])
        legalPersonCardTransactionList.add([cpfCnpj: "53449323000123", month: 3, customerId: 1326881, value: Utils.toBigDecimal(43790.00)])
        legalPersonCardTransactionList.add([cpfCnpj: "53449323000123", month: 4, customerId: 1326881, value: Utils.toBigDecimal(17350.00)])
        legalPersonCardTransactionList.add([cpfCnpj: "53449323000123", month: 5, customerId: 1326881, value: Utils.toBigDecimal(14420.00)])
        legalPersonCardTransactionList.add([cpfCnpj: "53449323000123", month: 6, customerId: 1326881, value: Utils.toBigDecimal(20930.00)])
        legalPersonCardTransactionList.add([cpfCnpj: "55457283000197", month: 1, customerId: 1368562, value: Utils.toBigDecimal(61344.87)])
        legalPersonCardTransactionList.add([cpfCnpj: "55457283000197", month: 2, customerId: 1368562, value: Utils.toBigDecimal(104378.59)])
        legalPersonCardTransactionList.add([cpfCnpj: "55457283000197", month: 3, customerId: 1368562, value: Utils.toBigDecimal(130246.29)])
        legalPersonCardTransactionList.add([cpfCnpj: "55457283000197", month: 4, customerId: 1368562, value: Utils.toBigDecimal(118885.19)])
        legalPersonCardTransactionList.add([cpfCnpj: "55457283000197", month: 5, customerId: 1368562, value: Utils.toBigDecimal(155087.13)])
        legalPersonCardTransactionList.add([cpfCnpj: "55457283000197", month: 6, customerId: 1368562, value: Utils.toBigDecimal(152924.63)])
        legalPersonCardTransactionList.add([cpfCnpj: "52649601000123", month: 1, customerId: 3593227, value: Utils.toBigDecimal(19731.91)])
        legalPersonCardTransactionList.add([cpfCnpj: "54069780000155", month: 2, customerId: 3681555, value: Utils.toBigDecimal(12855.00)])
        legalPersonCardTransactionList.add([cpfCnpj: "54069780000155", month: 3, customerId: 3681555, value: Utils.toBigDecimal(32220.00)])
        legalPersonCardTransactionList.add([cpfCnpj: "54069780000155", month: 4, customerId: 3681555, value: Utils.toBigDecimal(49299.69)])
        legalPersonCardTransactionList.add([cpfCnpj: "17662201000157", month: 3, customerId: 3732094, value: Utils.toBigDecimal(19776.00)])

        return legalPersonCardTransactionList.sort { it.cpfCnpj }
    }
}
