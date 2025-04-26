package cloud.cleo.clamav.test;

import cloud.cleo.clamav.ScanStatus;
import static cloud.cleo.clamav.ScanStatus.SCAN_TAG_NAME;
import java.io.PrintWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterAll;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

public class VirusScanValidationTest {

    private static final String BUCKET_NAME = System.getenv("VALIDATION_BUCKET");
    private static final String INFECTED_KEY = "eicar.txt";
    private static final String OVERSIZED_KEY = "large-test-file.zip";
    

    private static final S3Client s3 = S3Client.create();

    @BeforeAll
    static void checkSetup() {
        if (BUCKET_NAME == null || BUCKET_NAME.isEmpty()) {
            throw new IllegalStateException("VALIDATION_BUCKET environment variable must be set.");
        }
    }

    @Test
    public void validateScanOfKnownInfectedFile() throws InterruptedException {
        try {
            retriggerScan(INFECTED_KEY);
            waitForTagValue(INFECTED_KEY, ScanStatus.INFECTED);
        } finally {
            clearTags(INFECTED_KEY);
        }
    }

    @Test
    public void validateScanOfOversizedFile() throws InterruptedException {
        try {
            retriggerScan(OVERSIZED_KEY);
            waitForTagValue(OVERSIZED_KEY, ScanStatus.FILE_SIZE_EXCEEED);
        } finally {
            clearTags(OVERSIZED_KEY);
        }
    }
    
    @AfterAll
    static void cleanup() {
        if (s3 != null) {
            s3.close();
        }
    }

    private void waitForTagValue(String key, ScanStatus expectedValue) throws InterruptedException {
        long timeoutMillis = Duration.ofSeconds(60).toMillis();
        long sleepMillis = 10000;
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < timeoutMillis) {
            List<Tag> tags = getTags(key);
            String actual = tags.stream()
                    .filter(tag -> SCAN_TAG_NAME.equals(tag.key()))
                    .map(Tag::value)
                    .findFirst()
                    .orElse(null);

            if (expectedValue.name().equals(actual)) {
                assertThat(actual).isEqualTo(expectedValue.name());
                return;
            }

            Thread.sleep(sleepMillis);
        }

        throw new AssertionError("Timed out waiting for scan-status tag: " + expectedValue + " on key: " + key);
    }

    private List<Tag> getTags(String key) {
        GetObjectTaggingResponse response = s3.getObjectTagging(GetObjectTaggingRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .build());
        return response.tagSet();
    }

    private void clearTags(String key) {
        s3.deleteObjectTagging(DeleteObjectTaggingRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .build());
    }

    private void retriggerScan(String key) {
        CopyObjectRequest copyRequest = CopyObjectRequest.builder()
                .sourceBucket(BUCKET_NAME)
                .sourceKey(key)
                .destinationBucket(BUCKET_NAME)
                .destinationKey(key)
                .metadataDirective(MetadataDirective.COPY)
                .build();

        s3.copyObject(copyRequest);
    }

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(VirusScanValidationTest.class))
                .build();

        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(request, listener);

        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out, true));

        summary.getFailures().forEach(failure -> {
            System.err.println("Failure: " + failure.getTestIdentifier().getDisplayName());
            failure.getException().printStackTrace();
        });

        if (summary.getTotalFailureCount() > 0 || summary.getContainersFailedCount() > 0) {
            System.exit(1);
        }
    }
}
