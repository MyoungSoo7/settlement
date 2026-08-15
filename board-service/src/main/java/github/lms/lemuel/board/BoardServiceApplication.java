package github.lms.lemuel.board;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * board-service 독립 부팅 진입점.
 *
 * <p>메타 주도 게시판 플랫폼 — {@code board_definitions} 1행이 게시판 1개이고, 프론트의 단일
 * 라우트 {@code /boards/:boardKey} 가 정의를 읽어 스킨을 바꿔 그린다. 자체 DB(lemuel_board) 를
 * 소유하는 DB-per-service.
 *
 * <p>★ 스캔 범위를 {@code github.lms.lemuel.board} 로 <b>한정</b>한다(company-service 와 같은
 * 격리 철학). 루트에서 스캔하면 shared-common 의 Outbox·멱등·Audit 엔티티가 함께 잡히고,
 * {@code ddl-auto=validate} 가 lemuel_board 에 없는 테이블을 찾다 기동이 깨진다. 그렇다고 쓰지도
 * 않을 {@code outbox_events}·{@code processed_events} 를 만들어 두면 다음 사람은 이 서비스가
 * 이벤트를 발행한다고 오해한다 — <b>이 서비스는 발행 0·소비 0 이다</b>(docs/plan/board-service.md §3).
 *
 * <p>필요한 shared-common 빈(JWT 검증)은 {@code config.SecurityConfig} 가 명시적으로
 * {@code @Import} 한다. JPA 스캔은 {@code config.PersistenceConfig} 로 분리했다 —
 * 앱 클래스에 {@code @EnableJpaRepositories} 가 붙으면 {@code @WebMvcTest} 슬라이스가 JPA 를
 * 강제로 물어 컨텍스트가 깨진다.
 */
@SpringBootApplication(scanBasePackages = "github.lms.lemuel.board")
public class BoardServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(BoardServiceApplication.class, args);
    }
}
