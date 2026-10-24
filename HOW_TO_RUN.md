# WalletLedger — How to Run

WalletLedger is a Java 21 / Spring Boot REST API backend. It uses MySQL and Redis through Docker Compose for local development.

This guide is written for Windows PowerShell. Run commands from the project root — the folder containing `pom.xml`, `mvnw.cmd`, `compose.yaml`, and `.env.example`.

## 1. Install the prerequisites

Install and start:

- **JDK 21** — verify with `java -version`.
- **Docker Desktop** — open it and wait until Docker is running.
- **Internet access** for the first build and Docker image downloads.

You do not need to install Maven separately; the project includes the Maven Wrapper (`mvnw.cmd`).

Verify the tools:

```powershell
java -version
docker --version
docker compose version
```

## 2. Extract and open the project

1. Extract the WalletLedger ZIP.
2. Open PowerShell in the extracted project folder.
3. Confirm that `pom.xml`, `mvnw.cmd`, `compose.yaml`, and `.env.example` are present.

Example:

```powershell
cd "C:\path\to\walletledger"
```

Replace the example path with the folder where you extracted the project.

## 3. Create your local environment file

Create `.env` from the supplied example if you do not already have one:

```powershell
if (-not (Test-Path ".env")) {
    Copy-Item ".env.example" ".env"
}
```

Open `.env` and review the values. Use local development credentials only. Do not commit or share a real `.env` file.

If the example contains placeholder values, replace them with appropriate local values before starting the services. Keep the configuration consistent with `compose.yaml` and the application's datasource settings.

## 4. Start the supporting services

Make sure Docker Desktop is running, then run:

```powershell
docker compose up -d
```

Check the service status:

```powershell
docker compose ps
```

If a service does not become healthy, inspect its logs:

```powershell
docker compose logs --tail 100
```

The project configuration uses MySQL and Redis. Default host ports in the project setup may conflict with services already running on your computer; if so, resolve the port conflict in your local configuration before continuing.

## 5. Build the project and run the tests

Run:

```powershell
.\mvnw.cmd clean verify
```

The build should finish with `BUILD SUCCESS`. Integration tests use Testcontainers, so Docker must be available. The verified shared-project run completed **97 tests with 0 failures and 0 errors**; results on another machine can differ if its environment or resources differ.

## 6. Run the REST API

From the project root, run:

```powershell
.\mvnw.cmd spring-boot:run
```

Keep this terminal open while using the API. Check the startup logs for the actual port and any configuration errors.

Use Postman, curl, or another HTTP client to send requests to the API. Refer to `README.md` and the project's API documentation for the available endpoints and request formats. If SpringDoc is enabled, the startup configuration may expose Swagger UI and OpenAPI documentation; use the URL shown by the application or documented in the project.

## 7. Stop the application and services

1. Stop the running Spring Boot process with `Ctrl+C` in its terminal.
2. Stop the Docker Compose services when you are finished:

```powershell
docker compose down
```

This stops and removes the Compose containers and network. It does not normally remove named volumes. **Do not add `-v` unless you intentionally want to remove the Compose volumes and their stored data.**

## Troubleshooting

### `java` is not recognized or the wrong version appears
Install JDK 21 and make sure `java` is available on `PATH`. Open a new PowerShell window and run `java -version`.

### Docker/Testcontainers cannot start
Start Docker Desktop and wait for it to finish starting. Verify with:

```powershell
docker info
```

### A port is already in use
Another local service may already be using a configured MySQL or Redis host port. Identify the conflicting process or change the local port configuration consistently in the Compose and application settings.

### The application cannot connect to MySQL or Redis
Check `docker compose ps` and `docker compose logs --tail 100`. Verify `.env` values and the datasource/Redis settings used by the application. Do not paste real passwords into public issue reports.

### The build fails
Read the first test or compilation error in the Maven output. Make sure Docker is available for integration tests and rerun:

```powershell
.\mvnw.cmd clean verify
```

## Quick command checklist

Run these from the project root, in order:

```powershell
# 1. Create .env if needed
if (-not (Test-Path ".env")) { Copy-Item ".env.example" ".env" }

# 2. Start MySQL and Redis
docker compose up -d

# 3. Check containers
docker compose ps

# 4. Build and test
.\mvnw.cmd clean verify

# 5. Start the API (use a separate terminal after tests complete)
.\mvnw.cmd spring-boot:run
```

---

**Security reminder:** Share the source ZIP and `.env.example`, not your populated `.env`, passwords, tokens, or other private credentials.
