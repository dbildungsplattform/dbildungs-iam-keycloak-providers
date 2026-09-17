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

## Deploying to staging / production

This repository has no CI/CD of its own — the built JAR (step 1 above) is committed as a binary into `dbildungs-iam-keycloak/src/providers/`, and all image builds happen in that repository's GitHub Actions:

1. Build the JAR (`mvn clean package`) and copy it into `../dbildungs-iam-keycloak/src/providers/`, as described above. If you changed the provider logic, consider bumping the `<version>` in [`pom.xml`](./pom.xml) (and the resulting JAR filename referenced in `Dockerfile`/`README.md` of `dbildungs-iam-keycloak`) so the change is traceable.
2. Commit the updated JAR in `dbildungs-iam-keycloak` and push it on a branch / open a PR.
   - Every push to a non-`main` branch automatically builds and publishes a Docker image tagged with the ticket/branch identifier (workflow `image-and-helm-publish-check-deploy-on-push-scheduled.yml`, `target: deployment`), and can be deployed to a review/staging environment.
   - After merging to `main`, the same workflow builds and publishes the image tagged with the commit hash and `latest`.
3. To create an official production release, push a SemVer Git tag (e.g. `1.9.0`) on `dbildungs-iam-keycloak`. This triggers `create-release.yml`, which builds/publishes the `deployment` target image tagged with that version and releases the matching Helm chart.

There is no separate "production build" step for the provider itself — once the JAR is committed, the existing `dbildungs-iam-keycloak` pipelines take care of building and shipping the image for every environment.

