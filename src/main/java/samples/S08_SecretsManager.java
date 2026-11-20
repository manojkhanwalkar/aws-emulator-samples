package samples;

import samples.emulator.Emulator;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Sample 8: AWS Secrets Manager. Store a secret, read it, change it, and delete it.
 *
 * <p>Code that reads its database password or API key from Secrets Manager can be tested against the
 * emulator with throwaway values, never a real secret. Each new value makes a new <b>version</b>;
 * reading without naming a version returns the current one ({@code AWSCURRENT}), and the one before
 * stays readable as {@code AWSPREVIOUS}.
 */
public final class S08_SecretsManager {

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 8. AWS Secrets Manager: store, read, change, delete ==");
        String name = "samples/db-password-" + Names.suffix();

        try (SecretsManagerClient secrets = emulator.client(SecretsManagerClient.builder())) {
            String arn = secrets.createSecret(c -> c.name(name).secretString("first-password")).arn();
            System.out.println("Created secret " + arn);

            System.out.println("Current value: " + secrets.getSecretValue(g -> g.secretId(name)).secretString());

            // A new value is a new version; the previous one is kept as AWSPREVIOUS.
            secrets.putSecretValue(p -> p.secretId(name).secretString("second-password"));
            var current = secrets.getSecretValue(g -> g.secretId(name));
            System.out.println("After a change: " + current.secretString() + " (stages " + current.versionStages() + ")");
            var previous = secrets.getSecretValue(g -> g.secretId(name).versionStage("AWSPREVIOUS"));
            System.out.println("Previous value: " + previous.secretString() + " (stages " + previous.versionStages() + ")");

            // AWS keeps a deleted secret for a recovery window (7 to 30 days) unless told otherwise.
            secrets.deleteSecret(d -> d.secretId(name).forceDeleteWithoutRecovery(true));
            System.out.println("Deleted the secret");
        }
        System.out.println();
    }
}
