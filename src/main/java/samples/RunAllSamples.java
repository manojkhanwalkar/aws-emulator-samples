package samples;

import samples.emulator.Emulator;

/**
 * Runs every Java sample in order: first the ones that use one running emulator (1 to 8, 10, 14 to
 * 22 and 25), then the ones that start and stop their own private emulators (9, 11 and 12). Samples 13
 * and 26 are shell scripts, and 23 and 24 are tests (see README.md).
 *
 * <p>Start the emulator first ({@code aws-emulator} in a terminal), then run this class from IntelliJ,
 * or from a terminal with {@code mvn -q compile exec:java}.
 */
public final class RunAllSamples {

    private RunAllSamples() {}

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
            S10_IamAndStsKeys.run(emulator);
            S14_S3EventNotifications.run(emulator);
            S15_EventBridgeToSqs.run(emulator);
            S16_LambdaFunctions.run(emulator);
            S17_StepFunctions.run(emulator);
            S18_CloudFormationStack.run(emulator);
            S19_SqsDeadLetterAndFifo.run(emulator);
            S20_DynamoDbDeeper.run(emulator);
            S21_S3Deeper.run(emulator);
            S22_KmsAndSecrets.run(emulator);
            S25_AsyncClients.run(emulator);
        }
        S09_PrivateEmulatorPerRun.main(args);
        S11_OneEmulatorPerRegion.main(args);
        S12_LifecycleOptions.main(args);
        System.out.println("All samples ran.");
    }
}
