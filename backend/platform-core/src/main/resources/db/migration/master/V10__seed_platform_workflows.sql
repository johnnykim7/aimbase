-- 공용 워크플로우 시드 데이터

-- 1. 파일 분석 (범용): ZIP 업로드 → Claude Code 분석 → 정리
INSERT INTO platform_workflows (id, name, description, category, trigger_config, steps, input_schema) VALUES
('file-analysis', '파일 분석', '업로드된 파일(ZIP)을 Claude Code로 자유 탐색·분석하여 결과를 반환합니다. prompt 파라미터로 분석 목적을 지정하세요.', 'file_analysis',
 '{"type": "api"}',
 '[
   {"id": "s1", "name": "ZIP 압축 해제", "type": "TOOL_CALL", "config": {"tool": "zip_extract", "input": {"zip_path": "{{input.zip_path}}", "sub_directory": "{{input.sub_directory}}"}}, "dependsOn": []},
   {"id": "s2", "name": "Claude Code 분석", "type": "TOOL_CALL", "config": {"tool": "claude_code", "input": {"prompt": "{{input.prompt}}", "working_directory": "{{s1.structured_data.temp_path}}", "output_format": "json", "allowed_tools": ["Read", "Grep", "Glob"]}}, "dependsOn": ["s1"]},
   {"id": "s3", "name": "임시 폴더 정리", "type": "TOOL_CALL", "config": {"tool": "temp_cleanup", "input": {"temp_path": "{{s1.structured_data.temp_path}}"}}, "dependsOn": ["s2"]}
 ]',
 '{"type": "object", "properties": {"zip_path": {"type": "string", "description": "분석할 ZIP 파일 절대 경로"}, "prompt": {"type": "string", "description": "분석 지시 (예: 코드 리뷰해줘, API 문서 만들어줘)"}, "sub_directory": {"type": "string", "description": "ZIP 내 특정 하위 디렉토리만 분석 (선택)"}}, "required": ["zip_path", "prompt"]}');

-- 2. 코드 리뷰: 파일 분석의 프리셋 (프롬프트 고정)
INSERT INTO platform_workflows (id, name, description, category, trigger_config, steps, input_schema) VALUES
('code-review', '코드 리뷰', '소스 코드 ZIP을 업로드하면 코드 품질, 보안 취약점, 개선 사항을 분석하여 리뷰 리포트를 생성합니다.', 'file_analysis',
 '{"type": "api"}',
 '[
   {"id": "s1", "name": "ZIP 압축 해제", "type": "TOOL_CALL", "config": {"tool": "zip_extract", "input": {"zip_path": "{{input.zip_path}}"}}, "dependsOn": []},
   {"id": "s2", "name": "코드 리뷰 수행", "type": "TOOL_CALL", "config": {"tool": "claude_code", "input": {"prompt": "이 소스 코드를 전체적으로 리뷰해줘. 다음 항목을 포함해서 리포트를 작성해줘:\\n1. 코드 품질 (가독성, 구조, 네이밍)\\n2. 잠재적 버그 및 보안 취약점\\n3. 성능 개선 포인트\\n4. 개선 제안 (우선순위별)\\n결과는 JSON 형식으로 출력해줘.", "working_directory": "{{s1.structured_data.temp_path}}", "output_format": "json", "allowed_tools": ["Read", "Grep", "Glob"]}}, "dependsOn": ["s1"]},
   {"id": "s3", "name": "임시 폴더 정리", "type": "TOOL_CALL", "config": {"tool": "temp_cleanup", "input": {"temp_path": "{{s1.structured_data.temp_path}}"}}, "dependsOn": ["s2"]}
 ]',
 '{"type": "object", "properties": {"zip_path": {"type": "string", "description": "리뷰할 소스 코드 ZIP 파일 경로"}}, "required": ["zip_path"]}');

-- 3. 문서 생성: 소스에서 설계 문서 역생성
INSERT INTO platform_workflows (id, name, description, category, trigger_config, steps, input_schema) VALUES
('doc-generation', '문서 생성', '소스 코드 ZIP을 분석하여 API 명세, 아키텍처 문서, ERD 등 설계 문서를 자동 생성합니다.', 'file_analysis',
 '{"type": "api"}',
 '[
   {"id": "s1", "name": "ZIP 압축 해제", "type": "TOOL_CALL", "config": {"tool": "zip_extract", "input": {"zip_path": "{{input.zip_path}}"}}, "dependsOn": []},
   {"id": "s2", "name": "문서 생성", "type": "TOOL_CALL", "config": {"tool": "claude_code", "input": {"prompt": "이 소스 코드를 분석하여 다음 문서를 생성해줘:\\n1. 프로젝트 구조 개요\\n2. 주요 모듈/패키지 설명\\n3. API 엔드포인트 목록 (경로, 메서드, 설명)\\n4. 데이터 모델 (엔티티 관계)\\n5. 주요 비즈니스 로직 흐름\\n결과는 JSON 형식으로 출력해줘.", "working_directory": "{{s1.structured_data.temp_path}}", "output_format": "json", "allowed_tools": ["Read", "Grep", "Glob"]}}, "dependsOn": ["s1"]},
   {"id": "s3", "name": "임시 폴더 정리", "type": "TOOL_CALL", "config": {"tool": "temp_cleanup", "input": {"temp_path": "{{s1.structured_data.temp_path}}"}}, "dependsOn": ["s2"]}
 ]',
 '{"type": "object", "properties": {"zip_path": {"type": "string", "description": "분석할 소스 코드 ZIP 파일 경로"}}, "required": ["zip_path"]}');

-- 4. 텍스트 요약
INSERT INTO platform_workflows (id, name, description, category, trigger_config, steps, input_schema) VALUES
('text-summarize', '텍스트 요약', '긴 텍스트를 구조화된 요약으로 변환합니다.', 'text_processing',
 '{"type": "api"}',
 '[
   {"id": "s1", "name": "텍스트 요약", "type": "LLM_CALL", "config": {"prompt": "다음 텍스트를 구조화된 요약으로 만들어줘. 핵심 포인트, 세부 사항, 결론으로 나누어 정리해줘.\\n\\n{{input.text}}"}, "dependsOn": []}
 ]',
 '{"type": "object", "properties": {"text": {"type": "string", "description": "요약할 텍스트"}}, "required": ["text"]}');

-- 5. 텍스트 번역
INSERT INTO platform_workflows (id, name, description, category, trigger_config, steps, input_schema) VALUES
('text-translate', '텍스트 번역', '텍스트를 지정된 언어로 번역합니다.', 'text_processing',
 '{"type": "api"}',
 '[
   {"id": "s1", "name": "번역", "type": "LLM_CALL", "config": {"prompt": "다음 텍스트를 {{input.target_language}}로 번역해줘. 원문의 뉘앙스와 전문 용어를 정확히 반영해줘.\\n\\n{{input.text}}"}, "dependsOn": []}
 ]',
 '{"type": "object", "properties": {"text": {"type": "string", "description": "번역할 텍스트"}, "target_language": {"type": "string", "description": "번역 대상 언어 (예: English, 한국어, 日本語)"}}, "required": ["text", "target_language"]}');

-- 6. 데이터 정제: 비정형 → 구조화 JSON
INSERT INTO platform_workflows (id, name, description, category, trigger_config, steps, input_schema) VALUES
('data-transform', '데이터 정제', '비정형 텍스트나 CSV 데이터를 지정된 스키마의 구조화 JSON으로 변환합니다.', 'data_transform',
 '{"type": "api"}',
 '[
   {"id": "s1", "name": "데이터 변환", "type": "LLM_CALL", "config": {"prompt": "다음 데이터를 분석하고 구조화된 JSON으로 변환해줘.\\n\\n[출력 형식 지시]\\n{{input.schema_description}}\\n\\n[입력 데이터]\\n{{input.data}}"}, "dependsOn": []}
 ]',
 '{"type": "object", "properties": {"data": {"type": "string", "description": "변환할 비정형 데이터"}, "schema_description": {"type": "string", "description": "원하는 출력 JSON 구조 설명"}}, "required": ["data", "schema_description"]}');
