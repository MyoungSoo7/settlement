package github.lms.lemuel.market.domain;

/**
 * 시세 값의 출처.
 *
 * <ul>
 *   <li>{@code SAMPLE} — 실데이터가 없을 때 채워둔 근사 샘플. 신뢰해 계산에 쓰면 안 된다.</li>
 *   <li>{@code EXCHANGE} — 거래소 공시 실시세. upsert 로 SAMPLE 을 덮어쓴다.</li>
 * </ul>
 *
 * <p>어느 공급자(금융위 API 등)를 통해 받았는지는 여기 담지 않는다 — 그건 어댑터의 사정이고,
 * 도메인이 알아야 할 건 "이 값을 믿어도 되는가" 뿐이다.
 */
public enum ValueSource { SAMPLE, EXCHANGE }
