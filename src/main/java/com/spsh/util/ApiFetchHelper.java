package com.spsh.util;

import java.io.IOException;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import com.jayway.jsonpath.JsonPath;

public class ApiFetchHelper {

    public static final String ENV_KEY_INTERNAL_COMMUNICATION_API_KEY = "INTERNAL_COMMUNICATION_API_KEY";

    // Keycloak client IDs are conventionally alphanumeric plus '.', '_', '-'; used to validate mapper config at save time.
    private static final Pattern SAFE_KEYCLOAK_CLIENT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]*$");

    public static boolean isValidKeycloakClientId(String keycloakClientId) {
        return keycloakClientId == null || SAFE_KEYCLOAK_CLIENT_ID_PATTERN.matcher(keycloakClientId).matches();
    }

    public static String fetchApiData(String url, String userSub, String keycloakClientId, boolean includeEmailAddress) throws IOException {
        String apiKey = requireApiKey();
        HttpPost request = buildRequest(url, apiKey, userSub, keycloakClientId, includeEmailAddress);

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            return httpClient.execute(request, ApiFetchHelper::readResponseBody);
        }
    }

    private static String requireApiKey() throws IOException {
        String apiKey = System.getenv(ENV_KEY_INTERNAL_COMMUNICATION_API_KEY);
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException(String.format("Environment variable %s is not set or is empty.", ENV_KEY_INTERNAL_COMMUNICATION_API_KEY));
        }

        return apiKey;
    }

    private static HttpPost buildRequest(String url, String apiKey, String userSub, String keycloakClientId, boolean includeEmailAddress) {
        HttpPost request = new HttpPost(url);
        request.setHeader("Content-Type", "application/json");
        request.setHeader("api-key", apiKey);
        request.setEntity(new StringEntity(buildRequestBody(userSub, keycloakClientId, includeEmailAddress)));

        return request;
    }

    private static String buildRequestBody(String userSub, String keycloakClientId, boolean includeEmailAddress) {
        return String.format(
            "{\"sub\":\"%s\",\"keycloakClientId\":\"%s\",\"includeEmailAddress\":%b}",
            escapeJson(userSub), escapeJson(keycloakClientId), includeEmailAddress);
    }

    private static String readResponseBody(ClassicHttpResponse response) throws HttpException, IOException {
        int statusCode = response.getCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("Unexpected response status: " + statusCode);
        }
        HttpEntity entity = response.getEntity();

        return entity != null ? EntityUtils.toString(entity) : null;
    }

    public static boolean isPathExisting(String jsonData, String jsonPath) {
        try{
            JsonPath.read(jsonData, jsonPath);
            return true;
        } catch(com.jayway.jsonpath.PathNotFoundException e) {
            return false;
        }
    }

    public static Object extractFromJson(String jsonData, String jsonPath) {
        return JsonPath.read(jsonData, jsonPath);
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder(value.length());
        for (char character : value.toCharArray()) {
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}

