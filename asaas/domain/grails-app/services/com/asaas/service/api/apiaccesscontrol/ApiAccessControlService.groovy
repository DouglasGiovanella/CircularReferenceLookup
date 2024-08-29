package com.asaas.service.api.apiaccesscontrol

import com.asaas.api.apiaccesscontrol.adapter.ApiAccessControlSaveAdapter
import com.asaas.api.apiaccesscontrol.adapter.ApiAccessControlUpdateAdapter
import com.asaas.cache.api.ApiConfigCacheVO
import com.asaas.domain.apiaccesscontrol.ApiAccessControl

import com.asaas.apiaccesscontrol.ApiAccessControlIpType
import com.asaas.domain.api.ApiConfig
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.log.AsaasLogger
import com.asaas.service.api.ApiBaseService
import com.asaas.utils.IpUtils
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class ApiAccessControlService extends ApiBaseService {

    def apiConfigCacheService
    def apiConfigService
    def grailsCacheManager

    public ApiAccessControl save(Customer customer, ApiAccessControlSaveAdapter saveAdapter) {
        Long apiConfigId = apiConfigCacheService.getByCustomer(customer.id)?.id
        if (!apiConfigId) apiConfigId = apiConfigService.save(customer)?.id

        validateSave(apiConfigId, saveAdapter)

        ApiAccessControl apiAccessControl = new ApiAccessControl()
        apiAccessControl.apiConfig = ApiConfig.read(apiConfigId)
        apiAccessControl.ip = sanitizeIpByClass(saveAdapter.ip, saveAdapter.ipType)
        apiAccessControl.ipType = saveAdapter.ipType
        apiAccessControl.description = saveAdapter.description
        apiAccessControl.initialIpRange = saveAdapter.ip
        apiAccessControl.finalIpRange = buildFinalIpRange(saveAdapter.ip, saveAdapter.ipType)
        apiAccessControl.save(failOnError: true)

        evictCache(customer, apiAccessControl.ip)

        return apiAccessControl
    }

    public ApiAccessControl update(Customer customer, ApiAccessControlUpdateAdapter updateAdapter) {
        ApiAccessControl apiAccessControl = findByCustomer(customer, updateAdapter.id)
        validateUpdate(apiAccessControl, updateAdapter)

        apiAccessControl.ip = sanitizeIpByClass(updateAdapter.ip, updateAdapter.ipType)
        apiAccessControl.ipType = updateAdapter.ipType
        apiAccessControl.description = updateAdapter.description
        apiAccessControl.initialIpRange = updateAdapter.ip
        apiAccessControl.finalIpRange = buildFinalIpRange(updateAdapter.ip, updateAdapter.ipType)
        apiAccessControl.save(failOnError: true)

        evictCache(customer, apiAccessControl.ip)

        return apiAccessControl
    }

    public void delete(Customer customer, Long apiAccessControlId) {
        ApiAccessControl apiAccessControl = findByCustomer(customer, apiAccessControlId)
        apiAccessControl.deleted = true
        apiAccessControl.save(failOnError: true)

        evictCache(customer, apiAccessControl.ip)
    }

    public List<Map> list(Customer customer, Map search, Integer offset, Integer max) {
        final Map baseApiAccessControlQuery = search + [columnList: ["id", "ip", "ipType", "description"], disableSort: true]

        List<Map> allowedAccessControlList = null
        Long currentCustomerApiConfigId = apiConfigCacheService.getByCustomer(customer.id)?.id

        if (currentCustomerApiConfigId) {
            allowedAccessControlList = ApiAccessControl.query(baseApiAccessControlQuery + [apiConfigId: currentCustomerApiConfigId]).list(max: max, offset: offset)
        }

        return allowedAccessControlList
    }

    public List<Map> getAllowedAccessControlList(Customer customer) {
        final Map baseApiAccessControlQuery = [columnList: ["ip", "ipType", "initialIpRange", "finalIpRange"]]

        List<Map> allowedAccessControlList = []

        if (customer.accountOwnerId) {
            Long accountOwnerApiConfigId = apiConfigCacheService.getByCustomer(customer.accountOwnerId)?.id
            if (accountOwnerApiConfigId) {
                allowedAccessControlList.addAll(ApiAccessControl.query(baseApiAccessControlQuery + [apiConfigId: accountOwnerApiConfigId]).list())
            }
        }

        Long currentCustomerApiConfigId = apiConfigCacheService.getByCustomer(customer.id)?.id
        if (currentCustomerApiConfigId) {
            allowedAccessControlList.addAll(ApiAccessControl.query(baseApiAccessControlQuery + [apiConfigId: currentCustomerApiConfigId]).list())
        }

        return allowedAccessControlList
    }

    @Cacheable(value = "ApiAccessControl:isAllowedForCustomer", key = "(#customer.id + ':' + #ip).toString()")
    public Boolean isAllowedForCustomer(Customer customer, String ip) {
        List<Map> allowedAccessControlList = getAllowedAccessControlList(customer)

        if (!allowedAccessControlList) return true

        return isIpAllowedForIpRange(ip, allowedAccessControlList)
    }

    public Boolean hasCreatedIps(Customer customer) {
        Long customerApiConfigId = apiConfigCacheService.getByCustomer(customer.id)?.id

        if (customerApiConfigId) {
            return ApiAccessControl.query(["apiConfigId": customerApiConfigId, "exists": true]).get().asBoolean()
        }

        return false
    }

    public void validateSave(Long apiConfigId, ApiAccessControlSaveAdapter saveAdapter) {
        if (!IpUtils.isIpv4(saveAdapter.ip)) throw new BusinessException("O IP ${saveAdapter.ip} não é um IPV4 válido.")

        Boolean alreadyExistsIpConfiguration = ApiAccessControl.query([exists: true, apiConfigId: apiConfigId, ip: sanitizeIpByClass(saveAdapter.ip, saveAdapter.ipType)]).get()
        if (alreadyExistsIpConfiguration) throw new BusinessException("O IP ${saveAdapter.ip} já esta autorizado")
    }

    public void validateUpdate(ApiAccessControl apiAccessControl, ApiAccessControlUpdateAdapter updateAdapter) {
        if (!IpUtils.isIpv4(updateAdapter.ip)) throw new BusinessException("O IP ${updateAdapter.ip} não é um IPV4 válido.")

        Boolean alreadyExistsIpConfiguration = ApiAccessControl.query([exists: true, "id[ne]": apiAccessControl.id, apiConfigId: apiAccessControl.apiConfigId, ip: sanitizeIpByClass(updateAdapter.ip, updateAdapter.ipType)]).get()
        if (alreadyExistsIpConfiguration) throw new BusinessException("O IP ${updateAdapter.ip} já esta autorizado")
    }

    private String sanitizeIpByClass(String ip, ApiAccessControlIpType type) {
        if (type.isClassB()) return IpUtils.getClassB(ip)
        if (type.isClassC()) return IpUtils.getClassC(ip)
        return ip
    }

    private ApiAccessControl findByCustomer(Customer customer, Long apiAccessControlId) {
        ApiConfigCacheVO apiConfigCacheVO = apiConfigCacheService.getByCustomer(customer.id)
        ApiAccessControl apiAccessControl = ApiAccessControl.query([id: apiAccessControlId, apiConfigId: apiConfigCacheVO.id]).get()
        if (!apiAccessControl) throw new ResourceNotFoundException("Autorização de IP não foi encontrada para o cliente ${customer.id}. [${apiAccessControlId}]")

        return apiAccessControl
    }

    private void evictCache(Customer customer, String ip) {
        final String cacheKey = "${customer.id}:${ip}"
        grailsCacheManager.getCache("ApiAccessControl:isAllowedForCustomer").evict(cacheKey)
    }

    private String buildFinalIpRange(String ip, ApiAccessControlIpType ipType) {
        if (ipType.isFixed()) return ip

        final String defaultRange = "0"
        final String maxRange = "255"

        List<String> hexadecimals = ip.split("\\.")

        for (int i = 0; i < hexadecimals.size(); i++) {
            if (hexadecimals[i] == defaultRange) {
                hexadecimals[i] = maxRange
            }
        }

        return hexadecimals.join(".")
    }

    private Boolean isIpAllowedForIpRange(String ip, List<Map> allowedAccessControlList) {
        try {
            Long ipRequest = convertIpToLong(ip)

            for (Map map : allowedAccessControlList) {
                Long initialIpRange = convertIpToLong(map.initialIpRange.toString())
                Long finalIpRange = convertIpToLong(map.finalIpRange.toString())

                if (ipRequest >= initialIpRange && ipRequest <= finalIpRange) {
                    return true
                }
            }
            return false
        } catch (Exception ex) {
            AsaasLogger.error("ApiAccessControlService.isIpAllowedForIpRange >> Erro ao validar se o IP ${ip} está dentro do range: ${ex.message}")
            return false
        }
    }

    private Long convertIpToLong(String ipToConvert) {
        Long[] hexadecimals = ipToConvert.split("\\.").collect { it.toLong() }
        Long ip = 0

        ip += hexadecimals[0] * Math.pow(256, 3)
        ip += hexadecimals[1] * Math.pow(256, 2)
        ip += hexadecimals[2] * 256
        ip += hexadecimals[3]

        return ip
    }
}
