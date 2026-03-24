# JWT issuer validation

## Service

`ts-translation-service`

## Summary

- JWT issuer validation is enabled in the active decoder.
- `spring.security.oauth2.client.provider.oidc.issuer-uri` is used for OIDC discovery and JWKS lookup.
- `oidc.issuer` is the enforced issuer value and is sourced from `OIDC_ISSUER`.

## Previous state

- The service previously used a custom validator chain containing only `JwtTimestampValidator`.
- The active decoder did not retain a `JwtIssuerValidator`.
- A JWT with a valid signature from the configured JWKS and valid timestamps could therefore be accepted without checking whether `iss` matched the intended issuer.

## Current implementation

- `src/main/java/uk/gov/hmcts/reform/translate/config/SecurityConfiguration.java` now validates both timestamp and issuer.
- Issuer validation is enforced with `JwtIssuerValidator` using `oidc.issuer`.
- The implementation keeps a single enforced issuer rather than widening validation to multiple issuers.

## Configuration meaning

- `spring.security.oauth2.client.provider.oidc.issuer-uri`
  Used for OIDC discovery metadata and JWKS resolution.
- `oidc.issuer`
  Used by `JwtIssuerValidator` as the exact issuer value the service accepts.

## Test and build coverage

- `src/test/java/uk/gov/hmcts/reform/translate/config/SecurityConfigurationTest.java`
  Covers the focused validator chain behaviour.
- `src/integrationTest/java/uk/gov/hmcts/reform/translate/config/JwtDecoderIssuerValidationIT.java`
  Verifies the active decoder accepts a correctly signed token from the configured issuer and rejects the same key material with an unexpected issuer.
- `src/functionalTest/java/uk/gov/hmcts/reform/translate/JwtIssuerVerificationApp.java`
  Acquires a real BEFTA test token, decodes `iss`, and verifies it matches `OIDC_ISSUER` when enabled.
- `build.gradle`
  Wires `verifyFunctionalTestJwtIssuer` into `smoke` and `functional`, gated by `VERIFY_OIDC_ISSUER=true`.

## CI and deployment requirement

- `VERIFY_OIDC_ISSUER=true` keeps the verifier mandatory in CI and opt-in locally.
- Jenkins must export `OIDC_ISSUER` explicitly because the verifier reads process environment, not Helm-rendered runtime env inside the deployed pod.
- `OIDC_ISSUER` must stay aligned with the real token issuer for each environment.

## How to derive `OIDC_ISSUER`

- Do not guess the issuer from the public discovery URL alone.
- Decode only the JWT payload from a real access token for the target environment and inspect the `iss` claim.
- Do not store or document full bearer tokens. Record only the derived issuer value.

Example:

```bash
TOKEN='eyJ...'
PAYLOAD=$(printf '%s' "$TOKEN" | cut -d '.' -f2)
python3 - <<'PY' "$PAYLOAD"
import base64, json, sys
payload = sys.argv[1]
payload += '=' * (-len(payload) % 4)
print(json.loads(base64.urlsafe_b64decode(payload))["iss"])
PY
```

- JWTs are `header.payload.signature`.
- The second segment is base64url-encoded JSON.
- This decodes the payload only. It does not verify the signature.

## Outcome

- Prevents validly signed tokens from an unexpected issuer being accepted.
- Keeps discovery and enforcement semantics explicit.
- Makes issuer misconfiguration visible during build verification rather than only at runtime.
