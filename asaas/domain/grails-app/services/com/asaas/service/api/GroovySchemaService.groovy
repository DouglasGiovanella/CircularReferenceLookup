package com.asaas.service.api

import com.asaas.schema.AsaasSchemaValidator

class GroovySchemaService {

    public List<Map> validate(instance, schema) {
        AsaasSchemaValidator validator = new AsaasSchemaValidator()
        return validator.validate(instance, schema)
    }
}
