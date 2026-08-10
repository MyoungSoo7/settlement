-- V20260807130000: pg_stat_statements 확장 생성 — 쿼리 단위 실측 (DB 성능 감사 ④)
--
-- 확장은 DB 로컬 오브젝트라 여기서 생성해 배선을 완성한다 — 이후 정산 핫패스의 인덱스 유무 판정은
-- 추정이 아니라 pg_stat_statements(누적 통계)로 한다.
--
-- [왜 DO 블록으로 감쌌나 — 2026-08-11]
--   원래는 맨 `CREATE EXTENSION IF NOT EXISTS` 였다. "어느 환경에서나 안전하게 통과한다" 고 적혀
--   있었지만 사실이 아니었다. CREATE EXTENSION 은 슈퍼유저 권한을 요구하는데, 운영(K3s jen-postgres)
--   의 앱 계정은 슈퍼유저가 아니다. 8/11 승격 때 이 한 줄로 settlement-service 가
--   `permission denied to create extension` → 마이그레이션 체인 중단 → CrashLoopBackOff 로 죽었다.
--
--   로컬 compose 와 Testcontainers 는 슈퍼유저로 붙기 때문에 CI 는 전부 통과했다. 즉 이 계열의
--   실패는 *테스트로는 잡히지 않고 운영에서만 터진다*. 그래서 코드 쪽에서 막는다.
--
--   확장이 없으면 pg_stat_statements 뷰를 못 읽을 뿐, 정산 로직은 전혀 영향받지 않는다.
--   관측용 확장 하나 때문에 서비스가 못 뜨는 쪽이 훨씬 나쁘므로 권한 부족은 경고로 낮춘다.
--   반대로 권한이 있는 환경(로컬·CI·슈퍼유저 운영)에서는 종전대로 생성된다.
--
-- [운영에서 실제로 켜려면]
--   확장 생성만으로는 수집이 안 된다. 서버가 shared_preload_libraries 에 pg_stat_statements 를
--   싣고 기동해야 하며 이는 DB 재시작이 필요하다. 2026-08-11 jen-postgres StatefulSet 에
--   `-c shared_preload_libraries=pg_stat_statements` 를 넣어 적용했다.
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
EXCEPTION
    -- 앱 계정이 슈퍼유저가 아닌 운영 DB
    WHEN insufficient_privilege THEN
        RAISE WARNING 'pg_stat_statements 확장 생성 권한 없음 — 건너뛴다. 쿼리 통계가 필요하면 슈퍼유저로 CREATE EXTENSION 을 먼저 실행할 것';
    -- contrib 가 없는 슬림 이미지 (확장 control 파일 부재)
    WHEN undefined_file THEN
        RAISE WARNING 'pg_stat_statements 확장 파일 없음(contrib 미설치) — 건너뛴다';
END
$$;
