# AWS Emulator samples

[![CI](https://github.com/manojkhanwalkar/aws-emulator-samples/actions/workflows/ci.yml/badge.svg)](https://github.com/manojkhanwalkar/aws-emulator-samples/actions/workflows/ci.yml)
[![CodeQL](https://github.com/manojkhanwalkar/aws-emulator-samples/actions/workflows/codeql.yml/badge.svg)](https://github.com/manojkhanwalkar/aws-emulator-samples/actions/workflows/codeql.yml)

Small, commented Java programs that show how to use **[AWS Emulator](https://docs.aws.amazon.com/emulator/latest/userguide/what-is-service.html)**,
AWS's own local emulator of the AWS APIs. You test code that calls AWS on your own machine: no AWS
account needed, and your requests and data never go to AWS.

Each sample is one class with a `main` method: open the project in IntelliJ IDEA, start the emulator,
and click the green arrow.

## What the samples show

| # | Sample | What you learn |
|---|---|---|
| 1 | [`S01_ConnectAndCheck`](src/main/java/samples/S01_ConnectAndCheck.java) | Connect, use the health check, and prove requests reach the emulator, not AWS (account `000000000000`) |
| 2 | [`S02_S3Buckets`](src/main/java/samples/S02_S3Buckets.java) | Amazon S3: buckets and objects, and why S3 needs path-style addressing here |
| 3 | [`S03_DynamoDbTables`](src/main/java/samples/S03_DynamoDbTables.java) | DynamoDB: create a table, put, get, query, and a conditional write refused as on AWS |
| 4 | [`S04_SqsQueues`](src/main/java/samples/S04_SqsQueues.java) | SQS: send, receive with long polling, delete |
| 5 | [`S05_SnsFanOutToSqs`](src/main/java/samples/S05_SnsFanOutToSqs.java) | Services working together: one SNS message delivered to two SQS queues inside the emulator |
| 6 | [`S06_SameErrorsAsAws`](src/main/java/samples/S06_SameErrorsAsAws.java) | Invalid calls fail with the same status, error code and typed exception as on AWS; a not-yet-implemented operation answers HTTP 501 `NotImplemented` |
| 7 | [`S07_SignedRequests`](src/main/java/samples/S07_SignedRequests.java) | Every request must be signed with the emulator's own keys; other keys (even real ones) and a wrong secret are refused |
| 8 | [`S08_SecretsManager`](src/main/java/samples/S08_SecretsManager.java) | Secrets Manager: store, read, change (new version) and delete a secret |
| 9 | [`S09_PrivateEmulatorPerRun`](src/main/java/samples/S09_PrivateEmulatorPerRun.java) | Start your own emulator from code, on a free port, with separate empty state, and stop it |
| — | [`EmulatorBackedTest`](src/test/java/samples/EmulatorBackedTest.java) | The same in a JUnit test: the test class gets its own emulator; one test also uses `--auth off` |

**How the emulator works**

| # | Sample | What you learn |
|---|---|---|
| 10 | [`S10_IamAndStsKeys`](src/main/java/samples/S10_IamAndStsKeys.java) | Keys made *in* the emulator work: an IAM user's access key, and STS `AssumeRole` temporary credentials. Its policy checks are not AWS's: not a permission test |
| 11 | [`S11_OneEmulatorPerRegion`](src/main/java/samples/S11_OneEmulatorPerRegion.java) | One emulator does not keep every service apart by Region (CloudWatch Logs log groups show up in every Region); run one emulator per Region instead |
| 12 | [`S12_LifecycleOptions`](src/main/java/samples/S12_LifecycleOptions.java) | `--idle-timeout` stops an emulator by itself, cleanly; how to cap its memory (explained, not run) |
| 13 | [`scripts/cli-sample.sh`](scripts/cli-sample.sh) | Sample 1 with the AWS CLI, configured only with environment variables (`AWS_ENDPOINT_URL` and co.) |

**Services working together**

| # | Sample | What you learn |
|---|---|---|
| 14 | [`S14_S3EventNotifications`](src/main/java/samples/S14_S3EventNotifications.java) | Upload a file: S3 sends an event to an SQS queue and runs a Lambda function. Shows the queue policy S3 needs, the test event, prefix filters |
| 15 | [`S15_EventBridgeToSqs`](src/main/java/samples/S15_EventBridgeToSqs.java) | An EventBridge rule with an event pattern (numeric match) delivers matching custom events to SQS, once the queue's policy allows it |
| 16 | [`S16_LambdaFunctions`](src/main/java/samples/S16_LambdaFunctions.java) | Deploy Python and Node.js functions from zip files built in code and invoke them; function errors; which runtimes the emulator can run |
| 17 | [`S17_StepFunctions`](src/main/java/samples/S17_StepFunctions.java) | A two-step state machine (Lambda task, then a Choice), as a Standard and as an Express workflow (`StartSyncExecution` and the host prefix) |
| 18 | [`S18_CloudFormationStack`](src/main/java/samples/S18_CloudFormationStack.java) | Deploy a template with a bucket, a table and a queue, use them through the stack outputs, delete the stack (and why a non-empty bucket stops that) |

**Deeper into one service**

| # | Sample | What you learn |
|---|---|---|
| 19 | [`S19_SqsDeadLetterAndFifo`](src/main/java/samples/S19_SqsDeadLetterAndFifo.java) | A dead-letter queue (`maxReceiveCount`) and moving messages back; FIFO order per message group and deduplication |
| 20 | [`S20_DynamoDbDeeper`](src/main/java/samples/S20_DynamoDbDeeper.java) | `TransactWriteItems` cancelled by a condition, with its cancellation reasons; a GSI query; pagination; TTL |
| 21 | [`S21_S3Deeper`](src/main/java/samples/S21_S3Deeper.java) | Multipart upload, versioning and delete markers, presigned URLs accepted and then refused once expired |
| 22 | [`S22_KmsAndSecrets`](src/main/java/samples/S22_KmsAndSecrets.java) | KMS encrypt/decrypt with an encryption context, data keys, and a Secrets Manager secret under your own key (unreadable once the key is disabled) |

**Testing patterns**

| # | Sample | What you learn |
|---|---|---|
| 23 | [`@WithAwsEmulator`](src/test/java/samples/junit/WithAwsEmulator.java), [`AwsEmulatorExtension`](src/test/java/samples/junit/AwsEmulatorExtension.java), [`S23_JUnitExtensionTest`](src/test/java/samples/junit/S23_JUnitExtensionTest.java) | A JUnit 5 extension: one private emulator per test class, and SDK clients injected as test method parameters |
| 24 | [`S24_SpringBootTest`](src/test/java/samples/spring/S24_SpringBootTest.java) | A Spring Boot test: `@DynamicPropertySource` points the application's own `S3Client` bean at a private emulator |
| 25 | [`S25_AsyncClients`](src/main/java/samples/S25_AsyncClients.java) | `S3AsyncClient` and `DynamoDbAsyncClient` on the Netty HTTP client, and how its TLS trust is set |
| 26 | [`scripts/check-project.sh`](scripts/check-project.sh) | "Does my project work on it?": the fidelity tests and the release's fidelity scores for just the operations you call ([below](#does-my-project-work-on-aws-emulator)) |

Two helper classes do the plumbing, and are worth reading first:

- [`Emulator`](src/main/java/samples/emulator/Emulator.java) connects to a running emulator and points any AWS SDK client at it.
- [`EmulatorProcess`](src/main/java/samples/emulator/EmulatorProcess.java) starts and stops a private emulator.

## How it works, in four points

1. **Endpoint.** The emulator serves every AWS service, and its health route `GET /__admin/health`, on
   one HTTPS address: by default `https://127.0.0.1:9297`.
2. **Its own keys.** Each time it starts, it creates an access key and secret key that work only with that
   run, and checks that every request is signed with them (SigV4), as AWS checks your real keys.
   (`--auth off` turns the check off.)
3. **Its own certificate.** It serves HTTPS with a certificate it makes on each start (`ca.pem`).
4. **Files.** It writes the keys (`emulator_credentials`), the certificate (`ca.pem`, and
   `truststore.p12` for Java), and an AWS `config` file to `~/.aws/emulator/<port>/`, and deletes them
   when it stops cleanly. Everything it holds (buckets, tables, queues...) is in memory: there is no
   reset command, a restart gives you a clean, empty state.

So an AWS SDK client needs four settings to use it. `Emulator.client(...)` applies all four to any
service's client builder:

```java
try (Emulator emulator = Emulator.connect();
     S3Client s3 = emulator.client(S3Client.builder().forcePathStyle(true))) {
    s3.createBucket(b -> b.bucket("my-bucket"));   // ordinary SDK code from here on
}
```

| Setting | Set by `Emulator.client(...)` as |
|---|---|
| Endpoint | `endpointOverride(https://127.0.0.1:<port>)` |
| Region | `us-east-1` (the emulator accepts any, but use one throughout) |
| Credentials | the `[default]` profile of `~/.aws/emulator/<port>/emulator_credentials` |
| HTTPS trust | an HTTP client that trusts `~/.aws/emulator/<port>/ca.pem` and nothing else |

Your application code doesn't change. Only how the client is built differs from production.

`Emulator` also turns off the SDK's host prefix injection, as the emulator guide advises for Java, so
operations that would send requests to a host such as `data-127.0.0.1` reach the emulator. And it uses
`127.0.0.1`, not `localhost`, so the SDK puts S3 bucket names in the URL path, the only form the
emulator accepts.

## The `aws-emulator` options these samples use

| Option | What it does | Where |
|---|---|---|
| *(none)* | HTTPS on `127.0.0.1:9297`, requests must be signed, runs until Ctrl+C | samples 1–8, 10, 14–22, 25 |
| `-p 9300` | Another port. Each port has its own folder, keys and resources, so several emulators can run side by side | `AWS_EMULATOR_PORT` below |
| `-p 0` | The operating system picks a free port; read it from `endpoint_url` in the JSON startup line of the log | `EmulatorProcess` |
| `-t off` | No usage telemetry for this run (it is on by default) | everywhere |
| `--idle-timeout 600` | Stops by itself after 600 seconds without an API call, so a forgotten emulator doesn't keep running | `EmulatorProcess`; sample 12 uses `--idle-timeout 3` to watch it stop |
| `--auth off` | Serves every request, signed or not, and writes no credentials file. Only for throwaway setups | `EmulatorBackedTest`, sample 23 |
| `-v` | Prints the version | install check |

See the [command reference](https://docs.aws.amazon.com/emulator/latest/userguide/cli-reference.html).

## Run the samples in IntelliJ IDEA

1. **Install AWS Emulator** (once). On Fedora, RHEL or Amazon Linux:
   ```bash
   sudo dnf install -y https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/latest/aws-emulator-x86_64.rpm
   aws-emulator -v
   ```
   For Debian or Ubuntu, Arm, checking AWS's signature first, or macOS and Windows (in Docker), see
   [Getting started](https://docs.aws.amazon.com/emulator/latest/userguide/getting-started.html).
2. **Open the project:** *File › Open…*, choose this folder. IntelliJ imports the Maven project and
   downloads the dependencies. It needs a JDK 21 or later (*File › Project Structure › SDK*).
3. **Start the emulator** in a terminal (or with the *Start AWS Emulator* run configuration):
   ```bash
   aws-emulator -t off       # -t off: no usage telemetry sent to AWS
   ```
   It keeps running in that terminal; **Ctrl+C** stops it, and its buckets, tables and queues are gone.
   If it says `Address already in use`, something else has port 9297: start it with `-p 9300` and see
   the note on other ports below.
4. **Run a sample:** open it and click the green arrow next to `main`, or pick one of the ready-made run
   configurations in the toolbar (*0 - Run all samples*, *1 - Connect and check*, …). Samples 9, 11
   and 12 and the JUnit tests start their own emulators, so for those only step 1 is needed.

From a terminal instead (with Maven 3.9 or later installed):

```bash
mvn -q compile exec:java                                              # all samples
mvn -q compile exec:java -Dexec.mainClass=samples.S03_DynamoDbTables  # one sample
mvn test                                                              # the JUnit test
```

If your emulator runs on another port (`aws-emulator -p 9300`), set the `AWS_EMULATOR_PORT`
environment variable, or add the VM option `-Demulator.port=9300` in the run configuration
(*Modify options › Add VM options*; from a terminal, `mvn -q compile exec:java -Demulator.port=9300`).

Bucket, table and queue names get a random suffix (`samples-bucket-417bqz`), and each sample deletes
what it creates, so you can run them again and again against the same emulator.

## Example output

An excerpt, from emulator 1.0.531 (names, ports and your home folder differ):

```text
== 1. Connect and check ==
Emulator endpoint: https://127.0.0.1:9297
Its files (credentials, certificate, config): /home/you/.aws/emulator/9297
Health check (/__admin/health) is ok: true
Account: 000000000000   ARN: arn:aws:iam::000000000000:user/emulator
-> Account 000000000000: these requests reach the emulator, not AWS.

== 5. SNS to SQS fan-out (services working together) ==
...
Published one message to the topic
Queue samples-billing-ycpwz8 received: Order o-42 was placed
Queue samples-shipping-ycpwz8 received: Order o-42 was placed
Deleted the queues and the topic

== 6. The same errors as AWS ==
DynamoDB CreateTable with the name "ab":
   HTTP 400  ValidationException: 1 validation error detected: Value 'ab' at 'tableName' failed to satisfy constraint: Member must have length greater than or equal to 3
...
SNS Publish to a phone number (not implemented yet):
   HTTP 501  NotImplemented: operation Publish to a PhoneNumber is scaffolded but not implemented in this emulator

== 7. Requests must be signed with the emulator's keys ==
With the emulator's keys: accepted, account 000000000000
With other keys:          refused, HTTP 403 InvalidClientTokenId: The security token included in the request is invalid.
With a wrong secret key:  refused, HTTP 403 SignatureDoesNotMatch: The request signature we calculated does not match ...

== 9. A private emulator for each run ==
Started a private emulator on port 39943 (picked by the OS)
Its tables: [samples-private]
A second one on port 39505 has tables: [] (separate state)
Stopped. Its keys and certificate are deleted: true; its table went with it (everything was in memory).

All samples ran.
```

## Good to know

- **Not for production or security testing.** The emulator doesn't evaluate IAM or resource policies the
  way AWS does. Use it to build and test your code.
- **One Region, one account.** Resources aren't kept apart by Region. Use one Region throughout, or one
  emulator per Region on separate ports.
- **Fidelity varies by service.** Where an operation is implemented, the emulator aims for the same
  results and errors as AWS. An operation that isn't implemented yet answers HTTP 501
  `NotImplemented`, so you notice at once. For example, version 1.0.531 doesn't implement SNS `Publish` to
  a phone number (SMS), as sample 6 shows. AWS publishes per-service
  [fidelity scores](https://github.com/aws/aws-emulator/releases) with each release. Write your tests to
  expect what AWS does, not what the emulator does today.
- **Test runs, not long-running use.** Start it for your tests and stop it afterwards, or use
  `--idle-timeout`. Sample 9 and `EmulatorBackedTest` show the pattern.
- **Other tools:** the AWS CLI and other SDKs use environment variables instead of code. See
  [Pointing your SDK at AWS Emulator](https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html):
  ```bash
  export AWS_ENDPOINT_URL=https://127.0.0.1:9297
  export AWS_SHARED_CREDENTIALS_FILE=~/.aws/emulator/9297/emulator_credentials
  export AWS_CONFIG_FILE=~/.aws/emulator/9297/config
  export AWS_CA_BUNDLE=~/.aws/emulator/9297/ca.pem
  export AWS_EC2_METADATA_DISABLED=true
  export AWS_DISABLE_HOST_PREFIX_INJECTION=true
  export AWS_REGION=us-east-1 AWS_DEFAULT_REGION=us-east-1
  unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE   # or your real keys win
  aws sts get-caller-identity     # Account: 000000000000
  ```
  Java code that builds its clients the default way (no `Emulator` helper) also needs to trust the
  certificate: `export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$HOME/.aws/emulator/9297/truststore.p12 -Djavax.net.ssl.trustStoreType=PKCS12"`.
  That replaces the JVM's default trust store, so other HTTPS calls from that JVM then fail; this is why
  `Emulator` trusts `ca.pem` in its SDK clients only.

## More on samples 10 to 26

### Prerequisites

Samples 10 to 25 need nothing beyond the first nine: AWS Emulator, and a JDK. In addition:

- **Memory cap (sample 12, explained only).** The emulator keeps everything in memory (about 160 MiB
  idle, growing with what your tests create) and has no memory option of its own. Cap it from outside:

  ```bash
  systemd-run --user --scope -p MemoryMax=2G -p MemorySwapMax=0 aws-emulator     # Linux, cgroup v2
  docker run --rm --memory=2g --memory-swap=2g ... aws-emulator                  # Docker
  ```

  Over the cap the kernel kills it with exit code 137 (128 + SIGKILL): its resources are lost and,
  as it was not a clean stop, its files in `~/.aws/emulator/<port>/` stay until the next start on that
  port. The sample does not demonstrate this, on purpose: filling an emulator until it is killed only
  sets off out-of-memory alerts. See [Getting started on Linux](https://docs.aws.amazon.com/emulator/latest/userguide/getting-started-linux.html).
- **Sample 13** needs the [AWS CLI v2](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
  and `curl`. To install the CLI for your user only, without `sudo`: download and unzip the installer,
  then `./aws/install -i ~/.local/aws-cli -b ~/.local/bin`.
- **Sample 26** needs `curl` and `python3`, and internet access (it reads two files from GitHub).
- **Sample 24** uses Spring Boot (`spring-boot-test`) in **test scope only**, so the samples in
  `src/main` stay plain Java and the jar has no Spring in it.

Samples 13 and 26 are shell scripts: run them from a terminal (`scripts/cli-sample.sh`) or with their
run configurations. Samples 23 and 24 are tests: run them with the green arrow next to the class, or
`mvn test -Dtest='S23_*'`. Samples 11 and 12 start their own emulators; the rest use the running one.

### Does my project work on AWS Emulator?

AWS publishes [fidelity tests](https://github.com/aws/aws-emulator-evaluation-suite) (short boto3
programs, each checked against real AWS) and, with each [release](https://github.com/aws/aws-emulator/releases),
fidelity scores: which of those tests that release passes (the `scores.json.gz` asset). The guide's
[check-project](https://docs.aws.amazon.com/emulator/latest/userguide/check-project.html) page explains how
to use them: list the operations your project calls, find the fidelity tests for them in
`manifest.jsonl`, and check their scores for your release. For operations no test covers, compare the
emulator with AWS yourself.

`scripts/check-project.sh` does those steps for the operations listed in
[`scripts/project-operations.txt`](scripts/project-operations.txt) (this project's; write your own list the
same way, one `service:Operation` per line):

```bash
scripts/check-project.sh                          # the installed emulator's release
scripts/check-project.sh -r v1.0.531 -v           # a given release; -v names the failing tests
scripts/check-project.sh -f my-operations.txt     # your project's operations
```

```text
Operation                                      tests  passed  failed  unscored
s3:PutBucketNotificationConfiguration            128     ...
stepfunctions:StartSyncExecution                  58      45      13         0
...
```

A test counts for an operation if it calls it, so widely used calls (`sts:GetCallerIdentity`,
`sqs:CreateQueue`) appear in many tests about other things. Read the failing tests for the operations
you depend on (`behavioral/<service>/check_<name>.py`) to see whether they check behaviour your project
relies on. To run fidelity tests yourself against a newer emulator, use the suite's own runner
(`fidelity-tests-runner`, see its README; it needs [uv](https://docs.astral.sh/uv/)).

### Gaps found in emulator 1.0.531

The samples show these honestly rather than hiding them; each prints what the emulator did.

| Where | What the emulator does | What AWS does |
|---|---|---|
| IAM policies (10) | An IAM user with no policy is refused by S3 and DynamoDB, but SQS `ListQueues` succeeds | Refuses all three |
| Regions (11) | DynamoDB, SQS, SNS, KMS, Secrets Manager and SSM keep resources per Region, but CloudWatch Logs log groups are shared by every Region | Every one of these is regional |
| Lambda runtimes (16) | Runs `python3.x` (in a built-in RustPython that reports Python 3.12, whatever the runtime says) and `nodejs*.x` (a built-in engine that reports `v20.0.0`, `v22.0.0`...). Other runtimes (`java21`, `ruby3.3`, `provided.al2023`, `dotnet8`) can be created, but `Invoke` fails with HTTP 502 `InvalidRuntimeException` | Runs every supported runtime |
| Lambda isolation (16) | Function code runs on your machine with your user's permissions | Runs in an isolated environment |
| Step Functions (17) | `StartSyncExecution` works only with host prefix injection off (`sync-127.0.0.1` does not resolve); `Emulator.client(...)` turns it off, as the guide advises | (Not an emulator gap: AWS needs the prefix) |
| Key and bucket policies | Never checked (see the guide's Security page) | Checked |

Everything else these samples try behaves as on AWS in 1.0.531, including some details that are easy
to get wrong: S3 and EventBridge refuse to deliver to a queue whose policy does not allow them, S3 sends
an `s3:TestEvent` first and refuses overlapping notification rules, `DeleteStack` fails on a non-empty
bucket, `TransactionCanceledException` carries per-item reasons, TTL starts as `ENABLING`, expired
presigned URLs get `403 AccessDenied`, and Lambda writes function logs to CloudWatch Logs when its role
allows it.

## How the project is checked

Every pull request runs:

| Workflow | What it does |
|---|---|
| **CI** (`.github/workflows/ci.yml`) | Installs AWS Emulator (after checking AWS's signature), then runs `mvn verify` on Java 21 and 26. The tests run every sample against private emulators, and the build fails below 95% line coverage. |
| **CodeQL** | Static analysis of the Java code and the workflows; findings appear under *Security › Code scanning* |
| **Security** | Dependency review (fails on a new high or critical vulnerability) and a Gitleaks secret scan |

A version tag (`git tag v1.0.0 && git push origin v1.0.0`) runs **Release**, which tests the tagged
code and publishes a GitHub release with the jar. Dependabot opens weekly update pull requests for the
Maven dependencies and the (SHA-pinned) actions. Changes go through pull requests to `main`, which is
protected: the CI and security checks must pass before a merge.

To run the same checks locally (AWS Emulator installed, nothing else needed):

```bash
mvn verify        # tests + coverage report in target/site/jacoco/index.html (fails below 95%)
```

Without AWS Emulator installed, the emulator-backed tests are skipped (a few helper tests still run, using
the POSIX `sleep` and `true` commands), so build with `mvn verify -Djacoco.skip=true`.

## Project layout

```text
pom.xml                         Maven build: AWS SDK for Java 2.x, JUnit, exec plugin
.run/                           IntelliJ run configurations (one per sample, the test, the emulator;
                                IntelliJ lists them in the toolbar automatically)
src/main/java/samples/
  emulator/Emulator.java        connect to a running emulator; build SDK clients for it
  emulator/EmulatorProcess.java start and stop a private emulator
  S01_… to S25_…                the samples (13, 23, 24 and 26 are not in this folder)
  Checks.java, LambdaFunctions.java   small helpers the samples share
  RunAllSamples.java            runs all the Java samples in order
scripts/cli-sample.sh           sample 13, the AWS CLI with environment variables
scripts/check-project.sh        sample 26, fidelity tests and scores for your operations
src/main/resources/simplelogger.properties   SDK logging level (warn; set debug to see requests)
src/test/java/samples/            JUnit tests: every sample against a private emulator, and the helpers
src/test/java/samples/junit/      sample 23, a JUnit 5 extension (@WithAwsEmulator) and its test
src/test/java/samples/spring/     sample 24, a Spring Boot test
.github/                          CI, CodeQL, security checks, release, Dependabot, PR template
```
