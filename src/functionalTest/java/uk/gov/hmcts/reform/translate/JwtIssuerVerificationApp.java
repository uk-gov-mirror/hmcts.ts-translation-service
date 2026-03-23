package uk.gov.hmcts.reform.translate;

import com.nimbusds.jwt.SignedJWT;
import org.apache.commons.lang3.StringUtils;
import uk.gov.hmcts.befta.BeftaMain;
import uk.gov.hmcts.befta.TestAutomationConfig;
import uk.gov.hmcts.befta.data.UserData;
import uk.gov.hmcts.befta.util.EnvironmentVariableUtils;

import java.text.ParseException;
import java.util.concurrent.ExecutionException;

public final class JwtIssuerVerificationApp {

    private JwtIssuerVerificationApp() {
    }

    public static void main(String[] args) {
        String expectedIssuer = EnvironmentVariableUtils.getRequiredVariable("OIDC_ISSUER");
        String actualIssuer = resolveIssuerFromRealToken();

        if (!expectedIssuer.equals(actualIssuer)) {
            throw new IllegalStateException(
                "OIDC_ISSUER mismatch: expected `" + expectedIssuer + "` but token iss was `" + actualIssuer + "`"
            );
        }

        System.out.println("Verified OIDC_ISSUER matches functional test token iss: " + actualIssuer);
    }

    private static String resolveIssuerFromRealToken() {
        BeftaMain.setConfig(TestAutomationConfig.INSTANCE);
        TranslationServiceTestAutomationAdapter adapter = new TranslationServiceTestAutomationAdapter();
        BeftaMain.setTaAdapter(adapter);

        try {
            UserData manageTranslationUser = TranslationServiceTestDataLoader.loadManageTranslationUser();
            adapter.authenticate(
                manageTranslationUser,
                BeftaMain.getConfig().getUserTokenProviderConfig().getClientId()
            );
            return issuerFrom(manageTranslationUser.getAccessToken());
        } catch (ExecutionException exception) {
            throw new IllegalStateException(
                "Failed to get a real functional test token for issuer verification",
                exception
            );
        }
    }

    private static String issuerFrom(String accessToken) {
        try {
            String issuer = SignedJWT.parse(accessToken).getJWTClaimsSet().getIssuer();
            if (StringUtils.isBlank(issuer)) {
                throw new IllegalStateException("Decoded IDAM access token did not contain an iss claim");
            }
            return issuer;
        } catch (ParseException exception) {
            throw new IllegalStateException("Failed to parse IDAM access token as a JWT", exception);
        }
    }
}
