package com.spsh.saml;

import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;
import org.keycloak.dom.saml.v2.assertion.AttributeStatementType;
import org.keycloak.dom.saml.v2.assertion.AttributeStatementType.ASTChoiceType;
import org.keycloak.dom.saml.v2.assertion.AttributeType;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.saml.mappers.AbstractSAMLProtocolMapper;
import org.keycloak.protocol.saml.mappers.SAMLAttributeStatementMapper;
import org.keycloak.provider.ProviderConfigProperty;

import com.spsh.util.ApiFetchHelper;

public class SpshApiSamlMapper extends AbstractSAMLProtocolMapper implements SAMLAttributeStatementMapper {

    public static final String ENV_KEY_INTERNAL_COMMUNICATION_API_KEY = "INTERNAL_COMMUNICATION_API_KEY";
    public static final String PROVIDER_ID = "spsh-custom-saml-api-mapper";
    public static final String FETCH_URL = "fetchUrl";
    public static final String EXTRACT_JSON_PATH = "extractJsonPath";
    public static final String KEYCLOAK_CLIENT_ID = "keycloakClientId";
    public static final String INCLUDE_EMAIL_ADDRESS = "includeEmailAddress";
    private static final List<ProviderConfigProperty> configProperties = new ArrayList<>();
    private static final Logger LOGGER = Logger.getLogger(SpshApiSamlMapper.class);

    static {
        ProviderConfigProperty fetchUrlProperty = new ProviderConfigProperty();
        fetchUrlProperty.setName(FETCH_URL);
        fetchUrlProperty.setLabel("SPSH Fetch Url");
        fetchUrlProperty.setType(ProviderConfigProperty.STRING_TYPE);
        fetchUrlProperty.setHelpText("The URL to fetch data from the SPSH Backend.");
        configProperties.add(fetchUrlProperty);

        ProviderConfigProperty extractPathProperty = new ProviderConfigProperty();
        extractPathProperty.setName(EXTRACT_JSON_PATH);
        extractPathProperty.setLabel("SPSH Extract Json Path");
        extractPathProperty.setType(ProviderConfigProperty.STRING_TYPE);
        extractPathProperty.setHelpText("The JSON path to extract data from the API response.");
        configProperties.add(extractPathProperty);

        ProviderConfigProperty keycloakClientIdProperty = new ProviderConfigProperty();
        keycloakClientIdProperty.setName(KEYCLOAK_CLIENT_ID);
        keycloakClientIdProperty.setLabel("SPSH Keycloak Client");
        keycloakClientIdProperty.setType(ProviderConfigProperty.STRING_TYPE);
        keycloakClientIdProperty.setHelpText("The keycloakClientId identifier to send to the Backend. If left empty, the client ID of the current session is used.");
        configProperties.add(keycloakClientIdProperty);

        ProviderConfigProperty includeEmailAddressProperty = new ProviderConfigProperty();
        includeEmailAddressProperty.setName(INCLUDE_EMAIL_ADDRESS);
        includeEmailAddressProperty.setLabel("SPSH Include Email Address");
        includeEmailAddressProperty.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        includeEmailAddressProperty.setHelpText("Whether to request the user's email address from the Backend.");
        configProperties.add(includeEmailAddressProperty);
    }

    @Override
    public String getDisplayCategory() {
        return "Attribute Mapper";
    }

    @Override
    public String getDisplayType() {
        return "SPSH Custom SAML Api Mapper";
    }

    @Override
    public String getHelpText() {
        return "The mapper calls the provided SPSH fetch URL, extracts the JSON path from the API response, and maps the result if not null to the SAML assertion.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configProperties;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public void transformAttributeStatement(AttributeStatementType attributeStatement, ProtocolMapperModel mappingModel,
        KeycloakSession session, UserSessionModel userSession, AuthenticatedClientSessionModel clientSession) {

        String fetchUrl = mappingModel.getConfig().get(FETCH_URL);
        String extractJsonPath = mappingModel.getConfig().get(EXTRACT_JSON_PATH);
        boolean includeEmailAddress = Boolean.parseBoolean(mappingModel.getConfig().getOrDefault(INCLUDE_EMAIL_ADDRESS, "false"));
        String configuredKeycloakClientId = mappingModel.getConfig().get(KEYCLOAK_CLIENT_ID);
        String defaultKeycloakClientId = clientSession.getClient().getClientId();
        String keycloakClientId = (configuredKeycloakClientId == null || configuredKeycloakClientId.isEmpty())
            ? defaultKeycloakClientId : configuredKeycloakClientId;
        String userSub = userSession.getUser().getId();

        LOGGER.info(String.format("Setting SAML attribute via custom SpshApiSamlMapper for userSub: %s", userSub));
        LOGGER.debug(String.format("Using fetchUrl: %s", fetchUrl));
        LOGGER.debug(String.format("Using extractJsonPath: %s", extractJsonPath));
        LOGGER.debug(String.format("Using userSub: %s", userSub));
        LOGGER.debug(String.format("Using keycloakClientId: %s", keycloakClientId));
        LOGGER.debug(String.format("Using includeEmailAddress: %b", includeEmailAddress));

        if (fetchUrl == null) {
            LOGGER.warn("SpshApiOidcMapper: fetchUrl is null. No data will be fetched, extracted and mapped.");
            return;
        }
        if (extractJsonPath == null) {
            LOGGER.warn("SpshApiOidcMapper: extractJsonPath is null. No data will be fetched, extracted and mapped.");
            return;
        }
        if (userSub == null) {
            LOGGER.warn("SpshApiOidcMapper: userSub is null. No data will be fetched, extracted and mapped.");
            return;
        }

        try {
            String responseData = ApiFetchHelper.fetchApiData(fetchUrl, userSub, keycloakClientId, includeEmailAddress);
            Object extractedValue = ApiFetchHelper.extractFromJson(responseData, extractJsonPath);
            if (extractedValue != null) {
                AttributeType samlAttribute = new AttributeType(mappingModel.getName());
                samlAttribute.addAttributeValue(extractedValue);
                ASTChoiceType attributeChoice = new ASTChoiceType(samlAttribute);
                attributeStatement.addAttribute(attributeChoice);
            }
        } catch (Exception e) {
            LOGGER.error("Error fetching or processing API data", e);
        }
    }
}
