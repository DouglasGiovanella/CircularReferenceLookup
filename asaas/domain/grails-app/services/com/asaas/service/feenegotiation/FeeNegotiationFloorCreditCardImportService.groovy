package com.asaas.service.feenegotiation

import com.asaas.domain.feenegotiation.FeeNegotiationFloor
import com.asaas.domain.feenegotiation.FeeNegotiationMcc
import com.asaas.exception.BusinessException
import com.asaas.exception.ImportException
import com.asaas.feenegotiation.FeeNegotiationFloorRepository
import com.asaas.feenegotiation.FeeNegotiationImportDataParser
import com.asaas.feenegotiation.FeeNegotiationMccRepository
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationType
import com.asaas.feenegotiation.FeeNegotiationValueType
import com.asaas.feenegotiation.adapter.FeeNegotiationFloorAdapter
import com.asaas.importdata.ExcelImporter
import com.asaas.importdata.FileImporter
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class FeeNegotiationFloorCreditCardImportService {

    def feeNegotiationFloorService
    def feeNegotiationMccService
    def grailsApplication
    def messageService

    public void importFile(CommonsMultipartFile file) {
        if (file.empty) throw new BusinessException("Nenhum arquivo foi selecionado.")

        Utils.withNewTransactionAndRollbackOnError({
            processFile(file)
        }, [
            ignoreStackTrace: true,
            onError: { Exception exception ->
                throw exception
            }
        ])
    }

    public void sendInconsistencies(List<Map> invalidItemList, String email) {
        String emailSubject = "Inconsistências na importação de limites de taxa no cartão de crédito"
        String message = messageService.buildTemplate("/mailTemplate/feenegotiationfloorcreditcardimport/importInconsistenciesMessage", [invalidItemList: invalidItemList])

        messageService.send(grailsApplication.config.asaas.sender, email, null, emailSubject, message, true)
    }

    private void processFile(CommonsMultipartFile file) {
        final Integer limitOfRows = 220
        FileImporter importer = new ExcelImporter(file)
            .startAtRow(1)
            .setLimitOfRowsToImport(limitOfRows)
            .setColumnNames([
                "A": "mcc",
                "B": "fixedFee",
                "C": "upfrontFee",
                "D": "upToSixInstallmentsFee",
                "E": "upToTwelveInstallmentsFee",
                "F": "feeNegotiationType",
                "G": "shouldDelete"
            ])

        List<Map> inconsistencyInfoList = []
        importer.setFlushAndClearEvery(10).forEachRow { Map rowData ->
            try {
                saveOrDeleteFeeNegotiationFloor(rowData)
            } catch (ValidationException validationException) {
                List<String> inconsistencies = DomainUtils.getValidationMessages(validationException.errors)
                Map inconsistencyInfo = [rowData: rowData, inconsistencies: inconsistencies]

                inconsistencyInfoList.add(inconsistencyInfo)
            } catch (BusinessException businessException) {
                List<String> inconsistencies = [businessException.message]
                Map inconsistencyInfo = [rowData: rowData, inconsistencies: inconsistencies]

                inconsistencyInfoList.add(inconsistencyInfo)
            } catch (Exception exception) {
                throw exception
            }
        }

        if (!importer.numberOfReadRows) throw new BusinessException("Nenhuma linha processada")

        if (inconsistencyInfoList) {
            throw new ImportException("Foram encontradas inconsistências no arquivo, verifique a lista no seu email. Nenhum limite foi importado.", inconsistencyInfoList)
        }
    }

    private void saveOrDeleteFeeNegotiationFloor(Map rowData) {
        String parsedMcc = FeeNegotiationImportDataParser.parseMcc(rowData.mcc)
        FeeNegotiationType feeNegotiationType = FeeNegotiationImportDataParser.parseFeeNegotiationType(rowData.feeNegotiationType)
        Boolean shouldDelete = FeeNegotiationImportDataParser.shouldDelete(rowData.shouldDelete)

        if (shouldDelete) {
            deleteFeeNegotiationFloor(parsedMcc, feeNegotiationType)
            return
        }

        saveFeeNegotiationFloor(rowData, parsedMcc, feeNegotiationType)
    }

    private void saveFeeNegotiationFloor(Map rowData, String parsedMcc, FeeNegotiationType feeNegotiationType) {
        FeeNegotiationMcc feeNegotiationMcc = feeNegotiationMccService.findOrCreate(parsedMcc)

        for (String productFeeType : FeeNegotiationFloor.CREDIT_CARD_PRODUCT_FEE_TYPE_LIST) {
            FeeNegotiationValueType valueType = productFeeType == "fixedFee" ? FeeNegotiationValueType.FIXED : FeeNegotiationValueType.PERCENTAGE
            FeeNegotiationFloorAdapter feeNegotiationFloorAdapter = new FeeNegotiationFloorAdapter(feeNegotiationMcc, productFeeType, rowData[productFeeType], valueType, feeNegotiationType, FeeNegotiationProduct.CREDIT_CARD)

            feeNegotiationFloorService.saveOrUpdate(feeNegotiationFloorAdapter)
        }
    }

    private void deleteFeeNegotiationFloor(String parsedMcc, FeeNegotiationType feeNegotiationType) {
        FeeNegotiationMcc feeNegotiationMcc = FeeNegotiationMccRepository.query(["mcc": parsedMcc]).get()
        if (!feeNegotiationMcc) throw new BusinessException("Não foi encontrado um FeeNegotiationMcc para o mcc [$parsedMcc] informado.")

        for (String productFeeType : FeeNegotiationFloor.CREDIT_CARD_PRODUCT_FEE_TYPE_LIST) {
            Map search = [feeNegotiationMcc: feeNegotiationMcc, productFeeType: productFeeType, type: feeNegotiationType, product: FeeNegotiationProduct.CREDIT_CARD]
            FeeNegotiationFloor feeNegotiationFloor = FeeNegotiationFloorRepository.query(search).get()

            if (!feeNegotiationFloor) throw new BusinessException("Limite de taxa não encontrado.")

            feeNegotiationFloorService.delete(feeNegotiationFloor)
        }
    }
}
