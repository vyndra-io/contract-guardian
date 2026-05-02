# CI Integration

Contract Guardian runs as a gate on pull requests and merge requests. When it detects a breaking change, it fails the build — preventing the merge until the issue is resolved.

This guide covers GitHub Actions, GitLab CI, and Docker.

---

## GitHub Actions

### Minimal Setup

This runs Contract Guardian on every PR targeting `main` and prints results to the console:

```yaml
name: Contract Guardian
on:
  pull_request:
    branches: [main]

jobs:
  contract-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0         # required — full history needed for git diff

      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Build Contract Guardian
        run: |
          git clone https://github.com/vyndra-io/contract-guardian.git /tmp/cg
          cd /tmp/cg && mvn package -DskipTests -q

      - name: Scan for breaking changes
        run: |
          java -jar /tmp/cg/contract-guardian-cli/target/contract-guardian-cli-1.0.0.jar \
            scan --diff origin/${{ github.base_ref }}..HEAD
```

> **Important:** `fetch-depth: 0` is required. Without the full git history, `git show origin/main:file` cannot resolve the baseline file content.

### Full Setup — PR Comment + JUnit Report

This setup:
- Posts a formatted summary as a comment directly on the GitHub PR
- Produces a JUnit XML report that GitHub displays in the Checks tab

```yaml
name: Contract Guardian
on:
  pull_request:
    branches: [main]

jobs:
  contract-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Build Contract Guardian
        run: |
          git clone https://github.com/vyndra-io/contract-guardian.git /tmp/cg
          cd /tmp/cg && mvn package -DskipTests -q

      - name: Scan for breaking changes
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          java -jar /tmp/cg/contract-guardian-cli/target/contract-guardian-cli-1.0.0.jar \
            scan \
            --diff origin/${{ github.base_ref }}..HEAD \
            --reporter terminal \
            --reporter junit:contract-guardian-report.xml \
            --github-pr ${{ github.repository }}#${{ github.event.pull_request.number }}

      - name: Publish JUnit report
        uses: actions/upload-artifact@v4
        if: always()      # upload even when the scan fails
        with:
          name: contract-guardian-report
          path: contract-guardian-report.xml
```

> The built-in `GITHUB_TOKEN` has `pull_requests: write` permission by default — no extra configuration needed to post PR comments.

### Approval Override — GitHub

When a PR is blocked by a breaking change, an authorised approver can unblock it by applying the configured label. No changes to the workflow file are required — the scan command automatically checks for the label when `--github-pr` is set and `approval-required-to-bypass: true` is in the config.

**Config (`.contract-guardian.yml`):**
```yaml
gate:
  block-on: breaking
  approval-required-to-bypass: true
  approval-label: "schema-override"
  approvers:
    - alice
    - platform-team
```

**Override flow:**

1. PR is raised → scan fails → PR is blocked.
2. An approver listed under `approvers` adds the label `schema-override` to the PR in GitHub.
3. CI automatically re-runs (or the approver manually triggers it).
4. Contract Guardian fetches the PR labels and label events, sees the label was applied by a listed approver, and downgrades the verdict from **FAIL → WARN** (exit 0).
5. The PR comment is updated with a visible override notice; the PR unblocks.

The `GITHUB_TOKEN` already has permission to read labels and issue events — no extra scope is needed.

---

### Caching the Build

If you run Contract Guardian on every PR, caching the Maven local repository speeds up subsequent runs:

```yaml
      - uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: contract-guardian-${{ hashFiles('/tmp/cg/pom.xml') }}
          restore-keys: contract-guardian-
```

---

## GitLab CI

### Minimal Setup

```yaml
contract-guardian:
  stage: test
  image: eclipse-temurin:17-jre-alpine
  before_script:
    - apk add --no-cache git maven
    - git clone https://github.com/vyndra-io/contract-guardian.git /tmp/cg
    - cd /tmp/cg && mvn package -DskipTests -q
  script:
    - java -jar /tmp/cg/contract-guardian-cli/target/contract-guardian-cli-1.0.0.jar
        scan
        --diff origin/${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}..HEAD
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
```

### Full Setup — MR Note + JUnit Report

This setup posts a formatted summary as a note on the GitLab MR and produces a JUnit report:

```yaml
contract-guardian:
  stage: test
  image: eclipse-temurin:17-jre-alpine
  before_script:
    - apk add --no-cache git maven
    - git clone https://github.com/vyndra-io/contract-guardian.git /tmp/cg
    - cd /tmp/cg && mvn package -DskipTests -q
  script:
    - java -jar /tmp/cg/contract-guardian-cli/target/contract-guardian-cli-1.0.0.jar
        scan
        --diff origin/${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}..HEAD
        --reporter terminal
        --reporter junit:contract-guardian-report.xml
        --gitlab-mr ${CI_PROJECT_PATH}!${CI_MERGE_REQUEST_IID}
  variables:
    GITLAB_TOKEN: $GITLAB_CONTRACT_GUARDIAN_TOKEN
  artifacts:
    reports:
      junit: contract-guardian-report.xml
    when: always
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
```

**Setting up the token:**

1. In GitLab, go to your project → Settings → Access Tokens.
2. Create a token with `api` scope.
3. Add it as a CI/CD variable named `GITLAB_CONTRACT_GUARDIAN_TOKEN` (mask it so it doesn't appear in logs).

### Approval Override — GitLab

Same concept as GitHub. Add the label to the MR, re-run the pipeline, and Contract Guardian reads the label and its event history via the GitLab API.

**Config (`.contract-guardian.yml`):**
```yaml
gate:
  block-on: breaking
  approval-required-to-bypass: true
  approval-label: "schema-override"
  approvers:
    - alice
    - platform-team
```

Contract Guardian uses `resource_label_events` to verify *who* applied the label, so only listed approvers can grant an override — adding the label yourself does not count unless your username is in `approvers`.

The existing `GITLAB_CONTRACT_GUARDIAN_TOKEN` with `api` scope covers label and event reads — no extra scope is needed.

**Self-hosted GitLab:**

Add `GITLAB_API_URL` to point at your instance:

```yaml
variables:
  GITLAB_TOKEN: $GITLAB_CONTRACT_GUARDIAN_TOKEN
  GITLAB_API_URL: https://gitlab.example.com/api/v4
```

---

## Docker

A Dockerfile is included in the repository root. Build the image after running `mvn package`:

```bash
mvn package -DskipTests
docker build -t contract-guardian .
```

### Scan the Current Directory

```bash
docker run --rm \
  -v $(pwd):/workspace \
  -w /workspace \
  contract-guardian \
  scan --diff origin/main..HEAD
```

### Scan with a GitHub PR Comment

```bash
docker run --rm \
  -v $(pwd):/workspace \
  -w /workspace \
  -e GITHUB_TOKEN=$GITHUB_TOKEN \
  contract-guardian \
  scan \
  --diff origin/main..HEAD \
  --reporter terminal \
  --github-pr myorg/my-service#123
```

### Docker in GitHub Actions

```yaml
      - name: Build Contract Guardian image
        run: docker build -t contract-guardian /tmp/cg

      - name: Scan
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          docker run --rm \
            -v ${{ github.workspace }}:/workspace \
            -w /workspace \
            -e GITHUB_TOKEN \
            contract-guardian \
            scan \
            --diff origin/${{ github.base_ref }}..HEAD \
            --github-pr ${{ github.repository }}#${{ github.event.pull_request.number }}
```

---

## Maven Plugin (Build-time Validation)

For Maven projects, the plugin integrates directly with the build lifecycle — no separate CI step needed.

The plugin is distributed via [JitPack](https://jitpack.io), which builds it directly from the GitHub repository. No account or token is required.

### Step 1 — Add the JitPack Repository

In your service project's `pom.xml`:

```xml
<pluginRepositories>
  <pluginRepository>
    <id>jitpack</id>
    <url>https://jitpack.io</url>
  </pluginRepository>
</pluginRepositories>
```

### Step 2 — Add the Plugin

```xml
<plugin>
  <groupId>com.github.vyndra-io.contract-guardian</groupId>
  <artifactId>contract-guardian-maven-plugin</artifactId>
  <version>main-SNAPSHOT</version>
  <executions>
    <execution>
      <goals>
        <goal>validate</goal>
      </goals>
    </execution>
  </executions>
  <configuration>
    <diff>origin/main..HEAD</diff>
  </configuration>
</plugin>
```

Use `main-SNAPSHOT` to always track the latest build from `main`. To pin to a specific release, use a git tag as the version (e.g. `v1.0.0`).

The `validate` goal runs in the `validate` phase (the very first Maven lifecycle phase) and fails the build with a clear message when breaking changes are found.

### GitLab CI with the Maven Plugin

```yaml
contract-guardian:
  stage: test
  image: eclipse-temurin:17-jdk-alpine
  script:
    - mvn validate --batch-mode
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
```

No extra settings file or token is needed — JitPack is a public endpoint.

For full Maven plugin documentation, see [Maven Plugin](maven-plugin.md).

---

## Environment Variables

| Variable | Used by | Purpose |
|---|---|---|
| `GITHUB_TOKEN` | GitHub PR reporter, approval checker | Token with `pull_requests` write scope (built-in token is sufficient) |
| `GITLAB_TOKEN` | GitLab MR reporter, approval checker | Token with `api` scope |
| `GITLAB_API_URL` | GitLab MR reporter, approval checker | API base for self-hosted instances, e.g. `https://gitlab.example.com/api/v4` |

---

## Tips

- **Always set `fetch-depth: 0`** in GitHub Actions, or fetch the full history in GitLab. Contract Guardian uses `git show <ref>:<file>` to load baseline content. Shallow clones don't have the baseline commit.
- **Run only on PR/MR events**, not on push to main. On main there is nothing to diff against.
- **Use the JUnit XML reporter in CI** so your CI platform can display per-file results in its test UI, not just a pass/fail status.
- **Store tokens as secrets**, never hardcoded in workflow files.
