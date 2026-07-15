package github.lms.lemuel.user.domain;
import github.lms.lemuel.user.domain.exception.InvalidMembershipStateException;
import github.lms.lemuel.user.domain.exception.UserInvariantViolationException;

import lombok.Getter;
import java.time.LocalDateTime;

/**
 * User Domain Entity (순수 POJO, 스프링/JPA 의존성 없음)
 * DB 스키마: id, email, password, role, name, phone_number, is_active, created_at, updated_at
 */
@Getter
public class User {

    private Long id;
    private String email;
    private String passwordHash;
    private UserRole role;
    private String name;
    private String phoneNumber;
    private boolean active;
    private MembershipStatus membershipStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 기본 생성자
    public User() {
        this.role = UserRole.USER;
        this.active = true;
        this.membershipStatus = MembershipStatus.APPROVED;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 전체 생성자
    public User(Long id, String email, String passwordHash, UserRole role,
                LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, email, passwordHash, role, null, null, true, createdAt, updatedAt);
    }

    public User(Long id, String email, String passwordHash, UserRole role,
                String name, String phoneNumber, Boolean active,
                LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role != null ? role : UserRole.USER;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.active = active == null || active;
        this.membershipStatus = MembershipStatus.APPROVED;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static User create(String email, String passwordHash) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.validateEmail();
        user.validatePasswordHash();
        return user;
    }

    public static User createWithRole(String email, String passwordHash, UserRole role) {
        User user = create(email, passwordHash);
        user.role = role;
        return user;
    }

    /**
     * 영속 레코드 복원 팩토리(매퍼의 toDomain 전용). 저장된 필드를 그대로 재구성한다.
     * membershipStatus 를 포함해 DB 값을 그대로 복원한다(생성자는 APPROVED 로 강제하므로 사용 불가).
     */
    public static User rehydrate(Long id, String email, String passwordHash, UserRole role,
                                 String name, String phoneNumber, boolean active,
                                 MembershipStatus membershipStatus,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {
        User user = new User();
        user.id = id;
        user.email = email;
        user.passwordHash = passwordHash;
        user.role = role != null ? role : UserRole.USER;
        user.name = name;
        user.phoneNumber = phoneNumber;
        user.active = active;
        user.membershipStatus = membershipStatus != null ? membershipStatus : MembershipStatus.APPROVED;
        user.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        user.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
        return user;
    }

    /** 영속 저장이 부여한 식별자를 1회 부여한다(식별자는 불변 — 이미 부여됐으면 거부). */
    public void assignId(Long id) {
        if (this.id != null && !this.id.equals(id)) {
            throw new UserInvariantViolationException("User id is already assigned and immutable");
        }
        this.id = id;
    }

    public static User createWithProfile(String email, String passwordHash, UserRole role,
                                         String name, String phoneNumber) {
        User user = createWithRole(email, passwordHash, role);
        user.updateProfile(name, phoneNumber);
        return user;
    }

    // 도메인 규칙: 이메일 유효성 검증
    public void validateEmail() {
        if (email == null || email.isBlank()) {
            throw new UserInvariantViolationException("Email cannot be empty");
        }
        if (!email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            throw new UserInvariantViolationException("Invalid email format");
        }
    }

    // 도메인 규칙: 비밀번호 해시 검증
    public void validatePasswordHash() {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new UserInvariantViolationException("Password hash cannot be empty");
        }
    }

    // 비즈니스 메서드
    public void changeRole(UserRole newRole) {
        this.role = newRole;
        this.updatedAt = LocalDateTime.now();
    }

    public void updatePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateProfile(String name, String phoneNumber) {
        if (name != null) {
            String trimmed = name.trim();
            if (trimmed.length() > 100) {
                throw new UserInvariantViolationException("Name must not exceed 100 characters");
            }
            this.name = trimmed.isEmpty() ? null : trimmed;
        }
        if (phoneNumber != null) {
            String trimmed = phoneNumber.trim();
            if (!trimmed.isEmpty() && !trimmed.matches("^[0-9+\\-() ]{8,30}$")) {
                throw new UserInvariantViolationException("Invalid phone number format");
            }
            this.phoneNumber = trimmed.isEmpty() ? null : trimmed;
        }
        this.updatedAt = LocalDateTime.now();
    }

    public void deactivate() {
        this.active = false;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isAdmin() {
        return this.role == UserRole.ADMIN;
    }

    // ── 회원 승인 워크플로 ────────────────────────────────────
    // 상태머신: PENDING → APPROVED → SUSPENDED → APPROVED ; PENDING → REJECTED

    /** 업체 회원/시공기사는 가입 후 관리자 승인을 거쳐야 한다. */
    public boolean requiresApproval() {
        return this.role == UserRole.COMPANY || this.role == UserRole.TECHNICIAN;
    }

    /** 승인 대기 상태로 전환 (승인이 필요한 역할의 가입 시점). */
    public void markPending() {
        this.membershipStatus = MembershipStatus.PENDING;
        this.updatedAt = LocalDateTime.now();
    }

    /** 승인: PENDING → APPROVED */
    public void approveMembership() {
        requireMembership(MembershipStatus.PENDING);
        transitionMembership(MembershipStatus.APPROVED);
    }

    /** 반려: PENDING → REJECTED */
    public void rejectMembership() {
        requireMembership(MembershipStatus.PENDING);
        transitionMembership(MembershipStatus.REJECTED);
    }

    /** 정지: APPROVED → SUSPENDED */
    public void suspendMembership() {
        requireMembership(MembershipStatus.APPROVED);
        transitionMembership(MembershipStatus.SUSPENDED);
    }

    /** 정지 해제: SUSPENDED → APPROVED */
    public void reinstateMembership() {
        requireMembership(MembershipStatus.SUSPENDED);
        transitionMembership(MembershipStatus.APPROVED);
    }

    public boolean canUseService() {
        return this.membershipStatus != null && this.membershipStatus.canUseService();
    }

    private void requireMembership(MembershipStatus expected) {
        if (this.membershipStatus != expected) {
            throw new InvalidMembershipStateException(this.membershipStatus, expected);
        }
    }

    private void transitionMembership(MembershipStatus next) {
        this.membershipStatus = next;
        this.updatedAt = LocalDateTime.now();
    }

}
