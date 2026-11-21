package samples.junit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;
import samples.emulator.Emulator;
import samples.emulator.EmulatorBackedTestSupport;
import samples.emulator.EmulatorProcess;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Sample 23: a JUnit 5 extension that gives each test class its own private AWS Emulator and hands
 * tests ready-made AWS SDK clients. Use it through {@link WithAwsEmulator}.
 *
 * <p>How it works, in JUnit terms:
 * <ul>
 *   <li>{@link BeforeAllCallback}: before the class's first test, start an emulator with
 *       {@code EmulatorProcess} (free port, empty state, idle timeout as a safety net). It is kept in the
 *       class's {@link ExtensionContext.Store}; JUnit closes {@code AutoCloseable} values in a store
 *       when that context ends, so the emulator stops after the class's last test, even if tests fail.</li>
 *   <li>{@link ParameterResolver}: a parameter of type {@code Emulator}, {@code EmulatorProcess}, or any
 *       sync SDK client ({@code S3Client}, {@code DynamoDbClient}, ...) is resolved from that emulator.
 *       A client is built through its static {@code builder()} method and {@code Emulator.client(...)},
 *       and is closed after the test method (it lives in the method's store). S3 clients get path-style
 *       addressing, which the emulator needs.</li>
 * </ul>
 * Nothing here is specific to one service: any client the project has a module for works.
 */
public final class AwsEmulatorExtension implements BeforeAllCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(AwsEmulatorExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        assumeTrue(EmulatorBackedTestSupport.isInstalled(), "aws-emulator is not installed: skipping (see README.md)");
        running(context);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == Emulator.class || type == EmulatorProcess.class || isSyncClient(type);
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        Running running = running(context);
        if (type == Emulator.class) {
            return running.emulator();
        }
        if (type == EmulatorProcess.class) {
            return running.process();
        }
        SdkClient client = newClient(running.emulator(), type);
        // Closed by JUnit when the context that asked for it (usually the test method) ends.
        context.getStore(NAMESPACE).put(new Object(), (AutoCloseable) client::close);
        return client;
    }

    /** The class's emulator, started on first use. Nested classes share their outer class's emulator. */
    private static Running running(ExtensionContext context) {
        ExtensionContext classContext = context;
        while (classContext.getParent().filter(p -> p.getTestClass().isPresent()).isPresent()) {
            classContext = classContext.getParent().orElseThrow();
        }
        String[] options = classContext.getTestClass()
                .flatMap(c -> AnnotationSupport.findAnnotation(c, WithAwsEmulator.class))
                .map(WithAwsEmulator::options).orElse(new String[0]);
        return classContext.getStore(NAMESPACE).computeIfAbsent(Running.class,
                key -> Running.start(options), Running.class);
    }

    private static boolean isSyncClient(Class<?> type) {
        if (!SdkClient.class.isAssignableFrom(type) || !type.isInterface()) {
            return false;
        }
        try {
            Method builder = type.getMethod("builder");
            return Modifier.isStatic(builder.getModifiers()) && AwsSyncClientBuilder.class.isAssignableFrom(builder.getReturnType());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SdkClient newClient(Emulator emulator, Class<?> type) {
        Object builder;
        try {
            builder = type.getMethod("builder").invoke(null);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new ParameterResolutionException("Cannot build a " + type.getSimpleName(), e);
        }
        if (builder instanceof S3ClientBuilder s3) {
            s3.forcePathStyle(true);
        }
        // The raw cast is safe: builder() of a sync client returns an AwsClientBuilder & AwsSyncClientBuilder.
        return (SdkClient) emulator.client((AwsClientBuilder & AwsSyncClientBuilder) builder);
    }

    /** One private emulator and the connection to it; closing it stops the emulator. */
    private record Running(EmulatorProcess process, Emulator emulator) implements AutoCloseable {

        static Running start(String[] options) {
            EmulatorProcess process = EmulatorProcess.start(Duration.ofMinutes(10), options);
            return new Running(process, process.emulator());
        }

        @Override
        public void close() {
            emulator.close();
            process.close();
        }
    }
}
