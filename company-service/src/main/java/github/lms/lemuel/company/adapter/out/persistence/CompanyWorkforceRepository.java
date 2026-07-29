package github.lms.lemuel.company.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CompanyWorkforceRepository extends JpaRepository<CompanyWorkforceJpaEntity, Long> {

    // ★ ":workplaceName IS NULL OR ..." 패턴은 Postgres 가 null 파라미터를 bytea 로 추론해
    //   lower(bytea) 오류를 낸다(CompanyRepository 와 동일 함정) — null 분기는 어댑터에서
    //   findAll 로 처리하고 여기선 non-null 전제.
    @Query("""
            SELECT w FROM CompanyWorkforceJpaEntity w
            WHERE LOWER(w.workplaceName) LIKE LOWER(CONCAT('%', :workplaceName, '%'))
            ORDER BY w.workplaceName ASC
            """)
    Page<CompanyWorkforceJpaEntity> search(@Param("workplaceName") String workplaceName, Pageable pageable);

    /** 단건 상세 조회의 업무 복합키 — UNIQUE(workplace_name, biz_reg_no_prefix, snapshot_month) 를 그대로 탄다. */
    Optional<CompanyWorkforceJpaEntity> findByWorkplaceNameAndBizRegNoPrefixAndSnapshotMonth(
            String workplaceName, String bizRegNoPrefix, String snapshotMonth);
}
