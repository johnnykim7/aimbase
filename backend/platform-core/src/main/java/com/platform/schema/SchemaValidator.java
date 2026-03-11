package com.platform.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidator.class);

    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory;

    public SchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    }

    public ValidationResult validate(Map<String, Object> jsonSchema, Map<String, Object> data) {
        try {
            JsonNode schemaNode = objectMapper.valueToTree(jsonSchema);
            JsonNode dataNode = objectMapper.valueToTree(data);

            JsonSchema schema = schemaFactory.getSchema(schemaNode);
            Set<ValidationMessage> errors = schema.validate(dataNode);

            if (errors.isEmpty()) {
                return ValidationResult.success();
            }
            List<String> messages = errors.stream()
                    .map(ValidationMessage::getMessage)
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
