package github.lms.lemuel.user.application.service;

import github.lms.lemuel.user.application.port.in.CreateUserUseCase;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.application.port.out.PasswordHashPort;
import github.lms.lemuel.user.application.port.out.SaveUserPort;
import github.lms.lemuel.user.domain.User;
import github.lms.lemuel.user.domain.exception.DuplicateEmailException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CreateUserService implements CreateUserUseCase {

    private final LoadUserPort loadUserPort;
    private final SaveUserPort saveUserPort;
    private final PasswordHashPort passwordHashPort;

    @Override
    public User createUser(CreateUserCommand command) {
        log.info("회원가입 시작: email={}", command.email());

        // 1. 이메일 중복 확인
        if (loadUserPort.findByEmail(command.email()).isPresent()) {
            log.warn("이메일 중복: email={}", command.email());
            throw new DuplicateEmailException(command.email());
        }

        // 2. 비밀번호 해싱
        String hashedPassword = passwordHashPort.hash(command.rawPassword());

        // 3. User 도메인 생성 (도메인 검증 수행)
        User user = User.createWithRole(command.email(), hashedPassword, command.role());

        // 4. 저장
        User savedUser = saveUserPort.save(user);

        log.info("회원가입 완료: userId={}, email={}", savedUser.getId(), savedUser.getEmail());

        return savedUser;
    }
}
