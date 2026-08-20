package github.lms.lemuel.education;

import github.lms.lemuel.common.exception.GlobalExceptionHandler;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * education-service 독립 부팅 진입점.
 *
 * <p>스캔 범위를 {@code github.lms.lemuel.education} 으로 <b>한정</b>하므로 shared-common 의 빈은
 * 자동으로 붙지 않는다 — 필요한 것만 명시적으로 {@code @Import} 한다(JWT 스택은
 * {@code config.SecurityConfig} 가 담당).
 *
 * <p>{@link GlobalExceptionHandler} 를 여기서 물리는 이유: 이게 없으면 404/405 같은 기술 예외가
 * 스프링 기본 응답으로 나가 {@code errorCode} 가 빠진다. 상태 코드는 맞아서 눈에 잘 띄지 않지만,
 * 공용 클라이언트는 education 응답만 따로 파싱해야 했다 — 2026-08-20 전 서비스 405 점검에서
 * 18개 중 유일하게 어긋난 서비스로 드러났다.
 */
@SpringBootApplication(scanBasePackages = "github.lms.lemuel.education")
@ConfigurationPropertiesScan("github.lms.lemuel.education.config")
@Import(GlobalExceptionHandler.class)
public class EducationServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(entry -> System.setProperty(entry.getKey(), entry.getValue()));
        SpringApplication.run(EducationServiceApplication.class, args);
    }
}
