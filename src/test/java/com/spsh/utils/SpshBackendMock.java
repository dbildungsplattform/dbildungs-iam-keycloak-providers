package com.spsh.utils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.keycloak.util.JsonSerialization;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class SpshBackendMock implements AutoCloseable {

    private static final String HOST = "127.0.0.1";
    private static final String VERSION = "/v2";
    private static final String EXTERNAL_DATA_PATH = VERSION + "/keycloakinternal/externaldata";

    private final HttpServer server;
    private volatile int statusCode = 200;
    private volatile String responseBody;

    public SpshBackendMock() throws IOException {
        responseBody = createDefaultResponseBody();
        server = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
        server.createContext(EXTERNAL_DATA_PATH, this::handleRequest);
    }

    public void start() {
        server.start();
    }

    public String getExternalDataUrl() {
        return "http://" + HOST + ":" + server.getAddress().getPort() + EXTERNAL_DATA_PATH;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String createDefaultResponseBody() throws IOException {
        List<Map<String, String>> personenkontexte = List.of(Map.of(
            "dienststellennr", "1234567",
            "rolleId", "10000000-0000-4000-8000-000000000001"));

        return JsonSerialization.writeValueAsString(Map.of(
            "personId", "person-123",
            "vorname", "Erika",
            "nachname", "Mustermann",
            "rollenart", "LEHR",
            "personenkontexte", personenkontexte,
            "dienststellenNummern", List.of("1234567"),
            "emailAdresse", "erika.mustermann@example.org",
            "oxLoginId", "erika.mustermann"));
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
