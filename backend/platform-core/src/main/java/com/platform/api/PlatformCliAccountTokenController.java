package com.platform.api;

import com.platform.clitoken.CliAccountTokenService;
import com.platform.clitoken.TokenRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CR-196 PRD-647: 모든 테넌트의 실행기 계정 폴더 1년 토큰 목록. Master DB 만 본다(BIZ-024). */
@RestController
@RequestMapping("/api/v1/platform/cli-account-tokens")
@Tag(name = "Platform CLI Account Tokens", description = "실행기 계정 토큰 (CR-196)")
public class PlatformCliAccountTokenController {

    private final CliAccountTokenService tokens;

    public PlatformCliAccountTokenController(CliAccountTokenService tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    @Operation(summary = "모든 테넌트 실행기 계정 폴더의 1년 토큰 상태")
    public ApiResponse<List<TokenRow>> list() {
        return ApiResponse.ok(tokens.listAllTenants());
    }
}
