package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.MerchantPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * merchant_policies 테이블 매핑 (V6).
 * MCC 목록은 쉼표 구분 문자열로 저장한다 — 목록이 짧고(수십 개 이내) 변경 빈도가 낮아
 * 별도 조인 테이블보다 단순한 접근이 관리 비용 대비 이득이 크다.
 */
@Entity
@Table(name = "merchant_policies")
public class MerchantPolicyJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_account_id", nullable = false)
    private Long cardAccountId;

    @Column(name = "card_id")
    private Long cardId;    // null = 계정 단위 정책

    @Column(name = "blocked_mccs")
    private String blockedMccs;     // 쉼표 구분

    @Column(name = "allowed_mccs")
    private String allowedMccs;     // 쉼표 구분, 비면 전체 허용

    @Column(name = "max_per_transaction_amount", precision = 19, scale = 2)
    private BigDecimal maxPerTransactionAmount;

    @Column(name = "daily_spend_limit", precision = 19, scale = 2)
    private BigDecimal dailySpendLimit;

    @Column(name = "monthly_spend_limit", precision = 19, scale = 2)
    private BigDecimal monthlySpendLimit;

    @Column(name = "overseas_enabled", nullable = false)
    private boolean overseasEnabled = true;

    @Column(name = "online_enabled", nullable = false)
    private boolean onlineEnabled = true;

    protected MerchantPolicyJpaEntity() {
    }

    public static MerchantPolicyJpaEntity fromDomain(MerchantPolicy p) {
        MerchantPolicyJpaEntity e = new MerchantPolicyJpaEntity();
        e.id = p.getId();
        e.cardAccountId = p.getCardAccountId();
        e.cardId = p.getCardId();
        e.blockedMccs = joinMccs(p.getBlockedMccs());
        e.allowedMccs = joinMccs(p.getAllowedMccs());
        e.maxPerTransactionAmount = p.getMaxPerTransactionAmount();
        e.dailySpendLimit = p.getDailySpendLimit();
        e.monthlySpendLimit = p.getMonthlySpendLimit();
        e.overseasEnabled = p.isOverseasEnabled();
        e.onlineEnabled = p.isOnlineEnabled();
        return e;
    }

    public MerchantPolicy toDomain() {
        return MerchantPolicy.builder()
                .id(id)
                .cardAccountId(cardAccountId)
                .cardId(cardId)
                .blockedMccs(splitMccs(blockedMccs))
                .allowedMccs(splitMccs(allowedMccs))
                .maxPerTransactionAmount(maxPerTransactionAmount)
                .dailySpendLimit(dailySpendLimit)
                .monthlySpendLimit(monthlySpendLimit)
                .overseasEnabled(overseasEnabled)
                .onlineEnabled(onlineEnabled)
                .build();
    }

    private static String joinMccs(Set<String> mccs) {
        if (mccs == null || mccs.isEmpty()) return null;
        return String.join(",", mccs);
    }

    private static Set<String> splitMccs(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptySet();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
