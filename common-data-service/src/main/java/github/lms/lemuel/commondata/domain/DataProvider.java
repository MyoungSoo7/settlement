package github.lms.lemuel.commondata.domain;

/**
 * 데이터소스가 따르는 공공데이터 제공처(응답 봉투·인증 방식) 구분.
 *
 * <ul>
 *   <li>{@link #DATA_GO_KR} — 공공데이터포털 표준 봉투. 인증키는 쿼리 파라미터
 *       {@code serviceKey}, 봉투는 {@code response.header.resultCode="00"} + {@code body.items.item[]}.</li>
 *   <li>{@link #SEOUL_OPENAPI} — 서울 열린데이터광장. 인증키가 URL 경로에 들어가며
 *       ({@code /{KEY}/json/{SERVICE}/{START}/{END}}), 봉투는 {@code {서비스명: {list_total_count,
 *       RESULT.CODE="INFO-000", row[]}}}.</li>
 * </ul>
 */
public enum DataProvider {

    DATA_GO_KR,
    SEOUL_OPENAPI;

    /** 등록 요청 문자열 → enum. null/blank 는 기본 {@link #DATA_GO_KR}, 미지원 값은 400 유도. */
    public static DataProvider parse(String value) {
        if (value == null || value.isBlank()) {
            return DATA_GO_KR;
        }
        try {
            return valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "지원하지 않는 provider 입니다 (DATA_GO_KR|SEOUL_OPENAPI): " + value);
        }
    }
}
