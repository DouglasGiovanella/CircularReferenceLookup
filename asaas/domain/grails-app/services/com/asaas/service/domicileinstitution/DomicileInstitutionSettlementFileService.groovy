package com.asaas.service.domicileinstitution

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.domicileinstitution.DomicileInstitutionSettlementConciliationFile
import com.asaas.domain.domicileinstitution.DomicileInstitutionSettlementConciliationItem
import com.asaas.domain.domicileinstitution.DomicileInstitutionSettlementConciliationReturnFile
import com.asaas.domain.domicileinstitution.DomicileInstitutionSettlementFile
import com.asaas.domain.file.AsaasFile
import com.asaas.domicileinstitution.DomicileInstitutionSettlementConciliationFileStatus
import com.asaas.domicileinstitution.DomicileInstitutionSettlementConciliationItemStatus
import com.asaas.domicileinstitution.DomicileInstitutionSettlementFileStatus
import com.asaas.domicileinstitution.DomicileInstitutionSettlementOccurrence
import com.asaas.domicileinstitution.DomicileInstitutionSettlementType
import com.asaas.domicileinstitution.parser.DomicileInstitutionSettlementFileParser
import com.asaas.domicileinstitution.retriever.DomicileInstitutionSettlementFileRetriever
import com.asaas.domicileinstitution.vo.DomicileInstitutionConciliationVO
import com.asaas.domicileinstitution.vo.DomicileInstitutionScheduledSettlementFileVO
import com.asaas.domicileinstitution.vo.DomicileInstitutionTransactionsSettlementGroupVO
import com.asaas.domicileinstitution.vo.DomicileInstitutionTransactionsSettlementHeaderVO
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.pagination.SequencedResultList
import com.asaas.userpermission.AdminUserPermissionName
import com.asaas.userpermission.AdminUserPermissionUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.codehaus.groovy.grails.plugins.orm.auditable.AuditLogListener

@Transactional
class DomicileInstitutionSettlementFileService {

    def domicileInstitutionSettlementConciliationFileService
    def domicileInstitutionSettlementConciliationItemService
    def fileService

    public Map buildConciliationInfoMap(Map params, Integer limitPerPage, Integer currentPage) {
        Map parsedParams = parseListParams(params)
        validatePeriod(parsedParams."settlementFileDateCreated[ge]", parsedParams."settlementFileDateCreated[le]")

        SequencedResultList<DomicileInstitutionConciliationVO> domicileInstitutionConciliationList = SequencedResultList.build(
            DomicileInstitutionSettlementConciliationItem.findSlcResult(parsedParams),
            limitPerPage,
            currentPage
        )

        Map conciliationStatisticsInfoMap = getConciliationStatisticsInfoMap(parsedParams)

        for (DomicileInstitutionConciliationVO domicileInstitutionConciliation : domicileInstitutionConciliationList) {
            domicileInstitutionConciliation.canConciliateInContingency = canConciliateInContingency(domicileInstitutionConciliation)
            domicileInstitutionConciliation.canReprocessConciliation = canReprocessConciliation(domicileInstitutionConciliation)
            domicileInstitutionConciliation.canProcessManuallyConciliation = canProcessManuallyConciliation(domicileInstitutionConciliation)
        }

        return [
            domicileInstitutionConciliationList: domicileInstitutionConciliationList,
            receivedAmount: conciliationStatisticsInfoMap.receivedAmount,
            returnedAmount: conciliationStatisticsInfoMap.returnedAmount,
            processingAmount: conciliationStatisticsInfoMap.processingAmount,
            failedAmount: conciliationStatisticsInfoMap.failedAmount
        ]
    }

    public Map buildConciliationDetailsMap(Map params, Integer limitPerPage, Integer currentPage) {
        Map parsedParams = parseShowParams(params)

        SequencedResultList<DomicileInstitutionSettlementConciliationItem> domicileInstitutionSettlementConciliationItemList = SequencedResultList.build(
            DomicileInstitutionSettlementConciliationItem.query(parsedParams),
            limitPerPage,
            currentPage
        )

        List<DomicileInstitutionSettlementConciliationReturnFile> domicileInstitutionSettlementConciliationReturnFileList
        if (domicileInstitutionSettlementConciliationItemList) {
            Long conciliationFileReferenceId = domicileInstitutionSettlementConciliationItemList.get(0)?.conciliationFile?.id
            if (conciliationFileReferenceId) domicileInstitutionSettlementConciliationReturnFileList = DomicileInstitutionSettlementConciliationReturnFile.query([conciliationFileReferenceId: conciliationFileReferenceId]).list()
        }

        return [
            domicileInstitutionSettlementConciliationItemList: domicileInstitutionSettlementConciliationItemList,
            domicileInstitutionSettlementConciliationReturnFileList: domicileInstitutionSettlementConciliationReturnFileList
        ]
    }

    public DomicileInstitutionSettlementFile save(String fileName, String fileContents, DomicileInstitutionSettlementType type) {
        DomicileInstitutionTransactionsSettlementHeaderVO domicileInstitutionTransactionsSettlementHeaderVO = DomicileInstitutionSettlementFileParser.parseHeader(fileContents)
        validateSave(fileName, fileContents, domicileInstitutionTransactionsSettlementHeaderVO.issuerControlNumber, domicileInstitutionTransactionsSettlementHeaderVO.referenceDate)

        DomicileInstitutionTransactionsSettlementGroupVO domicileInstitutionTransactionsSettlementGroupVO = DomicileInstitutionSettlementFileParser.parse(fileContents, type)

        AsaasFile asaasFile = fileService.createFile(fileName, fileContents)
        DomicileInstitutionSettlementFile domicileInstitutionSettlementFile = new DomicileInstitutionSettlementFile()
        domicileInstitutionSettlementFile.referenceDate = domicileInstitutionTransactionsSettlementHeaderVO.referenceDate
        domicileInstitutionSettlementFile.issuerControlNumber = domicileInstitutionTransactionsSettlementHeaderVO.issuerControlNumber
        domicileInstitutionSettlementFile.acquirerCnpj = domicileInstitutionTransactionsSettlementGroupVO.acquirerCnpj
        domicileInstitutionSettlementFile.creditorIspb = domicileInstitutionTransactionsSettlementGroupVO.creditorIspb
        domicileInstitutionSettlementFile.debtorIspb = domicileInstitutionTransactionsSettlementGroupVO.debtorIspb
        domicileInstitutionSettlementFile.acquirerAgency = domicileInstitutionTransactionsSettlementGroupVO.acquirerAgency
        domicileInstitutionSettlementFile.acquirerAccount = domicileInstitutionTransactionsSettlementGroupVO.acquirerAccount
        domicileInstitutionSettlementFile.acquirerName = domicileInstitutionTransactionsSettlementGroupVO.acquirerName
        domicileInstitutionSettlementFile.status = DomicileInstitutionSettlementFileStatus.PENDING
        domicileInstitutionSettlementFile.type = type
        domicileInstitutionSettlementFile.fileName = fileName
        domicileInstitutionSettlementFile.file = asaasFile
        domicileInstitutionSettlementFile.save(failOnError: true)

        return domicileInstitutionSettlementFile
    }

    public void retrieveFiles(DomicileInstitutionSettlementType settlementType) {
        List<DomicileInstitutionScheduledSettlementFileVO> domicileInstitutionScheduledSettlementFileVOList = DomicileInstitutionSettlementFileRetriever.retrieve(settlementType)

        Utils.forEachWithFlushSession(domicileInstitutionScheduledSettlementFileVOList, 5, { DomicileInstitutionScheduledSettlementFileVO domicileInstitutionScheduledSettlementFileVO ->
            Utils.withNewTransactionAndRollbackOnError ( {
                save(domicileInstitutionScheduledSettlementFileVO.fileName, domicileInstitutionScheduledSettlementFileVO.fileContents, settlementType)
                DomicileInstitutionSettlementFileRetriever.deleteProcessedFile(domicileInstitutionScheduledSettlementFileVO.fileName)
            }, [logErrorMessage: "DomicileInstitutionSettlementFileService.retrieveFile >>> Erro ao salvar o arquivo de liquidação [Nome do Arquivo: ${domicileInstitutionScheduledSettlementFileVO.fileName}, Tipo: ${settlementType}]."])
        })
    }

    public void processPendingFiles(DomicileInstitutionSettlementType settlementType) {
        Map domicileInstitutionSettlementFileInfoMap = DomicileInstitutionSettlementFile.query([columnList: ["id", "file"], status: DomicileInstitutionSettlementFileStatus.PENDING, "type": settlementType]).get()

        if (!domicileInstitutionSettlementFileInfoMap) return

        AsaasFile file = domicileInstitutionSettlementFileInfoMap.file
        String fileContents = file.getFileContent()
        DomicileInstitutionTransactionsSettlementGroupVO domicileInstitutionTransactionsSettlementGroupVO = DomicileInstitutionSettlementFileParser.parse(fileContents, settlementType)

        Date domicileInstitutionSettlementConciliationFirstItemScheduleDate
        Utils.withNewTransactionAndRollbackOnError({
            List<DomicileInstitutionSettlementConciliationItem> domicileInstitutionSettlementConciliationItemList = domicileInstitutionSettlementConciliationItemService.processItems(domicileInstitutionSettlementFileInfoMap.id, domicileInstitutionTransactionsSettlementGroupVO)
            if (domicileInstitutionSettlementConciliationItemList) {
                domicileInstitutionSettlementConciliationFirstItemScheduleDate = domicileInstitutionSettlementConciliationItemList.first().scheduleDate
            } else {
                AsaasLogger.error("DomicileInstitutionSettlementFileService.processPendingFiles >>> Não foram identificados itens para a conciliação no arquivo. [DomicileInstitutionSettlementFileID: ${domicileInstitutionSettlementFileInfoMap.id}]")
            }
        }, [logErrorMessage: "DomicileInstitutionSettlementFileService.processPendingFiles >>> Erro ao processar itens da conciliação [DomicileInstitutionSettlementFileID: ${domicileInstitutionSettlementFileInfoMap.id}]"])

        if (domicileInstitutionSettlementConciliationFirstItemScheduleDate) {
            DomicileInstitutionSettlementFile domicileInstitutionSettlementFile = DomicileInstitutionSettlementFile.get(domicileInstitutionSettlementFileInfoMap.id)
            domicileInstitutionSettlementFile.settlementDate =  domicileInstitutionSettlementConciliationFirstItemScheduleDate
            domicileInstitutionSettlementFile.status = DomicileInstitutionSettlementFileStatus.PROCESSED
            domicileInstitutionSettlementFile.save(failOnError: true)
        } else {
            AsaasLogger.error("DomicileInstitutionSettlementFileService.processPendingFiles >>> Não foi identificado a data do agendamento para o primeiro item do arquivo. [DomicileInstitutionSettlementFileID: ${domicileInstitutionSettlementFileInfoMap.id}]")
        }
    }

    public void processConciliation(DomicileInstitutionSettlementType settlementType, Date startDate, Date endDate) {
        if (!isValidCycle(settlementType, false, null)) return

        final String asaasDebtorIspb = AsaasApplicationHolder.getConfig().asaas.ispb

        AuditLogListener.withoutAuditLogWhenExecutedByJobs ({
            List<Long> creditSettlementFileIdList = DomicileInstitutionSettlementFile.query([column: "id", type: settlementType, referenceDate: new Date().clearTime(), "dateCreated[ge]": startDate, "dateCreated[le]": endDate, status: DomicileInstitutionSettlementFileStatus.PROCESSED, debtorIspb: asaasDebtorIspb]).list()
            for (Long settlementFileId : creditSettlementFileIdList) {
                AsaasFile file
                Utils.withNewTransactionAndRollbackOnError ({
                    DomicileInstitutionSettlementFile domicileInstitutionSettlementFile = DomicileInstitutionSettlementFile.get(settlementFileId)
                    List<DomicileInstitutionSettlementConciliationItem> creditSettlementFileItemList = DomicileInstitutionSettlementConciliationItem.query([settlementFileId: settlementFileId, status: DomicileInstitutionSettlementConciliationItemStatus.PENDING]).list()

                    if (creditSettlementFileItemList) {
                        file = buildConciliationFile(domicileInstitutionSettlementFile, creditSettlementFileItemList, [], true, false, false)
                        domicileInstitutionSettlementFile.processedDate = new Date()
                        domicileInstitutionSettlementFile.save(failOnError: true)
                    } else {
                        AsaasLogger.error("DomicileInstitutionSettlementFileService.processConciliation >>> Não foram identificados itens para criação da conciliação")
                        return
                    }
                }, [logErrorMessage: "DomicileInstitutionSettlementFileService.processConciliation >>> Erro ao fazer a conciliação [DomicileInstitutionSettlementFileID: ${settlementFileId}]"])

                if (file) {
                    domicileInstitutionSettlementConciliationFileService.uploadConciliationFile(file.originalName, file.getFileContent())
                }
            }
        })
    }

    public void processCreditSecondConciliation() {
        if (!isValidCycle(DomicileInstitutionSettlementType.CREDIT, true, null)) return

        Map queryFilter = [column: "id", settlementDate: new Date().clearTime(), status: DomicileInstitutionSettlementFileStatus.SCHEDULE_CONCILIATED, type: DomicileInstitutionSettlementType.CREDIT, disableSort: true]
        if (!isAutomaticProcessHourForNonAsaasDebtor()) queryFilter += ["debtorIspb": AsaasApplicationHolder.getConfig().asaas.ispb]

        AuditLogListener.withoutAuditLogWhenExecutedByJobs ({
            List<Long> creditSettlementFileIdList = DomicileInstitutionSettlementFile.query(queryFilter).list()
            for (Long creditSettlementFileId : creditSettlementFileIdList) {
                AsaasFile file
                Utils.withNewTransactionAndRollbackOnError({
                    DomicileInstitutionSettlementFile domicileInstitutionSettlementFile = DomicileInstitutionSettlementFile.get(creditSettlementFileId)
                    List<DomicileInstitutionSettlementConciliationItem> creditSettlementFileItemList = DomicileInstitutionSettlementConciliationItem.query([settlementFileId: creditSettlementFileId, disableSort: true]).list()
                    if (!creditSettlementFileItemList) {
                        AsaasLogger.error("DomicileInstitutionSettlementFileService.processCreditSecondConciliation >>> Não foram identificados itens para criação da segunda conciliação")
                        return
                    }

                    List<DomicileInstitutionSettlementConciliationItem> pendingCreditSettlementFileItemList = creditSettlementFileItemList.findAll { [DomicileInstitutionSettlementOccurrence.SCHEDULED, DomicileInstitutionSettlementOccurrence.SUCCESS].contains(it.scheduleOccurrence) }
                    List<DomicileInstitutionSettlementConciliationItem> rejectedCreditSettlementFileItemList = []
                    if (creditSettlementFileItemList.size() > pendingCreditSettlementFileItemList.size()) {
                        rejectedCreditSettlementFileItemList = creditSettlementFileItemList.findAll { it.occurrence.isRejected() }
                    }
                    if (pendingCreditSettlementFileItemList) {
                        file = buildConciliationFile(domicileInstitutionSettlementFile, pendingCreditSettlementFileItemList, rejectedCreditSettlementFileItemList, false, false, false)
                    }
                }, [logErrorMessage: "processCreditSecondConciliation.processCreditSecondConciliation >>> Erro ao fazer a segunda conciliação [DomicileInstitutionSettlementFileID: ${creditSettlementFileId}]"])

                if (file) {
                    domicileInstitutionSettlementConciliationFileService.uploadConciliationFile(file.originalName, file.getFileContent())
                }
            }
        })
    }

    public void processContingencyConciliation(Long settlementFileOriginId) {
        AsaasFile file

        Utils.withNewTransactionAndRollbackOnError({
            DomicileInstitutionSettlementFile domicileInstitutionSettlementFile = DomicileInstitutionSettlementFile.get(settlementFileOriginId)
            domicileInstitutionSettlementFile.lock()

            if (!isValidContingencyCycle(domicileInstitutionSettlementFile.type)) {
                AsaasLogger.error("DomicileInstitutionSettlementFileService.processContingencyConciliation >>> Fora da grade de conciliação. [domicileInstitutionSettlementFileId: ${settlementFileOriginId}]")
                throw new BusinessException("Fora da grade de conciliação")
            }

            if (domicileInstitutionSettlementFile.status.isSettlementConciliated()) {
                AsaasLogger.error("DomicileInstitutionSettlementFileService.processContingencyConciliation >>> A arquivo já foi conciliado com sucesso. [domicileInstitutionSettlementFileId: ${settlementFileOriginId}]")
                throw new BusinessException("Arquivo já foi conciliado com sucesso.")
            }

            if (domicileInstitutionSettlementFile.status.isRejected() || domicileInstitutionSettlementFile.status.isPartialSettlementConciliated()) {
                AsaasLogger.error("DomicileInstitutionSettlementFileService.processContingencyConciliation >>> Arquivo rejeitado ou parcialmente liquidado não pode ser conciliado. [domicileInstitutionSettlementFileId: ${settlementFileOriginId}]")
                throw new BusinessException("Arquivo rejeitado ou parcialmente liquidado não pode ser conciliado.")
            }

            List<DomicileInstitutionSettlementConciliationItem> settlementFileItemList = DomicileInstitutionSettlementConciliationItem.query([settlementFileId: settlementFileOriginId]).list()

            if (!settlementFileItemList) {
                AsaasLogger.error("DomicileInstitutionSettlementFileService.processContingencyConciliation >>> Não foram identificados itens para criação da conciliação contingencial. [domicileInstitutionSettlementFileId: ${settlementFileOriginId}]")
                return
            }
            file = buildConciliationFile(domicileInstitutionSettlementFile, settlementFileItemList, [], false, true, false)
            domicileInstitutionSettlementFile.processedDate = new Date()
            domicileInstitutionSettlementFile.save(failOnError: true)
        }, [logErrorMessage: "DomicileInstitutionSettlementFileService.processContingencyConciliation >>> Erro ao fazer a conciliação [DomicileInstitutionSettlementFileID: ${settlementFileOriginId}]",
            ignoreStackTrace: true,
            onError: { Exception exception -> throw exception }])

        if (file) {
            domicileInstitutionSettlementConciliationFileService.uploadConciliationFile(file.originalName, file.getFileContent())
        }
    }

    public void reprocessConciliationWithError(Long settlementFileOriginId) {
        AsaasFile file
        Utils.withNewTransactionAndRollbackOnError({
            DomicileInstitutionSettlementFile domicileInstitutionSettlementFile = DomicileInstitutionSettlementFile.get(settlementFileOriginId)
            domicileInstitutionSettlementFile.lock()
            List<DomicileInstitutionSettlementConciliationItem> settlementFileItemList = DomicileInstitutionSettlementConciliationItem.query([settlementFileId: settlementFileOriginId, status: DomicileInstitutionSettlementConciliationItemStatus.ERROR]).list()

            if (!settlementFileItemList) {
                AsaasLogger.error("DomicileInstitutionSettlementFileService.reprocessConciliationWithError >>> Não foram identificados itens com erro para reprocessamento")
                return
            }
            file = buildConciliationFile(domicileInstitutionSettlementFile, settlementFileItemList, [], false, false, true)
        }, [logErrorMessage: "DomicileInstitutionSettlementFileService.reprocessConciliationWithError >>> Erro ao reprocessar conciliação com erro [DomicileInstitutionSettlementFileID: ${settlementFileOriginId}]",
            ignoreStackTrace: true,
            onError: { Exception exception -> throw exception }])

        if (file) {
            domicileInstitutionSettlementConciliationFileService.uploadConciliationFile(file.originalName, file.getFileContent())
        }
    }

    public void processManuallyConciliation(Long settlementAnticipationFileOriginId) {
        AsaasFile file
        Utils.withNewTransactionAndRollbackOnError ({
            DomicileInstitutionSettlementFile domicileInstitutionSettlementFile = DomicileInstitutionSettlementFile.get(settlementAnticipationFileOriginId)
            domicileInstitutionSettlementFile.lock()
            if (!isValidCycle(domicileInstitutionSettlementFile.type, false, domicileInstitutionSettlementFile.dateCreated)) {
                AsaasLogger.error("DomicileInstitutionSettlementFileService.processManuallyConciliation >>> Fora da grade de conciliação. [domicileInstitutionSettlementFileId: ${settlementAnticipationFileOriginId}]")
                throw new BusinessException("Fora da grade de conciliação.")
            }

            if (!domicileInstitutionSettlementFile.status.isPendingOrProcessed()) {
                AsaasLogger.error("DomicileInstitutionSettlementFileService.processManuallyConciliation >>> Arquivo já conciliado. [domicileInstitutionSettlementFileId: ${settlementAnticipationFileOriginId}]")
                throw new BusinessException("Arquivo já conciliado.")
            }

            List<DomicileInstitutionSettlementConciliationItem> creditSettlementFileItemList = DomicileInstitutionSettlementConciliationItem.query([settlementFileId: settlementAnticipationFileOriginId, status: DomicileInstitutionSettlementConciliationItemStatus.PENDING]).list()
            if (creditSettlementFileItemList) {
                file = buildConciliationFile(domicileInstitutionSettlementFile, creditSettlementFileItemList, [], true, false, false)
                domicileInstitutionSettlementFile.processedDate = new Date()
                domicileInstitutionSettlementFile.save(failOnError: true)
            } else {
                AsaasLogger.error("DomicileInstitutionSettlementFileService.processManuallyConciliation >>> Não foram identificados itens para criação da conciliação")
            }
        }, [logErrorMessage: "DomicileInstitutionSettlementFileService.processManuallyConciliation >>> Erro ao fazer a conciliação [DomicileInstitutionSettlementFileID: ${settlementAnticipationFileOriginId}]",
            ignoreStackTrace: true,
            onError: { Exception exception -> throw exception }])

        if (file) {
            domicileInstitutionSettlementConciliationFileService.uploadConciliationFile(file.originalName, file.getFileContent())
        }
    }

    public Boolean canConciliateInContingency(DomicileInstitutionConciliationVO domicileInstitutionConciliation) {
        Date now = new Date().clearTime()

        if (now <= domicileInstitutionConciliation.settlementDate) return false

        if (domicileInstitutionConciliation.status.isNotAllowedToContingency()) return false

        if (!isValidContingencyCycle(domicileInstitutionConciliation.type)) return false

        return true
    }

    public Boolean canReprocessConciliation(DomicileInstitutionConciliationVO domicileInstitutionConciliation) {
        if (settlementDateIsBeforeToday(domicileInstitutionConciliation.settlementDate)) return false

        if (!domicileInstitutionConciliation.statusConciliation?.isError()) return false

        if (!isValidCycle(domicileInstitutionConciliation.type, isSecondConcilitation(domicileInstitutionConciliation), null)) return false

        return true
    }

    public Boolean canProcessManuallyConciliation(DomicileInstitutionConciliationVO domicileInstitutionConciliation) {
        if (!AdminUserPermissionUtils.allowed(AdminUserPermissionName.CAN_EXECUTE_ACTIONS_SLC)) return false

        if (domicileInstitutionConciliation.canReprocessConciliation) return false

        final String asaasIspb = AsaasApplicationHolder.getConfig().asaas.ispb
        if (!domicileInstitutionConciliation.type.isAnticipation() && domicileInstitutionConciliation.debtorIspb == asaasIspb) return false

        if (!isValidCycle(domicileInstitutionConciliation.type, isSecondConcilitation(domicileInstitutionConciliation), domicileInstitutionConciliation.receivedDate)) return false

        if (!domicileInstitutionConciliation.status.isPendingOrProcessed()) return false

        Date today = new Date().clearTime()
        if (!domicileInstitutionConciliation.type.isCredit() && !CustomDateUtils.isSameDayOfYear(domicileInstitutionConciliation.settlementDate.clone().clearTime(), today)) return false

        if (domicileInstitutionConciliation.type.isCredit() && settlementDateIsBeforeToday(domicileInstitutionConciliation.settlementDate)) return false

        return true
    }

    public Boolean canEditOccurrenceCode(DomicileInstitutionSettlementConciliationItem domicileInstitutionSettlementConciliationItem) {
        if (!AdminUserPermissionUtils.allowed(AdminUserPermissionName.CAN_EXECUTE_ACTIONS_SLC)) return false

        DomicileInstitutionSettlementFile domicileInstitutionSettlementFile = domicileInstitutionSettlementConciliationItem.settlementFile

        if (domicileInstitutionSettlementFile.status.isSettlementConciliated()) return false

        if (domicileInstitutionSettlementFile.status.isRejected()) return false

        if (domicileInstitutionSettlementFile.status.isScheduleConciliated()) {
            if (isAutomaticProcessHourForNonAsaasDebtor()) return false
            if (domicileInstitutionSettlementConciliationItem.scheduleOccurrence.isRejected()) return false
        }

        if (settlementDateIsBeforeToday(domicileInstitutionSettlementFile.settlementDate)) return false

        return true
    }

    public Map getConciliationStatisticsInfoMap(Map params) {
        List<DomicileInstitutionConciliationVO> domicileInstitutionConciliationTotals = DomicileInstitutionSettlementConciliationItem.findSlcResult(params).list()

        Integer receivedAmount = 0
        Integer returnedAmount = 0
        Integer processingAmount = 0
        Integer failedAmount = 0

        for (DomicileInstitutionConciliationVO domicileInstitutionConciliation : domicileInstitutionConciliationTotals) {
            if (domicileInstitutionConciliation.receivedFileName) {
                receivedAmount++
            }
            if (domicileInstitutionConciliation.returnedFileName) {
                returnedAmount++
            }
            if (!domicileInstitutionConciliation.statusConciliation || domicileInstitutionConciliation.statusConciliation?.isPending()) {
                processingAmount++
            }
            if (domicileInstitutionConciliation.statusConciliation?.isError()) {
                failedAmount++
            }
        }

        return [
            receivedAmount: receivedAmount,
            returnedAmount: returnedAmount,
            processingAmount: processingAmount,
            failedAmount: failedAmount
        ]
    }

    public void validatePeriod(Date startDate, Date endDate) {
        final Integer daysLimitToFilter = 15

        if (!startDate || !endDate) {
            throw new BusinessException("O periodo deve ser informado")
        }

        if (startDate > endDate) {
            throw new BusinessException("A data inicial deve ser anterior à data final")
        }

        if (CustomDateUtils.calculateDifferenceInDays(startDate, endDate) > daysLimitToFilter) {
            throw new BusinessException("O período máximo para filtro é de até ${daysLimitToFilter} dias")
        }
    }

    private AsaasFile buildConciliationFile(DomicileInstitutionSettlementFile settlementFile, List<DomicileInstitutionSettlementConciliationItem> settlementFileItemList, List<DomicileInstitutionSettlementConciliationItem> rejectedCreditSettlementFileItemList, Boolean bypassBuildControlNumber, Boolean isSecondGrid, Boolean isScheduleReprocess) {
        if (!bypassBuildControlNumber) settlementFileItemList = domicileInstitutionSettlementConciliationItemService.setNewControlNumberAndOcurrence(settlementFileItemList, settlementFile.type, isScheduleReprocess)

        DomicileInstitutionSettlementConciliationFile domicileInstitutionSettlementConciliationFile = domicileInstitutionSettlementConciliationFileService.buildConciliationFile(settlementFile.type, settlementFileItemList, isSecondGrid)
        domicileInstitutionSettlementConciliationItemService.updateConciliationInfo(settlementFileItemList, domicileInstitutionSettlementConciliationFile, isScheduleReprocess)

        if (rejectedCreditSettlementFileItemList) domicileInstitutionSettlementConciliationItemService.updateConciliationInfo(rejectedCreditSettlementFileItemList, domicileInstitutionSettlementConciliationFile, isScheduleReprocess)

        setConciliationStatus(settlementFile, settlementFileItemList + rejectedCreditSettlementFileItemList)

        return domicileInstitutionSettlementConciliationFile.file
    }

    private Map parseListParams(Map params) {
        Map parsedParams = [:]

        if (params.startDate) {
            parsedParams."settlementFileDateCreated[ge]" = CustomDateUtils.fromString(params.startDate)
        }

        if (params.endDate) {
            parsedParams."settlementFileDateCreated[le]" = CustomDateUtils.fromString(params.endDate)
        }

        if (params.fileName) parsedParams."settlementFileName[like]" = params.fileName
        if (params."acquirerName[like]") parsedParams."acquirerName[like]" = params."acquirerName[like]"

        if (params.settlementFileType) {
            parsedParams.settlementFileType = DomicileInstitutionSettlementType.valueOf(params.settlementFileType.toString())
        }

        if (params.settlementFileStatus) {
            parsedParams.settlementFileStatus = DomicileInstitutionSettlementFileStatus.valueOf(params.settlementFileStatus.toString())
        }

        if (params.statusConciliation) {
            parsedParams.statusConciliation = DomicileInstitutionSettlementConciliationFileStatus.valueOf(params.statusConciliation.toString())
        }

        return parsedParams
    }

    private Map parseShowParams(Map params) {
        Map parsedParams = [:]

        if (params.settlementFileId) parsedParams.settlementFileId = params.settlementFileId
        if (params.centralizingCpfCnpj) parsedParams.centralizingCpfCnpj = params.centralizingCpfCnpj
        if (params.settlementExternalIdentifier) parsedParams.settlementExternalIdentifier = params.settlementExternalIdentifier
        if (params.controlNumber) parsedParams.controlNumber = params.controlNumber
        if (params.receivableUnitExternalIdentifier) parsedParams.receivableUnitExternalIdentifier = params.receivableUnitExternalIdentifier


        return parsedParams
    }

    private void validateSave(String fileName, String fileContents, String issuerControlNumber, Date referenceDate) {
        if (!fileContents) throw new RuntimeException("O arquivo está vazio.")

        if (!fileName) throw new RuntimeException("Informe o nome do arquivo.")
        Boolean isFileAlreadyImported = DomicileInstitutionSettlementFile.query([fileName: fileName, issuerControlNumber: issuerControlNumber, referenceDate: referenceDate, exists: true]).get().asBoolean()
        if (isFileAlreadyImported) throw new RuntimeException("Arquivo já importado [fileName: ${fileName}, issuerControlNumber: ${issuerControlNumber}, referenceDate: ${referenceDate}].")
    }

    private void setConciliationStatus(DomicileInstitutionSettlementFile domicileInstitutionSettlementFile, List<DomicileInstitutionSettlementConciliationItem> settlementFileItemList) {
        Integer rejectedConciliationItemsCount = settlementFileItemList.count { it.occurrence.isRejected() }
        Boolean settlementDateIsLessOrEqualToday = domicileInstitutionSettlementFile.settlementDate.clone() <= new Date().clearTime()

        if (rejectedConciliationItemsCount == settlementFileItemList.size()) {
            domicileInstitutionSettlementFile.status = DomicileInstitutionSettlementFileStatus.REJECTED
        } else if (rejectedConciliationItemsCount > 0 && settlementDateIsLessOrEqualToday) {
                domicileInstitutionSettlementFile.status = DomicileInstitutionSettlementFileStatus.PARTIAL_SETTLEMENT_CONCILIATED
        } else {
            if (domicileInstitutionSettlementFile.type.isCredit() && !settlementDateIsLessOrEqualToday) {
                domicileInstitutionSettlementFile.status = DomicileInstitutionSettlementFileStatus.SCHEDULE_CONCILIATED
            } else {
                domicileInstitutionSettlementFile.status = DomicileInstitutionSettlementFileStatus.SETTLEMENT_CONCILIATED
            }
        }

        domicileInstitutionSettlementFile.save(failOnError: true)
    }

    private Boolean isValidCycle(DomicileInstitutionSettlementType settlementType, Boolean secondConciliation, Date receivedDate) {
        Date now = new Date()

        if (settlementType.isAnticipation()) {
            Date anticipationFirstCycleStart = CustomDateUtils.setTime(new Date(), 6, 40, 0)
            Date anticipationFirstCycleEnd = CustomDateUtils.setTime(new Date(), 16, 30, 0)
            Date anticipationSecondCycleStart = CustomDateUtils.setTime(new Date(), 16, 50, 0)
            Date anticipationSecondCycleEnd = CustomDateUtils.setTime(new Date(), 18, 30, 0)

            return ((now > anticipationFirstCycleStart && now < anticipationFirstCycleEnd) || (now > anticipationSecondCycleStart && now < anticipationSecondCycleEnd))
        }

        if (settlementType.isCredit()) {
            Date firstCreditCycleStart = CustomDateUtils.setTime(new Date(), 0, 30, 0)
            if (secondConciliation) firstCreditCycleStart = CustomDateUtils.setTime(new Date(), 8, 50, 0)
            Date firstCreditCycleEnd = CustomDateUtils.setTime(new Date(), 18, 30, 0)
            return (now > firstCreditCycleStart && now < firstCreditCycleEnd)
        }

        if (receivedDate && settlementType.isDebit()) {
            Date debitReceivedFirstCycleEnd = CustomDateUtils.setTime(new Date(), 05, 30, 0)
            if (receivedDate > debitReceivedFirstCycleEnd) return isSecondCycle()
        }

        return isFirstCycle() || isSecondCycle()
    }

    private Boolean isValidContingencyCycle(DomicileInstitutionSettlementType settlementType) {
        Date now = new Date()

        if (settlementType.isAnticipation()) {
            Date anticipationCycleStart = CustomDateUtils.setTime(new Date(), 8, 00, 0)
            Date anticipationCycleEnd = CustomDateUtils.setTime(new Date(), 15, 15, 0)
            return (now > anticipationCycleStart && now < anticipationCycleEnd)
        }

        Date firstCreditCycleStart = CustomDateUtils.setTime(new Date(), 0, 30, 0)
        Date firstCreditCycleEnd = CustomDateUtils.setTime(new Date(), 10, 0, 0)
        return (now > firstCreditCycleStart && now < firstCreditCycleEnd)
    }

    private Boolean isFirstCycle() {
        Date now = new Date()
        Date firstCycleStart = CustomDateUtils.setTime(new Date(), 8, 50, 0)
        Date firstCycleEnd = CustomDateUtils.setTime(new Date(), 10, 00, 0)
        return (now > firstCycleStart && now < firstCycleEnd)
    }

    private Boolean isSecondCycle() {
        Date now = new Date()
        Date defaultSecondCycleStart = CustomDateUtils.setTime(new Date(), 14, 10, 0)
        Date defaultSecondCycleEnd = CustomDateUtils.setTime(new Date(), 18, 30, 0)
        return (now > defaultSecondCycleStart && now < defaultSecondCycleEnd)
    }

    private Boolean isSecondConcilitation(DomicileInstitutionConciliationVO domicileInstitutionConciliation) {
        Date now = new Date().clearTime()

        if (domicileInstitutionConciliation.type.isCredit() && now.equals(domicileInstitutionConciliation.receivedDate)) return true

        return false
    }

    private Boolean isAutomaticProcessHourForNonAsaasDebtor() {
        final Integer hourLimit = 10
        final Integer minuteLimit = 5

        return new Date() > CustomDateUtils.setTime(new Date().clearTime(), hourLimit, minuteLimit, 0)
    }

    private Boolean settlementDateIsBeforeToday(Date settlementDate) {
        return settlementDate < new Date().clearTime()
    }
}
