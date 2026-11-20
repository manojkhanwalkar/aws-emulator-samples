package samples;

import samples.emulator.Emulator;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

/**
 * Sample 1: connect to the emulator and prove that your requests go to it, not to AWS.
 *
 * <p>Two checks:
 * <ol>
 *   <li>The emulator's <b>health route</b>, {@code GET /__admin/health}, answers "ok" when it is ready.
 *       It is the only route the emulator adds to the AWS APIs.</li>
 *   <li>AWS STS {@code GetCallerIdentity} answers "who am I?". AWS would name your own account; the
 *       emulator always answers with account {@code 000000000000} and the user {@code emulator}. If you
 *       ever see your real account ID, your code is talking to AWS: check how the client was built.</li>
 * </ol>
 *
 * <p>Run: start the emulator ({@code aws-emulator} in a terminal), then click the green arrow next to
 * {@code main}.
 */
public final class S01_ConnectAndCheck {

    private S01_ConnectAndCheck() {}

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 1. Connect and check ==");
        System.out.println("Emulator endpoint: " + emulator.endpoint());
        System.out.println("Its files (credentials, certificate, config): " + emulator.directory());
        System.out.println("Health check (/__admin/health) is ok: " + emulator.isHealthy());

        // Build an STS client exactly as for AWS; Emulator.client(...) only adds the endpoint, Region,
        // credentials and the HTTPS trust for the emulator.
        try (StsClient sts = emulator.client(StsClient.builder())) {
            GetCallerIdentityResponse me = sts.getCallerIdentity();
            System.out.println("Account: " + me.account() + "   ARN: " + me.arn());
            if (Emulator.ACCOUNT_ID.equals(me.account())) {
                System.out.println("-> Account 000000000000: these requests reach the emulator, not AWS.");
            } else {
                throw new IllegalStateException("Not the emulator's account: requests are going somewhere else!");
            }
        }
        System.out.println();
    }
}
