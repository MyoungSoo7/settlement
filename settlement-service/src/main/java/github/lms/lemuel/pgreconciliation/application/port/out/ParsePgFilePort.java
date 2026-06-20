package github.lms.lemuel.pgreconciliation.application.port.out;

import github.lms.lemuel.pgreconciliation.domain.PgTransactionRow;

import java.io.InputStream;
import java.util.List;

/**
 * PG 정산 파일 (CSV / 고정폭 / JSON 등) 을 공통 도메인 모델로 변환하는 아웃바운드 포트.
 *
 * <p>각 PG 별 파일 형식이 다르므로 어댑터 구현체는 PG 별로 작성한다.
 * 본 프로젝트는 단순 CSV 파서를 기본 제공.
 */
public interface ParsePgFilePort {
    List<PgTransactionRow> parse(InputStream input);
}
