package com.skala.chagok.global.auth;

import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.domain.user.repository.AppUserRepository;

import jakarta.persistence.EntityNotFoundException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

/** 세션의 사용자 ID를 조회한다. 인증 비활성 모드는 명시적인 로컬 테스트용이다. */
@Component
@RequiredArgsConstructor
public class CurrentUserProvider {

    private final AppUserRepository appUserRepository;

    @org.springframework.beans.factory.annotation.Value("${chagok.auth.enabled:true}")
    private boolean authEnabled = true;

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public AppUser getCurrentUser() {
        if (authEnabled) {
            var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated() || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
                throw new com.skala.chagok.global.error.BusinessException(com.skala.chagok.global.error.ErrorCode.UNAUTHENTICATED);
            try {
                return appUserRepository.findById(java.util.UUID.fromString(authentication.getName()))
                        .orElseThrow(() -> new com.skala.chagok.global.error.BusinessException(com.skala.chagok.global.error.ErrorCode.UNAUTHENTICATED));
            } catch (IllegalArgumentException e) {
                throw new com.skala.chagok.global.error.BusinessException(com.skala.chagok.global.error.ErrorCode.UNAUTHENTICATED);
            }
        }
        return appUserRepository.findFirstByOrderByEmployeeNoAsc()
                .orElseThrow(() -> new EntityNotFoundException("등록된 사용자가 없습니다."));
    }
}
