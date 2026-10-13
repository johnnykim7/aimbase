package com.platform.auth;

import com.platform.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * CR-022 리소스를 만든 사람(created_by).
 *
 * <p>테넌트 테이블의 created_by 는 users(id) 를 참조한다. 시스템 API 키와 임퍼소네이션 중인
 * super admin(CR-096)은 그 테넌트 users 에 없어서 그대로 적으면 저장이 외래키로 실패한다.
 */
@Component
public class ResourceOwner {

    private final UserRepository userRepository;

    public ResourceOwner(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 내 것만 보기의 기준. 시스템 API 키는 null. */
    public String currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal up) {
            String id = up.getId();
            return id == null || id.startsWith("system-") ? null : id;
        }
        return null;
    }

    /** 저장할 created_by. 테넌트 users 에 없는 사용자면 null. */
    public String ownerIdToRecord() {
        String id = currentUserId();
        return id != null && userRepository.existsById(id) ? id : null;
    }
}
