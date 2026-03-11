package com.platform.api;

import com.platform.llm.LLMAdapterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/models")
@Tag(name = "Models", description = "사용 가능한 LLM 모델 목록")
public class ModelController {

    private final LLMAdapterRegistry adapterRegistry;

    public ModelController(LLMAdapterRegistry adapterRegistry) {
        this.adapterRegistry = adapterRegistry;
    }

    @GetMapping
    @Operation(summary = "지원 모델 목록 조회")
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> models = new ArrayList<>();

        for (var adapter : adapterRegistry.getAllAdapters()) {
            String provider = adapter.getProvider();
            for (String model : adapter.getSupportedModels()) {
                models.add(Map.of(
                        "id", provider + "/" + model,
                        "provider", provider,
                        "model", model
                ));
            }
        }

        return ApiResponse.ok(models);
    }
}
