package com.platform.llm.model;

import java.util.Map;

public sealed interface ContentBlock
        permits ContentBlock.Text, ContentBlock.Image, ContentBlock.ToolUse, ContentBlock.ToolResult {

    record Text(String text) implements ContentBlock {}

    record Image(String mediaType, String data) implements ContentBlock {}

    record ToolUse(String id, String name, Map<String, Object> input) implements ContentBlock {}

    record ToolResult(String toolUseId, String content) implements ContentBlock {}
}
