package com.platform.tool.builtin;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.tool.lsp.LSPClientManager;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-039 PRD-268: Language Server Protocol 클라이언트 도구.
 *
 * 핵심 3개 기능: definition, references, hover.
 * BIZ-076: LS 프로세스 5분 미사용 자동 종료.
 * BIZ-077: 초기 지원 언어 3개 (java, typescript, python).
 */
@Component
public class LSPTool implements EnhancedToolExecutor {

    private final LSPClientManager lspClientManager;

    public LSPTool(LSPClientManager lspClientManager) {
        this.lspClientManager = lspClientManager;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "lsp",
                "Query Language Server Protocol for code intelligence. "
                        + "Supports: definition (go to definition), references (find all references), "
                        + "hover (type info). Languages: java, typescript, python. "
                        + "The language server is started lazily on first use and auto-stopped after 5min idle.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "action", Map.of("type", "string",
                                        "enum", List.of("definition", "references", "hover", "status"),
                                        "description", "LSP action to perform"),
                                "language", Map.of("type", "string",
                                        "enum", List.of("java", "typescript", "python"),
                                        "description", "Programming language (required for definition/references/hover)"),
                                "file_path", Map.of("type", "string",
                                        "description", "Absolute path to the source file"),
                                "line", Map.of("type", "integer",
                                        "description", "Zero-based line number"),
                                "character", Map.of("type", "integer",
                                        "description", "Zero-based character offset")
                        ),
                        "required", List.of("action")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("lsp",
                List.of("lsp", "code-intelligence", "definition", "references", "hover"));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        String action = (String) input.get("action");
        if (action == null || action.isBlank()) {
            return ValidationResult.fail("'action' is required");
        }
        if ("status".equals(action)) {
            return ValidationResult.OK;
        }
        // definition/references/hover 공통 필수 필드
        if (input.get("language") == null) {
            return ValidationResult.fail("'language' is required for " + action);
        }
        if (input.get("file_path") == null) {
            return ValidationResult.fail("'file_path' is required for " + action);
        }
        if (input.get("line") == null) {
            return ValidationResult.fail("'line' is required for " + action);
        }
        if (input.get("character") == null) {
            return ValidationResult.fail("'character' is required for " + action);
        }
        return ValidationResult.OK;
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String action = (String) input.get("action");

        if ("status".equals(action)) {
            return handleStatus();
        }

        String language = (String) input.get("language");
        String filePath = (String) input.get("file_path");
        int line = toInt(input.get("line"));
        int character = toInt(input.get("character"));

        try {
            Map<String, Object> result = switch (action) {
                case "definition" -> lspClientManager.definition(language, filePath, line, character);
                case "references" -> lspClientManager.references(language, filePath, line, character);
                case "hover" -> lspClientManager.hover(language, filePath, line, character);
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            };

            return ToolResult.ok(result, formatSummary(action, filePath, line, character, result));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("LSP " + action + " failed: " + e.getMessage());
        }
    }

    private ToolResult handleStatus() {
        var activeStatus = lspClientManager.getActiveStatus();
        var supported = lspClientManager.getSupportedLanguages();
        Map<String, Object> result = Map.of(
                "supported_languages", supported,
                "active_servers", activeStatus
        );
        return ToolResult.ok(result,
                "LSP status: " + activeStatus.size() + " active servers, "
                        + "supported languages: " + supported);
    }

    private String formatSummary(String action, String filePath, int line, int character,
                                 Map<String, Object> result) {
        String location = filePath + ":" + (line + 1) + ":" + (character + 1);
        return switch (action) {
            case "definition" -> {
                Object res = result.get("result");
                if (res == null) yield "No definition found at " + location;
                yield "Definition found for " + location;
            }
            case "references" -> {
                Object res = result.get("result");
                int count = res instanceof List<?> l ? l.size() : 0;
                yield count + " reference(s) found for " + location;
            }
            case "hover" -> {
                Object contents = result.get("contents");
                if (contents == null) yield "No hover info at " + location;
                yield "Hover info at " + location;
            }
            default -> action + " result at " + location;
        };
    }

    private int toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) return Integer.parseInt(s);
        return 0;
    }
}
