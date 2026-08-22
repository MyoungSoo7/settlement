package main

import (
	"reflect"
	"testing"
)

func TestGetenv_FallsBackWhenUnsetOrEmpty(t *testing.T) {
	if got := getenv("PAYMENT_WEBHOOK_PORT_ABSENT", "8111"); got != "8111" {
		t.Fatalf("unset key must fall back, got %q", got)
	}

	t.Setenv("PAYMENT_WEBHOOK_PORT_EMPTY", "")
	if got := getenv("PAYMENT_WEBHOOK_PORT_EMPTY", "8111"); got != "8111" {
		t.Fatalf("empty value must fall back (an empty port would bind everything), got %q", got)
	}

	t.Setenv("PAYMENT_WEBHOOK_PORT_SET", "9999")
	if got := getenv("PAYMENT_WEBHOOK_PORT_SET", "8111"); got != "9999" {
		t.Fatalf("set value must win, got %q", got)
	}
}

func TestParseBrokers(t *testing.T) {
	cases := []struct {
		name string
		raw  string
		want []string
	}{
		// An empty broker list is what selects the LogPublisher — it must parse to
		// nil, not to a one-element slice containing "".
		{"empty", "", nil},
		{"whitespace only", "   ", nil},
		{"single", "broker:9092", []string{"broker:9092"}},
		{"multiple", "a:9092,b:9092", []string{"a:9092", "b:9092"}},
		{"padded", " a:9092 , b:9092 ", []string{"a:9092", "b:9092"}},
		{"trailing comma", "a:9092,", []string{"a:9092"}},
		{"blank entry", "a:9092,,b:9092", []string{"a:9092", "b:9092"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := parseBrokers(tc.raw)
			if !reflect.DeepEqual(got, tc.want) {
				t.Fatalf("parseBrokers(%q) = %#v, want %#v", tc.raw, got, tc.want)
			}
		})
	}
}
