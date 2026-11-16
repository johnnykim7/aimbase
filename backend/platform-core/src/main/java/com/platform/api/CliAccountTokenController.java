package com.platform.api;

import com.platform.auth.UserPrincipal;
import com.platform.clitoken.CliAccountTokenService;
import com.platform.clitoken.CliTokenRecipientService;
import com.platform.clitoken.Recipients;
import com.platform.clitoken.TokenRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** CR-196 PRD-646: 테넌트 관리자가 서버 실행기 계정 폴더의 1년 토큰을 보고 넣는다. 만료 알림 수신자도 여기서 고친다. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "CLI Account Tokens", description = "실행기 계정 1년 토큰과 만료 알림 수신자 (CR-196)")
public class CliAccountTokenController {

    private final CliAccountTokenService tokens;
    private final CliTokenRecipientService recipients;

    public CliAccountTokenController(CliAccountTokenService tokens, CliTokenRecipientService recipients) {
        this.tokens = tokens;
        this.recipients = recipients;
    }

    @GetMapping("/cli-account-tokens")
    @Operation(summary = "이 테넌트 서버 실행기 계정 폴더마다 1년 토큰 상태")
    public ApiResponse<List<TokenRow>> list() {
        return ApiResponse.ok(tokens.listCurrentTenant());
    }

    @GetMapping("/connections/{id}/account-token")
    @Operation(summary = "그 연결이 쓰는 실행기 계정 폴더의 1년 토큰 상태")
    public ApiResponse<TokenRow> get(@PathVariable String id) {
        return ApiResponse.ok(tokens.getForConnection(id));
    }

    @PutMapping("/connections/{id}/account-token")
    @Operation(summary = "그 연결이 쓰는 실행기 계정 폴더에 1년 토큰 넣기")
    public ApiResponse<TokenRow> put(@PathVariable String id, @RequestBody TokenRequest body,
                                     @AuthenticationPrincipal UserPrincipal principal,
                                     HttpServletRequest request) {
        return ApiResponse.ok(tokens.putForConnection(id, body != null ? body.token() : null, principal,
                request.getRemoteAddr()));
    }

    @GetMapping("/cli-token-recipients")
    @Operation(summary = "1년 토큰 만료 알림 수신자")
    public ApiResponse<Recipients> getRecipients() {
        return ApiResponse.ok(recipients.get());
    }

    @PutMapping("/cli-token-recipients")
    @Operation(summary = "1년 토큰 만료 알림 수신자 전체 바꾸기")
    public ApiResponse<Recipients> putRecipients(@RequestBody Recipients body) {
        return ApiResponse.ok(recipients.replace(body != null ? body : new Recipients(null, null)));
    }

    /** 토큰 값을 담는 요청. 기록에 찍히지 않게 문자열로 바꾸면 값을 가린다. */
    public record TokenRequest(String token) {
        @Override
        public String toString() { return "TokenRequest[token=***]"; }
    }
}
