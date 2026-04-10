package com.platform.tool.builtin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * CR-039 PRD-267: Jupyter Notebook(.ipynb) 셀 편집 도구.
 *
 * .ipynb는 JSON 구조이므로 Java 직접 편집 (Python sidecar 불필요).
 * nbformat v4 호환. 5가지 작업: add_cell, edit_cell, delete_cell, move_cell, read_cell.
 * BIZ-075: 최대 노트북 크기 10MB.
 */
@Component
public class NotebookEditTool implements EnhancedToolExecutor {

    private static final long MAX_NOTEBOOK_SIZE = 10 * 1024 * 1024; // 10MB
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "notebook_edit",
                "Edit Jupyter Notebook (.ipynb) files. Supports adding, editing, deleting, moving, "
                        + "and reading cells. Operates directly on the nbformat v4 JSON structure. "
                        + "Max notebook size: 10MB.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string",
                                        "description", "Absolute path to the .ipynb file"),
                                "action", Map.of("type", "string",
                                        "enum", List.of("add_cell", "edit_cell", "delete_cell", "move_cell", "read_cell"),
                                        "description", "Operation to perform"),
                                "cell_type", Map.of("type", "string",
                                        "enum", List.of("code", "markdown", "raw"),
                                        "description", "Cell type (for add_cell, default: code)"),
                                "cell_index", Map.of("type", "integer",
                                        "description", "Zero-based cell index to operate on"),
                                "source", Map.of("type", "string",
                                        "description", "Cell source content (for add_cell and edit_cell)"),
                                "target_index", Map.of("type", "integer",
                                        "description", "Target position (for move_cell)"),
                                "clear_outputs", Map.of("type", "boolean",
                                        "description", "Clear cell outputs when editing (default: false)")
                        ),
                        "required", List.of("file_path", "action")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "notebook_edit", "1.0",
                ToolScope.NATIVE, PermissionLevel.RESTRICTED_WRITE,
                false, false, false, true,
                RetryPolicy.NONE,
                List.of("notebook", "jupyter", "ipynb", "data"),
                List.of("read", "write")
        );
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        String filePath = (String) input.get("file_path");
        if (filePath == null || filePath.isBlank()) {
            return ValidationResult.fail("'file_path' is required");
        }
        if (!filePath.endsWith(".ipynb")) {
            return ValidationResult.fail("File must be a .ipynb file");
        }
        String action = (String) input.get("action");
        if (action == null || action.isBlank()) {
            return ValidationResult.fail("'action' is required");
        }
        return ValidationResult.OK;
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String filePath = (String) input.get("file_path");
        String action = (String) input.get("action");
        Path path = Path.of(filePath);

        try {
            return switch (action) {
                case "read_cell" -> readCell(path, input);
                case "add_cell" -> addCell(path, input);
                case "edit_cell" -> editCell(path, input);
                case "delete_cell" -> deleteCell(path, input);
                case "move_cell" -> moveCell(path, input);
                default -> ToolResult.error("Unknown action: " + action);
            };
        } catch (IOException e) {
            return ToolResult.error("IO error: " + e.getMessage());
        }
    }

    // ── read_cell ──

    private ToolResult readCell(Path path, Map<String, Object> input) throws IOException {
        if (!Files.exists(path)) {
            return ToolResult.error("File not found: " + path);
        }
        Map<String, Object> notebook = readNotebook(path);
        List<Map<String, Object>> cells = getCells(notebook);

        Integer cellIndex = getInt(input, "cell_index");
        if (cellIndex != null) {
            if (cellIndex < 0 || cellIndex >= cells.size()) {
                return ToolResult.error("Cell index out of range: " + cellIndex + " (total: " + cells.size() + ")");
            }
            Map<String, Object> cell = cells.get(cellIndex);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", cellIndex);
            result.put("cell_type", cell.get("cell_type"));
            result.put("source", joinSource(cell.get("source")));
            result.put("total_cells", cells.size());
            return ToolResult.ok(result, "Cell " + cellIndex + " (" + cell.get("cell_type") + "): " + truncate(joinSource(cell.get("source")), 200));
        }

        // 전체 노트북 요약
        List<Map<String, Object>> summary = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            Map<String, Object> cell = cells.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", i);
            entry.put("cell_type", cell.get("cell_type"));
            entry.put("source_preview", truncate(joinSource(cell.get("source")), 100));
            summary.add(entry);
        }
        Map<String, Object> result = Map.of("total_cells", cells.size(), "cells", summary);
        return ToolResult.ok(result, "Notebook has " + cells.size() + " cells");
    }

    // ── add_cell ──

    private ToolResult addCell(Path path, Map<String, Object> input) throws IOException {
        Map<String, Object> notebook;
        if (Files.exists(path)) {
            checkSize(path);
            notebook = readNotebook(path);
        } else {
            notebook = createEmptyNotebook();
        }
        List<Map<String, Object>> cells = getCells(notebook);

        String cellType = (String) input.getOrDefault("cell_type", "code");
        String source = (String) input.getOrDefault("source", "");
        Integer cellIndex = getInt(input, "cell_index");

        Map<String, Object> newCell = createCell(cellType, source);

        if (cellIndex != null && cellIndex >= 0 && cellIndex <= cells.size()) {
            cells.add(cellIndex, newCell);
        } else {
            cells.add(newCell);
            cellIndex = cells.size() - 1;
        }

        writeNotebook(path, notebook);
        return ToolResult.ok(
                Map.of("index", cellIndex, "cell_type", cellType, "total_cells", cells.size()),
                "Added " + cellType + " cell at index " + cellIndex + " (total: " + cells.size() + ")"
        );
    }

    // ── edit_cell ──

    private ToolResult editCell(Path path, Map<String, Object> input) throws IOException {
        if (!Files.exists(path)) {
            return ToolResult.error("File not found: " + path);
        }
        checkSize(path);
        Map<String, Object> notebook = readNotebook(path);
        List<Map<String, Object>> cells = getCells(notebook);

        Integer cellIndex = getInt(input, "cell_index");
        if (cellIndex == null) {
            return ToolResult.error("'cell_index' is required for edit_cell");
        }
        if (cellIndex < 0 || cellIndex >= cells.size()) {
            return ToolResult.error("Cell index out of range: " + cellIndex + " (total: " + cells.size() + ")");
        }

        String source = (String) input.get("source");
        if (source == null) {
            return ToolResult.error("'source' is required for edit_cell");
        }

        Map<String, Object> cell = cells.get(cellIndex);
        cell.put("source", sourceToList(source));

        boolean clearOutputs = Boolean.TRUE.equals(input.get("clear_outputs"));
        if (clearOutputs && cell.containsKey("outputs")) {
            cell.put("outputs", List.of());
            cell.put("execution_count", null);
        }

        writeNotebook(path, notebook);
        return ToolResult.ok(
                Map.of("index", cellIndex, "cell_type", cell.get("cell_type"), "total_cells", cells.size()),
                "Edited cell " + cellIndex + " (" + cell.get("cell_type") + ")"
        );
    }

    // ── delete_cell ──

    private ToolResult deleteCell(Path path, Map<String, Object> input) throws IOException {
        if (!Files.exists(path)) {
            return ToolResult.error("File not found: " + path);
        }
        Map<String, Object> notebook = readNotebook(path);
        List<Map<String, Object>> cells = getCells(notebook);

        Integer cellIndex = getInt(input, "cell_index");
        if (cellIndex == null) {
            return ToolResult.error("'cell_index' is required for delete_cell");
        }
        if (cellIndex < 0 || cellIndex >= cells.size()) {
            return ToolResult.error("Cell index out of range: " + cellIndex + " (total: " + cells.size() + ")");
        }

        Map<String, Object> removed = cells.remove((int) cellIndex);
        writeNotebook(path, notebook);
        return ToolResult.ok(
                Map.of("removed_index", cellIndex, "removed_type", removed.get("cell_type"), "total_cells", cells.size()),
                "Deleted cell " + cellIndex + " (" + removed.get("cell_type") + "), " + cells.size() + " cells remaining"
        );
    }

    // ── move_cell ──

    private ToolResult moveCell(Path path, Map<String, Object> input) throws IOException {
        if (!Files.exists(path)) {
            return ToolResult.error("File not found: " + path);
        }
        Map<String, Object> notebook = readNotebook(path);
        List<Map<String, Object>> cells = getCells(notebook);

        Integer cellIndex = getInt(input, "cell_index");
        Integer targetIndex = getInt(input, "target_index");
        if (cellIndex == null || targetIndex == null) {
            return ToolResult.error("'cell_index' and 'target_index' are required for move_cell");
        }
        if (cellIndex < 0 || cellIndex >= cells.size()) {
            return ToolResult.error("cell_index out of range: " + cellIndex);
        }
        if (targetIndex < 0 || targetIndex >= cells.size()) {
            return ToolResult.error("target_index out of range: " + targetIndex);
        }

        Map<String, Object> cell = cells.remove((int) cellIndex);
        cells.add(targetIndex, cell);
        writeNotebook(path, notebook);
        return ToolResult.ok(
                Map.of("from", cellIndex, "to", targetIndex, "cell_type", cell.get("cell_type")),
                "Moved cell from " + cellIndex + " to " + targetIndex
        );
    }

    // ── 유틸리티 ──

    @SuppressWarnings("unchecked")
    private Map<String, Object> readNotebook(Path path) throws IOException {
        return mapper.readValue(path.toFile(), new TypeReference<Map<String, Object>>() {});
    }

    private void writeNotebook(Path path, Map<String, Object> notebook) throws IOException {
        mapper.writeValue(path.toFile(), notebook);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getCells(Map<String, Object> notebook) {
        Object cells = notebook.get("cells");
        if (cells instanceof List<?>) {
            return (List<Map<String, Object>>) cells;
        }
        List<Map<String, Object>> newCells = new ArrayList<>();
        notebook.put("cells", newCells);
        return newCells;
    }

    private Map<String, Object> createEmptyNotebook() {
        Map<String, Object> nb = new LinkedHashMap<>();
        nb.put("nbformat", 4);
        nb.put("nbformat_minor", 5);
        nb.put("metadata", Map.of(
                "kernelspec", Map.of(
                        "display_name", "Python 3",
                        "language", "python",
                        "name", "python3"
                ),
                "language_info", Map.of("name", "python", "version", "3.11.0")
        ));
        nb.put("cells", new ArrayList<>());
        return nb;
    }

    private Map<String, Object> createCell(String cellType, String source) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("cell_type", cellType);
        cell.put("metadata", Map.of());
        cell.put("source", sourceToList(source));
        if ("code".equals(cellType)) {
            cell.put("execution_count", null);
            cell.put("outputs", List.of());
        }
        String cellId = UUID.randomUUID().toString().substring(0, 8);
        cell.put("id", cellId);
        return cell;
    }

    private List<String> sourceToList(String source) {
        if (source == null || source.isEmpty()) return List.of();
        String[] lines = source.split("\n", -1);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            result.add(i < lines.length - 1 ? lines[i] + "\n" : lines[i]);
        }
        return result;
    }

    private String joinSource(Object source) {
        if (source instanceof String s) return s;
        if (source instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                sb.append(item);
            }
            return sb.toString();
        }
        return "";
    }

    private void checkSize(Path path) throws IOException {
        long size = Files.size(path);
        if (size > MAX_NOTEBOOK_SIZE) {
            throw new IOException("Notebook exceeds 10MB limit: " + (size / 1024 / 1024) + "MB");
        }
    }

    private Integer getInt(Map<String, Object> input, String key) {
        Object val = input.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
