# Template Method Pattern

이 프로젝트에는 전형적인 사용자 정의 `abstract class` 기반 템플릿 메서드 구현보다는, Spring 프레임워크가 제공하는 템플릿 메서드 구조를 확장해서 사용하는 코드가 있다.

## 패턴 개요

템플릿 메서드 패턴은 상위 클래스가 전체 처리 흐름을 고정하고, 하위 클래스가 일부 단계만 오버라이드해서 세부 동작을 바꾸는 패턴이다.

이 프로젝트에서는 `OncePerRequestFilter`가 그 역할을 한다.

## 사용된 부분

### 1. `RateLimitFilter`

- 경로: `shared-common/src/main/java/github/lms/lemuel/common/ratelimit/RateLimitFilter.java`
- 핵심: `OncePerRequestFilter`를 상속하고 `doFilterInternal(...)`를 구현

```java
public class RateLimitFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        ...
    }
}
```

설명:
- 요청당 한 번만 실행되는 전체 필터 흐름은 `OncePerRequestFilter`가 관리한다.
- 실제 rate limit 검사 로직만 `RateLimitFilter`가 채워 넣는다.

### 2. `TraceIdFilter`

- 경로: `shared-common/src/main/java/github/lms/lemuel/common/config/observability/TraceIdFilter.java`
- 핵심: `doFilterInternal(...)`를 오버라이드해서 trace id 주입 로직 구현

설명:
- 필터 실행 시점, 한 번만 실행되도록 보장하는 공통 구조는 부모 클래스가 제공한다.
- 자식 클래스는 요청/응답에 trace id를 넣는 구체 로직만 담당한다.

### 3. `JwtAuthenticationFilter`

- 경로: `shared-common/src/main/java/github/lms/lemuel/common/config/jwt/JwtAuthenticationFilter.java`
- 핵심:
  - `doFilterInternal(...)` 오버라이드
  - `shouldNotFilter(...)` 오버라이드

설명:
- 인증 필터의 실행 골격은 부모 클래스가 제공한다.
- 어떤 요청을 제외할지와 인증 처리 방식은 하위 클래스가 결정한다.

### 4. `AuditContextFilter`

- 경로: `shared-common/src/main/java/github/lms/lemuel/common/audit/adapter/in/AuditContextFilter.java`
- 핵심: `doFilterInternal(...)`에서 감사 컨텍스트 설정/정리

설명:
- 필터 체인의 공통 실행 구조는 상위 클래스에 있고,
- 이 클래스는 감사용 actor 정보를 세팅하는 단계만 구현한다.

## 정리

이 프로젝트의 템플릿 메서드 패턴은 다음처럼 정리할 수 있다.

- 상위 클래스: `OncePerRequestFilter`
- 하위 구현 클래스:
  - `RateLimitFilter`
  - `TraceIdFilter`
  - `JwtAuthenticationFilter`
  - `AuditContextFilter`

즉, 이 프로젝트는 직접 템플릿 클래스를 정의하기보다, Spring이 제공하는 템플릿 메서드 구조를 확장해서 사용하고 있다.
