-- CR-120: 대용량 입력 분석 동작 프롬프트 6종 seed (extract/summarize/verify × map/reduce).
--
-- 사용처: DocumentAnalysis 구현(ExtractAnalysis/SummarizeAnalysis/VerifyAnalysis)이
--   PromptTemplateService.getTemplateOrFallback(key, FALLBACK) 로 참조한다.
--   각 구현에 동일 본문의 폴백 상수가 동봉돼 있어, 이 seed 가 미적재여도 동작한다(CR-036 원칙).
--
-- 자리표시자: 엔진(LargeInputStepExecutor)이 마지막에 {{chunk}}(map)/{{fragments}}(reduce)를 채운다.
--   동작은 유형 고유 지시만 책임지고 엔진은 청크 운반만 책임진다(엔진 유형 무지).

INSERT INTO prompt_templates (key, version, category, name, template, language, is_active, is_system) VALUES
('largeinput.extract.map', 1, 'largeinput', 'Large Input — Extract (map)',
 E'You are extracting facts from ONE fragment of a large document. Extract every relevant item exhaustively — do not summarize or skip. Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.\nEach extracted item MUST cite its source location (page range of this fragment) in a "source_ref" field.\nIf the fragment contains nothing relevant, return an empty list — do not invent.\n\nFragment:\n{{chunk}}',
 'en', true, true),

('largeinput.extract.reduce', 1, 'largeinput', 'Large Input — Extract (reduce)',
 E'You are consolidating extracted items from multiple fragments of one document. Merge duplicates, preserve every distinct item and its source_ref, and keep the union complete. Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.\n\nFragments'' extracted items:\n{{fragments}}',
 'en', true, true),

('largeinput.summarize.map', 1, 'largeinput', 'Large Input — Summarize (map)',
 E'Summarize ONE fragment of a large document concisely but completely — capture every key point in this fragment, omit nothing important. Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.\n\nFragment:\n{{chunk}}',
 'en', true, true),

('largeinput.summarize.reduce', 1, 'largeinput', 'Large Input — Summarize (reduce)',
 E'Combine these fragment summaries into one coherent summary of the whole document. Preserve all distinct points, remove redundancy, keep it faithful. Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.\n\nFragment summaries:\n{{fragments}}',
 'en', true, true),

('largeinput.verify.map', 1, 'largeinput', 'Large Input — Verify (map)',
 E'You are verifying ONE fragment of a document against a reference standard. Compare the fragment to the reference and report every discrepancy: wrong values, missing clauses, contradictions. For each finding give a verdict (MATCH | MISMATCH | MISSING) and cite the fragment''s location. Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.\n\nReference standard:\n{{reference_input}}\n\nFragment:\n{{chunk}}',
 'en', true, true),

('largeinput.verify.reduce', 1, 'largeinput', 'Large Input — Verify (reduce)',
 E'Consolidate verification findings from all fragments against the reference standard. Keep every distinct discrepancy with its verdict and location, merge duplicates, and produce an overall compliance assessment. Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.\n\nReference standard:\n{{reference_input}}\n\nFragment findings:\n{{fragments}}',
 'en', true, true);
