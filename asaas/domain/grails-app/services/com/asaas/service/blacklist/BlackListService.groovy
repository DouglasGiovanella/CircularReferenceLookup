package com.asaas.service.blacklist

import com.asaas.domain.blacklist.BlackList
import com.asaas.importdata.ExcelImporter
import com.asaas.importdata.FileImporter
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class BlackListService {

    def riskAnalysisRequestService

	public BlackList save(params) {
		BlackList blackList = new BlackList()
		Map parsedParams = parseParams(params)
		validateRequiredFields(blackList, parsedParams)

		if (blackList.hasErrors()) return blackList

		blackList.properties = parsedParams
		blackList.save(failOnError: true)

        riskAnalysisRequestService.createToCustomerInfoAddedBlackList(blackList)

		if (!params.mobilePhone || !params.phone) return blackList

		BlackList blackListWithMobilePhone = new BlackList()
		parsedParams.phone = Utils.removeNonNumeric(params.mobilePhone)
		parsedParams.email = null
		parsedParams.cpfCnpj = null
		validateRequiredFields(blackListWithMobilePhone, parsedParams)

		if (blackListWithMobilePhone.hasErrors()) return blackListWithMobilePhone

		blackListWithMobilePhone.properties = parsedParams
		blackListWithMobilePhone.save(failOnError: true)

		return blackListWithMobilePhone
	}

	private Map parseParams(Map params) {
        Map parsedParams = [
            name: params.name,
            email: Utils.replaceInvalidMailCharacters(params.email ?: ""),
            cpfCnpj: CpfCnpjUtils.buildCpfCnpj(params.cpfCnpj),
            phone: Utils.removeNonNumeric(params.phone ?: params.mobilePhone)
        ]

		if (!params.id) parsedParams.observations = addObservationComment("Criado em ${CustomDateUtils.formatDateTime(new Date())}", params.observations)

		return parsedParams
	}

	public BlackList update(params) {
		BlackList blackList = BlackList.get(params.id)

		Map parsedParams = parseParams(params)
		parsedParams.observations = addObservationComment(blackList.observations, params.observations)

		validateRequiredFields(blackList, parsedParams)

		if (blackList.hasErrors()) return blackList

		blackList.properties = parsedParams
		blackList.save(failOnError: true)

		riskAnalysisRequestService.createToCustomerInfoAddedBlackList(blackList)

		return blackList
	}

	public BlackList delete(Long blackListId) {
		BlackList blackList = BlackList.get(blackListId)

		blackList.deleted = true
		blackList.observations = addObservationComment(blackList.observations, "Bloqueio removido em ${CustomDateUtils.formatDateTime(new Date())}")
		blackList.save(failOnError: true)

		return blackList
	}

    public void importFile(CommonsMultipartFile file) {
        FileImporter importer = new ExcelImporter(file).setColumnNames(["cpfCnpj", "name"])
        importer.forEachRow { Map rowData ->
            save([
                name: rowData.name,
                cpfCnpj: rowData.cpfCnpj,
                observations: "Descredenciados - Arquivo: ${file.getOriginalFilename()}"
            ])
        }
    }

	protected String addObservationComment(String observations, String newObservation) {
		if (!newObservation) return observations

		StringBuilder observationsStringBuilder = new StringBuilder(observations ? observations : "")
		observationsStringBuilder.append("\n\n" + newObservation + "\n")
		observationsStringBuilder.append(" (Comentário adicionado em ${CustomDateUtils.formatDateTime(new Date())})")

		return observationsStringBuilder.toString()
	}

	protected void validateRequiredFields(BlackList blackList, Map params) {
		if (!params.email && !params.name && !params.cpfCnpj && !params.phone) {
			DomainUtils.addError(blackList, Utils.getMessageProperty("customer.blacklist.admin.invalid.fields"))
		} else if (params.email && !Utils.emailIsValid(params.email)) {
			DomainUtils.addError(blackList, Utils.getMessageProperty("customer.blacklist.admin.invalid.email"))
		} else if (params.cpfCnpj && !CpfCnpjUtils.validate(params.cpfCnpj)) {
			DomainUtils.addError(blackList, Utils.getMessageProperty("customer.blacklist.admin.invalid.cpfCnpj"))
		} else if (params.email && BlackList.query([email: params.email] + (blackList?.id ? ["id[ne]": blackList?.id] : [:])).get()) {
			DomainUtils.addError(blackList, Utils.getMessageProperty("customer.blacklist.admin.email.alreadyExists"))
		} else if (params.cpfCnpj && BlackList.query([cpfCnpj: params.cpfCnpj] + (blackList?.id ? ["id[ne]": blackList?.id] : [:])).get()) {
			DomainUtils.addError(blackList, Utils.getMessageProperty("customer.blacklist.admin.cpfCnpj.alreadyExists"))
		} else if (params.phone && BlackList.query([phone: params.phone] + (blackList?.id ? ["id[ne]": blackList?.id] : [:])).get()) {
			DomainUtils.addError(blackList, Utils.getMessageProperty("customer.blacklist.admin.phone.alreadyExists"))
		}
	}
}
