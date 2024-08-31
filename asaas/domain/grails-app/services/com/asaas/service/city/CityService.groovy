package com.asaas.service.city

import com.asaas.domain.city.City
import com.asaas.state.State
import grails.transaction.Transactional

@Transactional
class CityService {

	def find(params) {
		return City.search(params)
	}

	def findByDistrictAndState(name, state) {
		if (!state) return []

		return City.executeQuery("from City where district = :name and state = :state", [name: name, state: state as State])
	}

	def findByDistrict(String name) {
		return City.executeQuery("from City where district = :name", [name: name])
	}

	def findByDistrictAndName(String name) {
		return City.executeQuery("from City where district = :name and name = :name", [name: name])
	}

	def findByDistrictLike(name) {
		return City.executeQuery("from City where district like :name", [name: name])
	}

	def list(max, offset, search) {
		def list = City.createCriteria().list(max: max, offset: offset) {
			and { eq("deleted", false) }

			if (search?.name) {
				or {
					like("name", "" + search.name + "%")
					like("district", "" + search.name + "%")
				}
			}

			if (search?.state) {
				and { eq("state", search.state instanceof State ?: State.valueOf(search.state)) }
			}

			if (search?.ibgeCode) {
				and { eq("ibgeCode", search.ibgeCode) }
			}

			if (search?.sort) {
				order(search?.sort, search?.order ?: "asc")
			} else {
				order("districtCode", "asc")
				order("name", "asc")
			}
		}

		return list
	}

    City importCity(String ibgeCode, String cityName, String stateName) {
        final String defaultDistinctCode = '00'
        final Integer defaultCorreiosEstimatedDeliveryDays = 20

        City city = new City()
        city.ibgeCode = ibgeCode
        city.name = cityName
        city.district = cityName
        city.districtCode = defaultDistinctCode
        city.state = State.findByDescription(stateName)
        city.correiosEstimatedDeliveryDays = defaultCorreiosEstimatedDeliveryDays
        city.save(flush: true, failOnError: true)

        return city
    }
}
