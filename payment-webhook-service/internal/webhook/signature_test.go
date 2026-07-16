package webhook

import "testing"

func TestVerifySignature_Valid(t *testing.T) {
	secret := "s3cr3t"
	body := []byte(`{"eventType":"PAYMENT_STATUS_CHANGED","data":{"paymentKey":"pk_1"}}`)
	sig := ComputeSignature(secret, body)

	if !VerifySignature(secret, body, sig) {
		t.Fatal("expected valid signature to verify")
	}
}

func TestVerifySignature_TamperedBody(t *testing.T) {
	secret := "s3cr3t"
	body := []byte(`{"amount":1000}`)
	sig := ComputeSignature(secret, body)

	tampered := []byte(`{"amount":9999}`)
	if VerifySignature(secret, tampered, sig) {
		t.Fatal("expected tampered body to fail verification")
	}
}

func TestVerifySignature_TamperedSignature(t *testing.T) {
	secret := "s3cr3t"
	body := []byte(`{"amount":1000}`)

	if VerifySignature(secret, body, "not-a-real-signature") {
		t.Fatal("expected bogus signature to fail verification")
	}
	// A structurally valid but wrong base64 signature must also fail.
	if VerifySignature(secret, body, ComputeSignature("wrong-secret", body)) {
		t.Fatal("expected signature from wrong secret to fail")
	}
}

func TestVerifySignature_EmptyInputs(t *testing.T) {
	body := []byte(`x`)
	if VerifySignature("", body, ComputeSignature("", body)) {
		t.Fatal("empty secret must never verify")
	}
	if VerifySignature("s", body, "") {
		t.Fatal("empty provided signature must never verify")
	}
}
