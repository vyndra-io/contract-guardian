# Reporters

Contract Guardian supports four ways to deliver results. You can use **multiple reporters at the same time** — for example, print to the console, write a JUnit XML file, and post a PR comment all in a single scan run.

---

## Terminal Reporter (default)

Prints colored, human-readable output to the console. This is the default when no `--reporter` flag is passed.

```bash
contract-guardian scan --diff origin/main..HEAD
# same as:
contract-guardian scan --diff origin/main..HEAD --reporter terminal
```

**Example output:**

```
  Scanning 4 changed files...

  BREAKING  schemas/kafka/avro/payment-value.avsc
    Field "customer_id" removed — breaks backward compatibility
    Fix: Add a default value to the field before removing it

  BREAKING  api/openapi/catalog-service.yaml
    Endpoint removed: POST /products — existing callers will break
    Fix: Restore the endpoint or coordinate removal with all consumers

  WARNING   schemas/kafka/proto/order-event.proto
    Field 'status' (tag 4) removed from message 'OrderEvent' without reserved declaration
    Fix: Add 'reserved 4; reserved "status";' to the message body

  PASS      schemas/kafka/json/user-preferences.json
    No breaking changes detected

  Result: FAIL — 2 breaking, 1 warning, 1 pass  (scanned in 420ms)
  Policy requires 0 breaking changes to merge.
```

Color is enabled automatically when a terminal is detected and disabled when stdout is redirected to a file or pipe.

---

## JUnit XML Reporter

Writes a JUnit-compatible XML report to a file. Most CI systems (GitHub, GitLab, Jenkins, CircleCI) can parse this format and display results as a test run.

```bash
contract-guardian scan --diff origin/main..HEAD \
  --reporter junit:build/reports/contract-guardian.xml
```

**How findings map to JUnit structure:**

| Contract Guardian concept | JUnit element |
|---|---|
| Each scanned file | `<testsuite>` |
| Each finding | `<testcase>` |
| BREAKING finding | `<failure>` |
| WARNING finding | `<failure type="WARNING">` |
| File with no findings | Single passing `<testcase>` |

**Enable in GitHub Actions:**

```yaml
- name: Upload contract report
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: contract-guardian-report
    path: build/reports/contract-guardian.xml
```

**Enable in GitLab CI:**

```yaml
artifacts:
  reports:
    junit: build/reports/contract-guardian.xml
  when: always
```

---

## GitHub PR Comment Reporter

Posts a formatted Markdown summary as a comment on the pull request. On subsequent runs it updates the **same comment** rather than posting a new one.

### Setup

Set the `GITHUB_TOKEN` environment variable. In GitHub Actions, the built-in token works out of the box:

```yaml
env:
  GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

For local use or other CI systems, create a [personal access token](https://github.com/settings/tokens) with `pull_requests` write scope.

### Usage

```bash
# Format: owner/repo#<pr-number>
contract-guardian scan --diff origin/main..HEAD \
  --github-pr myorg/my-service#456
```

### What the Comment Looks Like

```
## Contract Guardian

**Result: FAIL** — 2 breaking, 1 warning, 1 pass

| File | Status | Findings |
|---|---|---|
| `schemas/kafka/avro/payment-value.avsc` | ❌ BREAKING | Field "customer_id" removed |
| `api/openapi/catalog-service.yaml` | ❌ BREAKING | Endpoint removed: POST /products |
| `schemas/kafka/proto/order-event.proto` | ⚠️ WARNING | Field 'status' removed without reserved |
| `schemas/kafka/json/user-preferences.json` | ✅ PASS | — |

<details>
<summary>Details and fix suggestions</summary>
...
</details>
```

> The HTML comment `<!-- contract-guardian -->` at the top of the comment is how the reporter identifies existing comments to update. Never delete it manually.

---

## GitLab MR Note Reporter

Posts the same Markdown summary as a note on a GitLab merge request. Updates the **same note** on subsequent runs.

### Setup

Set the `GITLAB_TOKEN` environment variable. Create a [personal access token](https://gitlab.com/-/profile/personal_access_tokens) with `api` scope.

```bash
export GITLAB_TOKEN=glpat-xxxxxxxxxxxxxxxxxxxx
```

In GitLab CI, use a project or group access token stored as a CI variable:

```yaml
variables:
  GITLAB_TOKEN: $GITLAB_CONTRACT_GUARDIAN_TOKEN
```

### Usage

```bash
# Format: project/path!<mr-iid>
contract-guardian scan --diff origin/main..HEAD \
  --gitlab-mr mygroup/my-service!23
```

Use the project path as it appears in the GitLab URL (e.g., `mygroup/my-service`), not the numeric project ID.

### Self-Hosted GitLab

```bash
export GITLAB_API_URL=https://gitlab.example.com/api/v4
```

If `GITLAB_API_URL` is not set, it defaults to `https://gitlab.com/api/v4`.

### Full GitLab CI Example

```yaml
contract-guardian:
  stage: test
  script:
    - java -jar /path/to/contract-guardian.jar scan
        --diff origin/${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}..HEAD
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

---

## Using Multiple Reporters Together

Combine any reporters in a single command by specifying `--reporter` multiple times:

```bash
contract-guardian scan --diff origin/main..HEAD \
  --reporter terminal \
  --reporter junit:build/reports/contract-guardian.xml \
  --github-pr myorg/my-service#456
```

All reporters run regardless of the scan result. A PR comment is posted even when the scan passes, so reviewers always see the current status.
