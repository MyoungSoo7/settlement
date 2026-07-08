package github.lms.lemuel.commondata.application.port.in;

import java.util.Map;

public interface SyncDataSourceUseCase {

    /**
     * 데이터소스 1개 수집 — overrideParams 는 소스 defaultParams 위에 덮어쓴다
     * (날짜/연도 의존 API 를 등록 변경 없이 호출하기 위한 통로).
     */
    SyncResult sync(String sourceCode, Map<String, String> overrideParams);
}
