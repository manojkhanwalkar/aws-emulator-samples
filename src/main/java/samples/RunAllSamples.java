package samples;

import samples.emulator.Emulator;

/**
 * Runs every sample in order, against one running emulator (samples 1 to 8), then sample 9, which
 * starts and stops its own private emulators.
 *
 * <p>Start the emulator first ({@code aws-emulator} in a terminal), then run this class from IntelliJ,
 * or from a terminal with {@code mvn -q compile exec:java}.
 */
public final class RunAllSamples {

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            S01_ConnectAndCheck.run(emulator);
            S02_S3Buckets.run(emulator);
            S03_DynamoDbTables.run(emulator);
            S04_SqsQueues.run(emulator);
            S05_SnsFanOutToSqs.run(emulator);
            S06_SameErrorsAsAws.run(emulator);
            S07_SignedRequests.run(emulator);
            S08_SecretsManager.run(emulator);
        }
        S09_PrivateEmulatorPerRun.main(args);
        System.out.println("All samples ran.");
    }
}
