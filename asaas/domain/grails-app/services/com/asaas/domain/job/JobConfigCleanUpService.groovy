package com.asaas.domain.job

import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.JobUtils
import grails.transaction.Transactional

@Transactional
class JobConfigCleanUpService {

    def jobConfigService

    public void deleteJobsRemovedFromCodebase() {
        final Integer limitOfDaysBeforeDeleting = 3

        List<String> jobsOnSchedulerList = JobUtils.listJobNamesOnScheduler()
        List<String> jobsOnDatabaseButNotOnScheduler = JobConfig.query([
            column: "job",
            "job[notIn]": jobsOnSchedulerList,
            "dateCreated[lt][orIsNull]": CustomDateUtils.sumDays(new Date(), limitOfDaysBeforeDeleting * -1)
        ]).list()
        if (!jobsOnDatabaseButNotOnScheduler) return

        Integer numberOfJobsToDelete = jobsOnDatabaseButNotOnScheduler.size()
        Integer deletedRows = jobConfigService.deleteAllByJobName(jobsOnDatabaseButNotOnScheduler)
        if (deletedRows != numberOfJobsToDelete) {
            AsaasLogger.warn("JobConfigCleanUpService.deleteJobsRemovedFromCodebase >> Jobs deletadas [${deletedRows}] difere da quantidade esperada [${numberOfJobsToDelete}]")
        }

        AsaasLogger.warn("JobConfigCleanUpService.deleteJobsRemovedFromCodebase >> Removidas ${deletedRows} linhas da JobConfig.\nJobs: [${jobsOnDatabaseButNotOnScheduler.join(", ")}]")
    }

    public void checkJobsStoppedForLong() {
        final Integer limitOfWeeksForStoppedJobs = 3
        final Integer limitOfDaysForStoppedJobs = 7 * limitOfWeeksForStoppedJobs

        Date limitDate = CustomDateUtils.sumDays(new Date(), limitOfDaysForStoppedJobs * -1)
        List<String> jobNameList = JobConfig.query([column: "job", stopped: true, "finishDate[le]": limitDate]).list()
        if (!jobNameList) return

        for (String jobName : jobNameList) {
            AsaasLogger.warn("JobCleanUpService.checkJobsStoppedForLong >> Job [${jobName}] está pausada há mais de ${limitOfWeeksForStoppedJobs} semanas. Verifique se é possível removê-la ou solicite ao DNA para incluí-la na lista de jobs ignoradas")
        }
    }
}
