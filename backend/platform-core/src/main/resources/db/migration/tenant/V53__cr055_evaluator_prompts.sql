-- CR-055: EVALUATOR_LOOP step의 기본 evaluator 프롬프트 템플릿 3종 seed.
-- 사용처: EvaluatorLoopStepExecutor가 step config의 evaluator.prompt_template_key로 참조한다.
-- 비대칭성 원칙(Generator ≠ Evaluator 관점) — 각 템플릿은 "평가자 역할"을 명시적으로 부여한다.

INSERT INTO prompt_templates (key, version, category, name, template, language, is_active, is_system) VALUES
('evaluator.literary_critic', 1, 'evaluator', 'Literary Critic Evaluator',
 E'You are a demanding literary critic. Evaluate the generated content on how well it preserves the source''s intent, metaphor, and rhythm.\n\nSource:\n{{loop.source}}\n\nGenerated:\n{{loop.generator_output}}\n\nPrevious feedback (if any):\n{{loop.previous_feedback}}\n\nRespond in JSON only, no additional text:\n{\n  "score":    <number 0-10; 10 is perfect>,\n  "passed":   <boolean, true if score >= 8.5>,\n  "feedback": "<1-3 sentences; be concrete about what is weak>"\n}',
 'en', true, true),

('evaluator.code_reviewer', 1, 'evaluator', 'Strict Code Reviewer',
 E'You are a strict senior engineer. Evaluate the submitted code against:\n- Correctness (meets the requirement)\n- Readability (naming, structure)\n- Error handling\n- Testability\n\nRequirement:\n{{loop.requirement}}\n\nSubmitted code:\n```\n{{loop.generator_output}}\n```\n\nPrevious feedback (if any):\n{{loop.previous_feedback}}\n\nRespond in JSON only:\n{\n  "score":    <number 0-10>,\n  "passed":   <boolean, true if score >= 8>,\n  "feedback": "<specific, actionable improvement notes>"\n}',
 'en', true, true),

('evaluator.persona_copy', 1, 'evaluator', 'Persona-based Copy Evaluator',
 E'You are the following persona: {{loop.persona}}\nEvaluate how compelling this marketing copy is to you on three dimensions:\n- Appeal (grabs attention)\n- Trust (no exaggeration)\n- Clarity (value is conveyed)\n\nCopy:\n{{loop.generator_output}}\n\nPrevious feedback (if any):\n{{loop.previous_feedback}}\n\nRespond in JSON only:\n{\n  "score":    <number 0-10>,\n  "passed":   <boolean, true if score >= 7.5>,\n  "feedback": "<which dimension is weakest and why>"\n}',
 'en', true, true);
