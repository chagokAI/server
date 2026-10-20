package com.skala.chagok.global.auth;

import com.skala.chagok.domain.user.entity.AppUser;
import com.skala.chagok.domain.user.repository.AppUserRepository;

import jakarta.persistence.EntityNotFoundException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

/**
 * 현재 로그인한 사용자를 돌려준다.
 *
 * <p>아직 인증이 없어 사번 순 첫 번째 사용자를 반환한다. 로그인 기능이 들어오면
 * 이 클래스 내부만 SecurityContext 조회로 교체하면 되고, 호출부는 그대로 둔다.
 */
@Component
@RequiredArgsConstructor
public class CurrentUserProvider {

    private final AppUserRepository appUserRepository;

    public AppUser getCurrentUser() {
        return appUserRepository.findFirstByOrderByEmployeeNoAsc()
                .orElseThrow(() -> new EntityNotFoundException("등록된 사용자가 없습니다."));
    }
}
