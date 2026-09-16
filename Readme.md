# DBILDUNGS-IAM-KEYCLOAK-PROVIDERS

This repository is designed for creating custom **providers** for our **dbildungs-iam-keycloak** instance. It serves as the foundation for extending Keycloak's functionality to meet our specific requirements, such as custom protocol mappers, event listeners, and other extensions.

## Local Setup

Prerequisite: [Maven](https://maven.apache.org/) (e.g. via `brew install maven`) and Docker.

To test local changes to the providers in a Keycloak instance started via `dbildungs-iam-server/compose.yaml`:

1. Build the provider JAR:
    ```bash
    mvn clean package
    ```
    Result: `target/keycloak-providers-1.8.jar` (already bundled as a fat JAR with all dependencies via the `maven-shade-plugin`).

2. Copy the JAR into the Keycloak image project (replaces the older version already located there):
    ```bash
    cp target/keycloak-providers-1.8.jar ../dbildungs-iam-keycloak/src/providers/keycloak-providers-1.8.jar
    ```

3. Rebuild the local dev image of `dbildungs-iam-keycloak` (copies `src/providers/` into `/opt/keycloak/providers/` and runs `kc.sh build`):
    ```bash
    cd ../dbildungs-iam-keycloak
    chmod +x build-dev.sh
    ./build-dev.sh
    ```
    This tags the image as `ghcr.io/dbildungsplattform/dbildungs-iam-keycloak:latest` — the same name referenced by `dbildungs-iam-server/compose.yaml`. Docker Compose won't pull again as long as an image with this tag already exists locally.

4. Recreate the Keycloak container in `dbildungs-iam-server` (a plain restart is not enough, the container must be recreated to actually use the new image):
    ```bash
    cd ../dbildungs-iam-server
    docker compose rm -sf keycloak
    docker compose --profile third-party up -d keycloak
    ```

The admin console is then available at [http://localhost:8080/admin/master/console/](http://localhost:8080/admin/master/console/) (login `admin`/`admin`).

To verify the providers, you can use the `vidis-test` client (Keycloak client "VIDIS-Testumgebung") — it is already preconfigured with the 6 mappers (`rolle`, `schulkennung`, `vorname`, `email`, `nachname`, `uid`) of type `spsh-custom-oidc-api-mapper` and already uses the V2 solution (`keycloakClientId`/`includeEmailAddress`), see `dbildungs-iam-server/config/dev-realm-spsh.json`.

