package github.lms.lemuel.integrity.application.service;

import github.lms.lemuel.integrity.application.port.in.ProjectionReconciliationUseCase;
import github.lms.lemuel.integrity.application.port.out.IntegrityQueryPort;
import github.lms.lemuel.integrity.application.port.out.KeyChecksum;
import github.lms.lemuel.integrity.application.port.out.LoadOrderPaymentKeysPort;
import github.lms.lemuel.integrity.application.port.out.PaymentKey;
import github.lms.lemuel.integrity.domain.ProjectionDiffReport;
import github.lms.lemuel.integrity.domain.ProjectionDiffReport.AmountMismatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * INV-12 프로젝션 행 diff 서비스 (Phase C) — order 원천 결제 키와 프로젝션 키를 하이브리드로 대사한다.
 *
 * <p>1차: 양측 키셋 체크섬(count·금액합·정렬 id md5)을 대조 → 같으면 행 diff 없이 통과(설계서 §5 데이터량 회피).
 * 2차: 어긋나면 양측 키를 id 키셋 페이지네이션으로 수집해 집합 diff → 누락/고아/금액불일치 id 를 특정한다.
 * order 는 기존 recon 클라이언트(타임아웃·재시도 내장)로, 프로젝션은 자기 settlement_db 로만 읽어 cross-DB 0.
 */
@Service
@Transactional(readOnly = true)
public class ProjectionReconciliationService implements ProjectionReconciliationUseCase {

    /** 지원하는 프로젝션 대상 — 현재는 결제 뷰(돈이 흐르는 축)만. */
    private static final String PAYMENT_ENTITY = "payment";
    /** 보고할 id 상위 N 건 기본 상한. */
    private static final int DEFAULT_LIMIT = 100;
    /** 키 페이지 크기 (order HTTP·프로젝션 SQL 공통). */
    private static final int PAGE_SIZE = 1000;
    /** 한쪽 키 수집 상한 — 초과 시 truncated 로 표시하고 부분 검사임을 알린다. */
    private static final int MAX_ROWS = 50_000;

    private final LoadOrderPaymentKeysPort orderKeysPort;
    private final IntegrityQueryPort queryPort;

    public ProjectionReconciliationService(LoadOrderPaymentKeysPort orderKeysPort,
                                           IntegrityQueryPort queryPort) {
        this.orderKeysPort = orderKeysPort;
        this.queryPort = queryPort;
    }

    @Override
    public ProjectionDiffReport reconcileProjection(LocalDate date, String entity, Integer limitOverride) {
        if (date == null) {
            throw new IllegalArgumentException("date 는 필수입니다");
        }
        String normalized = (entity == null || entity.isBlank()) ? PAYMENT_ENTITY : entity;
        if (!PAYMENT_ENTITY.equals(normalized)) {
            throw new IllegalArgumentException("지원하지 않는 프로젝션 대상입니다: " + entity + " (현재 payment 만 지원)");
        }
        int limit = (limitOverride != null && limitOverride > 0) ? limitOverride : DEFAULT_LIMIT;

        // 1차 스크리닝 — 체크섬이 같으면 데이터량 부담 없이 통과.
        KeyChecksum order = orderKeysPort.checksum(date);
        KeyChecksum projection = queryPort.projectionPaymentChecksum(date);
        if (order.matches(projection)) {
            return ProjectionDiffReport.matched(date, PAYMENT_ENTITY, order.count(), order.amountSum());
        }

        // 2차 — 키를 양측에서 수집해 id 집합 diff. 누락/고아/금액불일치를 특정한다.
        Collected orderKeys = collect((afterId, size) -> orderKeysPort.keys(date, afterId, size));
        Collected projKeys = collect((afterId, size) -> queryPort.projectionPaymentKeys(date, afterId, size));

        List<Long> missingIds = new ArrayList<>();
        BigDecimal missingAmount = BigDecimal.ZERO;
        long missingTotal = 0;
        List<AmountMismatch> mismatches = new ArrayList<>();
        long mismatchTotal = 0;
        for (Map.Entry<Long, BigDecimal> e : orderKeys.map().entrySet()) {
            BigDecimal projAmount = projKeys.map().get(e.getKey());
            if (projAmount == null) {
                missingTotal++;
                missingAmount = missingAmount.add(e.getValue());
                if (missingIds.size() < limit) {
                    missingIds.add(e.getKey());
                }
            } else if (projAmount.compareTo(e.getValue()) != 0) {
                mismatchTotal++;
                if (mismatches.size() < limit) {
                    mismatches.add(new AmountMismatch(e.getKey(), e.getValue(), projAmount));
                }
            }
        }

        List<Long> orphanIds = new ArrayList<>();
        long orphanTotal = 0;
        for (Long id : projKeys.map().keySet()) {
            if (!orderKeys.map().containsKey(id)) {
                orphanTotal++;
                if (orphanIds.size() < limit) {
                    orphanIds.add(id);
                }
            }
        }

        boolean truncated = orderKeys.truncated() || projKeys.truncated();
        return ProjectionDiffReport.of(date, PAYMENT_ENTITY,
                order.count(), order.amountSum(), projection.count(), projection.amountSum(),
                missingIds, missingAmount, missingTotal,
                orphanIds, orphanTotal,
                mismatches, mismatchTotal, truncated);
    }

    /** id 키셋 페이지네이션으로 키를 모두(상한 내) 수집한다. 반환은 id 오름차순 삽입 순서 유지. */
    private Collected collect(BiFunction<Long, Integer, List<PaymentKey>> pageSource) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        long afterId = 0;
        boolean truncated = false;
        while (true) {
            List<PaymentKey> page = pageSource.apply(afterId, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (PaymentKey k : page) {
                map.put(k.paymentId(), k.amount());
                if (k.paymentId() > afterId) {
                    afterId = k.paymentId();
                }
            }
            if (page.size() < PAGE_SIZE) {
                break;
            }
            if (map.size() >= MAX_ROWS) {
                truncated = true;
                break;
            }
        }
        return new Collected(map, truncated);
    }

    private record Collected(Map<Long, BigDecimal> map, boolean truncated) {
    }
}
