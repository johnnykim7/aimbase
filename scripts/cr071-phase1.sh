#!/usr/bin/env bash
# CR-071 Phase 1 — 모듈 분리 + ClaudeCliLlmAdapter/ClaudeCodeTool 즉시 삭제
# 실행: bash scripts/cr071-phase1.sh
# 작업 디렉토리: aimbase 루트 (Gradle 루트는 backend/)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "=== CR-071 Phase 1 시작 ==="
echo "ROOT: $ROOT"

# ────────────────────────────────────────────────────────────────────
# 1. 신규 cli-runner 모듈 디렉토리 생성
# ────────────────────────────────────────────────────────────────────
echo "[1/9] cli-runner 모듈 디렉토리 생성"
mkdir -p backend/cli-runner/src/main/java/com/platform/runner/claudecli
mkdir -p backend/cli-runner/src/test/java/com/platform/runner/claudecli

# ────────────────────────────────────────────────────────────────────
# 2. cli-runner build.gradle.kts 생성
# ────────────────────────────────────────────────────────────────────
echo "[2/9] cli-runner/build.gradle.kts 작성"
cat > backend/cli-runner/build.gradle.kts <<'EOF'
plugins {
    `java-library`
}

dependencies {
    // ClaudeCliWorker 가 platform-core 의 LLMRequest/Response/UnifiedMessage 등 사용 중
    // Phase 4 에서 어댑터가 cli-runner 를 의존하는 방향으로 정리될 예정 (현재는 양방향 의존 회피 위해 platform-core 의존 유지)
    api(project(":platform-core"))
    api("org.slf4j:slf4j-api:2.0.16")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.2")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
EOF

# ────────────────────────────────────────────────────────────────────
# 3. settings.gradle.kts 에 cli-runner 등록
# ────────────────────────────────────────────────────────────────────
echo "[3/9] settings.gradle.kts 갱신"
if ! grep -q 'include("cli-runner")' backend/settings.gradle.kts; then
    printf '%s\n' 'include("cli-runner")' >> backend/settings.gradle.kts
fi

# ────────────────────────────────────────────────────────────────────
# 4. 소스 이동 (platform-core → cli-runner) + 패키지 변경
# ────────────────────────────────────────────────────────────────────
echo "[4/9] claudecli 소스 7개 이동"
SRC_DIR="backend/platform-core/src/main/java/com/platform/llm/claudecli"
DST_DIR="backend/cli-runner/src/main/java/com/platform/runner/claudecli"

for f in ClaudeCliWorker.java ClaudeCliWorkerPool.java ClaudeCliCommandBuilder.java \
         ClaudeCliBranchScope.java ClaudeCliException.java ClaudeCliTimeoutException.java \
         ClaudeCliAdapterConfig.java; do
    if [[ -f "$SRC_DIR/$f" ]]; then
        # package 선언 + claudecli import 모두 새 패키지로 변경
        sed -e 's|package com\.platform\.llm\.claudecli;|package com.platform.runner.claudecli;|g' \
            -e 's|import com\.platform\.llm\.claudecli\.|import com.platform.runner.claudecli.|g' \
            "$SRC_DIR/$f" > "$DST_DIR/$f"
        rm "$SRC_DIR/$f"
        echo "  - $f"
    fi
done

echo "[4b/9] claudecli 단위 테스트 3개 이동"
TEST_SRC="backend/platform-core/src/test/java/com/platform/llm/claudecli"
TEST_DST="backend/cli-runner/src/test/java/com/platform/runner/claudecli"

for f in ClaudeCliWorkerTest.java ClaudeCliWorkerPoolTest.java ClaudeCliCommandBuilderTest.java; do
    if [[ -f "$TEST_SRC/$f" ]]; then
        sed -e 's|package com\.platform\.llm\.claudecli;|package com.platform.runner.claudecli;|g' \
            -e 's|import com\.platform\.llm\.claudecli\.|import com.platform.runner.claudecli.|g' \
            "$TEST_SRC/$f" > "$TEST_DST/$f"
        rm "$TEST_SRC/$f"
        echo "  - $f"
    fi
done

# 통합 테스트는 Phase 5에서 재작성 → 삭제
echo "[4c/9] AdapterComparisonIT / ClaudeCliLlmAdapterIT 삭제"
rm -f "$TEST_SRC/AdapterComparisonIT.java"
rm -f "$TEST_SRC/ClaudeCliLlmAdapterIT.java"

# 비어 있는 claudecli 디렉토리 정리
rmdir "$SRC_DIR" 2>/dev/null || true
rmdir "$TEST_SRC" 2>/dev/null || true

# ────────────────────────────────────────────────────────────────────
# 5. 즉시 삭제 — 소스 + 테스트
# ────────────────────────────────────────────────────────────────────
echo "[5/9] 레거시 클래스 삭제"

# 소스
rm -f backend/platform-core/src/main/java/com/platform/llm/adapter/ClaudeCliLlmAdapter.java
rm -f backend/platform-core/src/main/java/com/platform/tool/builtin/ClaudeCodeTool.java
rm -f backend/platform-core/src/main/java/com/platform/tool/builtin/ClaudeCodeToolConfig.java
rm -f backend/platform-core/src/main/java/com/platform/tool/builtin/ClaudeCodeNotificationService.java
rm -f backend/platform-core/src/main/java/com/platform/tool/builtin/ClaudeCodeCircuitBreaker.java
rm -f backend/platform-core/src/main/java/com/platform/tool/builtin/AgentAccountPoolManager.java
rm -f backend/platform-core/src/main/java/com/platform/tool/builtin/ErrorClassificationService.java
rm -f backend/platform-core/src/main/java/com/platform/runtime/ClaudeCodeRuntimeAdapter.java
rm -f backend/platform-core/src/main/java/com/platform/api/ClaudeCodePlatformController.java
rm -f backend/platform-core/src/main/java/com/platform/api/AgentAccountController.java

# Domain / Repository (AgentAccountPoolManager + ErrorClassificationService 의존 제거 동반)
rm -f backend/platform-core/src/main/java/com/platform/domain/master/ClaudeCodeErrorPatternEntity.java
rm -f backend/platform-core/src/main/java/com/platform/domain/master/AgentAccountEntity.java
rm -f backend/platform-core/src/main/java/com/platform/domain/master/AgentAccountAssignmentEntity.java
rm -f backend/platform-core/src/main/java/com/platform/repository/master/ClaudeCodeErrorPatternRepository.java
rm -f backend/platform-core/src/main/java/com/platform/repository/master/AgentAccountRepository.java
rm -f backend/platform-core/src/main/java/com/platform/repository/master/AgentAccountAssignmentRepository.java

# 테스트
rm -f backend/platform-core/src/test/java/com/platform/llm/adapter/ClaudeCliLlmAdapterTest.java
rm -f backend/platform-core/src/test/java/com/platform/tool/ClaudeCodeToolTest.java
rm -f backend/platform-core/src/test/java/com/platform/tool/builtin/ClaudeCodeToolRetryTest.java
rm -f backend/platform-core/src/test/java/com/platform/tool/builtin/ClaudeCodeToolStreamingTest.java

# ────────────────────────────────────────────────────────────────────
# 6. 의존 정리 — 컴파일 깨지는 곳 수동 수정 필요 (스크립트로 자동화 어려움)
# ────────────────────────────────────────────────────────────────────
echo "[6/9] ⚠ 수동 수정 필요 — 아래 안내 참조"
cat <<'NOTE'

  ────────────────────────────────────────────────────────────────────
  자동화 불가 — 다음 파일들은 직접 편집해야 합니다.
  agent 작업 결과(이전 세션)와 동일한 변경을 적용하세요.

  1. backend/platform-core/src/main/java/com/platform/llm/ConnectionAdapterFactory.java
     - import com.platform.llm.adapter.ClaudeCliLlmAdapter;        제거
     - import com.platform.llm.claudecli.ClaudeCliAdapterConfig;   제거
     - import com.platform.llm.claudecli.ClaudeCliWorkerPool;      제거
     - 생성자 파라미터: ClaudeCliAdapterConfig, ClaudeCliWorkerPool 제거
     - 필드: claudeCliConfig, claudeCliWorkerPool 제거
     - createAdapter() 의 anthropic-cli 분기에서:
         yield new ClaudeCliLlmAdapter(claudeCliWorkerPool, model, cliConfigDir);
       → 다음으로 교체:
         throw new UnsupportedOperationException(
             "ClaudeCliAdapter coming in Phase 4 (CR-071)");

  2. backend/platform-core/src/main/java/com/platform/api/ChatController.java
     - line 217 부근: com.platform.tool.builtin.ClaudeCodeTool.setStreamSink(sseSink); 제거
     - line 226 부근: com.platform.tool.builtin.ClaudeCodeTool.clearStreamSink();    제거
     - 관련 주석 (// CR-070 Phase A: ClaudeCodeTool도 ...) 제거

  3. backend/platform-core/src/main/java/com/platform/workflow/WorkflowEngine.java
     - ClaudeCliWorkerPool 의존 제거 + shutdownCliWorkers() 본문 비우기 (no-op 유지)

  4. backend/platform-core/src/main/java/com/platform/workflow/step/ParallelStepExecutor.java
     - ClaudeCliBranchScope / ClaudeCliWorkerPool 의존 제거 + try/finally 정리

  5. backend/platform-core/src/main/java/com/platform/agent/AgentRunEventRouter.java
     backend/platform-core/src/main/java/com/platform/api/AgentRunEventController.java
     backend/platform-core/src/main/java/com/platform/tool/builtin/AimbaseMcpConfigGenerator.java
     backend/platform-core/src/main/java/com/platform/tool/builtin/SuggestBackgroundPRTool.java
     backend/platform-core/src/main/java/com/platform/agent/AgentType.java
     backend/platform-core/src/main/java/com/platform/workflow/step/ToolCallStepExecutor.java
       → 주석/CSV 토큰 정리 (claude_code 토큰 제거 등)

  6. AimbaseMcpConfigGenerator
     - ClaudeCodeToolConfig import/의존 제거
     - jar 경로는 환경변수 AIMBASE_MCP_JAR 만 폴백으로

  ────────────────────────────────────────────────────────────────────
NOTE

# ────────────────────────────────────────────────────────────────────
# 7. application.yml 정리
# ────────────────────────────────────────────────────────────────────
echo "[7/9] ⚠ application.yml 수동 정리"
cat <<'NOTE'
  backend/platform-core/src/main/resources/application.yml
  - 최상위 'claude-code:' 블록 전체 삭제 (line 96 부근)
  - 'platform.llm.anthropic-cli:' 블록 삭제 (있다면)
NOTE

# ────────────────────────────────────────────────────────────────────
# 8. Flyway 마이그레이션 추가
# ────────────────────────────────────────────────────────────────────
echo "[8/9] V57 master + V59 tenant 마이그레이션 작성"

cat > backend/platform-core/src/main/resources/db/migration/master/V57__cr071_drop_legacy_claude_code.sql <<'EOF'
-- CR-071 Phase 1: ClaudeCodeTool / ClaudeCliLlmAdapter 자산 정리
-- 운영 중이 아니므로 단계적 deprecation 없이 즉시 drop.

-- 시드 워크플로우 정리 (V10/V11 의 'tool: claude_code' 워크플로우)
-- 정확한 컬럼명/JSON 경로는 실제 workflows 테이블 정의에 맞춰 보정 필요
DELETE FROM workflows
 WHERE definition::text LIKE '%"tool":"claude_code"%'
    OR definition::text LIKE '%"tool": "claude_code"%';

-- ClaudeCodeTool 에러 패턴 (V5)
DROP TABLE IF EXISTS claude_code_error_patterns;

-- 에이전트 계정 (V7) — claude_code 전용
DROP TABLE IF EXISTS agent_account_assignments;
DROP TABLE IF EXISTS agent_accounts;
EOF

cat > backend/platform-core/src/main/resources/db/migration/tenant/V59__cr071_remove_claudecode_perm.sql <<'EOF'
-- CR-071: 권한 규칙 정규식에서 ClaudeCode 토큰 제거
-- (V57/V58 은 CR-053 등에서 사용 중이므로 V59 사용)
UPDATE permission_rules
   SET pattern = REPLACE(pattern, '|ClaudeCode', '')
 WHERE pattern LIKE '%ClaudeCode%';
EOF

# ────────────────────────────────────────────────────────────────────
# 9. 빌드 검증 (옵션)
# ────────────────────────────────────────────────────────────────────
echo "[9/9] 빌드 검증 — 의존 정리 수동 작업 완료 후 별도 실행 권장"
cat <<'NOTE'

  의존 정리 (6, 7번) 완료 후 다음 명령으로 검증:

    cd backend
    ./gradlew :cli-runner:compileJava :cli-runner:test --no-daemon
    ./gradlew :platform-core:compileJava --no-daemon
    ./gradlew :platform-core:test --no-daemon

NOTE

echo "=== CR-071 Phase 1 스크립트 완료 ==="
