package github.lms.lemuel.commondata.domain;

import java.time.Instant;

/**
 * 데이터소스에서 수집한 아이템 1건.
 *
 * <p>payload 는 data.go.kr 응답 아이템의 JSON 원문을 그대로 보존한다 — 범용 커넥터라
 * 도메인 특화 스키마를 강제하지 않는다. {@code (sourceCode, recordKey)} 가 멱등 재수집의
 * 자연키이며, 재수집은 payload/collectedAt 갱신으로 흡수된다.
 */
public record DataRecord(Long id, String sourceCode, String recordKey,
                         String payload, Instant collectedAt) {

    public DataRecord {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("sourceCode 은(는) 필수입니다");
        }
        if (recordKey == null || recordKey.isBlank()) {
            throw new IllegalArgumentException("recordKey 은(는) 필수입니다");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload 은(는) 필수입니다");
        }
    }
}
