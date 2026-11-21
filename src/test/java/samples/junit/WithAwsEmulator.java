package samples.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Sample 23: put this on a test class to give it its own private AWS Emulator.
 *
 * <pre>{@code
 * @WithAwsEmulator
 * class OrderServiceTest {
 *     @Test
 *     void savesTheOrder(DynamoDbClient dynamo, S3Client s3) { ... }   // clients ready to use
 * }
 * }</pre>
 *
 * <p>{@link AwsEmulatorExtension} starts one emulator before the first test of the class (on a free
 * port, empty) and stops it after the last; test methods, constructors and lifecycle methods can ask for
 * an {@code Emulator}, an {@code EmulatorProcess} or any synchronous AWS SDK client as a parameter. If
 * {@code aws-emulator} is not installed, the class's tests are skipped, not failed.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(AwsEmulatorExtension.class)
public @interface WithAwsEmulator {

    /** More {@code aws-emulator} options for this class's emulator, for example {@code {"--auth", "off"}}. */
    String[] options() default {};
}
