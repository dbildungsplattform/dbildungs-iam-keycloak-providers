package com.spsh.saml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
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
import org.keycloak.crypto.Algorithm;
import org.keycloak.crypto.KeyUse;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.dom.saml.v2.assertion.AttributeStatementType;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeyManager;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.ProtocolMapper;
import org.keycloak.services.ErrorPage;
import org.keycloak.services.messages.Messages;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.mockito.MockedStatic;

import com.spsh.util.TokenInterceptorException;
import com.spsh.utils.CapturingSamlProtocol;
import com.spsh.utils.SpshBackendMock;

import jakarta.ws.rs.core.Response;

class SpshApiSamlMapperTest {

    private SpshBackendMock backend;
    private ProtocolMapperModel mapping;
    private KeycloakSession session;
    private KeycloakSessionFactory sessionFactory;
    private UserSessionModel userSession;
    private AuthenticatedClientSessionModel clientSession;
    private ClientSessionContext clientSessionContext;
    private ClientModel client;
    private AttributeStatementType attributeStatement;

    @BeforeEach
    void setUp() throws IOException {
        backend = new SpshBackendMock();
        backend.start();
        mapping = new ProtocolMapperModel();
        mapping.setName("personId");
        mapping.setConfig(new HashMap<>());
        mapping.getConfig().put("fetchUrl", backend.getExternalDataUrl());
        mapping.getConfig().put("extractJsonPath", "$.personId");

        session = mock(KeycloakSession.class);
        sessionFactory = mock(KeycloakSessionFactory.class);
        when(session.getKeycloakSessionFactory()).thenReturn(sessionFactory);
        UserModel user = mock(UserModel.class);
        when(user.getId()).thenReturn("user-123");
        userSession = mock(UserSessionModel.class);
        when(userSession.getUser()).thenReturn(user);
        client = mock(ClientModel.class);
        when(client.getClientId()).thenReturn("test-client");
        clientSession = mock(AuthenticatedClientSessionModel.class);
        when(clientSession.getClient()).thenReturn(client);
        clientSessionContext = mock(ClientSessionContext.class);
        when(clientSessionContext.getClientSession()).thenReturn(clientSession);
        when(clientSessionContext.getProtocolMappersStream()).thenAnswer(invocation -> Stream.of(mapping));
        attributeStatement = new AttributeStatementType();
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

        TokenInterceptorException exception = assertThrows(TokenInterceptorException.class, this::map);

        assertEquals("Failed to fetch or process SPSH API data for SAML assertion", exception.getMessage());
        assertInstanceOf(IOException.class, exception.getCause());
        assertEquals("Unexpected response status: " + responseStatus, exception.getCause().getMessage());
        assertTrue(attributeStatement.getAttributes().isEmpty());
    }

    @Test
    void connectionErrorsAbortMapping() {
        backend.close();

        TokenInterceptorException exception = assertThrows(TokenInterceptorException.class, this::map);

        assertInstanceOf(IOException.class, exception.getCause());
        assertTrue(attributeStatement.getAttributes().isEmpty());
    }

    @Test
    void invalidJsonAbortsMapping() {
        backend.setResponseBody("{");

        assertThrows(TokenInterceptorException.class, this::map);

        assertTrue(attributeStatement.getAttributes().isEmpty());
    }

    @Test
    void missingRequiredPathAbortsMapping() {
        backend.setResponseBody("{}");

        assertThrows(TokenInterceptorException.class, this::map);

        assertTrue(attributeStatement.getAttributes().isEmpty());
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
        mapping.setName("externalData");

        map();

        assertEquals("externalData", attributeStatement.getAttributes().get(0).getAttribute().getName());
        assertEquals(expectedValue, attributeStatement.getAttributes().get(0)
            .getAttribute().getAttributeValue().get(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 500})
    void failureReturnsErrorInsteadOfAssertion(int responseStatus) {
        backend.setStatusCode(responseStatus);
        SpshApiSamlMapper mapper = spy(new SpshApiSamlMapper());
        CapturingSamlProtocol protocol = prepareSamlProtocol(mapper);
        AuthenticationSessionModel authenticationSession = authenticationSession();
        Response errorResponse = mock(Response.class);
        try (MockedStatic<ErrorPage> errorPage = mockStatic(ErrorPage.class)) {
            errorPage.when(() -> ErrorPage.error(session, null, Response.Status.BAD_REQUEST,
                Messages.FAILED_TO_PROCESS_RESPONSE)).thenReturn(errorResponse);

            Response response = protocol.authenticated(authenticationSession, userSession, clientSessionContext);

            assertSame(errorResponse, response);
            assertNull(protocol.getIssuedDocument());
            verify(mapper).transformAttributeStatement(any(), eq(mapping), eq(session), eq(userSession), eq(clientSession));
            errorPage.verify(() -> ErrorPage.error(session, null, Response.Status.BAD_REQUEST,
                Messages.FAILED_TO_PROCESS_RESPONSE));
        }
    }

    @Test
    void successStillBuildsAssertion() {
        CapturingSamlProtocol protocol = prepareSamlProtocol(new SpshApiSamlMapper());

        protocol.authenticated(authenticationSession(), userSession, clientSessionContext);

        assertNotNull(protocol.getIssuedDocument());
        assertEquals("person-123", protocol.getIssuedDocument().getElementsByTagNameNS(
            "urn:oasis:names:tc:SAML:2.0:assertion", "AttributeValue").item(0).getTextContent());
    }

    private void map() {
        new SpshApiSamlMapper().transformAttributeStatement(
            attributeStatement, mapping, session, userSession, clientSession);
    }

    private CapturingSamlProtocol prepareSamlProtocol(SpshApiSamlMapper mapper) {
        mapping.setProtocolMapper(SpshApiSamlMapper.PROVIDER_ID);
        when(sessionFactory.getProviderFactory(ProtocolMapper.class, SpshApiSamlMapper.PROVIDER_ID)).thenReturn(mapper);
        when(clientSession.getUserSession()).thenReturn(userSession);
        when(userSession.getUser().getUsername()).thenReturn("test-user");
        RealmModel realm = mock(RealmModel.class);
        KeyManager keys = mock(KeyManager.class);
        when(session.keys()).thenReturn(keys);
        KeyWrapper key = new KeyWrapper();
        key.setKid("test-key");
        when(keys.getActiveKey(realm, KeyUse.SIG, Algorithm.RS256)).thenReturn(key);
        CapturingSamlProtocol protocol = new CapturingSamlProtocol();
        protocol.setSession(session).setRealm(realm);

        return protocol;
    }

    private AuthenticationSessionModel authenticationSession() {
        AuthenticationSessionModel authenticationSession = mock(AuthenticationSessionModel.class);
        when(authenticationSession.getClient()).thenReturn(client);
        when(authenticationSession.getRedirectUri()).thenReturn("https://client.test/saml");

        return authenticationSession;
    }
}

