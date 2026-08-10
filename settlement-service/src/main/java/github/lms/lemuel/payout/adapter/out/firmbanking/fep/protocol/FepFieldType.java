package github.lms.lemuel.payout.adapter.out.firmbanking.fep.protocol;

/**
 * 전문 필드 타입 — 은행 전문 관례를 따른다.
 *
 * <ul>
 *   <li>{@link #AN} — 영숫자·한글 혼용. EUC-KR 바이트 기준 좌측 정렬 + 우측 공백 패딩.</li>
 *   <li>{@link #N} — 숫자 전용. 우측 정렬 + 좌측 '0' 패딩 (금액·일련번호·일시).</li>
 * </ul>
 */
public enum FepFieldType {
    AN,
    N
}
