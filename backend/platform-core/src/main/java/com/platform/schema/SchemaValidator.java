package com.platform.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidator.class);

    private final ObjectMapper objectMapper;
    // networknt 2.0.0: JsonSchemaFactory → SchemaRegistry, SpecVersion.VersionFlag.V7 → SpecificationVersion.DRAFT_7
    private final SchemaRegistry schemaRegistry;

    public SchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
    }

    public ValidationResult validate(Map<String, Object> jsonSchema, Map<String, Object> data) {
        try {
            JsonNode schemaNode = objectMapper.valueToTree(jsonSchema);
            JsonNode dataNode = objectMapper.valueToTree(data);

            // networknt 2.0.0: getSchema(JsonNode) → Schema, validate(JsonNode) → List<Error>
            Schema schema = schemaRegistry.getSchema(schemaNode);
            List<Error> errors = schema.validate(dataNode);

            if (errors.isEmpty()) {
                return ValidationResult.success();
            }
            List<String> messages = errors.stream()
                    .map(Error::getMessage)
                    .toList();
            return ValidationResult.failure(messages);
        } catch (Exception e) {
            log.error("Schema validation error", e);
            return ValidationResult.failure("Validation error: " + e.getMessage());
        }
    }

    public record ValidationResult(boolean valid, List<String> errors) {

        public static ValidationResult success() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult failure(String error) {
            return new ValidationResult(false, List.of(error));
        }

        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }
}
