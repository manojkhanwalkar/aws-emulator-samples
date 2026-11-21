package samples;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import samples.emulator.Emulator;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Sample 22: AWS KMS, and a Secrets Manager secret encrypted under your own KMS key.
 *
 * <ol>
 *   <li>Create a customer managed key and an alias for it, encrypt and decrypt with it. The ciphertext
 *       names its key, so {@code Decrypt} needs no key ID. An <b>encryption context</b> (extra
 *       authenticated data) must match on decrypt, or KMS refuses with
 *       {@code InvalidCiphertextException}.</li>
 *   <li>{@code GenerateDataKey}: the envelope-encryption pattern for data bigger than 4 KB: KMS gives you
 *       a data key in plain and encrypted form; you encrypt locally with the plain one and store only
 *       the encrypted one.</li>
 *   <li>A secret stored under that key ({@code KmsKeyId}), read back. Then the key is disabled, and the
 *       secret can no longer be read, as on AWS.</li>
 * </ol>
 * KMS keys cannot be deleted at once, on AWS or here: {@code ScheduleKeyDeletion} with a waiting period
 * of 7 to 30 days. The emulator does not check key policies (see the emulator guide, Security).
 */
public final class S22_KmsAndSecrets {

    private S22_KmsAndSecrets() {}

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 22. KMS and a secret under a customer managed key ==");
        String suffix = Names.suffix();
        String alias = "alias/samples-payments-" + suffix;
        String secretName = "samples/payments/api-key-" + suffix;

        try (KmsClient kms = emulator.client(KmsClient.builder());
             SecretsManagerClient secrets = emulator.client(SecretsManagerClient.builder())) {
            String keyId = kms.createKey(k -> k.description("Samples: payments key")).keyMetadata().keyId();
            kms.createAlias(a -> a.aliasName(alias).targetKeyId(keyId));
            System.out.println("Created KMS key " + keyId + " with alias " + alias);
            try {
                // 1. Encrypt with the alias, decrypt with no key ID at all.
                Map<String, String> context = Map.of("purpose", "card-token");
                SdkBytes secretData = SdkBytes.fromUtf8String("tok_4242 (a fictional card token)");
                SdkBytes ciphertext = kms.encrypt(e -> e.keyId(alias).encryptionContext(context).plaintext(secretData))
                        .ciphertextBlob();
                System.out.println("Encrypted " + secretData.asByteArray().length + " bytes into " + ciphertext.asByteArray().length + " bytes of ciphertext");
                String plain = kms.decrypt(d -> d.ciphertextBlob(ciphertext).encryptionContext(context)).plaintext().asUtf8String();
                System.out.println("Decrypted with the same encryption context: \"" + plain + "\"");
                System.out.println("Decrypt with another encryption context: " + Checks.outcome(() -> kms.decrypt(d -> d
                        .ciphertextBlob(ciphertext).encryptionContext(Map.of("purpose", "other"))).plaintext().asUtf8String()));

                // 2. Envelope encryption: a 256-bit data key, plain and encrypted under our key.
                GenerateDataKeyResponse dataKey = kms.generateDataKey(g -> g.keyId(alias).keySpec(DataKeySpec.AES_256));
                byte[] restored = kms.decrypt(d -> d.ciphertextBlob(dataKey.ciphertextBlob())).plaintext().asByteArray();
                System.out.println("GenerateDataKey: a " + dataKey.plaintext().asByteArray().length * 8 + "-bit key; decrypting its"
                        + " encrypted copy gives the same key: "
                        + java.util.Arrays.equals(restored, dataKey.plaintext().asByteArray()));

                // 3. A secret encrypted under our key instead of the default aws/secretsmanager key.
                secrets.createSecret(c -> c.name(secretName).kmsKeyId(alias).secretString("{\"apiKey\": \"example-not-real\"}"));
                System.out.println("Secret " + secretName + " uses KmsKeyId "
                        + secrets.describeSecret(d -> d.secretId(secretName)).kmsKeyId());
                System.out.println("Its value: " + secrets.getSecretValue(g -> g.secretId(secretName)).secretString());

                // Disable the key: KMS refuses to use it, so the secret cannot be decrypted any more.
                kms.disableKey(d -> d.keyId(keyId));
                System.out.println("Encrypt with the disabled key: " + Checks.outcome(() -> kms.encrypt(e -> e.keyId(keyId)
                        .plaintext(SdkBytes.fromString("x", StandardCharsets.UTF_8))).keyId()));
                System.out.println("GetSecretValue with the key disabled: "
                        + Checks.outcome(() -> secrets.getSecretValue(g -> g.secretId(secretName)).name()));
                kms.enableKey(e -> e.keyId(keyId));
            } finally {
                try {
                    secrets.deleteSecret(d -> d.secretId(secretName).forceDeleteWithoutRecovery(true));
                } catch (AwsServiceException notCreated) {
                    // an earlier step failed before creating it
                }
                kms.deleteAlias(d -> d.aliasName(alias));
                // The shortest waiting period AWS allows is 7 days; the key is unusable from now on.
                String state = kms.scheduleKeyDeletion(s -> s.keyId(keyId).pendingWindowInDays(7)).keyStateAsString();
                System.out.println("Deleted the secret and alias; the key is now " + state + " (deleted after 7 days)");
            }
        }
        System.out.println();
    }
}
