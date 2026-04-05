package com.platform.api;

import com.platform.domain.ToolExecutionLogEntity;
import com.platform.repository.ToolExecutionLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * CR-029: 도구 실행 이력 API.
 */
@RestController
@RequestMapping("/api/v1/tool-executions")
@Tag(name = "Tool Execution Log", description = "도구 실행 이력 (CR-029)")
public class ToolExecutionLogController {

    private final ToolExecutionLogRepository repository;

    public ToolExecutionLogController(ToolExecutionLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "도구 실행 이력 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(required = false) String session_id,
            @RequestParam(required = false) String workflow_run_id,
            @RequestParam(required = false) String tool_name,
            Pageable pageable) {

        Page<ToolExecutionLogEntity> page;
        if (session_id != null) {
            page = repository.findBySessionId(session_id, pageable);
        } else if (tool_name != null) {
            page = repository.findByToolName(tool_name, pageable);
        } else {
            page = repository.findAll(pageable);
        }
        return ApiResponse.page(page);
    }

    @GetMapping("/{id}")
    @Operation(summary = "도구 실행 이력 상세 조회")
    public ApiResponse<ToolExecutionLogEntity> get(@PathVariable UUID id) {
        return repository.findById(id)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.error("실행 이력을 찾을 수 없습니다: " + id));
    }
}
