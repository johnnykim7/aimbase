-- 패스 모드 워크플로우: zip 대신 로컬 경로를 직접 지정하여 분석
-- zip_extract/temp_cleanup 스텝 없이 ClaudeCodeTool이 로컬 경로에서 바로 파일 접근
-- MCP 도구(FlowGuard 등)를 allowed_tools에 포함하여 분석 결과를 외부 서비스에 직접 등록 가능

-- 1. 패스 기반 파일 분석 (범용): 로컬 경로 → Claude Code 분석 (MCP 도구 포함)
INSERT INTO platform_workflows (id, name, description, category, trigger_config, steps, input_schema) VALUES
('path-analysis', '패스 기반 분석', '로컬 경로의 소스/문서를 Claude Code로 직접 분석합니다. zip 업로드 없이 경로만 지정하면 됩니다. MCP 도구를 통해 분석 결과를 외부 서비스에 등록할 수 있습니다.', 'file_analysis',
 '{"type": "api"}',
 '[
   {"id": "s1", "name": "소스 분석 및 처리", "type": "TOOL_CALL", "config": {"tool": "claude_code", "input": {"prompt": "{{input.prompt}}", "working_directory": "{{input.local_path}}", "output_format": "json", "allowed_tools": ["Read", "Grep", "Glob", "mcp__*"]}}, "dependsOn": []}
 ]',
 '{"type": "object", "properties": {"local_path": {"type": "string", "description": "분석할 소스/문서가 있는 로컬 디렉토리 절대 경로"}, "prompt": {"type": "string", "description": "분석 지시 (예: 소스 분석해서 FlowGuard에 테스트 Step으로 등록해줘)"}}, "required": ["local_path", "prompt"]}');

-- 2. 패스 기반 코드 리뷰: 로컬 경로에서 직접 코드 리뷰
INSERT INTO platform_workflows (id, name, description, category, trigger_config, steps, input_schema) VALUES
('path-code-review', '패스 기반 코드 리뷰', '로컬 경로의 소스 코드를 직접 리뷰합니다. zip 업로드 없이 경로만 지정하면 됩니다.', 'file_analysis',
 '{"type": "api"}',
 '[
   {"id": "s1", "name": "코드 리뷰 수행", "type": "TOOL_CALL", "config": {"tool": "claude_code", "input": {"prompt": "이 소스 코드를 전체적으로 리뷰해줘. 다음 항목을 포함해서 리포트를 작성해줘:\\n1. 코드 품질 (가독성, 구조, 네이밍)\\n2. 잠재적 버그 및 보안 취약점\\n3. 성능 개선 포인트\\n4. 개선 제안 (우선순위별)\\n결과는 JSON 형식으로 출력해줘.", "working_directory": "{{input.local_path}}", "output_format": "json", "allowed_tools": ["Read", "Grep", "Glob"]}}, "dependsOn": []}
 ]',
 '{"type": "object", "properties": {"local_path": {"type": "string", "description": "리뷰할 소스 코드가 있는 로컬 디렉토리 절대 경로"}}, "required": ["local_path"]}');

-- 3. 패스 기반 문서 생성: 로컬 소스에서 설계 문서 역생성
INSERT INTO platform_workflows (id, name, description, category, trigger_config, steps, input_schema) VALUES
('path-doc-generation', '패스 기반 문서 생성', '로컬 경로의 소스 코드를 분석하여 설계 문서를 자동 생성합니다. zip 업로드 없이 경로만 지정하면 됩니다.', 'file_analysis',
 '{"type": "api"}',
 '[
   {"id": "s1", "name": "문서 생성", "type": "TOOL_CALL", "config": {"tool": "claude_code", "input": {"prompt": "이 소스 코드를 분석하여 다음 문서를 생성해줘:\\n1. 프로젝트 구조 개요\\n2. 주요 모듈/패키지 설명\\n3. API 엔드포인트 목록 (경로, 메서드, 설명)\\n4. 데이터 모델 (엔티티 관계)\\n5. 주요 비즈니스 로직 흐름\\n결과는 JSON 형식으로 출력해줘.", "working_directory": "{{input.local_path}}", "output_format": "json", "allowed_tools": ["Read", "Grep", "Glob"]}}, "dependsOn": []}
 ]',
 '{"type": "object", "properties": {"local_path": {"type": "string", "description": "분석할 소스 코드가 있는 로컬 디렉토리 절대 경로"}}, "required": ["local_path"]}');

-- 4. DSL 생성 + FlowGuard 등록: 소스/문서 분석 → 테스트 DSL 생성 → MCP로 FlowGuard에 등록
INSERT INTO platform_workflows (id, name, description, category, trigger_config, steps, input_schema) VALUES
('path-dsl-generation', 'DSL 생성 및 등록', '로컬 경로의 소스와 테스트 문서를 분석하여 테스트 DSL을 생성하고, FlowGuard MCP를 통해 자동 등록합니다.', 'test_automation',
 '{"type": "api"}',
 '[
   {"id": "s1", "name": "소스 분석 및 DSL 생성·등록", "type": "TOOL_CALL", "config": {"tool": "claude_code", "input": {"prompt": "{{input.local_path}} 경로의 소스 코드와 테스트 문서를 분석하여:\\n1. 주요 기능과 테스트 시나리오를 식별\\n2. 각 시나리오에 대한 FlowGuard Step DSL(JSON)을 생성\\n3. MCP 도구를 사용하여 FlowGuard에 Step/Scenario로 등록\\n\\n등록 결과를 JSON으로 보고해줘.", "working_directory": "{{input.local_path}}", "output_format": "json", "allowed_tools": ["Read", "Grep", "Glob", "mcp__*"]}}, "dependsOn": []}
 ]',
 '{"type": "object", "properties": {"local_path": {"type": "string", "description": "분석할 소스/문서가 있는 로컬 디렉토리 절대 경로"}}, "required": ["local_path"]}');
