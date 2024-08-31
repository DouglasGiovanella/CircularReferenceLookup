package com.asaas.service.atlasConfig

import com.asaas.domain.atlas.AtlasConfig
import com.asaas.exception.BusinessException
import com.asaas.utils.DomainUtils
import com.asaas.http.HttpRequestManager

import grails.validation.ValidationException
import grails.transaction.Transactional

import java.security.MessageDigest

@Transactional
class AtlasConfigService {

    def grailsApplication
    def atlasConfigCacheService

    public AtlasConfig saveIfNecessary(Map parsedParams, Boolean executeSave) {
        AtlasConfig atlasConfig = validateAtlasConfig(parsedParams)

        if (atlasConfig.hasErrors()) {
            throw new ValidationException(null, atlasConfig.errors)
        }

        atlasConfig.releaseVersion = parsedParams.releaseVersion
        atlasConfig.expandedJsKeyIntegrity = parsedParams.expandedJsKeyIntegrity
        atlasConfig.expandedCssKeyIntegrity = parsedParams.expandedCssKeyIntegrity
        atlasConfig.minifiedJsKeyIntegrity = parsedParams.minifiedJsKeyIntegrity
        atlasConfig.minifiedCssKeyIntegrity = parsedParams.minifiedCssKeyIntegrity
        atlasConfig.isCurrentRelease = false

        if (!executeSave) return atlasConfig

        return atlasConfig.save(failOnError: true)
    }

    public AtlasConfig updateCurrentRelease(Long id) {
        AtlasConfig lastActiveAtlas = AtlasConfig.query([isCurrentRelease: true]).get()

        if (lastActiveAtlas?.id == id) {
            throw new BusinessException("Essa versão já está definida no sistema")
        }

        if (lastActiveAtlas) {
            lastActiveAtlas.isCurrentRelease = false
            lastActiveAtlas.save(failOnError: true, flush: true)
        }

        AtlasConfig atlasConfig = AtlasConfig.query([id: id]).get()
        atlasConfig.isCurrentRelease = true
        atlasConfig.save(failOnError: true, flush: true)

        atlasConfigCacheService.evict()

        return atlasConfig
    }

    public AtlasConfig removeAtlasVersion(Long id) {
        AtlasConfig atlasConfig = AtlasConfig.query([id: id]).get()

        if (atlasConfig.isCurrentRelease) {
            throw new BusinessException("Não é possível remover uma versão que está definida como atual no sistema.")
        }

        atlasConfig.deleted = true
        atlasConfig.save(failOnError: true)

        return atlasConfig
    }

    public Map generateIntegrityKey(String releaseVersion) {
        Map atlasUrlList = [
            atlasCssUrl: grailsApplication.config.asaas.atlas.css.url.replace("{VERSION}", releaseVersion),
            atlasJsUrl: grailsApplication.config.asaas.atlas.js.url.replace("{VERSION}", releaseVersion),
            atlasMinCssUrl: grailsApplication.config.asaas.atlas.minified.css.url.replace("{VERSION}", releaseVersion),
            atlasMinJsUrl: grailsApplication.config.asaas.atlas.minified.js.url.replace("{VERSION}", releaseVersion)
        ]

        Map integrityList = [:]

        for (Map.Entry url : atlasUrlList) {
            HttpRequestManager httpRequestManager = new HttpRequestManager(url.value, [:], null)
            httpRequestManager.returnAsFile = true
            httpRequestManager.get()

            if (!httpRequestManager.success) {
                throw new IllegalArgumentException("Ao tentar gerar as chaves de integridades da versão ${ releaseVersion } do Atlas, a resposta da requisição retornou o erro: ${ httpRequestManager.responseHttpStatus }")
            }

            if (httpRequestManager.responseFile) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(httpRequestManager.responseFile.getBytes())
                integrityList[url.key] = "sha256-" + digest.encodeBase64().toString()
            }
        }

        return integrityList
    }

    private AtlasConfig validateAtlasConfig(Map parsedParams) {
        AtlasConfig atlasConfig = new AtlasConfig()

        if (!parsedParams.releaseVersion) {
            DomainUtils.addError(atlasConfig, "Não há versão da release!")
        }

        if (!parsedParams.expandedJsKeyIntegrity) {
            DomainUtils.addError(atlasConfig, "Não há chave de integridade para arquivo javascript!")
        }

        if (!parsedParams.expandedCssKeyIntegrity) {
            DomainUtils.addError(atlasConfig, "Não há chave de integridade para arquivo css!")
        }

        if (!parsedParams.minifiedJsKeyIntegrity) {
            DomainUtils.addError(atlasConfig, "Não há chave de integridade para arquivo minificado javascript!")
        }

        if (!parsedParams.minifiedCssKeyIntegrity) {
            DomainUtils.addError(atlasConfig, "Não há chave de integridade para arquivo minificado css!")
        }

        return atlasConfig
    }
}
