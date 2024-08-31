package com.asaas.service.stage

import grails.transaction.Transactional

import com.asaas.domain.stage.Stage
import com.asaas.domain.stage.StageEvent

@Transactional
class StageEventService {

    public List<StageEvent> listImmediateEventsForStage(Stage stage) {
		return StageEvent.executeQuery("from StageEvent s where s.stage = :stage and startDelayCycleInterval = 0 and active = true and deleted = false", [stage: stage])
    }

	public List<Long> listActiveStageEvents() {
		return StageEvent.executeQuery("select id from StageEvent s where (startDelayCycleInterval > 0 or startDelayCycleInterval is null) and active = true and deleted = false")
	}

	public List<StageEvent> listEventsThatHaveExpiration() {
		return StageEvent.executeQuery("from StageEvent s where createdEventExpirationCycle is not null and createdEventExpirationCycleInterval > 0 and deleted = false")
	}
}
