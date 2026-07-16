package webhook

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
)

// SignatureHeader is the HTTP header carrying the webhook HMAC signature.
const SignatureHeader = "Toss-Signature"

// ComputeSignature returns the base64-encoded HMAC-SHA256 of body under secret.
// This is the value expected in the SignatureHeader.
func ComputeSignature(secret string, body []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	return base64.StdEncoding.EncodeToString(mac.Sum(nil))
}

// VerifySignature reports whether provided matches the HMAC-SHA256 of body under
// secret. The comparison is constant-time (hmac.Equal) to avoid timing attacks.
//
// NOTE: This is a generic HMAC-over-raw-body scheme. Toss' production webhook
// signature spec differs (see README TODO) and must be substituted before real
// use; the verification plumbing, header capture and constant-time compare all
// stay the same.
func VerifySignature(secret string, body []byte, provided string) bool {
	if secret == "" || provided == "" {
		return false
	}
	expected := ComputeSignature(secret, body)
	// Compare the raw HMAC bytes in constant time. Decode both sides; a decode
	// failure on the provided value simply means "not equal".
	expectedBytes, err := base64.StdEncoding.DecodeString(expected)
	if err != nil {
		return false
	}
	providedBytes, err := base64.StdEncoding.DecodeString(provided)
	if err != nil {
		return false
	}
	return hmac.Equal(expectedBytes, providedBytes)
}
