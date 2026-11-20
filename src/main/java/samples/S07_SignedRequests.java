package samples;

import samples.emulator.Emulator;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.StsException;

/**
 * Sample 7: requests must be signed with the emulator's own keys.
 *
 * <p>By default the emulator checks every request's SigV4 signature, as AWS does. It accepts only the
 * keys it generated for this run (in {@code ~/.aws/emulator/<port>/emulator_credentials}). Why that
 * helps:
 * <ul>
 *   <li>A web page or another program on your machine cannot quietly send requests to it.</li>
 *   <li>If your <b>real</b> AWS keys are still in your environment, the emulator refuses them instead of
 *       quietly accepting them, which tells you your setup is mixed up.</li>
 * </ul>
 * The sample makes three calls and prints the outcome of each:
 * <ol>
 *   <li>Signed with this run's keys: accepted.</li>
 *   <li>Signed with keys the emulator did not issue: HTTP 403. Emulator 1.0.531 answers STS's own error
 *       for an unknown key, {@code InvalidClientTokenId}. (The emulator guide also lists
 *       {@code AccessDenied}, "Sign the request with the emulator credentials", for keys it does not
 *       accept; either way it is a 403 refusal.)</li>
 *   <li>The emulator's access key with the wrong secret key: HTTP 403 {@code SignatureDoesNotMatch},
 *       as AWS answers for a wrong secret.</li>
 * </ol>
 * The keys work only with this run, and only on its port: after a restart, read the new ones (as
 * {@link Emulator} does). For a throwaway setup you can start the emulator with {@code --auth off}: it
 * then serves every request, signed or not, and writes no credentials file.
 */
public final class S07_SignedRequests {

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 7. Requests must be signed with the emulator's keys ==");

        // Signed with this run's keys: accepted.
        try (StsClient sts = emulator.client(StsClient.builder())) {
            System.out.println("With the emulator's keys: accepted, account " + sts.getCallerIdentity().account());
        }

        // Same endpoint, but signed with keys the emulator did not issue (made up here; your real AWS
        // keys are refused the same way).
        var otherKeys = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("AKIAEXAMPLEEXAMPLE00", "not-the-emulators-secret-key-0000000000000"));
        tryCallerIdentity(emulator, "With other keys:         ", otherKeys);

        // The emulator's own access key, but the wrong secret key: the signature cannot match.
        String accessKey = emulator.credentials().resolveCredentials().accessKeyId();
        var wrongSecret = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, "0000000000000000000000000000000000000000"));
        tryCallerIdentity(emulator, "With a wrong secret key: ", wrongSecret);
        System.out.println();
    }

    /** Calls STS GetCallerIdentity signed with {@code keys}, and prints whether it was accepted. */
    private static void tryCallerIdentity(Emulator emulator, String label, AwsCredentialsProvider keys) {
        try (StsClient sts = emulator.client(StsClient.builder(), keys)) {
            sts.getCallerIdentity();
            System.out.println(label + " accepted (is the emulator running with --auth off?)");
        } catch (StsException refused) {
            System.out.println(label + " refused, HTTP " + refused.statusCode() + " "
                    + refused.awsErrorDetails().errorCode() + ": " + refused.awsErrorDetails().errorMessage());
        }
    }
}
