package cloud.cleo.clamav.test;

import cloud.cleo.clamav.ScanStatus;
import static cloud.cleo.clamav.ScanStatus.ONLY_TAG_INFECTED;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.Order;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/**
 * Test to validate the container Lambda can properly set tags and identify a known infected file.
 *
 * In order for tests to run, you need to set VALIDATION_BUCKET in workflow and of course populate that bucket with test
 * files.
 *
 * @author sjensen
 */
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

    /**
     * When all tagging is enabled, right before scanning takes place, it should be in SCANNING state, so this is a test
     * is forced at Order(1) to ensure it tests this first. Scanning takes at least 20 seconds, so this is valid.
     *
     * @throws InterruptedException
     */
    @Test
    @Order(1)
    public void validateScanningTagSetImmediatelyIfNotOnlyInfected() throws InterruptedException {
        assumeTrue(!ONLY_TAG_INFECTED, "Skipping because ONLY_TAG_INFECTED is true");

        try {
            retriggerScan(INFECTED_KEY);
            waitForTagValue(INFECTED_KEY, ScanStatus.SCANNING);
        } finally {
            clearTags(INFECTED_KEY);
        }
    }

    /**
     * Validate a known virus file (EICAR Signature) tests to be INFECTED.
     *
     * @throws InterruptedException
     */
    @Test
    @Order(2)
    public void validateScanOfKnownInfectedFile() throws InterruptedException {
        try {
            if (ONLY_TAG_INFECTED) {
                // If Test 1 executed, then no need to retrigger
                retriggerScan(INFECTED_KEY);
            }
            waitForTagValue(INFECTED_KEY, ScanStatus.INFECTED);
        } finally {
            clearTags(INFECTED_KEY);
        }
    }

    /**
     * When file to scan is over MAX_BYTES, then ensure it is not scanned and immediately tagged FILE_SIZE_EXCEEDED.
     *
     * @throws InterruptedException
     */
    @Test
    @Order(3)
    public void validateScanOfOversizedFile() throws InterruptedException {
        try {
            retriggerScan(OVERSIZED_KEY);
            waitForTagValue(OVERSIZED_KEY, ScanStatus.FILE_SIZE_EXCEEED);
        } finally {
            clearTags(OVERSIZED_KEY);
        }
    }

    /**
     * CLose S3 client at end to shut down threads.
     */
    @AfterAll
    static void cleanup() {
        if (s3 != null) {
            s3.close();
        }
    }

    /**
     * Wait up to a minute for scan tag to have an expected value.
     *
     * @param key
     * @param expectedValue
     * @throws InterruptedException
     */
    private void waitForTagValue(String key, ScanStatus expectedValue) throws InterruptedException {
        long timeoutMillis = Duration.ofSeconds(60).toMillis();
        long sleepMillis = 5000;
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

    /**
     * Get all tags on S3 Object.
     *
     * @param key
     * @return
     */
    private List<Tag> getTags(String key) {
        GetObjectTaggingResponse response = s3.getObjectTagging(GetObjectTaggingRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .build());
        return response.tagSet();
    }

    /**
     * Remove any tags on the object.
     *
     * @param key
     */
    private void clearTags(String key) {
        s3.deleteObjectTagging(DeleteObjectTaggingRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .build());
    }

    /**
     * Copy file onto itself to trigger a ObjectCreate event to start scanning.
     *
     * @param key
     */
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

    /**
     * Called via mvn exec:java
     *
     * @param args
     */
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
