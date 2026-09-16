package com.spsh.oidc;

import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperContainerModel;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.ProtocolMapperConfigException;
import org.keycloak.protocol.ProtocolMapperUtils;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import com.spsh.util.ApiFetchHelper;

import jakarta.ws.rs.InternalServerErrorException;

public class SpshApiOidcMapper extends AbstractOIDCProtocolMapper implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {


    public static final String ENV_KEY_INTERNAL_COMMUNICATION_API_KEY = "INTERNAL_COMMUNICATION_API_KEY";
    public static final String PROVIDER_ID = "spsh-custom-oidc-api-mapper";
    public static final String FETCH_URL = "fetchUrl";
    public static final String EXTRACT_JSON_PATH = "extractJsonPath";
    public static final String IGNORE_MISSING_PATH = "ignoreMissingPath";
    public static final String KEYCLOAK_CLIENT_ID = "keycloakClientId";
    public static final String INCLUDE_EMAIL_ADDRESS = "includeEmailAddress";
    private static final List<ProviderConfigProperty> configProperties = new ArrayList<>();
    private static final Logger LOGGER = Logger.getLogger(SpshApiOidcMapper.class);

    static {
        OIDCAttributeMapperHelper.addTokenClaimNameConfig(configProperties);
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, SpshApiOidcMapper.class);

        ProviderConfigProperty multivaluedProperty = new ProviderConfigProperty();
        multivaluedProperty.setName(ProtocolMapperUtils.MULTIVALUED);
        multivaluedProperty.setLabel(ProtocolMapperUtils.MULTIVALUED_LABEL);
        multivaluedProperty.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        multivaluedProperty.setHelpText(ProtocolMapperUtils.MULTIVALUED_HELP_TEXT);
        configProperties.add(multivaluedProperty);

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

        ProviderConfigProperty ignoreMissingPathProperty = new ProviderConfigProperty();
        ignoreMissingPathProperty.setName(IGNORE_MISSING_PATH);
        ignoreMissingPathProperty.setLabel("SPSH Ignore Missing Path");
        ignoreMissingPathProperty.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        ignoreMissingPathProperty.setHelpText("If JSON Path cannot be found in response received from Backend, do not throw an error, just ignore it.");
        configProperties.add(ignoreMissingPathProperty);

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
        return "Token Mapper";
    }

    @Override
    public String getDisplayType() {
        return "SPSH Custom OIDC Api Mapper";
    }

    @Override
    public String getHelpText() {
        return "The mapper calls the provided SPSH fetch url, extracts the provided JsonPath from the api response and maps the result if not null to the claim";
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
    public void validateConfig(KeycloakSession session, RealmModel realm, ProtocolMapperContainerModel client, ProtocolMapperModel mapperModel)
        throws ProtocolMapperConfigException {
        String keycloakClientId = mapperModel.getConfig().get(KEYCLOAK_CLIENT_ID);
        if (!ApiFetchHelper.isValidKeycloakClientId(keycloakClientId)) {
            throw new ProtocolMapperConfigException(
                "The configured Keycloak Client must only contain letters, digits, '.', '_' and '-'.",
                "spshInvalidKeycloakClientId");
        }
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel,
      UserSessionModel userSession, KeycloakSession keycloakSession,
      ClientSessionContext clientSessionCtx) {
        String fetchUrl = mappingModel.getConfig().get(FETCH_URL);
        String extractJsonPath = mappingModel.getConfig().get(EXTRACT_JSON_PATH);
        boolean ignoreMissingPath = Boolean.parseBoolean(mappingModel.getConfig().getOrDefault(IGNORE_MISSING_PATH, "false"));
        boolean includeEmailAddress = Boolean.parseBoolean(mappingModel.getConfig().getOrDefault(INCLUDE_EMAIL_ADDRESS, "false"));
        String configuredKeycloakClientId = mappingModel.getConfig().get(KEYCLOAK_CLIENT_ID);
        String defaultKeycloakClientId = clientSessionCtx.getClientSession().getClient().getClientId();
        String keycloakClientId = (configuredKeycloakClientId == null || configuredKeycloakClientId.isEmpty())
            ? defaultKeycloakClientId : configuredKeycloakClientId;
        String userSub = userSession.getUser().getId();

        LOGGER.info(String.format("Setting claims via custom SpshApiOidcMapper for userSub: %s", userSub));
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
            boolean isExisting = ApiFetchHelper.isPathExisting(responseData, extractJsonPath);
            if(!isExisting && ignoreMissingPath) {
                LOGGER.info(String.format("Ignoring due to configuration that JSON Path %s does not exist in response", extractJsonPath));
                return;
            }
            if(!isExisting && !ignoreMissingPath) {
                throw new InternalServerErrorException(String.format("JSON Path %s does not exist in response: %s", extractJsonPath, responseData));
            }
            Object extractedValue = ApiFetchHelper.extractFromJson(responseData, extractJsonPath);
            if (extractedValue != null) {
                OIDCAttributeMapperHelper.mapClaim(token, mappingModel, extractedValue);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}