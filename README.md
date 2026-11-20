# AWS Emulator samples

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
| *(none)* | HTTPS on `127.0.0.1:9297`, requests must be signed, runs until Ctrl+C | samples 1–8 |
| `-p 9300` | Another port. Each port has its own folder, keys and resources, so several emulators can run side by side | `AWS_EMULATOR_PORT` below |
| `-p 0` | The operating system picks a free port; read it from `endpoint_url` in the JSON startup line of the log | `EmulatorProcess` |
| `-t off` | No usage telemetry for this run (it is on by default) | everywhere |
| `--idle-timeout 600` | Stops by itself after 600 seconds without an API call, so a forgotten emulator doesn't keep running | `EmulatorProcess` |
| `--auth off` | Serves every request, signed or not, and writes no credentials file. Only for throwaway setups | `EmulatorBackedTest` |
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
   configurations in the toolbar (*0 - Run all samples*, *1 - Connect and check*, …). Sample 9 and the
   JUnit test start their own emulators, so for those only step 1 is needed.

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

## Project layout

```text
pom.xml                         Maven build: AWS SDK for Java 2.x, JUnit, exec plugin
.run/                           IntelliJ run configurations (one per sample, the test, the emulator;
                                IntelliJ lists them in the toolbar automatically)
src/main/java/samples/
  emulator/Emulator.java        connect to a running emulator; build SDK clients for it
  emulator/EmulatorProcess.java start and stop a private emulator
  S01_… to S09_…                the samples
  RunAllSamples.java            runs them all in order
src/main/resources/simplelogger.properties   SDK logging level (warn; set debug to see requests)
src/test/java/samples/EmulatorBackedTest.java a JUnit test with its own emulator
```
