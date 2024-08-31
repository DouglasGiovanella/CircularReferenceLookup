package com.asaas.service.segmentio

import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.mobileappidentifier.MobileAppIdentifier
import datadog.trace.api.Trace
import org.springframework.web.context.request.RequestContextHolder

import static grails.async.Promises.task

import grails.transaction.Transactional

@Transactional
class AsaasSegmentioService {

    public static final RESERVED_NAMES = [
                                            "alias",
                                            "identify",
                                            "pages",
                                            "table_sync_index",
                                            "td_alias_compressed",
                                            "td_identifies_compressed",
                                            "td_pages_compressed",
                                            "td_tracks_compressed",
                                            "track",
                                            "track_event_table_index",
                                            "raw_event",
                                            "raw_alias_compressed",
                                            "raw_identifies_compressed",
                                            "raw_screens_compressed",
                                            "raw_pages_compressed",
                                            "raw_tracks_compressed"
                                        ]

    def segmentioService

    @Trace(resourceName = "AsaasSegmentioService.track")
    def track(id, String event, Map data) {
        if (AsaasSegmentioService.RESERVED_NAMES.contains(event)) {
            if (AsaasEnvironment.isProduction()) {
                AsaasLogger.error("AsaasSegmentioService.track -> Track sendo gerado com palavra reservada - event[${event}], id[${id}], data[${data}]")
                return
            }

            throw new RuntimeException("Não é possível gerar track com a palavra reservada [${event}]")
        }

        try {
            data.isMobile = MobileAppIdentifier.isMobileApp(RequestContextHolder?.requestAttributes?.params?.origin)
            Thread.start {
                try {
                    segmentioService.track(id, event, data)
                } catch (Exception e) {
                    AsaasLogger.error("Ocorreu um erro no método 'track' do segmentioService. id[${id}], event[${event}], data[${data}]", e)
                }
            }
        } catch (Exception e) {
            AsaasLogger.error("Ocorreu um erro no método 'track' do AsaasSegmentioService. id[${id}], event[${event}], data[${data}]", e)
        }
    }

    def identify(id, Map data) {
        Thread.start {
            try {
                segmentioService.identify(id, data)
            } catch (Exception e) {
                AsaasLogger.error("Ocorreu um erro no método 'identify' do segmentioService. id[${id}], data[${data}]", e)
            }
        }
    }

    public void alias(String from, String to) {
        task {
            try {
                segmentioService.alias(from, to)
            } catch (Exception e) {
                AsaasLogger.error("AsaasSegmentioService.alias - from[${from}], to[${to}]", e)
            }
        }
    }
}
