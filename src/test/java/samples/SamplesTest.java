package samples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import samples.emulator.Emulator;
import samples.emulator.EmulatorProcess;

/**
 * Runs every sample against a private emulator and checks what it printed, so the samples keep working
 * as the emulator and the SDK change. Each sample's {@code run(Emulator)} is the same code its
 * {@code main} runs; only the emulator differs (a private one here, so nothing else is touched).
 */
class SamplesTest {

    private static EmulatorProcess process;
    private static Emulator emulator;

    @BeforeAll
    static void startEmulator() {
        assumeTrue(samples.emulator.EmulatorBackedTestSupport.isInstalled(), "aws-emulator is not installed: skipping (see README.md)");
        process = EmulatorProcess.start();
        emulator = process.emulator();
    }

    @AfterAll
    static void stopEmulator() {
        if (emulator != null) {
            emulator.close();
        }
        if (process != null) {
            process.close();
        }
    }

    @Test
    void connectAndCheckReachesTheEmulatorsAccount() {
        String out = output(S01_ConnectAndCheck::run);
        assertTrue(out.contains("Health check (/__admin/health) is ok: true"), out);
        assertTrue(out.contains("Account: 000000000000"), out);
    }

    @Test
    void s3SampleStoresReadsAndCleansUp() {
        String out = output(S02_S3Buckets::run);
        assertTrue(out.contains("Read greetings/hello.txt: \"Hello from AWS Emulator!\""), out);
        assertTrue(out.contains("[greetings/bye.txt, greetings/hello.txt]"), out);
        assertTrue(out.contains("Deleted the objects and the bucket"), out);
    }

    @Test
    void dynamoDbSampleQueriesAndRefusesTheConditionalOverwrite() {
        String out = output(S03_DynamoDbTables::run);
        assertTrue(out.contains("Query customer = ada: 2 orders"), out);
        assertTrue(out.contains("ConditionalCheckFailedException"), out);
    }

    @Test
    void sqsSampleHandlesEveryMessage() {
        assertTrue(output(S04_SqsQueues::run).contains("Handled and deleted 3 messages"));
    }

    @Test
    void snsFanOutReachesBothQueues() {
        String out = output(S05_SnsFanOutToSqs::run);
        assertEquals(2, out.split("received: Order o-42 was placed", -1).length - 1, out);
    }

    @Test
    void errorsMatchAws() {
        String out = output(S06_SameErrorsAsAws::run);
        assertTrue(out.contains("HTTP 400  ValidationException"), out);
        assertTrue(out.contains("HTTP 404  NoSuchBucket"), out);
        assertTrue(out.contains("NonExistentQueue"), out);
        assertTrue(out.contains("(caught as the typed NoSuchBucketException)"), out);
        assertTrue(out.contains("(caught as the typed QueueDoesNotExistException)"), out);
    }

    @Test
    void onlyTheEmulatorsKeysAreAccepted() {
        String out = output(S07_SignedRequests::run);
        assertTrue(out.contains("accepted, account 000000000000"), out);
        assertTrue(out.contains("refused, HTTP 403"), out);
    }

    @Test
    void secretsSampleKeepsThePreviousVersion() {
        String out = output(S08_SecretsManager::run);
        assertTrue(out.contains("After a change: second-password"), out);
        assertTrue(out.contains("Previous value: first-password"), out);
    }

    @Test
    void privateEmulatorsAreSeparateAndLeaveNothingBehind() {
        String out = output(e -> S09_PrivateEmulatorPerRun.main(new String[0]));
        assertTrue(out.contains("has tables: [] (separate state)"), out);
        assertTrue(out.contains("Its keys and certificate are deleted: true"), out);
    }

    @Test
    void connectingToAPortWithNoEmulatorExplainsHowToStartOne() throws Exception {
        int free;
        try (ServerSocket socket = new ServerSocket(0)) {
            free = socket.getLocalPort();
        }
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Emulator.at(free));
        assertTrue(e.getMessage().contains("aws-emulator -p " + free), e.getMessage());

        String previous = System.getProperty("emulator.port");
        System.setProperty("emulator.port", Integer.toString(free));
        try {
            assertThrows(IllegalStateException.class, Emulator::connect);
        } finally {
            if (previous == null) {
                System.clearProperty("emulator.port");
            } else {
                System.setProperty("emulator.port", previous);
            }
        }
    }

    @Test
    void connectFindsThePrivateEmulatorByItsPort() {
        String previous = System.getProperty("emulator.port");
        System.setProperty("emulator.port", Integer.toString(process.port()));
        try (Emulator connected = Emulator.connect()) {
            assertEquals(process.port(), connected.port());
            assertEquals("https://127.0.0.1:" + process.port(), connected.endpoint().toString());
        } finally {
            if (previous == null) {
                System.clearProperty("emulator.port");
            } else {
                System.setProperty("emulator.port", previous);
            }
        }
    }

    @Test
    void everySampleRunsFromItsMainMethodAsInIntelliJ() {
        // What clicking the green arrow does: main() connects through emulator.port (here the private
        // emulator) and runs the sample. RunAllSamples runs them all, then sample 9.
        String previous = System.getProperty("emulator.port");
        System.setProperty("emulator.port", Integer.toString(process.port()));
        try {
            String out = output(e -> {
                S01_ConnectAndCheck.main(new String[0]);
                S02_S3Buckets.main(new String[0]);
                S03_DynamoDbTables.main(new String[0]);
                S04_SqsQueues.main(new String[0]);
                S05_SnsFanOutToSqs.main(new String[0]);
                S06_SameErrorsAsAws.main(new String[0]);
                S07_SignedRequests.main(new String[0]);
                S08_SecretsManager.main(new String[0]);
                RunAllSamples.main(new String[0]);
            });
            assertTrue(out.contains("All samples ran."), out);
        } finally {
            if (previous == null) {
                System.clearProperty("emulator.port");
            } else {
                System.setProperty("emulator.port", previous);
            }
        }
    }

    @Test
    void namesAreSixLowerCaseCharacters() {
        for (int i = 0; i < 100; i++) {
            assertTrue(Names.suffix().matches("[0-9a-z]{6}"));
        }
    }

    /** What {@code sample} printed while running against the private emulator. */
    private static String output(Consumer<Emulator> sample) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            sample.accept(emulator);
        } finally {
            System.setOut(original);
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        original.print(out); // still visible in the test log
        return out;
    }
}
