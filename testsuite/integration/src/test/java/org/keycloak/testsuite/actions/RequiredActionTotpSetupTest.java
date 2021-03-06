/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.keycloak.testsuite.actions;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.constants.KerberosConstants;
import org.keycloak.events.Details;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.models.ClientModel;
import org.keycloak.models.OTPPolicy;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredActionProviderModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.HmacOTP;
import org.keycloak.models.utils.TimeBasedOTP;
import org.keycloak.protocol.oidc.mappers.UserSessionNoteMapper;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.testsuite.AssertEvents;
import org.keycloak.testsuite.OAuthClient;
import org.keycloak.testsuite.pages.AccountTotpPage;
import org.keycloak.testsuite.pages.AppPage;
import org.keycloak.testsuite.pages.AppPage.RequestType;
import org.keycloak.testsuite.pages.LoginConfigTotpPage;
import org.keycloak.testsuite.pages.LoginPage;
import org.keycloak.testsuite.pages.LoginTotpPage;
import org.keycloak.testsuite.pages.RegisterPage;
import org.keycloak.testsuite.rule.KeycloakRule;
import org.keycloak.testsuite.rule.KeycloakRule.KeycloakSetup;
import org.keycloak.testsuite.rule.WebResource;
import org.keycloak.testsuite.rule.WebRule;
import org.keycloak.utils.CredentialHelper;
import org.openqa.selenium.WebDriver;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class RequiredActionTotpSetupTest {

    private static OTPPolicy originalPolicy;

    @ClassRule
    public static KeycloakRule keycloakRule = new KeycloakRule(new KeycloakSetup() {

        @Override
        public void config(RealmManager manager, RealmModel defaultRealm, RealmModel appRealm) {
            CredentialHelper.setRequiredCredential(manager.getSession(), CredentialRepresentation.TOTP, appRealm);
            //appRealm.addRequiredCredential(CredentialRepresentation.TOTP);
            RequiredActionProviderModel requiredAction = appRealm.getRequiredActionProviderByAlias(UserModel.RequiredAction.CONFIGURE_TOTP.name());
            requiredAction.setDefaultAction(true);
            appRealm.updateRequiredActionProvider(requiredAction);
            appRealm.setResetPasswordAllowed(true);
            originalPolicy = appRealm.getOTPPolicy();
        }

    });

    @Rule
    public AssertEvents events = new AssertEvents(keycloakRule);

    @Rule
    public WebRule webRule = new WebRule(this);

    @WebResource
    protected WebDriver driver;

    @WebResource
    protected AppPage appPage;

    @WebResource
    protected LoginPage loginPage;

    @WebResource
    protected LoginTotpPage loginTotpPage;

    @WebResource
    protected LoginConfigTotpPage totpPage;

    @WebResource
    protected AccountTotpPage accountTotpPage;

    @WebResource
    protected OAuthClient oauth;

    @WebResource
    protected RegisterPage registerPage;

    protected TimeBasedOTP totp = new TimeBasedOTP();

    @Test
    public void setupTotpRegister() {
        loginPage.open();
        loginPage.clickRegister();
        registerPage.register("firstName", "lastName", "email@mail.com", "setupTotp", "password", "password", null);

        String userId = events.expectRegister("setupTotp", "email@mail.com").assertEvent().getUserId();

        totpPage.assertCurrent();

        totpPage.configure(totp.generateTOTP(totpPage.getTotpSecret()));

        String sessionId = events.expectRequiredAction(EventType.UPDATE_TOTP).user(userId).detail(Details.USERNAME, "setuptotp").assertEvent().getSessionId();

        Assert.assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        events.expectLogin().user(userId).session(sessionId).detail(Details.USERNAME, "setuptotp").assertEvent();
    }

    @Test
    public void setupTotpExisting() {
        loginPage.open();
        loginPage.login("test-user@localhost", "password");

        totpPage.assertCurrent();

        String totpSecret = totpPage.getTotpSecret();

        totpPage.configure(totp.generateTOTP(totpSecret));

        String sessionId = events.expectRequiredAction(EventType.UPDATE_TOTP).assertEvent().getSessionId();

        Assert.assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        Event loginEvent = events.expectLogin().session(sessionId).assertEvent();

        oauth.openLogout();

        events.expectLogout(loginEvent.getSessionId()).assertEvent();

        loginPage.open();
        loginPage.login("test-user@localhost", "password");
        String src = driver.getPageSource();
        loginTotpPage.login(totp.generateTOTP(totpSecret));

        Assert.assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        events.expectLogin().assertEvent();
    }



    @Test
    public void setupTotpRegisteredAfterTotpRemoval() {
        // Register new user
        loginPage.open();
        loginPage.clickRegister();
        registerPage.register("firstName2", "lastName2", "email2@mail.com", "setupTotp2", "password2", "password2", null);

        String userId = events.expectRegister("setupTotp2", "email2@mail.com").assertEvent().getUserId();

        // Configure totp
        totpPage.assertCurrent();

        String totpCode = totpPage.getTotpSecret();
        totpPage.configure(totp.generateTOTP(totpCode));

        // After totp config, user should be on the app page
        Assert.assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        events.expectRequiredAction(EventType.UPDATE_TOTP).user(userId).detail(Details.USERNAME, "setuptotp2").assertEvent();

        Event loginEvent = events.expectLogin().user(userId).detail(Details.USERNAME, "setuptotp2").assertEvent();

        // Logout
        oauth.openLogout();
        events.expectLogout(loginEvent.getSessionId()).user(userId).assertEvent();

        // Try to login after logout
        loginPage.open();
        loginPage.login("setupTotp2", "password2");

        // Totp is already configured, thus one-time password is needed, login page should be loaded
        String uri = driver.getCurrentUrl();
        String src = driver.getPageSource();
        Assert.assertTrue(loginPage.isCurrent());
        Assert.assertFalse(totpPage.isCurrent());

        // Login with one-time password
        loginTotpPage.login(totp.generateTOTP(totpCode));

        loginEvent = events.expectLogin().user(userId).detail(Details.USERNAME, "setupTotp2").assertEvent();

        // Open account page
        accountTotpPage.open();
        accountTotpPage.assertCurrent();

        // Remove google authentificator
        accountTotpPage.removeTotp();

        events.expectAccount(EventType.REMOVE_TOTP).user(userId).assertEvent();

        // Logout
        oauth.openLogout();
        events.expectLogout(loginEvent.getSessionId()).user(userId).assertEvent();

        // Try to login
        loginPage.open();
        loginPage.login("setupTotp2", "password2");

        // Since the authentificator was removed, it has to be set up again
        totpPage.assertCurrent();
        totpPage.configure(totp.generateTOTP(totpPage.getTotpSecret()));

        String sessionId = events.expectRequiredAction(EventType.UPDATE_TOTP).user(userId).detail(Details.USERNAME, "setupTotp2").assertEvent().getSessionId();

        Assert.assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        events.expectLogin().user(userId).session(sessionId).detail(Details.USERNAME, "setupTotp2").assertEvent();
    }

    @Test
    public void setupOtpPolicyChangedTotp8Digits() {
        // set policy to 8 digits
        keycloakRule.update(new KeycloakRule.KeycloakSetup() {

            @Override
            public void config(RealmManager manager, RealmModel adminstrationRealm, RealmModel appRealm) {
                OTPPolicy newPolicy = new OTPPolicy();
                newPolicy.setLookAheadWindow(1);
                newPolicy.setDigits(8);
                newPolicy.setPeriod(30);
                newPolicy.setType(UserCredentialModel.TOTP);
                newPolicy.setAlgorithm(HmacOTP.HMAC_SHA1);
                newPolicy.setInitialCounter(0);
                appRealm.setOTPPolicy(newPolicy);
            }

        });


        loginPage.open();
        loginPage.login("test-user@localhost", "password");

        totpPage.assertCurrent();

        String totpSecret = totpPage.getTotpSecret();

        TimeBasedOTP timeBased = new TimeBasedOTP(HmacOTP.HMAC_SHA1, 8, 30, 1);
        totpPage.configure(timeBased.generateTOTP(totpSecret));

        String sessionId = events.expectRequiredAction(EventType.UPDATE_TOTP).assertEvent().getSessionId();

        Assert.assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        Event loginEvent = events.expectLogin().session(sessionId).assertEvent();

        oauth.openLogout();

        events.expectLogout(loginEvent.getSessionId()).assertEvent();

        loginPage.open();
        loginPage.login("test-user@localhost", "password");
        String src = driver.getPageSource();
        String token = timeBased.generateTOTP(totpSecret);
        Assert.assertEquals(8, token.length());
        loginTotpPage.login(token);

        Assert.assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        events.expectLogin().assertEvent();

        keycloakRule.update(new KeycloakRule.KeycloakSetup() {

            @Override
            public void config(RealmManager manager, RealmModel adminstrationRealm, RealmModel appRealm) {
                appRealm.setOTPPolicy(originalPolicy);
            }

        });

    }

    @Test
    public void setupOtpPolicyChangedHotp() {
        keycloakRule.update(new KeycloakRule.KeycloakSetup() {

            @Override
            public void config(RealmManager manager, RealmModel adminstrationRealm, RealmModel appRealm) {
                OTPPolicy newPolicy = new OTPPolicy();
                newPolicy.setLookAheadWindow(0);
                newPolicy.setDigits(6);
                newPolicy.setPeriod(30);
                newPolicy.setType(UserCredentialModel.HOTP);
                newPolicy.setAlgorithm(HmacOTP.HMAC_SHA1);
                newPolicy.setInitialCounter(0);
                appRealm.setOTPPolicy(newPolicy);
            }

        });


        loginPage.open();
        loginPage.login("test-user@localhost", "password");

        totpPage.assertCurrent();

        String totpSecret = totpPage.getTotpSecret();

        HmacOTP otpgen = new HmacOTP(6, HmacOTP.HMAC_SHA1, 1);
        totpPage.configure(otpgen.generateHOTP(totpSecret, 0));
        String uri = driver.getCurrentUrl();
        String sessionId = events.expectRequiredAction(EventType.UPDATE_TOTP).assertEvent().getSessionId();

        Assert.assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        Event loginEvent = events.expectLogin().session(sessionId).assertEvent();

        oauth.openLogout();

        events.expectLogout(loginEvent.getSessionId()).assertEvent();

        loginPage.open();
        loginPage.login("test-user@localhost", "password");
        String token = otpgen.generateHOTP(totpSecret, 1);
        loginTotpPage.login(token);

        Assert.assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        events.expectLogin().assertEvent();

        oauth.openLogout();
        events.expectLogout(null).session(AssertEvents.isUUID()).assertEvent();

        // test lookAheadWindow

        keycloakRule.update(new KeycloakRule.KeycloakSetup() {

            @Override
            public void config(RealmManager manager, RealmModel adminstrationRealm, RealmModel appRealm) {
                OTPPolicy newPolicy = new OTPPolicy();
                newPolicy.setLookAheadWindow(5);
                newPolicy.setDigits(6);
                newPolicy.setPeriod(30);
                newPolicy.setType(UserCredentialModel.HOTP);
                newPolicy.setAlgorithm(HmacOTP.HMAC_SHA1);
                newPolicy.setInitialCounter(0);
                appRealm.setOTPPolicy(newPolicy);
            }

        });


        loginPage.open();
        loginPage.login("test-user@localhost", "password");
        token = otpgen.generateHOTP(totpSecret, 4);
        loginTotpPage.assertCurrent();
        loginTotpPage.login(token);

        Assert.assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        events.expectLogin().assertEvent();





        keycloakRule.update(new KeycloakRule.KeycloakSetup() {

            @Override
            public void config(RealmManager manager, RealmModel adminstrationRealm, RealmModel appRealm) {
                appRealm.setOTPPolicy(originalPolicy);
            }

        });


    }



}
