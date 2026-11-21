package samples;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import samples.emulator.Emulator;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.LambdaException;
import software.amazon.awssdk.services.lambda.model.LogType;

/**
 * Sample 16: AWS Lambda. Deploy small functions from zip files built in code, and invoke them.
 *
 * <p>The emulator does not just store functions: it <b>runs</b> them, on your machine. Emulator 1.0.531
 * runs the <b>Python</b> and <b>Node.js</b> runtimes with interpreters built into the emulator (for
 * Python it reports RustPython, a Python 3.12 written in Rust; Node.js code runs in a built-in
 * JavaScript engine that reports the runtime's major version). So:
 * <ul>
 *   <li>Pure Python and JavaScript code works. Libraries with native code (C extensions, native Node
 *       add-ons) are a different matter; test what your function imports.</li>
 *   <li>Other runtimes (java21, ruby3.3, provided.al2023 for Go and Rust, dotnet8...) can be created,
 *       but invoking them fails with {@code InvalidRuntimeException}. On AWS they would run. The sample
 *       shows this for java21.</li>
 *   <li>Function code is <b>not isolated</b> from your machine: it runs with your user's permissions.
 *       Run only code you trust (see the emulator guide, Security).</li>
 * </ul>
 * What it shows: a Python and a Node.js function invoked with a JSON payload, the log tail an invoke can
 * return, a function error ({@code FunctionError: Unhandled}, a 200 response as on AWS), and the
 * unsupported runtime.
 */
public final class S16_LambdaFunctions {

    private S16_LambdaFunctions() {}

    private static final String PYTHON_SOURCE = """
            import os, sys

            def handler(event, context):
                print("Greeting", event["name"])           # goes to the log
                if event.get("fail"):
                    raise ValueError("asked to fail")
                return {"greeting": os.environ["GREETING"] + ", " + event["name"],
                        "python": sys.version.split()[0],
                        "interpreter": sys.version.split("[")[-1].rstrip("]"),
                        "requestId": context.aws_request_id}
            """;

    private static final String NODE_SOURCE = """
            exports.handler = async (event) => {
              console.log("Greeting", event.name);
              return { greeting: `${process.env.GREETING}, ${event.name}`, node: process.version };
            };
            """;

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 16. Lambda: deploy and invoke functions ==");
        String suffix = Names.suffix();
        String roleName = "samples-lambda-role-" + suffix;
        String python = "samples-hello-python-" + suffix;
        String node = "samples-hello-node-" + suffix;
        String java = "samples-hello-java-" + suffix;

        try (LambdaClient lambda = emulator.client(LambdaClient.builder());
             IamClient iam = emulator.client(IamClient.builder());
             CloudWatchLogsClient logs = emulator.client(CloudWatchLogsClient.builder())) {
            // Every function needs an execution role, here as on AWS.
            String roleArn = LambdaFunctions.createRole(iam, roleName, "lambda.amazonaws.com", LambdaFunctions.LOGS_POLICY);
            try {
                // 1. Python: the zip holds lambda_function.py; the handler is <file>.<function>.
                createWithGreeting(lambda, python, "python3.12", "lambda_function.handler", roleArn,
                        "lambda_function.py", PYTHON_SOURCE);
                InvokeResponse ok = invoke(lambda, python, "{\"name\": \"Ada\"}");
                System.out.println("Python  -> " + ok.payload().asUtf8String());
                System.out.println("  Its log tail: " + logTail(ok));

                // A function that raises: the Invoke call itself succeeds (HTTP 200), and the error is in
                // FunctionError and the payload, exactly as on AWS. Check FunctionError in your code!
                InvokeResponse failed = invoke(lambda, python, "{\"name\": \"Ada\", \"fail\": true}");
                System.out.println("Python, raising -> HTTP " + failed.statusCode() + ", FunctionError "
                        + failed.functionError() + ": " + failed.payload().asUtf8String());

                // 2. Node.js: the zip holds index.js; the handler is index.handler.
                createWithGreeting(lambda, node, "nodejs22.x", "index.handler", roleArn, "index.js", NODE_SOURCE);
                System.out.println("Node.js -> " + invoke(lambda, node, "{\"name\": \"Grace\"}").payload().asUtf8String());

                // 3. A runtime the emulator cannot run: creating works, invoking does not.
                createWithGreeting(lambda, java, "java21", "example.Handler::handleRequest", roleArn,
                        "example/Handler.class", "not real bytecode: the emulator never gets to run it");
                // (If a later emulator version runs java21, this check fails: update the sample then.)
                LambdaException e = Checks.expectFailure(LambdaException.class, () -> invoke(lambda, java, "{}"));
                System.out.println("java21  -> HTTP " + e.statusCode() + " " + e.awsErrorDetails().errorCode()
                        + ": " + e.awsErrorDetails().errorMessage());
            } finally {
                for (String function : List.of(python, node, java)) {
                    try {
                        lambda.deleteFunction(d -> d.functionName(function));
                    } catch (LambdaException notCreated) {
                        // an earlier step failed before creating it
                    }
                    LambdaFunctions.deleteLogGroup(logs, function);
                }
                LambdaFunctions.deleteRole(iam, roleName);
            }
            System.out.println("Deleted the functions, their log groups and the role");
        }
        System.out.println();
    }

    private static void createWithGreeting(LambdaClient lambda, String name, String runtime, String handler,
                                           String roleArn, String fileName, String source) {
        LambdaFunctions.createFunction(lambda, name, runtime, handler, roleArn, fileName, source);
        // Configuration such as environment variables reaches the code as on AWS.
        lambda.updateFunctionConfiguration(c -> c.functionName(name)
                .environment(e -> e.variables(java.util.Map.of("GREETING", "Hello"))));
        lambda.waiter().waitUntilFunctionUpdatedV2(w -> w.functionName(name));
        System.out.println("Created " + runtime + " function " + name);
    }

    /** A synchronous invoke (RequestResponse) that also asks for the last 4 KB of the function's log. */
    private static InvokeResponse invoke(LambdaClient lambda, String name, String payload) {
        return lambda.invoke(i -> i.functionName(name).logType(LogType.TAIL)
                .payload(SdkBytes.fromString(payload, StandardCharsets.UTF_8)));
    }

    /** The log tail comes base64-encoded; the line the function printed is the interesting one. */
    private static String logTail(InvokeResponse response) {
        String log = new String(Base64.getDecoder().decode(response.logResult()), StandardCharsets.UTF_8);
        return log.lines().filter(line -> line.contains("Greeting")).findFirst().orElse(log.strip());
    }
}
