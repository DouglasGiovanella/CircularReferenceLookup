package com.asaas.service.api.openapi

import com.asaas.openapi.enums.OpenApiLanguageCode
import io.swagger.v3.oas.integration.GenericOpenApiContext
import io.swagger.v3.oas.integration.SwaggerConfiguration
import io.swagger.v3.oas.integration.api.OpenAPIConfiguration
import io.swagger.v3.oas.integration.api.OpenApiContext
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.codehaus.groovy.grails.commons.GrailsApplication
import com.asaas.openapi.OpenApiGrailsScanner
import com.asaas.openapi.OpenApiReader
import grails.transaction.Transactional

@Transactional
class OpenApiService {

    GrailsApplication grailsApplication

    OpenAPI generateDocument(String namespace, OpenApiLanguageCode languageCode) {
        OpenAPIConfiguration config = new SwaggerConfiguration().openAPI(buildConfig(namespace))
        config.setReaderClass("com.asaas.openapi.OpenApiDocReader")
        OpenApiContext openApiContext = new GenericOpenApiContext().openApiConfiguration(config)
        openApiContext.setOpenApiScanner(new OpenApiGrailsScanner(grailsApplication: grailsApplication, namespace: namespace))
        openApiContext.setOpenApiReader(new OpenApiReader(application: grailsApplication, config: config, languageCode: languageCode))
        openApiContext.init()

        return openApiContext.read()
    }

    OpenAPI buildConfig(String namespace) {
        Map config = grailsApplication.config.openApi.doc."${namespace}"

        Info info = new Info().title(config.info.title).description(config.info.description).version(config.info.version)
        OpenAPI openAPI = new OpenAPI()
        openAPI.info(info)

        List<Map> serverConfig = config.servers
        if (serverConfig) {
            List<Server> servers = serverConfig.collect { serverMap -> new Server().url(serverMap?.url ?: null).description(serverMap?.description ?: null) }
            openAPI.servers(servers)
        }

        ConfigObject securitySchemes = config.components?.securitySchemes
        securitySchemes?.each { name, map ->
            if (!openAPI.components) {
                openAPI.components(new Components())
            }

            SecurityScheme secScheme = new SecurityScheme(map)
            openAPI.components.addSecuritySchemes(name, secScheme)
        }

        ConfigObject globalSecurity = config.security
        globalSecurity?.each { name, map ->
            if (!openAPI.security) {
                openAPI.security([])
            }

            openAPI.security.add(new SecurityRequirement().addList(name, map ?: []))
        }

        return openAPI
    }
}
