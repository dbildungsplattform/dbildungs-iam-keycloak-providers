package com.spsh.utils;

import static org.mockito.Mockito.mock;

import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.RealmModel;
import org.keycloak.protocol.saml.JaxrsSAML2BindingBuilder;
import org.keycloak.protocol.saml.SamlProtocol;
import org.w3c.dom.Document;

import jakarta.ws.rs.core.Response;

public class CapturingSamlProtocol extends SamlProtocol {

    private Document issuedDocument;

    public Document getIssuedDocument() {
        return issuedDocument;
    }

    @Override
    protected String getResponseIssuer(RealmModel realm) {
        return "https://keycloak.test/realms/test";
    }

    @Override
    protected Response buildAuthenticatedResponse(AuthenticatedClientSessionModel clientSession, String redirectUri,
        Document samlDocument, JaxrsSAML2BindingBuilder bindingBuilder) {
        issuedDocument = samlDocument;

        return mock(Response.class);
    }
}
