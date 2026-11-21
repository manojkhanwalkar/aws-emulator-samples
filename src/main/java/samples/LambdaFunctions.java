package samples;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;

/**
 * Helpers for the samples that deploy AWS Lambda functions (14, 16, 17): build the deployment package
 * in memory, and create an execution role and a function, all with the ordinary SDK calls you would
 * make against AWS.
 */
final class LambdaFunctions {

    /** A Lambda execution role may write its function's logs to CloudWatch Logs, as on AWS. */
    static final String LOGS_POLICY = """
            {"Version": "2012-10-17",
             "Statement": [{"Effect": "Allow",
                            "Action": ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"],
                            "Resource": "*"}]}""";

    private LambdaFunctions() {}

    /** A zip file with one source file in it: the deployment package of a function, built in code. */
    static SdkBytes zip(String fileName, String source) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(fileName));
            zip.write(source.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return SdkBytes.fromByteArray(bytes.toByteArray());
    }

    /**
     * Creates an IAM role that {@code service} (for example {@code lambda.amazonaws.com}) may assume,
     * with one inline policy, and returns its ARN.
     */
    static String createRole(IamClient iam, String roleName, String service, String policy) {
        String arn = iam.createRole(r -> r.roleName(roleName).assumeRolePolicyDocument("""
                {"Version": "2012-10-17",
                 "Statement": [{"Effect": "Allow", "Principal": {"Service": "%s"}, "Action": "sts:AssumeRole"}]}"""
                .formatted(service))).role().arn();
        iam.putRolePolicy(p -> p.roleName(roleName).policyName("inline").policyDocument(policy));
        return arn;
    }

    /** Deletes a role made by {@link #createRole}. */
    static void deleteRole(IamClient iam, String roleName) {
        iam.deleteRolePolicy(p -> p.roleName(roleName).policyName("inline"));
        iam.deleteRole(r -> r.roleName(roleName));
    }

    /**
     * Creates a function from one source file and waits until it is Active (a new function starts as
     * Pending, on AWS as here). Returns its ARN.
     */
    static String createFunction(LambdaClient lambda, String name, String runtime, String handler,
                                 String roleArn, String fileName, String source) {
        String arn = lambda.createFunction(f -> f.functionName(name).runtime(runtime).handler(handler).role(roleArn)
                .code(c -> c.zipFile(zip(fileName, source))).timeout(10)).functionArn();
        lambda.waiter().waitUntilFunctionActiveV2(w -> w.functionName(name));
        return arn;
    }
}
