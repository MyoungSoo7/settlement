package github.lms.lemuel.card.domain;

import github.lms.lemuel.card.domain.exception.InvalidCardTransitionException;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 임직원 카드 애그리거트(순수 POJO — 프레임워크 의존 0). CardAccount(마스터 한도) 아래에서
 * 서브한도를 배분받아 발급된다. 카드번호는 호출자가 이미 마스킹한 값만 받는다 — 원본 PAN 은
 * 이 도메인에 들어오지 않는다(PCI 스코프 축소).
 *
 * <pre>
 * ISSUED ⇄ SUSPENDED
 *   ↘        ↙
 *    CANCELED
 * </pre>
 *
 * <p>{@link #suspend()} 는 멱등이다 — organization-service 의 {@code member_removed} 이벤트가
 * at-least-once 로 재수신될 수 있어, 이미 SUSPENDED 인 카드를 다시 정지해도 조용히 무시해야 한다.
 * 반대로 {@link #cancel()} 은 멱등이 아니다 — 해지는 명시적 운영 행위라 중복 요청은 오류로 드러나야 한다.
 */
public class Card {

    private Long id;
    private final Long cardAccountId;
    private final Long holderUserId;
    private final String maskedCardNo;
    private BigDecimal subLimit;
    private CardStatus status;
    private long version;

    private Card(Builder b) {
        this.id = b.id;
        this.cardAccountId = Objects.requireNonNull(b.cardAccountId, "cardAccountId");
        this.holderUserId = Objects.requireNonNull(b.holderUserId, "holderUserId");
        this.maskedCardNo = requireMasked(b.maskedCardNo);
        this.subLimit = requireNonNegative(b.subLimit);
        this.status = Objects.requireNonNull(b.status, "status");
        this.version = b.version;
    }

    /** 카드 발급 — 기본 상태 ISSUED. */
    public static Card issue(Long cardAccountId, Long holderUserId, String maskedCardNo, BigDecimal subLimit) {
        return builder()
                .cardAccountId(cardAccountId)
                .holderUserId(holderUserId)
                .maskedCardNo(maskedCardNo)
                .subLimit(subLimit)
                .status(CardStatus.ISSUED)
                .build();
    }

    /** 서브한도 변경. CardAccount.assertCanIssue 로 마스터 한도와의 불변식은 응용 계층이 먼저 검증한다. */
    public void changeSubLimit(BigDecimal newSubLimit) {
        requireMutable();
        this.subLimit = requireNonNegative(newSubLimit);
    }

    /** 정지 — 멱등. 이미 SUSPENDED 면 아무 일도 하지 않는다(이벤트 재수신 대비). */
    public void suspend() {
        if (status == CardStatus.SUSPENDED) {
            return;
        }
        transitionTo(CardStatus.SUSPENDED);
    }

    public void resume() {
        transitionTo(CardStatus.ISSUED);
    }

    /** 해지 — 멱등이 아니다. 이미 CANCELED 인 카드를 다시 해지하면 예외로 드러난다. */
    public void cancel() {
        transitionTo(CardStatus.CANCELED);
    }

    // ── 상태 전이 가드 ──

    private void transitionTo(CardStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidCardTransitionException(status, target);
        }
        this.status = target;
    }

    private void requireMutable() {
        if (status == CardStatus.CANCELED) {
            throw new InvalidCardTransitionException("CANCELED 카드는 변경할 수 없습니다. 현재=" + status);
        }
    }

    private static BigDecimal requireNonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("서브한도는 음수일 수 없습니다: " + value);
        }
        return value;
    }

    // ★ "연속 숫자 N자리"만 보면 구분자(공백·대시)로 4자리씩 묶인 미마스킹 PAN을 놓친다
    // ("5678 9012 3456 7890", "5678-9012-3456-7890" 모두 연속 숫자는 4자리뿐이라 통과해버림 —
    // 실제 카드가 표시되는 표준 형태가 그대로 이 게이트를 통과하는 거짓 음성). 그래서 구분자를
    // 전부 제거한 뒤 남은 숫자 개수로 판단한다.
    //
    // 임계값 12 인 이유: PAN(카드 실번호)은 통상 13~19자리라 숫자 12자리 이하는 나올 수 없다.
    // 반대로 PCI 가 허용하는 표시 형식 중 "첫6자리+마지막4자리"(예: "123456******7890")는 숫자가
    // 정확히 10자리인 정당한 마스킹이라 반드시 통과해야 한다(거짓 양성 금지). 13(PAN 최소)과
    // 10(PCI 표시 최대) 사이인 12를 기준으로 "12자리 이상이면 거부"로 두면 양쪽을 다 만족한다.
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");
    private static final int UNMASKED_PAN_DIGIT_THRESHOLD = 12;

    /**
     * 마스킹된 카드번호만 허용 — 구분자를 제거한 뒤 남은 숫자가
     * {@value #UNMASKED_PAN_DIGIT_THRESHOLD}자리 이상이면 원본 PAN 유출로 간주해 거부한다.
     * PAN 은 이 도메인에 들어오지 않아야 한다(PCI 스코프 축소, 클래스 javadoc 참조).
     */
    private static String requireMasked(String maskedCardNo) {
        if (maskedCardNo == null || maskedCardNo.isBlank()) {
            throw new IllegalArgumentException("마스킹된 카드번호는 필수입니다");
        }
        String digitsOnly = NON_DIGIT.matcher(maskedCardNo).replaceAll("");
        if (digitsOnly.length() >= UNMASKED_PAN_DIGIT_THRESHOLD) {
            throw new IllegalArgumentException("마스킹되지 않은 카드번호(PAN)로 추정되어 거부합니다");
        }
        return maskedCardNo;
    }

    // ── getter ──

    public Long getId() {
        return id;
    }

    public Long getCardAccountId() {
        return cardAccountId;
    }

    public Long getHolderUserId() {
        return holderUserId;
    }

    public String getMaskedCardNo() {
        return maskedCardNo;
    }

    public BigDecimal getSubLimit() {
        return subLimit;
    }

    public CardStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 정적 팩토리(issue)와 영속성 어댑터의 재구성이 공용하는 빌더. */
    public static class Builder {
        private Long id;
        private Long cardAccountId;
        private Long holderUserId;
        private String maskedCardNo;
        private BigDecimal subLimit;
        private CardStatus status;
        private long version;

        public Builder id(Long v) {
            this.id = v;
            return this;
        }

        public Builder cardAccountId(Long v) {
            this.cardAccountId = v;
            return this;
        }

        public Builder holderUserId(Long v) {
            this.holderUserId = v;
            return this;
        }

        public Builder maskedCardNo(String v) {
            this.maskedCardNo = v;
            return this;
        }

        public Builder subLimit(BigDecimal v) {
            this.subLimit = v;
            return this;
        }

        public Builder status(CardStatus v) {
            this.status = v;
            return this;
        }

        public Builder version(long v) {
            this.version = v;
            return this;
        }

        public Card build() {
            return new Card(this);
        }
    }
}
