package com.platform.schema;

import com.platform.domain.SchemaEntity;
import com.platform.domain.SchemaEntityId;
import com.platform.repository.SchemaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public class SchemaRegistry {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistry.class);

    private final SchemaRepository schemaRepository;
    private final SchemaValidator schemaValidator;

    public SchemaRegistry(SchemaRepository schemaRepository, SchemaValidator schemaValidator) {
        this.schemaRepository = schemaRepository;
        this.schemaValidator = schemaValidator;
    }

    @Cacheable(value = "schemas", key = "#id + ':' + #version")
    public Optional<Map<String, Object>> getSchema(String id, int version) {
        return schemaRepository.findById(new SchemaEntityId(id, version))
                .map(SchemaEntity::getJsonSchema);
    }

    @Cacheable(value = "schemas", key = "#id + ':latest'")
    public Optional<Map<String, Object>> getLatestSchema(String id) {
        return schemaRepository.findLatestById(id)
                .map(SchemaEntity::getJsonSchema);
    }

    public SchemaValidator.ValidationResult validate(String schemaId, Map<String, Object> data) {
        Optional<Map<String, Object>> schema = getLatestSchema(schemaId);
        if (schema.isEmpty()) {
            return SchemaValidator.ValidationResult.failure("Schema not found: " + schemaId);
        }
        return schemaValidator.validate(schema.get(), data);
    }
}
