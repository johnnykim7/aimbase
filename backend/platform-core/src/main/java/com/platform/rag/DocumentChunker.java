package com.platform.rag;

import com.platform.rag.model.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 텍스트를 청크로 분할하는 컴포넌트.
 *
 * strategy="fixed" (기본): 고정 문자 크기 + overlap
 * strategy="sentence": 문장 경계 기준 분할
 */
@Component
public class DocumentChunker {

    private static final int DEFAULT_SIZE = 512;
    private static final int DEFAULT_OVERLAP = 50;

    /**
     * 텍스트를 청크 목록으로 분할.
     *
     * @param documentId     문서 ID
     * @param content        전체 텍스트
     * @param chunkingConfig {"strategy":"fixed","size":512,"overlap":50}
     * @return Chunk 목록
     */
    public List<Chunk> chunk(String documentId, String content, Map<String, Object> chunkingConfig) {
        if (content == null || content.isBlank()) return List.of();

        String strategy = (String) chunkingConfig.getOrDefault("strategy", "fixed");
        int size = toInt(chunkingConfig.get("size"), DEFAULT_SIZE);
        int overlap = toInt(chunkingConfig.get("overlap"), DEFAULT_OVERLAP);

        return switch (strategy) {
            case "sentence" -> chunkBySentence(documentId, content, size, overlap);
            default -> chunkFixed(documentId, content, size, overlap);
        };
    }

    // ─── Fixed-size 청킹 ──────────────────────────────────────────────────

    private List<Chunk> chunkFixed(String documentId, String content, int size, int overlap) {
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        int chunkIndex = 0;

        while (start < content.length()) {
            int end = Math.min(start + size, content.length());
            String chunkContent = content.substring(start, end).strip();

            if (!chunkContent.isBlank()) {
                chunks.add(new Chunk(
                        documentId,
                        chunkIndex++,
                        chunkContent,
                        Map.of("start", start, "end", end, "strategy", "fixed")
                ));
            }

            if (end >= content.length()) break;
            start = end - overlap;
            if (start < 0) start = 0;
        }

        return chunks;
    }

    // ─── Sentence-boundary 청킹 ──────────────────────────────────────────

    private List<Chunk> chunkBySentence(String documentId, String content, int maxSize, int overlap) {
        // 간단한 문장 분리: '. ', '? ', '! ', '\n\n' 기준
        List<String> sentences = splitSentences(content);

        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int chunkIndex = 0;
        int startOffset = 0;
        int currentOffset = 0;

        for (String sentence : sentences) {
            if (current.length() + sentence.length() > maxSize && current.length() > 0) {
                String chunkContent = current.toString().strip();
                if (!chunkContent.isBlank()) {
                    chunks.add(new Chunk(
                            documentId,
                            chunkIndex++,
                            chunkContent,
                            Map.of("start", startOffset, "strategy", "sentence")
                    ));
                }
                // overlap: 마지막 N자 유지
                String overlapText = current.length() > overlap
                        ? current.substring(current.length() - overlap)
                        : current.toString();
                current = new StringBuilder(overlapText);
                startOffset = currentOffset - overlapText.length();
            }
            current.append(sentence);
            currentOffset += sentence.length();
        }

        // 마지막 청크
        if (!current.toString().isBlank()) {
            chunks.add(new Chunk(
                    documentId,
                    chunkIndex,
                    current.toString().strip(),
                    Map.of("start", startOffset, "strategy", "sentence")
            ));
        }

        return chunks;
    }

    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);

            // 문장 종결 조건
            if ((c == '.' || c == '?' || c == '!') && i + 1 < text.length() && text.charAt(i + 1) == ' ') {
                sentences.add(current.toString());
                current = new StringBuilder();
                i++; // 공백 건너뜀
            } else if (c == '\n' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                sentences.add(current.toString());
                current = new StringBuilder();
                i++; // 두 번째 \n 건너뜀
            }
        }

        if (!current.toString().isBlank()) {
            sentences.add(current.toString());
        }

        return sentences;
    }

    // ─── 유틸 ─────────────────────────────────────────────────────────────

    private int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
