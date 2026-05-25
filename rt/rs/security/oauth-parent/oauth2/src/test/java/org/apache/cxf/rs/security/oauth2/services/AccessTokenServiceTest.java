/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.rs.security.oauth2.services;

import java.security.Principal;
import java.util.List;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.SecurityContext;

import org.apache.cxf.jaxrs.ext.MessageContext;
import org.apache.cxf.jaxrs.impl.MetadataMap;
import org.apache.cxf.rs.security.oauth2.common.AccessTokenRegistration;
import org.apache.cxf.rs.security.oauth2.common.Client;
import org.apache.cxf.rs.security.oauth2.common.OAuthPermission;
import org.apache.cxf.rs.security.oauth2.common.ServerAccessToken;
import org.apache.cxf.rs.security.oauth2.common.UserSubject;
import org.apache.cxf.rs.security.oauth2.grants.code.AuthorizationCodeDataProvider;
import org.apache.cxf.rs.security.oauth2.grants.code.AuthorizationCodeRegistration;
import org.apache.cxf.rs.security.oauth2.grants.code.ServerAuthorizationCodeGrant;
import org.apache.cxf.rs.security.oauth2.provider.OAuthServiceException;
import org.apache.cxf.rs.security.oauth2.tokens.bearer.BearerAccessToken;
import org.apache.cxf.rs.security.oauth2.utils.OAuthConstants;

import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class AccessTokenServiceTest extends Assert {

    @Test
    public void testNonMatchingClientIdWithBasicAuth() throws Exception {
        AccessTokenService service = new AccessTokenService();

        MessageContext mc = EasyMock.createMock(MessageContext.class);
        SecurityContext sc = EasyMock.createMock(SecurityContext.class);
        Principal principal = EasyMock.createMock(Principal.class);

        Client dummyClient = new Client("consumer-id", "secret", true);
        TestAuthCodeDataProvider dataProvider = new TestAuthCodeDataProvider(dummyClient);

        EasyMock.expect(mc.getSecurityContext()).andReturn(sc).anyTimes();
        EasyMock.expect(sc.getUserPrincipal()).andReturn(principal).anyTimes();
        EasyMock.expect(principal.getName()).andReturn("consumer-id").anyTimes();

        EasyMock.replay(mc, sc, principal);

        service.setMessageContext(mc);
        service.setDataProvider(dataProvider);

        MultivaluedMap<String, String> params = new MetadataMap<String, String>();
        params.putSingle(OAuthConstants.GRANT_TYPE, "authorization_code");
        params.putSingle(OAuthConstants.CLIENT_ID, "different-client-id");
        params.putSingle(OAuthConstants.CLIENT_SECRET, "secret");

        try {
            service.handleTokenRequest(params);
            fail("Expected WebApplicationException for non-matching client ID");
        } catch (WebApplicationException ex) {
            assertEquals(401, ex.getResponse().getStatus());
        }
    }

    @Test
    public void testMatchingClientIdWithBasicAuth() throws Exception {
        AccessTokenService service = new AccessTokenService();

        MessageContext mc = EasyMock.createMock(MessageContext.class);
        SecurityContext sc = EasyMock.createMock(SecurityContext.class);
        Principal principal = EasyMock.createMock(Principal.class);

        Client client = new Client("consumer-id", "this-is-a-secret", true);
        client.getAllowedGrantTypes().add("authorization_code");

        TestAuthCodeDataProvider dataProvider = new TestAuthCodeDataProvider(client);

        EasyMock.expect(mc.getSecurityContext()).andReturn(sc).anyTimes();
        EasyMock.expect(sc.getUserPrincipal()).andReturn(principal).anyTimes();
        EasyMock.expect(principal.getName()).andReturn("consumer-id").anyTimes();

        EasyMock.replay(mc, sc, principal);

        service.setMessageContext(mc);
        service.setDataProvider(dataProvider);

        MultivaluedMap<String, String> params = new MetadataMap<String, String>();
        params.putSingle(OAuthConstants.GRANT_TYPE, "authorization_code");
        params.putSingle(OAuthConstants.CLIENT_ID, "consumer-id");
        params.putSingle(OAuthConstants.CLIENT_SECRET, "this-is-a-secret");

        try {
            service.handleTokenRequest(params);
        } catch (WebApplicationException ex) {
            assertFalse("Should not get 401 when client_id matches principal",
                        ex.getResponse().getStatus() == 401);
        }

        EasyMock.verify(mc, sc, principal);
    }

    @Test
    public void testNonMatchingClientIdWithBasicAuthNoClientIdInParams() throws Exception {
        AccessTokenService service = new AccessTokenService();

        MessageContext mc = EasyMock.createMock(MessageContext.class);
        SecurityContext sc = EasyMock.createMock(SecurityContext.class);
        Principal principal = EasyMock.createMock(Principal.class);

        Client client = new Client("consumer-id", "this-is-a-secret", true);
        client.getAllowedGrantTypes().add("authorization_code");

        TestAuthCodeDataProvider dataProvider = new TestAuthCodeDataProvider(client);

        EasyMock.expect(mc.getSecurityContext()).andReturn(sc).anyTimes();
        EasyMock.expect(sc.getUserPrincipal()).andReturn(principal).anyTimes();
        EasyMock.expect(sc.getAuthenticationScheme()).andReturn("BASIC").anyTimes();
        EasyMock.expect(principal.getName()).andReturn("consumer-id").anyTimes();

        EasyMock.replay(mc, sc, principal);

        service.setMessageContext(mc);
        service.setDataProvider(dataProvider);

        MultivaluedMap<String, String> params = new MetadataMap<String, String>();
        params.putSingle(OAuthConstants.GRANT_TYPE, "authorization_code");

        try {
            service.handleTokenRequest(params);
        } catch (WebApplicationException ex) {
            assertFalse("Should not get 401 when using principal name for BASIC auth",
                        ex.getResponse().getStatus() == 401);
        }

        EasyMock.verify(mc, sc, principal);
    }

    private static class TestAuthCodeDataProvider implements AuthorizationCodeDataProvider {
        private final Client client;

        TestAuthCodeDataProvider(Client client) {
            this.client = client;
        }

        public Client getClient(String clientId) throws OAuthServiceException {
            if (client.getClientId().equals(clientId)) {
                return client;
            }
            return null;
        }

        public ServerAccessToken createAccessToken(AccessTokenRegistration reg)
            throws OAuthServiceException {
            return new BearerAccessToken(reg.getClient(), 3600);
        }

        public ServerAccessToken getAccessToken(String accessToken) throws OAuthServiceException {
            return null;
        }

        public ServerAccessToken getPreauthorizedToken(Client client, List<String> requestedScopes,
                                                       UserSubject subject, String grantType)
            throws OAuthServiceException {
            return null;
        }

        public ServerAccessToken refreshAccessToken(Client client, String refreshToken,
                                                    List<String> requestedScopes)
            throws OAuthServiceException {
            return null;
        }

        public void removeAccessToken(ServerAccessToken accessToken) throws OAuthServiceException {
        }

        public List<OAuthPermission> convertScopeToPermissions(Client client,
                                                               List<String> requestedScope) {
            return null;
        }

        public ServerAuthorizationCodeGrant createCodeGrant(AuthorizationCodeRegistration reg)
            throws OAuthServiceException {
            return null;
        }

        public ServerAuthorizationCodeGrant removeCodeGrant(String code)
            throws OAuthServiceException {
            return null;
        }
    }
}
