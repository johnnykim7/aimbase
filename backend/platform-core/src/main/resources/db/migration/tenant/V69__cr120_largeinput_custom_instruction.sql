-- CR-120: 분석 동작 프롬프트에 {{custom_instruction}} 자리표시자 추가.
--
-- 소비앱이 도메인 지시문(예: USFK RFP fact 스키마·충족주체 분류)을 LARGE_INPUT config 의
-- custom_instruction 으로 넘기면 엔진이 이 자리에 그대로 끼워넣는다. 특정 도메인 action 을
-- 플랫폼에 신설하지 않기 위한 범용 통로(엔진/동작은 내용을 모름).
--
-- V57(cr053) 패턴: (key, version=1) pk 유지, template 만 교체. 폴백 상수(impl/*Analysis.java)와 본문 일치.

UPDATE prompt_templates SET template = $PROMPT$You are extracting facts from ONE fragment of a large document. Extract every relevant item exhaustively — do not summarize or skip. Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.
Each extracted item MUST cite its source location (page range of this fragment) in a "source_ref" field.
If the fragment contains nothing relevant, return an empty list — do not invent.
{{custom_instruction}}

Fragment:
{{chunk}}$PROMPT$
WHERE key = 'largeinput.extract.map' AND version = 1;

UPDATE prompt_templates SET template = $PROMPT$You are consolidating extracted items from multiple fragments of one document. Merge duplicates, preserve every distinct item and its source_ref, and keep the union complete. Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.
{{custom_instruction}}

Fragments' extracted items:
{{fragments}}$PROMPT$
WHERE key = 'largeinput.extract.reduce' AND version = 1;

UPDATE prompt_templates SET template = $PROMPT$Summarize ONE fragment of a large document concisely but completely — capture every key point in this fragment, omit nothing important. Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.
{{custom_instruction}}

Fragment:
{{chunk}}$PROMPT$
WHERE key = 'largeinput.summarize.map' AND version = 1;

UPDATE prompt_templates SET template = $PROMPT$Combine these fragment summaries into one coherent summary of the whole document. Preserve all distinct points, remove redundancy, keep it faithful. Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.
{{custom_instruction}}

Fragment summaries:
{{fragments}}$PROMPT$
WHERE key = 'largeinput.summarize.reduce' AND version = 1;

UPDATE prompt_templates SET template = $PROMPT$You are verifying ONE fragment of a document against a reference standard. Compare the fragment to the reference and report every discrepancy: wrong values, missing clauses, contradictions. For each finding give a verdict (MATCH | MISMATCH | MISSING) and cite the fragment's location. Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.
{{custom_instruction}}

Reference standard:
{{reference_input}}

Fragment:
{{chunk}}$PROMPT$
WHERE key = 'largeinput.verify.map' AND version = 1;

UPDATE prompt_templates SET template = $PROMPT$Consolidate verification findings from all fragments against the reference standard. Keep every distinct discrepancy with its verdict and location, merge duplicates, and produce an overall compliance assessment. Focus areas: {{focus_areas}}. Goal: {{analysis_goal}}.
{{custom_instruction}}

Reference standard:
{{reference_input}}

Fragment findings:
{{fragments}}$PROMPT$
WHERE key = 'largeinput.verify.reduce' AND version = 1;
