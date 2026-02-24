# CI/CD Pipeline Setup Guide

## Overview
This guide will help you set up the production-grade CI pipeline for this Spring Boot 3.x project with hexagonal architecture.

---

## 🔧 Prerequisites

1. **GitHub Repository** with admin access
2. **SonarCloud Account** (free for open-source projects)
   - Sign up at: https://sonarcloud.io
3. **Java 21** installed locally (for testing)
4. **Gradle 8+** (included via wrapper)

---

## 📋 Step-by-Step Setup

### Step 1: Configure SonarCloud

1. **Create SonarCloud Organization**
   - Go to https://sonarcloud.io
   - Click "+" → "Analyze new project"
   - Import your GitHub repository
   - Note your **Organization Key** and **Project Key**

2. **Generate SonarCloud Token**
   - Go to: https://sonarcloud.io/account/security
   - Generate a new token
   - Copy and save it securely

3. **Update build.gradle.kts**
   - Open `build.gradle.kts`
   - Replace placeholders in the `sonar` block:
   ```kotlin
   sonar {
       properties {
           property("sonar.projectKey", "YOUR_GITHUB_ORG_lemuel")  // e.g., "myorg_lemuel"
           property("sonar.organization", "YOUR_SONARCLOUD_ORG")    // e.g., "myorg"
           property("sonar.host.url", "https://sonarcloud.io")
       }
   }
   ```

---

### Step 2: Configure GitHub Secrets

Navigate to your GitHub repository → **Settings** → **Secrets and variables** → **Actions**

Add the following secret:

| Secret Name | Value | Description |
|------------|-------|-------------|
| `SONAR_TOKEN` | `<your-sonarcloud-token>` | Token from SonarCloud (Step 1.2) |

> **Note**: `GITHUB_TOKEN` is automatically provided by GitHub Actions

---

### Step 3: Verify Workflow Files

Ensure these files exist in your repository:

```
.github/
├── workflows/
│   └── ci.yml                    # ✅ Created
└── pull_request_template.md      # ✅ Created
```

---

### Step 4: Test the Pipeline Locally

Before pushing, test the build locally:

```bash
# Clean build
./gradlew clean build

# Run tests
./gradlew test

# Generate coverage report
./gradlew jacocoTestReport

# Verify coverage threshold (70%)
./gradlew jacocoTestCoverageVerification

# Check coverage report
open build/reports/jacoco/test/html/index.html
```

---

### Step 5: Push and Verify

1. **Commit and push changes:**
   ```bash
   git add .
   git commit -m "ci: Add production-grade CI pipeline with SonarCloud"
   git push origin master
   ```

2. **Verify the pipeline:**
   - Go to: **Actions** tab in GitHub
   - Check the "CI Pipeline" workflow is running
   - Both jobs should pass:
     - ✅ `build-and-test`
     - ✅ `sonarcloud`

3. **Check SonarCloud dashboard:**
   - Go to: https://sonarcloud.io/dashboard?id=YOUR_PROJECT_KEY
   - Verify Quality Gate status

---

## 🎯 What the Pipeline Does

### Build & Test Job
- ✅ Runs `./gradlew clean build`
- ✅ Executes all tests with `./gradlew test`
- ✅ Generates JaCoCo coverage report
- ✅ Enforces 70% minimum coverage threshold
- ✅ Uploads coverage reports as artifacts (30-day retention)
- ✅ Adds coverage comment to PRs
- ✅ **Fails build if tests fail or coverage < 70%**

### SonarCloud Analysis Job
- ✅ Runs static code analysis
- ✅ Checks code quality and security vulnerabilities
- ✅ Enforces Quality Gate rules
- ✅ **Blocks PR merge if Quality Gate fails**

---

## 🚨 Pipeline Enforcement Rules

### Pull Request Merge Requirements
To merge a PR, ALL of the following must pass:

1. ✅ All tests pass
2. ✅ Code coverage ≥ 70%
3. ✅ SonarCloud Quality Gate passes
4. ✅ No build failures

### Automatic Actions
- 📊 Coverage report posted as PR comment
- 🔍 SonarCloud analysis linked in PR
- ❌ Build fails immediately if coverage < 70%
- ⛔ Quality Gate failure prevents merge

---

## 📊 Recommended README Badges

Add these badges to your `README.md`:

```markdown
# Lemuel - Order Management System

[![CI Pipeline](https://github.com/YOUR_ORG/lemuel/actions/workflows/ci.yml/badge.svg)](https://github.com/YOUR_ORG/lemuel/actions/workflows/ci.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=YOUR_PROJECT_KEY&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=YOUR_PROJECT_KEY)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=YOUR_PROJECT_KEY&metric=coverage)](https://sonarcloud.io/summary/new_code?id=YOUR_PROJECT_KEY)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=YOUR_PROJECT_KEY&metric=bugs)](https://sonarcloud.io/summary/new_code?id=YOUR_PROJECT_KEY)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=YOUR_PROJECT_KEY&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=YOUR_PROJECT_KEY)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=YOUR_PROJECT_KEY&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=YOUR_PROJECT_KEY)

## Architecture

This project follows **Hexagonal Architecture** (Ports & Adapters):

```
src/main/java/github/lms/lemuel/
├── domain/          # Core business logic (entities, value objects, domain services)
├── application/     # Use cases and application services
└── adapter/         # External adapters (REST controllers, JPA repositories, etc.)
```

### Key Principles
- ✅ No business logic in Controllers
- ✅ Dependencies point inward (Adapters → Application → Domain)
- ✅ Domain is framework-agnostic
- ✅ Port/Adapter separation maintained

## Tech Stack
- **Java 21** with Spring Boot 3.5.x
- **Gradle Kotlin DSL**
- **PostgreSQL** + Flyway migrations
- **Elasticsearch** for search
- **Spring Batch** for background jobs
- **MapStruct** for object mapping
- **JWT** authentication
- **SpringDoc OpenAPI** for API docs

## Getting Started

### Prerequisites
- Java 21
- Docker & Docker Compose (for PostgreSQL, Elasticsearch)

### Run Locally
\`\`\`bash
# Start dependencies
docker-compose up -d

# Run application
./gradlew bootRun
\`\`\`

### Run Tests
\`\`\`bash
./gradlew test

# With coverage report
./gradlew test jacocoTestReport
\`\`\`

### View Coverage Report
\`\`\`bash
open build/reports/jacoco/test/html/index.html
\`\`\`

## API Documentation
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI Spec: http://localhost:8080/v3/api-docs

## Contributing
Please read our [Pull Request Template](.github/pull_request_template.md) before submitting PRs.

### PR Requirements
- ✅ All tests pass
- ✅ Coverage ≥ 70%
- ✅ SonarCloud Quality Gate passes
- ✅ Follows hexagonal architecture principles
```

Replace `YOUR_ORG` and `YOUR_PROJECT_KEY` with your actual values.

---

## 🔍 Troubleshooting

### Issue: Coverage check fails
**Solution**: Increase test coverage by adding unit tests. Target files with low coverage shown in the JaCoCo report.

### Issue: SonarCloud Quality Gate fails
**Solution**:
1. Check the SonarCloud dashboard for specific issues
2. Fix code smells, bugs, or security hotspots
3. Push again

### Issue: Build fails due to PostgreSQL connection
**Solution**: The workflow uses GitHub Actions service containers. Ensure the service configuration in `.github/workflows/ci.yml` is correct.

### Issue: "SONAR_TOKEN not found"
**Solution**: Verify the secret is added correctly in GitHub Settings → Secrets and variables → Actions

---

## 📚 Additional Resources

- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [SonarCloud Documentation](https://docs.sonarcloud.io/)
- [JaCoCo Plugin](https://docs.gradle.org/current/userguide/jacoco_plugin.html)
- [Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/)

---

## ✅ Configuration Summary

### Modified Files
- ✅ `build.gradle.kts` - Added JaCoCo + SonarCloud plugins
- ✅ `.github/workflows/ci.yml` - CI pipeline workflow
- ✅ `.github/pull_request_template.md` - PR template

### GitHub Secrets Required
| Secret | Required |
|--------|----------|
| `SONAR_TOKEN` | ✅ Yes |
| `GITHUB_TOKEN` | ✅ Auto-provided |

### Coverage Rules
- **Minimum**: 70% overall coverage
- **Enforcement**: Build fails if below threshold
- **Report**: XML + HTML formats

### Quality Gate
- **Provider**: SonarCloud
- **Wait for result**: Yes (`sonar.qualitygate.wait=true`)
- **Merge blocking**: Yes (via GitHub status check)

---

## 🎉 You're All Set!

Once configured, every push and PR will:
1. ✅ Build the project
2. ✅ Run all tests
3. ✅ Generate coverage report
4. ✅ Enforce 70% coverage threshold
5. ✅ Run SonarCloud analysis
6. ✅ Block merge if Quality Gate fails

Happy coding! 🚀
