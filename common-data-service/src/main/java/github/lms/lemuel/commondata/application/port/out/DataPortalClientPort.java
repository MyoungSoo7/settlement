package github.lms.lemuel.commondata.application.port.out;

import github.lms.lemuel.commondata.domain.DataSource;

import java.util.List;
import java.util.Map;

/**
 * 공공데이터포털(data.go.kr) 표준 봉투 HTTP 클라이언트 포트.
 */
public interface DataPortalClientPort {

    /** 인증키(DATA_GO_KR_API_KEY) 설정 여부 — 미설정이면 수집 비활성(시드로만 동작). */
    boolean isConfigured();

    /**
     * 소스의 전 페이지를 순회해 아이템을 수집한다.
     *
     * @param overrideParams 소스 defaultParams 위에 덮어쓸 호출 파라미터
     */
    List<PortalItem> fetchItems(DataSource source, Map<String, String> overrideParams);

    /**
     * @param recordKey   keyFields 값 조인(구분자 {@code |}) — 키 필드 결측 시 payload SHA-256
     * @param payloadJson 아이템 JSON 원문
     */
    record PortalItem(String recordKey, String payloadJson) { }
}
