package github.lms.lemuel.commondata.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.time.Instant;

@Entity
@Table(name = "data_records")
public class DataRecordJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "record_key", nullable = false, length = 300)
    private String recordKey;

    /** 수집 아이템 JSON 원문. */
    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    protected DataRecordJpaEntity() {
    }

    Long getId() {
        return id;
    }

    String getRecordKey() {
        return recordKey;
    }

    String getPayload() {
        return payload;
    }

    Instant getCollectedAt() {
        return collectedAt;
    }

    static DataRecordJpaEntity create(Long sourceId, String recordKey) {
        DataRecordJpaEntity entity = new DataRecordJpaEntity();
        entity.sourceId = sourceId;
        entity.recordKey = recordKey;
        return entity;
    }

    void apply(String payload, Instant collectedAt) {
        this.payload = payload;
        this.collectedAt = collectedAt != null ? collectedAt : Instant.now();
    }
}
