# JWT issuer validation

## Service

`ts-translation-service`

## Summary

- JWT issuer validation is enabled in the active decoder.
- `spring.security.oauth2.client.provider.oidc.issuer-uri` is used for OIDC discovery and JWKS lookup.
- `oidc.issuer` is the enforced issuer value and is sourced from `OIDC_ISSUER`.
- See [HMCTS Guidance](#hmcts-guidance) for the central policy reference.

## HMCTS Guidance

- [JWT iss Claim Validation guidance](https://tools.hmcts.net/confluence/spaces/SISM/pages/1958056812/JWT+iss+Claim+Validation+for+OIDC+and+OAuth+2+Tokens#JWTissClaimValidationforOIDCandOAuth2Tokens-Configurationrecommendation)
- Use that guidance as the reference point for service-level issuer decisions and configuration recommendations.

## Quick Reference

| Topic | Current repo position |
| --- | --- |
| Validation model | Single configured issuer |
| Current service position | Explicitly enforced legacy `FORGEROCK` issuer |
| Discovery source | `spring.security.oauth2.client.provider.oidc.issuer-uri` |
| Enforced issuer | `oidc.issuer` / `OIDC_ISSUER` |
| Runtime rule | `OIDC_ISSUER` must match the `iss` claim in real accepted tokens |

## Current service position

- `ts-translation-service` is currently configured to enforce the legacy `FORGEROCK` issuer for deployed environments.
- That matches the current Helm and Jenkins configuration in this repo and keeps issuer validation explicit rather than inferred from discovery metadata.
- This repo does not currently implement a multi-issuer allow-list because the service is operating in a single configured issuer mode.

## Migration to IDAM issuer

- Moving this service to `IDAM` is an upstream configuration change, not a code-path change in this repo.
- If this service is moved to `IDAM`, the prerequisite issuer-policy update will be in the upstream `idam-access-config` repository, typically by setting `oauth2.required_issuer: IDAM` for this service there.
- After that upstream change, this repo’s `OIDC_ISSUER` values in Helm, preview config, and Jenkins must be updated to the new token issuer and verified against a real token.
- Until that upstream change is made, the correct compliant behavior in this repo is to validate `iss` against the explicitly configured legacy `FORGEROCK` issuer rather than disable issuer checks or guess from discovery.

## Previous state

- The service previously used a custom validator chain containing only `JwtTimestampValidator`.
- The active decoder did not retain a `JwtIssuerValidator`.
- A JWT with a valid signature from the configured JWKS and valid timestamps could therefore be accepted without checking whether `iss` matched the intended issuer.

## Current implementation

- `src/main/java/uk/gov/hmcts/reform/translate/config/SecurityConfiguration.java` now validates both timestamp and issuer.
- Issuer validation is enforced with `JwtIssuerValidator` using `oidc.issuer`.
- The implementation keeps a single enforced issuer rather than widening validation to multiple issuers.

## Configuration meaning

| Setting | Purpose |
| --- | --- |
| `spring.security.oauth2.client.provider.oidc.issuer-uri` | OIDC discovery metadata and JWKS resolution |
| `oidc.issuer` | Exact issuer value enforced by `JwtIssuerValidator` |

## Test and build coverage

| Area | Coverage |
| --- | --- |
| `src/test/java/uk/gov/hmcts/reform/translate/config/SecurityConfigurationTest.java` | Focused validator chain behaviour |
| `src/integrationTest/java/uk/gov/hmcts/reform/translate/config/JwtDecoderIssuerValidationIT.java` | Active decoder accepts a correctly signed token from the configured issuer and rejects the same key material with an unexpected issuer |
| `src/functionalTest/java/uk/gov/hmcts/reform/translate/JwtIssuerVerificationApp.java` | Acquires a real BEFTA test token, decodes `iss`, and verifies it matches `OIDC_ISSUER` when enabled |
| `build.gradle` | Wires `verifyFunctionalTestJwtIssuer` into `smoke` and `functional`, gated by `VERIFY_OIDC_ISSUER=true` |

## CI and deployment requirement

- `VERIFY_OIDC_ISSUER=true` keeps the verifier mandatory in CI and opt-in locally.
- Jenkins must export `OIDC_ISSUER` explicitly because the verifier reads process environment, not Helm-rendered runtime env inside the deployed pod.
- `OIDC_ISSUER` must stay aligned with the real token issuer for each environment.
- Use [HMCTS Guidance](#hmcts-guidance) as the central policy reference for service-level issuer decisions.

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

## Acceptance Checklist

Before merging JWT issuer-validation changes, confirm all of the following:

- The active `JwtDecoder` is built from `spring.security.oauth2.client.provider.oidc.issuer-uri`.
- The active validator chain includes both `JwtTimestampValidator` and `JwtIssuerValidator(oidc.issuer)`.
- There is no disabled, commented-out, or alternate runtime path that leaves issuer validation off.
- `issuer-uri` is used for discovery and JWKS lookup only.
- `oidc.issuer` / `OIDC_ISSUER` is used as the enforced token `iss` value only.
- `OIDC_ISSUER` is explicitly configured and not guessed from the discovery URL.
- App config, Helm values, preview values, and CI/Jenkins values are aligned for the target environment.
- If `OIDC_ISSUER` changed, it was verified against a real token for the target environment.
- There is a test that accepts a token with the expected issuer.
- There is a test that rejects a token with an unexpected issuer.
- There is a test that rejects an expired token.
- There is decoder-level coverage using a signed token, not only validator-only coverage.
- At least one failure assertion clearly proves issuer rejection, for example by checking for `iss`.
- CI or build verification checks that a real token issuer matches `OIDC_ISSUER`, or the repo documents why that does not apply.
- Comments and docs do not describe the old insecure behavior.
- Any repo-specific difference from peer services is intentional and documented.

Do not merge if any of the following are true:

- issuer validation is constructed but not applied
- only timestamp validation is active
- `OIDC_ISSUER` was inferred rather than verified
- Helm and CI/Jenkins issuer values disagree without explanation
- only happy-path tests exist

## Configuration Policy

- `spring.security.oauth2.client.provider.oidc.issuer-uri` is used for OIDC discovery and JWKS lookup only.
- `oidc.issuer` / `OIDC_ISSUER` is the enforced JWT issuer and must match the token `iss` claim exactly.
- Do not derive `OIDC_ISSUER` from `IDAM_OIDC_URL` or the discovery URL.
- Production-like environments must provide `OIDC_ISSUER` explicitly.
- Requiring explicit `OIDC_ISSUER` with no static fallback in main runtime config is the preferred pattern, but it is not yet mandatory across all services.
- Local or test-only fallbacks are acceptable only when they are static, intentional, and clearly scoped to non-production use.
- The build enforces this policy with `verifyOidcIssuerPolicy`, which fails if `oidc.issuer` is derived from discovery config.

## References

- [HMCTS Guidance](#hmcts-guidance)
