# How to Run WalletLedger

WalletLedger is a Java 21 / Spring Boot REST API backend using MySQL and Redis through Docker Compose.

## Prerequisites

- Git
- Docker Desktop with Docker Compose
- Internet access for the initial image downloads and build
- Java 21 if you want to run Maven tests or start the application locally outside Docker

Verify your tools when running locally:

```powershell
java -version
docker --version
docker compose version
```

Maven does not need to be installed separately; the project includes `mvnw.cmd`.

## Run with Docker Compose (Windows PowerShell)

Clone the repository and enter the project directory:

```powershell
git clone https://github.com/JAY1196/walletledger.git
Set-Location walletledger
Copy-Item .env.example .env
```

Edit `.env` and replace every password placeholder with a strong, unique local password before starting the services. Do not commit or share your populated `.env` file.

Build and start the application and its dependencies:

```powershell
docker compose up -d --build
```

Check service status:

```powershell
docker compose ps
```

The configured host ports are:

- API: `http://localhost:8081`
- MySQL: `127.0.0.1:3307`
- Redis: `127.0.0.1:6380`

Check API readiness:

```powershell
Invoke-WebRequest http://localhost:8081/actuator/health/readiness
```

The readiness endpoint should return a successful HTTP response when the API is ready.

- Swagger UI URL: **Not confirmed** from the checked source configuration.
- OpenAPI URL: **Not confirmed** from the checked source configuration.

## Run tests locally

With Java 21 installed and Docker available for integration tests, run:

```powershell
.\mvnw.cmd clean verify
```

The documented verification baseline is 97 tests across 21 test classes, with zero failures, errors, or skipped tests. Results on another machine may differ depending on its environment and resources.

## Run the API locally outside Docker

If you want to run the application through Maven instead of the packaged Docker application, start the supporting services first:

```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run
```

Keep the terminal open while using the API. Check the application startup logs for configuration errors and the actual listening port.

## Stop the services

Stop the Docker Compose stack:

```powershell
docker compose down
```

This removes the Compose containers and network but normally preserves named volumes and their stored database data. **Do not add `-v` unless you intentionally want to delete the associated volumes and stored data.**

## Troubleshooting

### Docker or Testcontainers cannot start

Start Docker Desktop and wait until it is ready. Check connectivity with:

```powershell
docker info
docker compose ps
```

### A port is already in use

Another service may already be using a configured MySQL, Redis, or API host port. Stop the conflicting service or change the corresponding host-port setting in `.env`, keeping the Compose configuration consistent.

### The application cannot connect to MySQL or Redis

Inspect service status and logs:

```powershell
docker compose ps
docker compose logs --tail 100
```

Verify the `.env` values and application connection settings. Never include real passwords or tokens in public issue reports.

### The build or tests fail

Read the first relevant compilation or test error in the Maven output. Confirm Java 21 is being used and Docker is available for integration tests, then retry:

```powershell
java -version
docker info
.\mvnw.cmd clean verify
```

### The API does not become ready

Inspect the application and dependency logs:

```powershell
docker compose logs --tail 100
Invoke-WebRequest http://localhost:8081/actuator/health/readiness
```

Resolve any startup or connection errors before retrying the readiness check.

---

**Security reminder:** Share the source code and `.env.example`, not your populated `.env`, passwords, tokens, or other private credentials.
