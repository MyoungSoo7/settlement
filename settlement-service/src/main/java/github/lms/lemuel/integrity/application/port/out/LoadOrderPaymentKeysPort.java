package github.lms.lemuel.integrity.application.port.out;

import java.time.LocalDate;
import java.util.List;

/**
 * order 원천 결제 키를 얻는 아웃바운드 포트 (INV-12) — 내부 대사 API
 * {@code /internal/recon/payment-keys-checksum}·{@code /payment-keys} 프록시.
 * 양측 모두 자기 DB 만 읽는다 (cross-DB 0, ADR 0020).
 *
 * <p>먼저 {@link #checksum(LocalDate)} 로 3-스칼라 요약을 받아 프로젝션과 대조하고,
 * 어긋날 때만 {@link #keys(LocalDate, long, int)} 로 실제 키 페이지를 당겨 diff 한다.
 */
public interface LoadOrderPaymentKeysPort {

    /** 해당 날짜 order 캡처 결제 키셋 체크섬 (1차 스크리닝). */
    KeyChecksum checksum(LocalDate date);

    /** afterId 초과 결제 키 페이지 (id 오름차순, 최대 limit 건). */
    List<PaymentKey> keys(LocalDate date, long afterId, int limit);
}
