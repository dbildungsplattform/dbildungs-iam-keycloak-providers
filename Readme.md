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

This repository has no CI/CD of its own. The JAR you build locally (step 1 above) is target-agnostic — it's just a plain Java artifact, unrelated to any Docker `--target`. Once it's committed into `dbildungs-iam-keycloak/src/providers/`, it is picked up by **every** image build of that repository, because the `Dockerfile` copies `src/providers/` in its shared `base` stage, which both the `development` (local, `build-dev.sh`) and `deployment` (staging/production, built by CI) stages are derived from. So there is no separate build step needed for staging/production — the same JAR you already tested locally is exactly what ships everywhere.

Steps to actually get a change live:

1. Build the JAR (`mvn clean package`) and copy it into `../dbildungs-iam-keycloak/src/providers/`, as described above. If you changed the provider logic, consider bumping the `<version>` in [`pom.xml`](./pom.xml) (and the resulting JAR filename referenced in `Dockerfile`/`README.md` of `dbildungs-iam-keycloak`) so the change is traceable.
2. Commit the updated JAR **in the `dbildungs-iam-keycloak` repository** (not here) on a feature/ticket branch, and push it.
   - This alone already triggers `image-and-helm-publish-check-deploy-on-push-scheduled.yml`, which builds and publishes a Docker image (`target: deployment`) tagged with the ticket/branch identifier — usable to deploy the change to a review/staging environment.
   - After merging that branch into `main`, the same workflow builds and publishes an image tagged with the commit hash and `latest`.
3. To create an official, versioned production release, push a SemVer Git tag (e.g. `1.9.0`) on `dbildungs-iam-keycloak`. This triggers `create-release.yml`, which builds/publishes the `deployment` target image tagged with that version and releases the matching Helm chart.

`build-dev.sh` (`--target development`) is only ever used for your local Docker container and is unrelated to this — it is never invoked by CI.

