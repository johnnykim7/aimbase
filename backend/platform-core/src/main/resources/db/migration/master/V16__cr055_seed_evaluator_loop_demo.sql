-- CR-055: EVALUATOR_LOOP 데모 워크플로우.
-- 문학 번역 개선 루프 — Anthropic Evaluator-Optimizer 패턴을 시각적으로 체험.
--
-- 사용법:
--   POST /api/v1/platform/workflows/literary-translation-refine/run
--   body: { "source": "번역할 원문 한국어 텍스트" }
--
-- 흐름: source 입력 → EVALUATOR_LOOP (generator: 번역가 / evaluator: 문학 평론가, score>=8.5 까지 최대 3회 반복)
-- 기대 동작: 초벌 번역 → 평론가 피드백 → 재번역 (보통 2-3회차에 통과)

INSERT INTO platform_workflows (id, name, description, category, trigger_config, steps, input_schema) VALUES
('literary-translation-refine', '문학 번역 개선 루프',
 '한국어 원문을 영어로 번역하고, 문학 평론가 관점에서 평가하여 품질이 임계값을 넘을 때까지 반복 개선합니다. Anthropic Evaluator-Optimizer 패턴 데모 (CR-055).',
 'demo',
 '{"type": "api"}',
 $workflow$[
   {
     "id": "refine",
     "name": "번역 개선 루프",
     "type": "EVALUATOR_LOOP",
     "dependsOn": [],
     "config": {
       "max_iterations": 3,
       "generator": {
         "model": "auto",
         "system": "You are a professional Korean-to-English literary translator. Preserve the source's metaphor, rhythm, and tone as much as possible.",
         "prompt": "Translate the following Korean text into English.\n\nSource:\n{{input.source}}\n\n{{loop.iteration}}{{loop.previous_output}}{{loop.feedback}}\n\n(If iteration > 0, the previous output and feedback above are from the critic — incorporate the feedback and improve the translation.)",
         "max_tokens": 2048
       },
       "evaluator": {
         "model": "auto",
         "prompt_template_key": "evaluator.literary_critic",
         "max_tokens": 1024,
         "response_format": {
           "type": "json_schema",
           "schema": {
             "type": "object",
             "required": ["score", "passed", "feedback"],
             "properties": {
               "score":    { "type": "number", "minimum": 0, "maximum": 10 },
               "passed":   { "type": "boolean" },
               "feedback": { "type": "string", "maxLength": 2000 }
             }
           }
         }
       },
       "pass_criteria": {
         "type": "SCORE_THRESHOLD",
         "field": "score",
         "threshold": 8.5,
         "operator": "GTE"
       }
     }
   }
 ]$workflow$,
 '{"type": "object", "properties": {"source": {"type": "string", "description": "번역할 한국어 원문"}}, "required": ["source"]}');
