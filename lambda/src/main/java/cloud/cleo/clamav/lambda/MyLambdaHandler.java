package cloud.cleo.clamav.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

public class MyLambdaHandler implements RequestHandler<S3EventNotification, Void> {

    // Create an AWS SDK S3 client (v2).
    private final S3Client s3Client = S3Client.builder()
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();

    // Configure a Log4j2 logger.
    private static final Logger log = LogManager.getLogger(MyLambdaHandler.class);

    @Override
    public Void handleRequest(S3EventNotification event, Context context) {

        event.getRecords().forEach(record -> {
            String bucket = record.getS3().getBucket().getName();
            String encodedKey = record.getS3().getObject().getKey();
            // URL-decode the key to correctly handle special characters
            String key = URLDecoder.decode(encodedKey, StandardCharsets.UTF_8);

            log.info("Processing file from bucket: {}, key: {}", bucket, key);

            if (bucket == null || bucket.isEmpty() || key == null || key.isEmpty()) {
                log.error("Invalid S3 event: bucket and key must be provided");
                return;
            }

            // Download the file to /tmp.
            String fileName = new File(key).getName();
            String localFilePath = "/tmp/" + fileName;
            try {
                log.info("Downloading file {} from bucket {} to {}", key, bucket, localFilePath);
                GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build();
                s3Client.getObject(getObjectRequest, Path.of(localFilePath));
            } catch (Exception e) {
                log.error("Error downloading file: {}", e.getMessage());
                return;
            }

            // Run ClamAV (clamscan) on the downloaded file.
            String status;
            String output;
            try {
                log.info("Running clamscan on file: {}", localFilePath);
                ProcessBuilder pb = new ProcessBuilder(
                        "clamscan",
                        "--database=/var/task/clamav_defs",
                        "--stdout",
                        localFilePath
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();
                int exitCode = process.waitFor();
                log.debug("Process Output: {}", new String(process.getInputStream().readAllBytes()));

                // According to ClamAV: 0 means CLEAN, 1 means INFECTED, else ERROR.
                status = switch (exitCode) {
                    case 0 ->
                        "CLEAN";
                    case 1 ->
                        "INFECTED";
                    default ->
                        "ERROR";
                };
                log.info("Scan result for {}: {}", key, status);
            } catch (IOException | InterruptedException e) {
                log.error("Error running clamscan: {}", e.getMessage());
                return;
            } finally {
                try {
                    Files.deleteIfExists(Path.of(localFilePath));
                } catch (IOException e) {
                    log.warn("Warning: Could not delete local file {}: {}", localFilePath, e.getMessage());
                }
            }

            // Update the S3 object's tagging with the scan result.
            try {
                Tag scanTag = Tag.builder()
                        .key("scan-status")
                        .value(status)
                        .build();
                Tagging tagging = Tagging.builder()
                        .tagSet(Arrays.asList(scanTag))
                        .build();
                PutObjectTaggingRequest putTaggingRequest = PutObjectTaggingRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .tagging(tagging)
                        .build();
                s3Client.putObjectTagging(putTaggingRequest);
                log.info("Updated object tags for {} with scan-status: {}", key, status);
            } catch (Exception e) {
                log.error("Error updating object tags for {}: {}", key, e.getMessage());
            }
        });
        return null;
    }
}
