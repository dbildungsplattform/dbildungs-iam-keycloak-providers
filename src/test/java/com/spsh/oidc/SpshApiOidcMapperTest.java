package com.spsh.oidc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.HashMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.ProtocolMapper;
import org.keycloak.protocol.oidc.TokenManager;
import org.keycloak.representations.AccessToken;

import com.spsh.util.TokenInterceptorException;
import com.spsh.utils.SpshBackendMock;

class SpshApiOidcMapperTest {

    private SpshBackendMock backend;
    private ProtocolMapperModel mapping;
    private KeycloakSession session;
    private UserSessionModel userSession;
    private ClientSessionContext clientSessionContext;
    private ClientModel client;
    private AccessToken accessToken;

    @BeforeEach
    void setUp() throws IOException {
        backend = new SpshBackendMock();
        backend.start();
        mapping = new ProtocolMapperModel();
        mapping.setName("personId");
        mapping.setProtocolMapper(SpshApiOidcMapper.PROVIDER_ID);
        mapping.setConfig(new HashMap<>());
        mapping.getConfig().put("fetchUrl", backend.getExternalDataUrl());
        mapping.getConfig().put("extractJsonPath", "$.personId");
        mapping.getConfig().put("claim.name", "personId");
        mapping.getConfig().put("access.token.claim", "true");

        session = mock(KeycloakSession.class);
        KeycloakSessionFactory sessionFactory = mock(KeycloakSessionFactory.class);
        when(session.getKeycloakSessionFactory()).thenReturn(sessionFactory);
        when(sessionFactory.getProviderFactory(ProtocolMapper.class, SpshApiOidcMapper.PROVIDER_ID))
            .thenReturn(new SpshApiOidcMapper());
        UserModel user = mock(UserModel.class);
        when(user.getId()).thenReturn("user-123");
        userSession = mock(UserSessionModel.class);
        when(userSession.getUser()).thenReturn(user);
        client = mock(ClientModel.class);
        when(client.getClientId()).thenReturn("test-client");
        KeycloakContext context = mock(KeycloakContext.class);
        when(context.getClient()).thenReturn(client);
        when(session.getContext()).thenReturn(context);
        AuthenticatedClientSessionModel clientSession = mock(AuthenticatedClientSessionModel.class);
        when(clientSession.getClient()).thenReturn(client);
        clientSessionContext = mock(ClientSessionContext.class);
        when(clientSessionContext.getClientSession()).thenReturn(clientSession);
        when(clientSessionContext.getProtocolMappersStream()).thenAnswer(invocation -> Stream.of(mapping));
        accessToken = new AccessToken();
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 500, 502, 503})
    void backendHttpErrorsAbortMapping(int responseStatus) {
        backend.setStatusCode(responseStatus);
        mapping.getConfig().put("ignoreMissingPath", "true");

        TokenInterceptorException exception = assertThrows(TokenInterceptorException.class, this::map);

        assertEquals("Failed to fetch or process SPSH API data for OIDC token", exception.getMessage());
        assertInstanceOf(IOException.class, exception.getCause());
        assertEquals("Unexpected response status: " + responseStatus, exception.getCause().getMessage());
        assertTrue(accessToken.getOtherClaims().isEmpty());
    }

    @Test
    void connectionErrorsAbortMapping() {
        backend.close();

        TokenInterceptorException exception = assertThrows(TokenInterceptorException.class, this::map);

        assertInstanceOf(IOException.class, exception.getCause());
        assertTrue(accessToken.getOtherClaims().isEmpty());
    }

    @Test
    void invalidJsonAbortsMapping() {
        backend.setResponseBody("{");

        assertThrows(TokenInterceptorException.class, this::map);

        assertTrue(accessToken.getOtherClaims().isEmpty());
    }

    @Test
    void missingRequiredPathAbortsMapping() {
        backend.setResponseBody("{}");

        assertThrows(TokenInterceptorException.class, this::map);

        assertTrue(accessToken.getOtherClaims().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "$.personId, person-123",
        "$.vorname, Erika",
        "$.nachname, Mustermann",
        "$.rollenart, LEHR",
        "$.personenkontexte[0].dienststellennr, 1234567",
        "$.personenkontexte[0].rolleId, 10000000-0000-4000-8000-000000000001",
        "$.dienststellenNummern[0], 1234567",
        "$.emailAdresse, erika.mustermann@example.org",
        "$.oxLoginId, erika.mustermann"
    })
    void successfulResponseMapsV2Field(String jsonPath, String expectedValue) {
        mapping.getConfig().put("extractJsonPath", jsonPath);
        mapping.getConfig().put("claim.name", "externalData");

        map();

        assertEquals(expectedValue, accessToken.getOtherClaims().get("externalData"));
    }

    @Test
    void stillIgnoresMissingPathWhenConfigured() {
        backend.setResponseBody("{}");
        mapping.getConfig().put("ignoreMissingPath", "true");

        map();

        assertTrue(accessToken.getOtherClaims().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 500})
    void failurePreventsTokenResponseAndSigning(int responseStatus) {
        backend.setStatusCode(responseStatus);
        TokenManager tokenManager = new TokenManager() {
            @Override
            protected AccessToken initToken(KeycloakSession keycloakSession, RealmModel realm, ClientModel clientModel,
                UserModel user, UserSessionModel userSessionModel, ClientSessionContext context, boolean isOffline) {
                return accessToken;
            }
        };
        var builder = tokenManager.responseBuilder(mock(RealmModel.class), client,
            mock(EventBuilder.class), session, userSession, clientSessionContext);

        assertThrows(TokenInterceptorException.class, () -> builder.generateAccessToken().build());

        assertNull(builder.getAccessToken());
        verify(session, never()).tokens();
    }

    private void map() {
        new TokenManager().transformAccessToken(session, accessToken, userSession, clientSessionContext);
    }
}
