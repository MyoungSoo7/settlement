package github.lms.lemuel.company.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CompanyDocumentRepository extends JpaRepository<CompanyDocumentJpaEntity, Long> {

    Optional<CompanyDocumentJpaEntity> findByStockCodeAndFileName(String stockCode, String fileName);

    @Query("""
            select new github.lms.lemuel.company.adapter.out.persistence.CompanyDocumentMeta(
                d.id, d.stockCode, d.title, d.fileName, d.contentType, d.sizeBytes, d.uploadedAt)
            from CompanyDocumentJpaEntity d
            where d.stockCode = :stockCode
            order by d.uploadedAt desc, d.id desc
            """)
    List<CompanyDocumentMeta> findMetaByStockCode(@Param("stockCode") String stockCode);
}
