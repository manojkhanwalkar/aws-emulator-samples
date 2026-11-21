package samples;

import java.util.function.Supplier;
import samples.emulator.Emulator;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

/**
 * Sample 10: keys made <i>inside</i> the emulator. Create an IAM user and an access key for it, sign
 * requests with that key, then get temporary credentials from STS {@code AssumeRole} and sign with those.
 *
 * <p>Sample 7 showed that the emulator only accepts its own keys. The emulator guide adds: "Keys that
 * you create in the emulator itself, through IAM ({@code CreateAccessKey}) or AWS STS, also work." That
 * lets you test code that creates keys, rotates them, or switches to a role's temporary credentials.
 *
 * <p><b>This is not a permission test.</b> The emulator does not evaluate IAM policies the way AWS does.
 * Some services check the policies of users and roles you create in it, others don't; a request signed
 * with the emulator's own key is never denied; bucket policies and KMS key policies are never checked.
 * The sample prints what happens so you can see it: S3 refuses the new user until it has a policy, but
 * SQS (in emulator 1.0.531) lets it list queues with no policy at all, which AWS would refuse. Test
 * permissions on real AWS.
 */
public final class S10_IamAndStsKeys {

    private S10_IamAndStsKeys() {}

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 10. IAM users, access keys and STS temporary credentials ==");
        String suffix = Names.suffix();
        String userName = "samples-user-" + suffix;
        String roleName = "samples-role-" + suffix;

        try (IamClient iam = emulator.client(IamClient.builder());
             StsClient sts = emulator.client(StsClient.builder())) {
            // 1. An IAM user and an access key for it, made with the emulator's own (admin) key.
            iam.createUser(u -> u.userName(userName));
            AccessKey key = iam.createAccessKey(k -> k.userName(userName)).accessKey();
            System.out.println("Created IAM user " + userName + " and its access key " + key.accessKeyId());
            StaticCredentialsProvider userKeys = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(key.accessKeyId(), key.secretAccessKey()));

            try {
                // 2. Sign with the new key: the emulator knows who is calling.
                try (StsClient asUser = emulator.client(StsClient.builder(), userKeys);
                     S3Client s3AsUser = emulator.client(S3Client.builder().forcePathStyle(true), userKeys);
                     SqsClient sqsAsUser = emulator.client(SqsClient.builder(), userKeys)) {
                    System.out.println("Signed with the new key, GetCallerIdentity says: " + asUser.getCallerIdentity().arn());

                    // 3. What does a user with no policy get? Print it honestly: this is the emulator's
                    //    behaviour, not proof of what AWS allows.
                    attempt("S3 ListBuckets, user has no policy     ", () -> s3AsUser.listBuckets().buckets().size() + " buckets");
                    attempt("SQS ListQueues, user has no policy     ", () -> sqsAsUser.listQueues().queueUrls().size() + " queues");
                    System.out.println("  (On AWS both are refused: no policy allows them.)");

                    // An inline policy that allows listing buckets.
                    iam.putUserPolicy(p -> p.userName(userName).policyName("list-buckets").policyDocument("""
                            {"Version": "2012-10-17",
                             "Statement": [{"Effect": "Allow", "Action": "s3:ListAllMyBuckets", "Resource": "*"}]}"""));
                    attempt("S3 ListBuckets, after an Allow policy  ", () -> s3AsUser.listBuckets().buckets().size() + " buckets");
                }

                // 4. Delete the key: requests signed with it are refused from now on.
                iam.deleteAccessKey(d -> d.userName(userName).accessKeyId(key.accessKeyId()));
                try (StsClient asUser = emulator.client(StsClient.builder(), userKeys)) {
                    attempt("GetCallerIdentity with the deleted key ", () -> asUser.getCallerIdentity().arn());
                }
            } finally {
                // Each step only if it applies: an earlier failure may have skipped the policy or left the key.
                ignoreMissing(() -> iam.deleteUserPolicy(p -> p.userName(userName).policyName("list-buckets")));
                ignoreMissing(() -> iam.deleteAccessKey(d -> d.userName(userName).accessKeyId(key.accessKeyId())));
                iam.deleteUser(u -> u.userName(userName));
            }

            // 5. A role, and temporary credentials for it from STS AssumeRole. The trust policy names who
            //    may assume the role (here: anyone in the emulator's account).
            String roleArn = iam.createRole(r -> r.roleName(roleName).assumeRolePolicyDocument("""
                    {"Version": "2012-10-17",
                     "Statement": [{"Effect": "Allow", "Principal": {"AWS": "arn:aws:iam::000000000000:root"},
                                    "Action": "sts:AssumeRole"}]}""")).role().arn();
            try {
                Credentials temporary = sts.assumeRole(a -> a.roleArn(roleArn).roleSessionName("samples-session")
                        .durationSeconds(900)).credentials();
                System.out.println("AssumeRole gave temporary credentials " + temporary.accessKeyId()
                        + ", valid until " + temporary.expiration());
                // Temporary credentials are three parts: key, secret and session token.
                StaticCredentialsProvider roleKeys = StaticCredentialsProvider.create(AwsSessionCredentials.create(
                        temporary.accessKeyId(), temporary.secretAccessKey(), temporary.sessionToken()));
                try (StsClient asRole = emulator.client(StsClient.builder(), roleKeys)) {
                    String arn = asRole.getCallerIdentity().arn();
                    System.out.println("Signed with them, GetCallerIdentity says: " + arn);
                    Checks.expect(arn.contains(":assumed-role/" + roleName + "/samples-session"), "Expected the assumed role, got " + arn);
                }
            } finally {
                iam.deleteRole(r -> r.roleName(roleName));
            }
            System.out.println("Deleted the user and the role");
            System.out.println("-> Keys made in the emulator work. Its policy checks are not AWS's: test permissions on AWS.");
        }
        System.out.println();
    }

    /** Makes one call and prints whether the emulator accepted or refused it. */
    private static void attempt(String what, Supplier<String> call) {
        System.out.println(what + ": " + Checks.outcome(call));
    }

    /** Runs a clean-up step, ignoring "it isn't there" (NoSuchEntity). */
    private static void ignoreMissing(Runnable step) {
        try {
            step.run();
        } catch (software.amazon.awssdk.services.iam.model.NoSuchEntityException alreadyGone) {
            // never created, or already deleted
        }
    }
}
