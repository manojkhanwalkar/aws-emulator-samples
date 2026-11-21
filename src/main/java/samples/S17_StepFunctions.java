package samples;

import java.time.Duration;
import java.util.List;
import samples.emulator.Emulator;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.HistoryEvent;
import software.amazon.awssdk.services.sfn.model.StartSyncExecutionResponse;
import software.amazon.awssdk.services.sfn.model.StateMachineType;

/**
 * Sample 17: AWS Step Functions. A two-step state machine, run inside the emulator: step 1 calls a
 * Lambda function that adds tax to an order, step 2 (a Choice) sends big orders one way and small ones
 * another.
 *
 * <p>It runs the same definition two ways:
 * <ul>
 *   <li>a <b>Standard</b> workflow: {@code StartExecution} returns at once; you poll
 *       {@code DescribeExecution} and can read the execution's history;</li>
 *   <li>an <b>Express</b> workflow with {@code StartSyncExecution}, which waits and returns the result.</li>
 * </ul>
 *
 * <p><b>The host prefix.</b> The SDK sends {@code StartSyncExecution} to a host named
 * {@code sync-<endpoint host>}, which for the emulator is {@code sync-127.0.0.1}: a host that does not
 * exist. {@code Emulator.client(...)} turns host prefix injection off
 * ({@code SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION}), as the emulator guide advises, so the
 * call works. The last step builds a client without that setting to show the failure you would get.
 * (The AWS CLI and Boto3 use {@code AWS_DISABLE_HOST_PREFIX_INJECTION=true} instead.)
 */
public final class S17_StepFunctions {

    private S17_StepFunctions() {}

    /** Step 1's function: adds 10% tax to the net amount. */
    private static final String TAX_FUNCTION = """
            def handler(event, context):
                gross = round(event["net"] * 1.10, 2)
                return {"orderId": event["orderId"], "net": event["net"], "gross": gross}
            """;

    /** Amazon States Language: Task (Lambda) -> Choice -> one of two Pass states. */
    private static final String DEFINITION = """
            {"Comment": "Add tax, then route by size",
             "StartAt": "AddTax",
             "States": {
               "AddTax": {"Type": "Task", "Resource": "arn:aws:states:::lambda:invoke",
                          "Parameters": {"FunctionName": "%s", "Payload.$": "$"},
                          "OutputPath": "$.Payload", "Next": "BySize"},
               "BySize": {"Type": "Choice",
                          "Choices": [{"Variable": "$.gross", "NumericGreaterThanEquals": 100, "Next": "BigOrder"}],
                          "Default": "SmallOrder"},
               "BigOrder":   {"Type": "Pass", "Result": "needs approval", "ResultPath": "$.route", "End": true},
               "SmallOrder": {"Type": "Pass", "Result": "ship now", "ResultPath": "$.route", "End": true}}}""";

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 17. Step Functions: a two-step state machine ==");
        String suffix = Names.suffix();
        String functionName = "samples-add-tax-" + suffix;
        String lambdaRole = "samples-add-tax-role-" + suffix;
        String sfnRole = "samples-orders-flow-role-" + suffix;

        try (SfnClient sfn = emulator.client(SfnClient.builder());
             LambdaClient lambda = emulator.client(LambdaClient.builder());
             IamClient iam = emulator.client(IamClient.builder());
             CloudWatchLogsClient logs = emulator.client(CloudWatchLogsClient.builder())) {
            String lambdaRoleArn = LambdaFunctions.createRole(iam, lambdaRole, "lambda.amazonaws.com", LambdaFunctions.LOGS_POLICY);
            String functionArn = LambdaFunctions.createFunction(lambda, functionName, "python3.12",
                    "lambda_function.handler", lambdaRoleArn, "lambda_function.py", TAX_FUNCTION);
            // Step Functions needs a role that may invoke the function.
            String sfnRoleArn = LambdaFunctions.createRole(iam, sfnRole, "states.amazonaws.com", """
                    {"Version": "2012-10-17",
                     "Statement": [{"Effect": "Allow", "Action": "lambda:InvokeFunction", "Resource": "%s"}]}"""
                    .formatted(functionArn));
            String definition = DEFINITION.formatted(functionArn);
            String standardArn = null;
            String expressArn = null;
            try {
                // 1. Standard workflow: start, then poll until it is no longer RUNNING.
                standardArn = sfn.createStateMachine(c -> c.name("samples-orders-" + suffix).type(StateMachineType.STANDARD)
                        .roleArn(sfnRoleArn).definition(definition)).stateMachineArn();
                String machine = standardArn;
                String executionArn = sfn.startExecution(s -> s.stateMachineArn(machine)
                        .input("{\"orderId\": \"o-17\", \"net\": 120}")).executionArn();
                DescribeExecutionResponse done = waitForExecution(sfn, executionArn);
                System.out.println("Standard, order o-17 (net 120): " + done.status() + " -> " + done.output());
                List<String> steps = sfn.getExecutionHistory(h -> h.executionArn(executionArn)).events().stream()
                        .map(HistoryEvent::typeAsString).toList();
                System.out.println("  History: " + String.join(" > ", steps));

                // 2. Express workflow, run synchronously: the answer comes back in the response.
                expressArn = sfn.createStateMachine(c -> c.name("samples-orders-express-" + suffix)
                        .type(StateMachineType.EXPRESS).roleArn(sfnRoleArn).definition(definition)).stateMachineArn();
                String express = expressArn;
                StartSyncExecutionResponse sync = sfn.startSyncExecution(s -> s.stateMachineArn(express)
                        .input("{\"orderId\": \"o-18\", \"net\": 40}"));
                System.out.println("Express (StartSyncExecution), order o-18 (net 40): " + sync.status() + " -> " + sync.output());
                Checks.expect(sync.output().contains("ship now") && done.output().contains("needs approval"),
                        "Unexpected routing");

                // 3. The same call from a client that still injects host prefixes.
                try (SdkHttpClient http = ApacheHttpClient.builder().tlsTrustManagersProvider(emulator::trustManagers).build();
                     SfnClient withPrefix = SfnClient.builder().endpointOverride(emulator.endpoint()).region(Emulator.REGION)
                             .credentialsProvider(emulator.credentials()).httpClient(http)
                             .overrideConfiguration(o -> o.retryStrategy(AwsRetryStrategy.doNotRetry()))
                             .build()) {
                    SdkClientException e = Checks.expectFailure(SdkClientException.class, () -> withPrefix
                            .startSyncExecution(s -> s.stateMachineArn(express).input("{\"orderId\": \"o-19\", \"net\": 1}")));
                    Throwable cause = Checks.rootCause(e);
                    System.out.println("With host prefixes on, StartSyncExecution fails: "
                            + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                }
            } finally {
                for (String arn : new String[] {standardArn, expressArn}) {
                    if (arn != null) {
                        sfn.deleteStateMachine(d -> d.stateMachineArn(arn));
                    }
                }
                lambda.deleteFunction(d -> d.functionName(functionName));
                LambdaFunctions.deleteLogGroup(logs, functionName);
                LambdaFunctions.deleteRole(iam, sfnRole);
                LambdaFunctions.deleteRole(iam, lambdaRole);
            }
            System.out.println("Deleted the state machines, the function, its log group and the roles");
        }
        System.out.println();
    }

    /** Polls a Standard execution every 200 ms, for up to 15 seconds. */
    private static DescribeExecutionResponse waitForExecution(SfnClient sfn, String executionArn) {
        DescribeExecutionResponse execution = sfn.describeExecution(d -> d.executionArn(executionArn));
        for (int i = 0; i < 75 && execution.status() == ExecutionStatus.RUNNING; i++) {
            Checks.pause(Duration.ofMillis(200));
            execution = sfn.describeExecution(d -> d.executionArn(executionArn));
        }
        Checks.expect(execution.status() != ExecutionStatus.RUNNING, "Execution still running: " + executionArn);
        return execution;
    }
}
