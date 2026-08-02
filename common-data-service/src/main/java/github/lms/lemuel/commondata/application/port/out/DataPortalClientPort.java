package github.lms.lemuel.commondata.application.port.out;

import github.lms.lemuel.commondata.domain.DataProvider;
import github.lms.lemuel.commondata.domain.DataSource;

import java.util.List;
import java.util.Map;

/**
 * 공공데이터 수집 HTTP 클라이언트 포트 — 제공처({@link DataProvider})별 구현이 1개씩 있고,
 * 수집 시 소스의 provider 와 일치하는 구현이 선택된다.
 */
public interface DataPortalClientPort {

    /** 이 클라이언트가 담당하는 제공처. */
    DataProvider provider();

    /** 제공처 인증키 설정 여부 — 미설정이면 해당 provider 수집 비활성. */
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
