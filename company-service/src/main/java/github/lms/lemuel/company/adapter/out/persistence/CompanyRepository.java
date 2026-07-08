package github.lms.lemuel.company.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyRepository extends JpaRepository<CompanyJpaEntity, String> {

    // ★ ":keyword IS NULL OR ..." 패턴은 Postgres 가 null 파라미터를 bytea 로 추론해
    //   lower(bytea) 오류를 낸다 — null 분기는 어댑터에서 findAll 로 처리하고 여기선 non-null 전제.
    @Query("""
            SELECT c FROM CompanyJpaEntity c
            WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR c.stockCode LIKE CONCAT('%', :keyword, '%')
            ORDER BY c.name ASC
            """)
    Page<CompanyJpaEntity> search(@Param("keyword") String keyword, Pageable pageable);
}
