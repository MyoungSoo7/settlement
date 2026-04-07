package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.SocialLoginUseCase;
import github.lms.lemuel.user.application.port.out.*;
import github.lms.lemuel.user.domain.SocialAccount;
import github.lms.lemuel.user.domain.SocialProvider;
import github.lms.lemuel.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 소셜 로그인 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SocialLoginService implements SocialLoginUseCase {

    private final SocialAuthPort socialAuthPort;
    private final LoadSocialAccountPort loadSocialAccountPort;
    private final SaveSocialAccountPort saveSocialAccountPort;
    private final LoadUserPort loadUserPort;
    private final SaveUserPort saveUserPort;
    private final PasswordHashPort passwordHashPort;
    private final TokenProviderPort tokenProviderPort;

    @Override
    @Transactional
    public LoginResult socialLogin(SocialLoginCommand command) {
        // 1. OAuth2 제공자로부터 사용자 정보 조회
        SocialAuthPort.SocialUserInfo socialUserInfo = socialAuthPort.authenticate(
                command.provider(), command.code(), command.redirectUri()
        );

        // 2. 기존 소셜 계정이 있는지 확인
        return loadSocialAccountPort.findByProviderAndProviderId(command.provider(), socialUserInfo.providerId())
                .map(existingAccount -> loginExistingUser(existingAccount, socialUserInfo))
                .orElseGet(() -> registerNewUser(command.provider(), socialUserInfo));
    }

    @Override
    @Transactional
    public SocialAccount linkSocialAccount(Long userId, SocialLoginCommand command) {
        // 1. 사용자 존재 확인
        User user = loadUserPort.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // 2. OAuth2 제공자로부터 사용자 정보 조회
        SocialAuthPort.SocialUserInfo socialUserInfo = socialAuthPort.authenticate(
                command.provider(), command.code(), command.redirectUri()
        );

        // 3. 이미 연동된 소셜 계정인지 확인
        if (loadSocialAccountPort.existsByProviderAndProviderId(command.provider(), socialUserInfo.providerId())) {
            throw new IllegalStateException("This social account is already linked to another user");
        }

        // 4. 소셜 계정 생성 및 저장
        SocialAccount socialAccount = SocialAccount.create(
                userId,
                command.provider(),
                socialUserInfo.providerId(),
                socialUserInfo.email(),
                socialUserInfo.name(),
                socialUserInfo.profileImage()
        );

        return saveSocialAccountPort.save(socialAccount);
    }

    @Override
    @Transactional
    public void unlinkSocialAccount(Long userId, SocialProvider provider) {
        // 사용자 존재 확인
        loadUserPort.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        saveSocialAccountPort.deleteByUserIdAndProvider(userId, provider);
        log.info("Unlinked social account: userId={}, provider={}", userId, provider);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SocialAccount> getSocialAccounts(Long userId) {
        return loadSocialAccountPort.findByUserId(userId);
    }

    /**
     * 기존 소셜 계정으로 로그인
     */
    private LoginResult loginExistingUser(SocialAccount existingAccount,
                                          SocialAuthPort.SocialUserInfo socialUserInfo) {
        // 프로필 정보 갱신
        existingAccount.updateProfile(socialUserInfo.name(), socialUserInfo.profileImage());
        saveSocialAccountPort.save(existingAccount);

        // 사용자 조회 및 토큰 생성
        User user = loadUserPort.findById(existingAccount.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found for social account: " + existingAccount.getId()));

        String token = tokenProviderPort.generateToken(user.getEmail(), user.getRole().name());
        return new LoginResult(token, user.getEmail(), user.getRole().name(), false);
    }

    /**
     * 신규 사용자 등록 (소셜 로그인 기반)
     */
    private LoginResult registerNewUser(SocialProvider provider,
                                        SocialAuthPort.SocialUserInfo socialUserInfo) {
        // 이메일로 기존 사용자 확인
        User user = loadUserPort.findByEmail(socialUserInfo.email())
                .orElseGet(() -> {
                    // 신규 사용자 생성 (랜덤 비밀번호 해시)
                    String randomPassword = passwordHashPort.hash(UUID.randomUUID().toString());
                    User newUser = User.create(socialUserInfo.email(), randomPassword);
                    return saveUserPort.save(newUser);
                });

        // 소셜 계정 생성
        SocialAccount socialAccount = SocialAccount.create(
                user.getId(),
                provider,
                socialUserInfo.providerId(),
                socialUserInfo.email(),
                socialUserInfo.name(),
                socialUserInfo.profileImage()
        );
        saveSocialAccountPort.save(socialAccount);

        // 토큰 생성
        String token = tokenProviderPort.generateToken(user.getEmail(), user.getRole().name());
        log.info("New user registered via social login: email={}, provider={}", user.getEmail(), provider);
        return new LoginResult(token, user.getEmail(), user.getRole().name(), true);
    }
}
