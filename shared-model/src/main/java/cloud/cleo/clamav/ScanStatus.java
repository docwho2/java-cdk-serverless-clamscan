package cloud.cleo.clamav;


/**
 * Possible Statuses that can be applied for a scan for tagging.
 */
public enum ScanStatus {
    
    /**
     * Scan was completed and found to be clean.
     */
    CLEAN,
    /**
     * Scan was completed and found to be infected.
     */
    INFECTED,
    /**
     * Scan aborted because file size is too big to scan.
     */
    FILE_SIZE_EXCEEED,
    /**
     * Scanning is in progress.
     */
    SCANNING,
    /**
     * An error occurred during the scanning progress.
     */
    ERROR;
    
    /**
     * Name of tag to be used for the scanning status.
     */
    public static final String SCAN_TAG_NAME = "scan-status";
}
