package com.asaas.service.urlshortener

import com.asaas.domain.urlshortener.UrlShortener
import grails.transaction.Transactional
import groovy.json.JsonOutput
import org.apache.commons.lang.RandomStringUtils

@Transactional
class UrlShortenerService {

	def grailsApplication
	def grailsLinkGenerator

    public String createShortenedUrl(String controller, String action, String resourceId) {
        return createShortenedUrl(controller, action, resourceId, null, false)
    }

    public String createShortenedUrl(String controller, String action, String resourceId, Map params, Boolean absolute) {
        String paramsJson = params ? JsonOutput.toJson(params).toString() : null

        UrlShortener urlShortener = save(controller, action, resourceId, paramsJson)

        if (absolute) {
            return grailsLinkGenerator.link(controller: 'urlShortener', action: 'index', id: urlShortener.token, absolute: true)
        }

        return grailsLinkGenerator.link(controller: 'urlShortener', action: 'index', id: urlShortener.token, base: grailsApplication.config.asaas.app.shortenedUrl)
    }

    private UrlShortener save(String controller, String action, String resourceId, String params) {
        UrlShortener urlShortener = UrlShortener.query([controller: controller, action: action, resourceId: resourceId]).get()
        if (urlShortener) return urlShortener

        urlShortener = new UrlShortener()
        urlShortener.controller = controller
        urlShortener.action = action
        urlShortener.resourceId = resourceId
        urlShortener.params = params
        urlShortener.token = buildToken()

        urlShortener.save(failOnError: true)
        return urlShortener
    }

	private String buildToken() {
		String token = RandomStringUtils.randomAlphanumeric(6).toLowerCase()

		if (tokenAlreadyExists(token)) {
			return buildToken()
		} else {
			return token
		}
	}

	private Boolean tokenAlreadyExists(String token) {
		return UrlShortener.query([token: token, column: "id"]).get().asBoolean()
	}
}
