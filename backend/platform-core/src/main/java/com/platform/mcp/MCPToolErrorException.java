package com.platform.mcp;

/**
 * CR-127: 원격 MCP 도구가 {@code isError:true} 로 실패했음을 나타내는 예외.
 *
 * <p>기존에는 {@code MCPServerClient.callTool()} 이 에러 결과를 로그로만 남기고 텍스트를
 * 정상 반환값처럼 돌려줘, 호출자가 성공/실패를 구분할 수 없었다. 그 결과 CLI 에는
 * {@code isError:false} 로 나가고 모델은 실패를 인지하지 못한 채 없는 데이터를 창작했다.
 * (실측: bp-wes 브리핑 도구 8회 전부 에러 → 모델이 처리량 12,450 창작, 실제 4,964)
 *
 * <p><b>전송 계층 예외와 구분되어야 한다.</b> {@link MCPToolExecutor} 는 예외를 잡아
 * 재연결 후 재시도하는데, 이는 죽은 세션·IO 장애를 가정한 복구다. 도구 로직 에러
 * (잘못된 인자 등)는 재연결해도 같은 결과이므로 재시도 대상에서 제외해야 한다.
 * 따라서 이 타입은 별도로 잡아 그대로 재던진다.
 */
public class MCPToolErrorException extends RuntimeException {

    private final String toolName;

    public MCPToolErrorException(String toolName, String message) {
        super("MCP tool '" + toolName + "' returned an error result: " + message);
        this.toolName = toolName;
    }

    /** 실패한 도구 이름. */
    public String getToolName() {
        return toolName;
    }
}
