package main

import (
	"io"
	"log/slog"
	"testing"
	"time"
)

func quiet() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func TestGetenv_FallsBackWhenUnsetOrEmpty(t *testing.T) {
	if got := getenv("MARKET_STREAM_PORT_ABSENT", "8110"); got != "8110" {
		t.Fatalf("미설정은 기본값이어야 한다: %q", got)
	}
	t.Setenv("MARKET_STREAM_PORT_EMPTY", "")
	if got := getenv("MARKET_STREAM_PORT_EMPTY", "8110"); got != "8110" {
		t.Fatalf("빈 값은 기본값이어야 한다(빈 포트는 전체 바인딩이 된다): %q", got)
	}
	t.Setenv("MARKET_STREAM_PORT_SET", "9110")
	if got := getenv("MARKET_STREAM_PORT_SET", "8110"); got != "9110" {
		t.Fatalf("설정값이 이겨야 한다: %q", got)
	}
}

// 잘못된 값은 기동을 죽이지 않고 기본값으로 내려앉는다 — 다만 조용히는 아니고 WARN 을 남긴다.
func TestGetInt_InvalidValueFallsBackToDefault(t *testing.T) {
	t.Setenv("MARKET_STREAM_SUB_BUFFER", "not-a-number")
	if got := getInt("MARKET_STREAM_SUB_BUFFER", 16, quiet()); got != 16 {
		t.Fatalf("파싱 실패는 기본값이어야 한다: %d", got)
	}
	t.Setenv("MARKET_STREAM_SUB_BUFFER", "32")
	if got := getInt("MARKET_STREAM_SUB_BUFFER", 16, quiet()); got != 32 {
		t.Fatalf("유효값은 그대로 쓰여야 한다: %d", got)
	}
	if got := getInt("MARKET_STREAM_SUB_BUFFER_ABSENT", 16, quiet()); got != 16 {
		t.Fatalf("미설정은 기본값이어야 한다: %d", got)
	}
}

func TestGetInt64_InvalidValueFallsBackToDefault(t *testing.T) {
	t.Setenv("MARKET_STREAM_SEED", "9e9")
	if got := getInt64("MARKET_STREAM_SEED", 7, quiet()); got != 7 {
		t.Fatalf("파싱 실패는 기본값이어야 한다: %d", got)
	}
	t.Setenv("MARKET_STREAM_SEED", "1234567890123")
	if got := getInt64("MARKET_STREAM_SEED", 7, quiet()); got != 1234567890123 {
		t.Fatalf("유효값은 그대로 쓰여야 한다: %d", got)
	}
	if got := getInt64("MARKET_STREAM_SEED_ABSENT", 7, quiet()); got != 7 {
		t.Fatalf("미설정은 기본값이어야 한다: %d", got)
	}
}

func TestGetDuration_InvalidValueFallsBackToDefault(t *testing.T) {
	t.Setenv("MARKET_STREAM_TICK_INTERVAL", "1 second")
	if got := getDuration("MARKET_STREAM_TICK_INTERVAL", time.Second, quiet()); got != time.Second {
		t.Fatalf("파싱 실패는 기본값이어야 한다: %v", got)
	}
	t.Setenv("MARKET_STREAM_TICK_INTERVAL", "250ms")
	if got := getDuration("MARKET_STREAM_TICK_INTERVAL", time.Second, quiet()); got != 250*time.Millisecond {
		t.Fatalf("유효값은 그대로 쓰여야 한다: %v", got)
	}
	if got := getDuration("MARKET_STREAM_TICK_ABSENT", time.Second, quiet()); got != time.Second {
		t.Fatalf("미설정은 기본값이어야 한다: %v", got)
	}
}

func TestLoadConfig_Defaults(t *testing.T) {
	for _, k := range []string{
		"MARKET_STREAM_PORT", "MARKET_STREAM_TICK_INTERVAL", "MARKET_STREAM_SUB_BUFFER",
		"MARKET_STREAM_SEED", "MARKET_STREAM_SOURCE", "MARKET_BASE_URL", "MARKET_STREAM_POLL_INTERVAL",
	} {
		t.Setenv(k, "")
	}

	cfg := loadConfig(quiet())

	if cfg.port != "8110" {
		t.Fatalf("기본 포트: %q", cfg.port)
	}
	if cfg.tickInterval != time.Second {
		t.Fatalf("기본 틱 주기: %v", cfg.tickInterval)
	}
	if cfg.subBuffer != 16 {
		t.Fatalf("기본 구독 버퍼: %d", cfg.subBuffer)
	}
	// 기본은 외부 의존 0 — 시뮬레이션이어야 브로커/마켓서비스 없이도 뜬다.
	if cfg.sourceKind != "simulated" {
		t.Fatalf("기본 소스: %q", cfg.sourceKind)
	}
	if cfg.pollInterval != 60*time.Second {
		t.Fatalf("기본 폴링 주기: %v", cfg.pollInterval)
	}
}

func TestLoadConfig_EnvOverrides(t *testing.T) {
	t.Setenv("MARKET_STREAM_PORT", "9999")
	t.Setenv("MARKET_STREAM_TICK_INTERVAL", "100ms")
	t.Setenv("MARKET_STREAM_SUB_BUFFER", "4")
	t.Setenv("MARKET_STREAM_SEED", "42")
	t.Setenv("MARKET_STREAM_SOURCE", "polling")
	t.Setenv("MARKET_BASE_URL", "http://market-service:8094")
	t.Setenv("MARKET_STREAM_POLL_INTERVAL", "5s")

	cfg := loadConfig(quiet())

	if cfg.port != "9999" || cfg.tickInterval != 100*time.Millisecond || cfg.subBuffer != 4 ||
		cfg.seed != 42 || cfg.sourceKind != "polling" || cfg.marketBase != "http://market-service:8094" ||
		cfg.pollInterval != 5*time.Second {
		t.Fatalf("환경변수가 반영되지 않았다: %+v", cfg)
	}
}

func TestBuildSource_SelectsImplementationByKind(t *testing.T) {
	sim := buildSource(config{seed: 1, sourceKind: "simulated"}, quiet())
	if sim.Name() != "simulated" {
		t.Fatalf("기본은 시뮬레이션이어야 한다: %q", sim.Name())
	}

	poll := buildSource(config{seed: 1, sourceKind: "polling", marketBase: "http://x", pollInterval: time.Minute}, quiet())
	if poll.Name() != "polling" {
		t.Fatalf("polling 지정 시 폴링 소스여야 한다: %q", poll.Name())
	}

	// 어느 쪽이든 틱은 합성이다 — EXCHANGE 로 승격되면 거짓말이 된다.
	for _, s := range []interface{ ValueSource() string }{sim, poll} {
		if s.ValueSource() != "SAMPLE" {
			t.Fatalf("합성 틱은 SAMPLE 이어야 한다: %q", s.ValueSource())
		}
	}

	// 알 수 없는 값은 안전한 기본(시뮬레이션)으로 떨어진다.
	unknown := buildSource(config{seed: 1, sourceKind: "quantum"}, quiet())
	if unknown.Name() != "simulated" {
		t.Fatalf("알 수 없는 소스 종류는 시뮬레이션으로 떨어져야 한다: %q", unknown.Name())
	}
}
