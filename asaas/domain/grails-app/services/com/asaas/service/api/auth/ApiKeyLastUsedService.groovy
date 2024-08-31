package com.asaas.service.api.auth

import com.asaas.api.auth.ApiAuthResultVO
import com.asaas.domain.api.ApiConfig
import com.asaas.domain.api.UserApiKey
import com.asaas.domain.apiconfig.ApiConfigLastUsedDateSaveAsyncAction
import com.asaas.domain.userapikey.UserApiKeyLastUsedDateSaveAsyncAction
import com.asaas.log.AsaasLogger
import com.asaas.redis.RedissonProxy
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.redisson.api.RBucket

import java.util.concurrent.TimeUnit

@Transactional
class ApiKeyLastUsedService {

    def baseAsyncActionService

    public void save(ApiAuthResultVO apiAuthResultVO) {
        try {
            Date lastUsedDate = new Date()
            Boolean shouldSaveApiKeyLastUsedDate = shouldSaveApiKeyLastUsedDate(apiAuthResultVO, lastUsedDate)

            if (!shouldSaveApiKeyLastUsedDate) return

            Map asyncActionData = [lastUsedDate: CustomDateUtils.fromDate(lastUsedDate, CustomDateUtils.DATABASE_DATETIME_FORMAT)]

            if (apiAuthResultVO.userApiKey) {
                asyncActionData.put("apiKeyId", apiAuthResultVO.userApiKey.id.toString())
                baseAsyncActionService.save(new UserApiKeyLastUsedDateSaveAsyncAction(), asyncActionData)
            } else {
                asyncActionData.put("apiKeyId", apiAuthResultVO.apiConfigCacheVO.id.toString())
                baseAsyncActionService.save(new ApiConfigLastUsedDateSaveAsyncAction(), asyncActionData)
            }
        } catch (Exception exception) {
            AsaasLogger.warn("ApiKeyLastUsedService.save >> Erro ao salvar data de ultimo uso da chave da API", exception)
        }
    }

    public void processUserApiKeyLastUsedDate() {
        final Integer maxItemsPerExecution = 500
        final Integer maxItemsPerThread = 50
        final Integer flushEvery = 100

        List<Map> items = baseAsyncActionService.listPendingData(UserApiKeyLastUsedDateSaveAsyncAction, [:], maxItemsPerExecution)

        ThreadUtils.processWithThreadsOnDemand(items, maxItemsPerThread, { List<Map> subItemsList ->
            Utils.forEachWithFlushNewSession(subItemsList, flushEvery, { Map item ->
                Utils.withNewTransactionAndRollbackOnError({
                    Long apiKeyId = Utils.toLong(item."apiKeyId")
                    Date lastUsedDate = CustomDateUtils.getDateFromStringWithDateParse(item."lastUsedDate")
                    Long asyncActionId = Utils.toLong(item."asyncActionId")

                    UserApiKey.executeUpdate("UPDATE UserApiKey SET lastUsed = :lastUsedDate, lastUpdated = :lastUpdated WHERE id = :apiKeyId"
                        , [lastUsedDate: lastUsedDate, lastUpdated: new Date(), apiKeyId: apiKeyId])

                    UserApiKeyLastUsedDateSaveAsyncAction.executeUpdate("DELETE FROM UserApiKeyLastUsedDateSaveAsyncAction WHERE id = :asyncActionId"
                        , [asyncActionId: asyncActionId])
                }, [logErrorMessage: "ApiKeyLastUsedService.processUserApiKeyLastUsedDate >> Erro ao processar atualização da coluna last_used para UserApiKey ${subItemsList}"])
            })
        })
    }

    public void processApiConfigLastUsedDate() {
        final Integer maxItemsPerExecution = 500
        final Integer maxItemsPerThread = 50
        final Integer flushEvery = 100

        List<Map> items = baseAsyncActionService.listPendingData(ApiConfigLastUsedDateSaveAsyncAction, [:], maxItemsPerExecution)

        ThreadUtils.processWithThreadsOnDemand(items, maxItemsPerThread, { List<Map> subItemsList ->
            Utils.forEachWithFlushNewSession(subItemsList, flushEvery, { Map item ->
                Utils.withNewTransactionAndRollbackOnError({
                    Long apiKeyId = Utils.toLong(item."apiKeyId")
                    Date lastUsedDate = CustomDateUtils.getDateFromStringWithDateParse(item."lastUsedDate")
                    Long asyncActionId = Utils.toLong(item."asyncActionId")

                    ApiConfig.executeUpdate("UPDATE ApiConfig SET lastUsed = :lastUsedDate, lastUpdated = :lastUpdated WHERE id = :apiKeyId"
                        , [lastUsedDate: lastUsedDate, lastUpdated: new Date(), apiKeyId: apiKeyId])

                    ApiConfigLastUsedDateSaveAsyncAction.executeUpdate("DELETE FROM ApiConfigLastUsedDateSaveAsyncAction WHERE id = :asyncActionId"
                        , [asyncActionId: asyncActionId])
                }, [logErrorMessage: "ApiKeyLastUsedService.processApiConfigLastUsedDate >> Erro ao processar atualização da coluna last_used para ApiConfig ${subItemsList}"])
            })
        })
    }

    private Boolean shouldSaveApiKeyLastUsedDate(ApiAuthResultVO apiAuthResultVO, Date lastUsedDate) {
        try {
            RedissonProxy redissonProxy = RedissonProxy.instance
            if (!redissonProxy.isConnected()) return false

            String key = buildKeyToCache(apiAuthResultVO)
            RBucket<Date> bucket = redissonProxy.getBucket(key, Date)
            return bucket.trySet(lastUsedDate, 3, TimeUnit.HOURS)
        } catch (Exception exception) {
            AsaasLogger.warn("ApiKeyLastUsedService.shouldSaveApiKeyLastUsedDate >> Erro ao persistir ultima data de uso da chave", exception)
            return false
        }
    }

    private String buildKeyToCache(ApiAuthResultVO apiAuthResultVO) {
        if (apiAuthResultVO.userApiKey) {
            return "userApiKey:${apiAuthResultVO.userApiKey.id}:lastUsed"
        }

        return "apiConfig:${apiAuthResultVO.apiConfigCacheVO.id}:lastUsed"
    }
}
