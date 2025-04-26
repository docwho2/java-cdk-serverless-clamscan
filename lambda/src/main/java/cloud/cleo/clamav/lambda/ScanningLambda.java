package cloud.cleo.clamav.lambda;

import cloud.cleo.clamav.ScanStatus;
import static cloud.cleo.clamav.ScanStatus.SCAN_TAG_NAME;
import com.amazonaws.services.lambda.runtime.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

/**
 * Consume S3 Object create event and then scan Object and set Tag with result.
 *
 * @author sjensen
 */
public class ScanningLambda implements RequestHandler<S3EventNotification, Void> {

    /**
     * When true, only set tagging on object when it is infected. This makes it easier to fire a lambda on Tag event to
     * react to infected files. Otherwise when false tagging events will fire on all statuses which may not be what you
     * want. If you want to deny access via bucket policy while scanning, then this will need to be false.
     */
    final static boolean ONLY_TAG_INFECTED = true;

    // Max size in bytes to process (100MB is safe given 512MB /tmp in Lambda)
    final static int MAX_BYTES = 100000000;

    // Create an S3 client with CRT Async (better download performance and Async calls)
    final static S3AsyncClient s3Client = S3AsyncClient.crtCreate();

    // Configure a Log4j2 logger.
    final static Logger log = LogManager.getLogger(ScanningLambda.class);

    @Override
    public Void handleRequest(S3EventNotification event, Context context) {
        // There will only ever be one record
        event.getRecords().forEach(record -> {
            String bucket = record.getS3().getBucket().getName();
            String key = record.getS3().getObject().getUrlDecodedKey();

            log.info("Processing file from bucket: {}, key: {}", bucket, key);

            if (bucket == null || bucket.isEmpty() || key == null || key.isEmpty()) {
                log.error("Invalid S3 event: bucket and key must be provided");
                return;
            }

            // Check file size before downloading
            try {
                long size = s3Client.headObject(b -> b.bucket(bucket).key(key)).join().contentLength();
                if (size > MAX_BYTES) {
                    log.warn("Skipping file {} due to size ({} bytes) exceeding max of {} bytes", key, size, MAX_BYTES);
                    setScanTagStatus(bucket, key, ScanStatus.FILE_SIZE_EXCEEED).join();
                    return;
                }
            } catch (Exception e) {
                log.error("Failed to get object metadata for key {}: {}", key, e.getMessage());
                return;
            }

            if (!ONLY_TAG_INFECTED) {
                // Set the status to scanning immediately, so download can be denied via policy if desired
                setScanTagStatus(bucket, key, ScanStatus.SCANNING); // Don't wait for Async response
            }

            // Download the file to /tmp. Cleanup key if it has many / characters and yield filename only
            Path localFilePath = Path.of("/tmp", new File(key).getName());
            try {
                log.info("Downloading file {} from bucket {} to {}", key, bucket, localFilePath);
                GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build();
                s3Client.getObject(getObjectRequest, localFilePath)
                        .join(); // Wait for completion before proceeding
            } catch (Exception e) {
                log.error("Error downloading file: {}", e.getMessage());
                return;
            }

            // Run ClamAV (clamscan) on the downloaded file.
            ScanStatus status;
            try {
                log.info("Running clamscan on file: {}", localFilePath);
                ProcessBuilder pb = new ProcessBuilder(
                        "clamscan",
                        "-v",
                        "--database=/var/task/clamav_defs",
                        "--stdout",
                        "--max-filesize=" + MAX_BYTES,
                        "--max-scansize=" + MAX_BYTES,
                        "-r",
                        "--tempdir=/tmp",
                        localFilePath.toString()
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();

                int remainingMillis = context.getRemainingTimeInMillis();
                log.info("Remaining Millis before Lambda will timeout: {}", remainingMillis);

                // Wait up till the amount of time left the Lambda has with 10 second buffer
                boolean finished = process.waitFor(remainingMillis - 10000, TimeUnit.MILLISECONDS);

                if (!finished) {
                    process.destroyForcibly();
                    log.error("clamscan process timed out!");
                    if (! ONLY_TAG_INFECTED ) {
                        setScanTagStatus(bucket, key, ScanStatus.ERROR).join(); // Wait for result before exiting
                    }
                    return;
                }

                try (final var is = process.getInputStream()) {
                    log.debug("Process Output: {}", new String(is.readAllBytes()));
                }

                // According to ClamAV: 0 means CLEAN, 1 means INFECTED, else ERROR.
                status = switch (process.exitValue()) {
                    case 0 ->
                        ScanStatus.CLEAN;
                    case 1 ->
                        ScanStatus.INFECTED;
                    default ->
                        ScanStatus.ERROR;
                };
                log.info("Scan result for {}: {}", key, status);
            } catch (IOException | InterruptedException e) {
                log.error("Error running clamscan: ", e);
                return;
            } finally {
                try {
                    Files.deleteIfExists(localFilePath);
                } catch (IOException e) {
                    log.warn("Warning: Could not delete local file {}: {}", localFilePath, e.getMessage());
                }
            }

            if (ONLY_TAG_INFECTED && !ScanStatus.INFECTED.equals(status)) {
                // Scan is not INFECTED, so do not set tagging 
                log.debug("Not setting tag on Object because of flag and file is not INFECTED");
                return;
            }

            // Update the S3 object's tagging with the scan result.
            setScanTagStatus(bucket, key, status).join(); // Wait for result before exiting

        });
        return null;
    }

    /**
     * Add/Update Scan Status tag to S3 Object. This method preserves any other tags that may be on the Object.
     *
     * @param bucket
     * @param key
     * @param status
     */
    private CompletableFuture<PutObjectTaggingResponse> setScanTagStatus(String bucket, String key, ScanStatus status) {
        try {
            // Get current tags
            List<Tag> existingTags = s3Client.getObjectTagging(b -> b.bucket(bucket).key(key))
                    .join() // Get result now since we need in order to put all tags
                    .tagSet();

            // Remove any existing "scan-status" tag
            List<Tag> updatedTags = new ArrayList<>();
            for (Tag tag : existingTags) {
                if (!tag.key().equals(SCAN_TAG_NAME)) {
                    updatedTags.add(tag);
                }
            }

            // Add updated scan-status tag
            updatedTags.add(Tag.builder()
                    .key(SCAN_TAG_NAME)
                    .value(status.toString())
                    .build());

            // Put updated tag set
            Tagging tagging = Tagging.builder()
                    .tagSet(updatedTags)
                    .build();
            PutObjectTaggingRequest putTaggingRequest = PutObjectTaggingRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .tagging(tagging)
                    .build();

            log.info("Updating object tags for {} with scan-status: {}", key, status);
            return s3Client.putObjectTagging(putTaggingRequest);
        } catch (Exception e) {
            log.error("Error updating object tags", e);
            return CompletableFuture.failedFuture(e);
        }
    }
}
