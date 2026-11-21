package samples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import samples.emulator.Emulator;
import samples.junit.WithAwsEmulator;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;

/**
 * Runs samples 10 to 22, 25 and the CLI script (13) the way IntelliJ does, through their
 * {@code main} methods, against this class's private emulator (from {@link WithAwsEmulator}, sample
 * 23), and checks what they printed. Samples 11 and 12 start emulators of their own.
 */
@WithAwsEmulator
class MoreSamplesTest {

    @Test
    void iamAndStsKeysWork(Emulator emulator) {
        String out = runMain(emulator, S10_IamAndStsKeys::main);
        assertTrue(out.contains("GetCallerIdentity says: arn:aws:iam::000000000000:user/samples-user-"), out);
        assertTrue(out.contains("S3 ListBuckets, user has no policy     : refused, HTTP 403 AccessDenied"), out);
        assertTrue(out.contains("S3 ListBuckets, after an Allow policy  : accepted"), out);
        assertTrue(out.contains("with the deleted key : refused, HTTP 403 InvalidClientTokenId"), out);
        assertTrue(out.contains(":assumed-role/samples-role-"), out);
    }

    @Test
    void oneEmulatorPerRegionKeepsRegionsApart(Emulator emulator) {
        String out = runMain(emulator, S11_OneEmulatorPerRegion::main);
        assertTrue(out.contains("Seen from eu-west-1: table not seen"), out);
        assertTrue(out.contains("Seen from the eu-west-1 emulator: table not seen, log group not seen"), out);
    }

    @Test
    void idleTimeoutStopsTheEmulatorCleanly(Emulator emulator) {
        String out = runMain(emulator, S12_LifecycleOptions::main);
        assertTrue(out.contains("Still running after 5 seconds of calls"), out);
        assertTrue(out.contains("exit code 0; its files are deleted: true (a clean stop)"), out);
        assertTrue(out.contains("killed with exit code 137"), out); // only explained, never done
    }

    @Test
    void anEmulatorThatCannotStartIsReportedAndCleanedUp() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        int free = port;
        // "false -p <port> ...": exits at once, like an emulator that cannot start.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> S12_LifecycleOptions.startOn("false", free));
        assertTrue(e.getMessage().contains("did not start on port " + free), e.getMessage());
        assertFalse(Files.exists(Path.of(System.getProperty("user.home"), ".aws", "emulator", Integer.toString(free))));
        assertThrows(IOException.class, () -> S12_LifecycleOptions.startOn("samples-no-such-command", free));
    }

    @Test
    void theCliScriptWorksWithEnvironmentVariablesOnly(Emulator emulator) throws IOException, InterruptedException {
        assumeTrue(onPath("aws"), "the AWS CLI is not installed: skipping");
        ProcessBuilder script = new ProcessBuilder("/usr/bin/env", "bash", "scripts/cli-sample.sh").redirectErrorStream(true);
        script.environment().put("AWS_EMULATOR_PORT", Integer.toString(emulator.port()));
        // Make sure real keys in the test's environment cannot leak in: the script unsets them itself.
        script.environment().put("AWS_ACCESS_KEY_ID", "AKIAIOSFODNN7EXAMPLE");
        Path log = Files.createTempFile("cli-sample", ".log");
        script.redirectOutput(log.toFile());
        Process process = script.start();
        String out;
        try {
            // The output goes to a file, so the timeout applies even if the script hangs.
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "cli-sample.sh did not finish in 60 seconds");
        } finally {
            process.destroyForcibly();
            out = Files.readString(log, StandardCharsets.UTF_8);
            Files.deleteIfExists(log);
        }
        System.out.print(out);
        assertEquals(0, process.exitValue(), out);
        assertTrue(out.contains("Account: 000000000000"), out);
        assertTrue(out.contains("Read back from s3://samples-cli-"), out);
        assertTrue(out.contains(": hello from the AWS CLI"), out);
    }

    @Test
    void s3EventsReachTheQueueAndTheFunction(Emulator emulator) {
        String out = runMain(emulator, S14_S3EventNotifications::main);
        assertTrue(out.contains("Unable to validate the following destination configurations"), out);
        assertTrue(out.contains("Queue got: s3:TestEvent"), out);
        assertTrue(out.contains("Queue got: ObjectCreated:Put, key uploads/report.csv"), out);
        assertTrue(out.contains("Function log: New object: invoices/invoice-17.pdf"), out);
    }

    @Test
    void eventBridgeDeliversOnlyMatchingEventsOnceTheQueueAllowsIt(Emulator emulator) {
        String out = runMain(emulator, S15_EventBridgeToSqs::main);
        assertTrue(out.contains("TestEventPattern for a total of 99: no match"), out);
        assertTrue(out.contains("Without a queue policy, the queue received: []"), out);
        assertTrue(out.contains("\"orderId\":\"o-3\""), out);
    }

    @Test
    void lambdaRunsPythonAndNodeButNotJava(Emulator emulator) {
        String out = runMain(emulator, S16_LambdaFunctions::main);
        assertTrue(out.contains("\"greeting\": \"Hello, Ada\""), out);
        assertTrue(out.contains("Its log tail: Greeting Ada"), out);
        assertTrue(out.contains("FunctionError Unhandled"), out);
        assertTrue(out.contains("\"greeting\":\"Hello, Grace\""), out);
        assertTrue(out.contains("java21  -> "), out);
        assertNoLambdaLogGroupsLeft(emulator);
    }

    @Test
    void stepFunctionsRouteOrdersBySize(Emulator emulator) {
        String out = runMain(emulator, S17_StepFunctions::main);
        assertTrue(out.contains("SUCCEEDED -> {\"orderId\":\"o-17\",\"net\":120,\"gross\":132.0,\"route\":\"needs approval\"}"), out);
        assertTrue(out.contains("\"route\":\"ship now\""), out);
        assertTrue(out.contains("With host prefixes on, StartSyncExecution fails"), out);
        assertNoLambdaLogGroupsLeft(emulator);
    }

    /** The samples delete what they make, log groups included, so repeated runs leave nothing behind. */
    private static void assertNoLambdaLogGroupsLeft(Emulator emulator) {
        try (CloudWatchLogsClient logs = emulator.client(CloudWatchLogsClient.builder())) {
            assertEquals(java.util.List.of(), logs.describeLogGroups(d -> d.logGroupNamePrefix("/aws/lambda/samples-"))
                    .logGroups().stream().map(g -> g.logGroupName()).toList());
        }
    }

    @Test
    void cloudFormationCreatesAndDeletesTheStack(Emulator emulator) {
        String out = runMain(emulator, S18_CloudFormationStack::main);
        assertTrue(out.contains(": CREATE_COMPLETE"), out);
        assertTrue(out.contains("The queue's VisibilityTimeout, set in the template: 60"), out);
        assertTrue(out.contains("Stack after delete: DELETE_COMPLETE"), out);
        assertTrue(out.contains("table true, bucket true"), out);
    }

    @Test
    void sqsDeadLettersAndFifoOrder(Emulator emulator) {
        String out = runMain(emulator, S19_SqsDeadLetterAndFifo::main);
        assertTrue(out.contains("Dead-letter queue holds: [charge order o-7]"), out);
        assertTrue(out.contains("After StartMessageMoveTask, the source queue has: [charge order o-7]"), out);
        assertTrue(out.contains("Then, in order: [A: deposit 100, A: withdraw 30]"), out);
    }

    @Test
    void dynamoDbTransactionsIndexesPagesAndTtl(Emulator emulator) {
        String out = runMain(emulator, S20_DynamoDbDeeper::main);
        assertTrue(out.contains("Transfer 60 again: TransactionCanceledException"), out);
        assertTrue(out.contains("item 0: ConditionalCheckFailed"), out);
        assertTrue(out.contains("Balances unchanged: 40 and 160"), out);
        assertTrue(out.contains("3 pages: [acc-1, acc-2][acc-3, acc-4][acc-5]"), out);
        assertTrue(out.contains("Live sessions (expired ones filtered out on read): 0"), out);
    }

    @Test
    void s3MultipartVersionsAndPresignedUrls(Emulator emulator) {
        String out = runMain(emulator, S21_S3Deeper::main);
        assertTrue(out.contains("Completed: one object of 6 MiB"), out);
        assertTrue(out.contains("still gives: \"terms, version 1\""), out);
        assertTrue(out.contains("GET now:            HTTP 200 \"Q3 report\""), out);
        assertTrue(out.contains("GET after 6 seconds: HTTP 403"), out);
    }

    @Test
    void kmsAndASecretUnderACustomerManagedKey(Emulator emulator) {
        String out = runMain(emulator, S22_KmsAndSecrets::main);
        assertTrue(out.contains("Decrypted with the same encryption context: \"tok_4242 (a fictional card token)\""), out);
        assertTrue(out.contains("another encryption context: refused, HTTP 400 InvalidCiphertextException"), out);
        assertTrue(out.contains("Encrypt with the disabled key: refused, HTTP 400 DisabledException"), out);
        assertTrue(out.contains("gives the same key: true"), out);
        assertTrue(out.contains("GetSecretValue with the key disabled: refused"), out);
        assertTrue(out.contains("the key is now PendingDeletion"), out);
    }

    @Test
    void asyncClientsOnNetty(Emulator emulator) {
        String out = runMain(emulator, S25_AsyncClients::main);
        assertTrue(out.contains("Uploaded 20 objects concurrently"), out);
        assertTrue(out.contains("upper-cased it: PHOTO 7"), out);
        assertTrue(out.contains("DynamoDB async put, then get: Ada"), out);
    }

    /** Runs a sample's main method, connected to {@code emulator} through emulator.port, and returns what it printed. */
    private static String runMain(Emulator emulator, java.util.function.Consumer<String[]> main) {
        String previousPort = System.getProperty("emulator.port");
        System.setProperty("emulator.port", Integer.toString(emulator.port()));
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            main.accept(new String[0]);
        } finally {
            System.setOut(original);
            if (previousPort == null) {
                System.clearProperty("emulator.port");
            } else {
                System.setProperty("emulator.port", previousPort);
            }
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        original.print(out); // still visible in the test log
        return out;
    }

    private static boolean onPath(String command) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, command))) {
                return true;
            }
        }
        return false;
    }
}
