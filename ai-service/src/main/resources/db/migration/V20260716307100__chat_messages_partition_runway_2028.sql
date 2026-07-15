-- V20260716307100: chat_messages 파티션 런웨이 확대 (2027_07 ~ 2028_12) — 크로스컷 DB 리뷰 F3 (ai)
--
-- [설계 근거]
--   chat_messages(챗봇 메시지 시계열)는 created_at 월별 RANGE 파티션이 2027_06 까지만 선생성돼 있다. 이후
--   삽입은 DEFAULT 파티션으로 흘러들어 프루닝·리텐션 이점이 사라지므로 월별 파티션을 2028_12 까지 미리 깐다.
--   경계는 원 정의와 동일하게 TIMESTAMPTZ('...+00'). conversation_id FK(ON DELETE CASCADE)는 부모 상속으로 유지.
-- [⚠ DEFAULT 파티션 트랩]
--   해당 월 데이터가 이미 DEFAULT 파티션에 적재된 뒤에는 그 월 파티션 생성이 실패한다. 이 확대는 데이터가
--   쌓이기 전(현 시점 전부 미래 구간)에 적용돼야 하며, 대용량 운영 전환은 점검창에서 수행한다.
-- [운영 주의 — 근본 해법]
--   ensure_chat_message_partition(months_ahead) 를 월 1회 크론/스케줄러로 호출해 런웨이를 굴리는 것이 정답.
--   본 마이그레이션은 그 전까지의 일회성 런웨이(약 18개월) 확보다.

CREATE TABLE IF NOT EXISTS chat_messages_2027_07 PARTITION OF chat_messages FOR VALUES FROM ('2027-07-01 00:00:00+00') TO ('2027-08-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2027_08 PARTITION OF chat_messages FOR VALUES FROM ('2027-08-01 00:00:00+00') TO ('2027-09-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2027_09 PARTITION OF chat_messages FOR VALUES FROM ('2027-09-01 00:00:00+00') TO ('2027-10-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2027_10 PARTITION OF chat_messages FOR VALUES FROM ('2027-10-01 00:00:00+00') TO ('2027-11-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2027_11 PARTITION OF chat_messages FOR VALUES FROM ('2027-11-01 00:00:00+00') TO ('2027-12-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2027_12 PARTITION OF chat_messages FOR VALUES FROM ('2027-12-01 00:00:00+00') TO ('2028-01-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2028_01 PARTITION OF chat_messages FOR VALUES FROM ('2028-01-01 00:00:00+00') TO ('2028-02-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2028_02 PARTITION OF chat_messages FOR VALUES FROM ('2028-02-01 00:00:00+00') TO ('2028-03-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2028_03 PARTITION OF chat_messages FOR VALUES FROM ('2028-03-01 00:00:00+00') TO ('2028-04-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2028_04 PARTITION OF chat_messages FOR VALUES FROM ('2028-04-01 00:00:00+00') TO ('2028-05-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2028_05 PARTITION OF chat_messages FOR VALUES FROM ('2028-05-01 00:00:00+00') TO ('2028-06-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2028_06 PARTITION OF chat_messages FOR VALUES FROM ('2028-06-01 00:00:00+00') TO ('2028-07-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2028_07 PARTITION OF chat_messages FOR VALUES FROM ('2028-07-01 00:00:00+00') TO ('2028-08-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2028_08 PARTITION OF chat_messages FOR VALUES FROM ('2028-08-01 00:00:00+00') TO ('2028-09-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2028_09 PARTITION OF chat_messages FOR VALUES FROM ('2028-09-01 00:00:00+00') TO ('2028-10-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2028_10 PARTITION OF chat_messages FOR VALUES FROM ('2028-10-01 00:00:00+00') TO ('2028-11-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2028_11 PARTITION OF chat_messages FOR VALUES FROM ('2028-11-01 00:00:00+00') TO ('2028-12-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS chat_messages_2028_12 PARTITION OF chat_messages FOR VALUES FROM ('2028-12-01 00:00:00+00') TO ('2029-01-01 00:00:00+00');
