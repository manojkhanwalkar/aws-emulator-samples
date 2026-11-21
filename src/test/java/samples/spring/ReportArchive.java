package samples.spring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;

/** Sample 24, the application side: a service that keeps reports in an S3 bucket, using the S3Client bean. */
@Service
public class ReportArchive {

    private final S3Client s3;
    private final String bucket;

    public ReportArchive(S3Client s3, @Value("${reports.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    public void store(String name, String content) {
        s3.putObject(p -> p.bucket(bucket).key("reports/" + name), RequestBody.fromString(content));
    }

    public String read(String name) {
        return s3.getObject(g -> g.bucket(bucket).key("reports/" + name), ResponseTransformer.toBytes()).asUtf8String();
    }
}
