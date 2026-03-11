package com.platform.api;

import com.platform.extension.ExtensionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/extensions")
@Tag(name = "Extensions", description = "플랫폼 Extension 관리")
public class ExtensionController {

    private final ExtensionRegistry extensionRegistry;

    public ExtensionController(ExtensionRegistry extensionRegistry) {
        this.extensionRegistry = extensionRegistry;
    }

    @GetMapping
    @Operation(summary = "로드된 Extension 목록 조회",
               description = "현재 플랫폼에 로드된 모든 Extension과 각 Extension이 제공하는 Tool 목록을 반환합니다.")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(extensionRegistry.listExtensions());
    }
}
