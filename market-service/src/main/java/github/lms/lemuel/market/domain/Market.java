package github.lms.lemuel.market.domain;

/**
 * 상장 시장 구분.
 *
 * <p>외부 피드의 시장 코드 문자열을 이 enum 으로 번역하는 책임은 도메인이 아니라 어댑터에 있다
 * (예: {@code KrxApiClient.toMarket}). 도메인이 특정 공급자의 코드 체계를 알면, 공급자가 바뀔 때
 * 도메인이 함께 흔들린다.
 */
public enum Market {
    KOSPI, KOSDAQ, KONEX
}
