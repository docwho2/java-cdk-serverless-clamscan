package cloud.cleo.clamav.cdk;

import cloud.cleo.clamav.ScanStatus;
import static cloud.cleo.clamav.ScanStatus.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Size;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.amazon.awscdk.services.ecr.assets.Platform;
import software.amazon.awscdk.services.iam.AnyPrincipal;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.DockerImageCode;
import software.amazon.awscdk.services.lambda.DockerImageFunction;
import software.amazon.awscdk.services.lambda.EcrImageCodeProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketPolicy;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.IBucket;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
import software.constructs.Construct;

/**
 * CDK Stack to deploy Clam AV Container image and set permissions on S3 Buckets that will be scanned.
 *
 * @author sjensen
 */
public class ClamavLambdaStack extends Stack {

    private static final String PRIMARY_LAMBDA_NAME = "ClamavLambdaFunction";

    public ClamavLambdaStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        boolean addBucketPolicy = getContextBoolean("addBucketPolicy", false);

        String validationBucket = System.getenv("VALIDATION_BUCKET") != null
                ? !System.getenv("VALIDATION_BUCKET").isBlank() ? System.getenv("VALIDATION_BUCKET") : null : null;

        // Retrieve a comma-separated list of bucket names from context.
        // For example: cdk deploy --context bucketNames="bucket1,bucket2,bucket3"
        String bucketNamesContext = (String) this.getNode().tryGetContext("bucketNames");
        List<IBucket> buckets = new ArrayList<>();
        if (bucketNamesContext != null && !bucketNamesContext.isBlank()) {
            String[] names = bucketNamesContext.split(",");
            int count = 0;
            for (String name : names) {
                String trimmedName = name.trim();
                // Create a reference to the existing bucket.
                IBucket bucket = Bucket.fromBucketName(this, "SourceBucket" + count, trimmedName);
                buckets.add(bucket);

                if (addBucketPolicy) {
                    // Never apply policy to Validation bucket if defined
                    if (validationBucket == null || !validationBucket.equals(bucket.getBucketName())) {
                        // Apply a bucket Policy to the Bucket
                        BucketPolicy policy = BucketPolicy.Builder.create(this, "BucketPolicy" + count)
                                .bucket(bucket)
                                .build();

                        // Add Appropiate policy
                        if (ONLY_TAG_INFECTED) {
                            policy.getDocument().addStatements(getBucketPolicyDenyInfectedOnly(bucket));
                        } else {
                            policy.getDocument().addStatements(getBucketPolicyDeny(bucket));
                        }
                    }
                }

                count++;
            }
        }

        // Build the Docker image asset.
        // The bundling step runs a Maven build using a Maven image that supports Java 21,
        // then copies the produced JAR into the asset output so that the Dockerfile COPY
        // instruction (which expects target/lambda.jar) works properly.
        DockerImageAsset imageAsset = DockerImageAsset.Builder.create(this, "ClamavLambdaImage")
                .platform(isCloudShell() ? Platform.LINUX_AMD64 : Platform.LINUX_ARM64)
                .directory(".")
                .build();

        // Create a Docker-based Lambda function using the built image.
        DockerImageFunction lambdaFunction = DockerImageFunction.Builder.create(this, PRIMARY_LAMBDA_NAME)
                .code(DockerImageCode.fromEcr(imageAsset.getRepository(),
                        EcrImageCodeProps.builder().tagOrDigest(imageAsset.getImageTag()).build()))
                //
                // We use ARM because its cheaper for CPU bound executions like CLamAV scanning
                .architecture(isCloudShell() ? Architecture.X86_64 : Architecture.ARM_64)
                //
                // This seems fine for scanning 100MB files or less.  Increasing will not yield much faster scans, jut cost you more
                // 3009 gives you 3 VCPU vs <3009 which drops you to 2 VCPU
                .memorySize(3009)
                //
                // Default is 512MB, but can be increased to support larger file sizes for scanning
                // NOTE: increasing this will incur additional costs
                .ephemeralStorageSize(Size.mebibytes(512))
                //
                // Scans should complete within a minute, so 10 mins is pretty conservative to allow scan to complete
                .timeout(Duration.minutes(10))
                .functionName(PRIMARY_LAMBDA_NAME)
                .description("Scans S3 files based on ObjectCreate events")
                .logRetention(RetentionDays.ONE_MONTH) // Don't let the logs hang around forever
                // Ensure the Lambda also gets the ENV flag
                .environment(Map.of("ONLY_TAG_INFECTED", ONLY_TAG_INFECTED.toString()))
                .build();

        // For each bucket passed via CLI:
        for (IBucket bucket : buckets) {
            // Grant read permissions (to download objects into /tmp to perform scans).
            bucket.grantRead(lambdaFunction);

            // Grant permission to update object tags for the scan result.
            bucket.grantWrite(lambdaFunction, null, List.of("s3:PutObjectTagging"));

            // Add the Lambda function as an event target for all object created events.
            bucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(lambdaFunction));
        }

        // Lambda Function Name Output
        software.amazon.awscdk.CfnOutput.Builder.create(this, getStackName() + "-LambdaName")
                .description("Lambda Function Name for Virus Scanning")
                .value(lambdaFunction.getFunctionName())
                .build();

        // Lambda Function ARN Output
        software.amazon.awscdk.CfnOutput.Builder.create(this, getStackName() + "-LambdaArn")
                .description("Lambda Function ARN for Virus Scanning")
                .value(lambdaFunction.getFunctionArn())
                // Export in case someone wants to wire stuff up in another stack
                .exportName("ClamavLambdaFunctionArn")
                .build();

    }

    /**
     * Bucket Policy for just denying files marked INFECTED. This is used when ONLY_TAG_INFECTED is true.
     *
     * @param bucket
     * @return
     */
    private PolicyStatement getBucketPolicyDenyInfectedOnly(IBucket bucket) {
        return PolicyStatement.Builder.create()
                .sid("DenyReadIfInfected")
                .effect(Effect.DENY)
                .principals(List.of(new AnyPrincipal()))
                .actions(List.of("s3:GetObject"))
                .resources(List.of(bucket.arnForObjects("*")))
                .conditions(Map.of(
                        "StringEquals", Map.of(
                                "s3:ExistingObjectTag/" + SCAN_TAG_NAME, INFECTED.name()
                        )
                ))
                .build();
    }

    /**
     * Bucket Policy for denying anything but CLEAN. This is used when ONLY_TAG_INFECTED is false.
     *
     * @param bucket
     * @return
     */
    private PolicyStatement getBucketPolicyDeny(IBucket bucket) {

        // All statuses except clean
        List<String> denyStatuses = java.util.Arrays.stream(ScanStatus.values())
                .filter(status -> status != CLEAN)
                .map(Enum::name)
                .toList();

        return PolicyStatement.Builder.create()
                .sid("DenyReadIfScanning")
                .effect(Effect.DENY)
                .principals(List.of(new AnyPrincipal()))
                .actions(List.of("s3:GetObject"))
                .resources(List.of(bucket.arnForObjects("*")))
                .conditions(Map.of(
                        "StringEquals", Map.of(
                                "s3:ExistingObjectTag/" + SCAN_TAG_NAME, denyStatuses
                        )
                ))
                .build();
    }

    private boolean getContextBoolean(String key, boolean defaultValue) {
        Object contextValue = this.getNode().tryGetContext(key);
        if (contextValue instanceof String str) {
            return "true".equalsIgnoreCase(str.trim());
        }
        return defaultValue;
    }

    /**
     * Detect if using CloudShell which means we need x86 architecture/platform.
     *
     * @return
     */
    private static boolean isCloudShell() {
        String toolingAgent = System.getenv("AWS_TOOLING_USER_AGENT");
        return toolingAgent != null && toolingAgent.startsWith("AWS-CloudShell/");
    }

    /**
     * Entry point for CDK.
     *
     * @param args
     */
    public static void main(final String[] args) {
        App app = new App();
        new ClamavLambdaStack(app, "ClamavLambdaStack", StackProps.builder()
                .description("Scan AWS S3 Objects with Clam AV in Lambda based container")
                .build());
        app.synth();
    }
}
