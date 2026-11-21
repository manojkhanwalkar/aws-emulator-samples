package samples;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLContext;
import samples.emulator.Emulator;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Sample 21: Amazon S3 beyond put and get.
 *
 * <ol>
 *   <li><b>Multipart upload</b>, the way large files are uploaded: start an upload, send parts (each at
 *       least 5 MiB except the last), and complete it; S3 joins the parts into one object.</li>
 *   <li><b>Versioning.</b> With versioning on, overwriting or deleting an object keeps the old versions.
 *       A delete only adds a "delete marker", and you can still read an older version by its ID.</li>
 *   <li><b>Presigned URLs.</b> A URL that lets someone without AWS keys download one object until the
 *       URL expires. The emulator checks the signature and the expiry like AWS: a plain HTTP GET works
 *       before, and gets HTTP 403 after.</li>
 * </ol>
 * The presigner needs the same settings as the client (endpoint, Region, keys, path-style), and the
 * plain HTTP client must trust the emulator's certificate.
 */
public final class S21_S3Deeper {

    private S21_S3Deeper() {}

    private static final int MIB = 1024 * 1024;

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 21. S3: multipart upload, versioning, presigned URLs ==");
        String bucket = "samples-documents-" + Names.suffix();

        try (S3Client s3 = emulator.client(S3Client.builder().forcePathStyle(true))) {
            s3.createBucket(b -> b.bucket(bucket));
            try {
                multipart(s3, bucket);
                versioning(s3, bucket);
                presignedUrls(emulator, bucket, s3);
            } finally {
                // A versioned bucket is empty only when every version and delete marker is gone.
                s3.listObjectVersionsPaginator(l -> l.bucket(bucket)).forEach(page -> {
                    page.versions().forEach(v -> s3.deleteObject(d -> d.bucket(bucket).key(v.key()).versionId(v.versionId())));
                    page.deleteMarkers().forEach(m -> s3.deleteObject(d -> d.bucket(bucket).key(m.key()).versionId(m.versionId())));
                });
                s3.deleteBucket(b -> b.bucket(bucket));
            }
            System.out.println("Deleted every version and the bucket");
        }
        System.out.println();
    }

    private static void multipart(S3Client s3, String bucket) {
        String key = "backups/archive.bin";
        String uploadId = s3.createMultipartUpload(c -> c.bucket(bucket).key(key)).uploadId();

        // Two parts: 5 MiB (the minimum for every part but the last) and 1 MiB.
        List<CompletedPart> parts = new ArrayList<>();
        byte[][] contents = {filled(5 * MIB, (byte) 'a'), filled(MIB, (byte) 'b')};
        for (int i = 0; i < contents.length; i++) {
            int partNumber = i + 1;
            String eTag = s3.uploadPart(u -> u.bucket(bucket).key(key).uploadId(uploadId).partNumber(partNumber),
                    RequestBody.fromBytes(contents[i])).eTag();
            parts.add(CompletedPart.builder().partNumber(partNumber).eTag(eTag).build());
        }
        System.out.println("Multipart upload " + key + ": uploaded " + s3.listParts(l -> l.bucket(bucket).key(key)
                .uploadId(uploadId)).parts().size() + " parts");

        String eTag = s3.completeMultipartUpload(c -> c.bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload(m -> m.parts(parts))).eTag();
        long size = s3.headObject(h -> h.bucket(bucket).key(key)).contentLength();
        // A multipart object's ETag is not an MD5 of the content: it ends in -<number of parts>.
        System.out.println("Completed: one object of " + size / MIB + " MiB, ETag " + eTag);
        Checks.expect(size == 6L * MIB && eTag.endsWith("-2\""), "Unexpected multipart result: " + size + " bytes, ETag " + eTag);
    }

    private static void versioning(S3Client s3, String bucket) {
        s3.putBucketVersioning(v -> v.bucket(bucket).versioningConfiguration(c -> c.status(BucketVersioningStatus.ENABLED)));
        String key = "contracts/terms.txt";
        String v1 = s3.putObject(p -> p.bucket(bucket).key(key), RequestBody.fromString("terms, version 1")).versionId();
        String v2 = s3.putObject(p -> p.bucket(bucket).key(key), RequestBody.fromString("terms, version 2")).versionId();
        System.out.println("Versioning on; wrote " + key + " twice: versions " + v1 + " and " + v2);

        s3.deleteObject(d -> d.bucket(bucket).key(key));
        boolean visible = s3.listObjectsV2(l -> l.bucket(bucket).prefix(key)).keyCount() > 0;
        System.out.println("Deleted it: listed now? " + visible + ". But its history is kept:");
        s3.listObjectVersions(l -> l.bucket(bucket).prefix(key)).versions().stream()
                .map(ObjectVersion::versionId).forEach(id -> System.out.println("  version " + id));
        System.out.println("  plus " + s3.listObjectVersions(l -> l.bucket(bucket).prefix(key)).deleteMarkers().size()
                + " delete marker");
        String first = s3.getObject(g -> g.bucket(bucket).key(key).versionId(v1), ResponseTransformer.toBytes()).asUtf8String();
        System.out.println("Reading version " + v1 + " still gives: \"" + first + "\"");
    }

    private static void presignedUrls(Emulator emulator, String bucket, S3Client s3) {
        String key = "reports/q3.txt";
        s3.putObject(p -> p.bucket(bucket).key(key), RequestBody.fromString("Q3 report"));

        try (S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(emulator.endpoint())
                .region(Emulator.REGION)
                .credentialsProvider(emulator.credentials())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            URI url = URI.create(presigner.presignGetObject(p -> p.signatureDuration(Duration.ofSeconds(5))
                    .getObjectRequest(g -> g.bucket(bucket).key(key))).url().toString());
            System.out.println("Presigned GET URL, valid 5 seconds: " + url.toString()
                    .replaceAll("X-Amz-(Credential|Date|Signature)=[^&]+", "X-Amz-$1=..."));

            // Anyone with the URL can download, with no keys: a plain HTTP GET.
            HttpResponse<String> before = get(emulator, url);
            System.out.println("  GET now:            HTTP " + before.statusCode() + " \"" + before.body() + "\"");
            Checks.pause(Duration.ofSeconds(6));
            HttpResponse<String> after = get(emulator, url);
            String code = after.body().replaceAll("(?s).*<Code>([^<]*)</Code>.*", "$1");
            System.out.println("  GET after 6 seconds: HTTP " + after.statusCode() + " " + code + " (expired)");
            Checks.expect(before.statusCode() == 200 && after.statusCode() == 403, "Expected 200 then 403");
        }
    }

    /** A plain HTTPS GET, with no AWS SDK and no keys, trusting the emulator's certificate. */
    private static HttpResponse<String> get(Emulator emulator, URI url) {
        try {
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, emulator.trustManagers(), null);
            try (HttpClient http = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5)).build()) {
                return http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofString());
            }
        } catch (IOException | GeneralSecurityException | InterruptedException e) {
            throw new IllegalStateException("GET " + url + " failed", e);
        }
    }

    private static byte[] filled(int size, byte value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, value);
        return bytes;
    }
}
