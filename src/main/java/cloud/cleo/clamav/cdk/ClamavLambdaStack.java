/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.clamav.cdk;

import java.util.ArrayList;
import java.util.List;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.DockerImageCode;
import software.amazon.awscdk.services.lambda.DockerImageFunction;
import software.amazon.awscdk.services.lambda.EcrImageCodeProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.IBucket;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
import software.constructs.Construct;

/**
 *
 * @author sjensen
 */
public class ClamavLambdaStack extends Stack {
    
    // Max size in bytes to process
    final static int MAX_BYTES = 40000000;


    public ClamavLambdaStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);
        
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
                count++;
            }
        }

        // Build the Docker image asset.
        // The bundling step runs a Maven build using a Maven image that supports Java 21,
        // then copies the produced JAR into the asset output so that the Dockerfile COPY
        // instruction (which expects target/lambda.jar) works properly.
        DockerImageAsset imageAsset = DockerImageAsset.Builder.create(this, "ClamavLambdaImage")
                .directory(".")  // Dockerfile is in the top level repo directory
                .build();

        // Create a Docker-based Lambda function using the built image.
        DockerImageFunction lambdaFunction = DockerImageFunction.Builder.create(this, "ClamavLambdaFunction")
                .code(DockerImageCode.fromEcr(imageAsset.getRepository(),
                        EcrImageCodeProps.builder().tagOrDigest(imageAsset.getImageTag()).build()))
                .architecture(Architecture.ARM_64)
                .functionName("ClamavLambdaFunction")
                .memorySize(3009)
                .timeout(Duration.minutes(10))
                .logRetention(RetentionDays.ONE_MONTH)
                .build();

        // For each bucket passed via CLI:
        for (IBucket bucket : buckets) {
            // Grant read permissions (to download objects).
            bucket.grantRead(lambdaFunction);

            // Grant permission to update object tags for the scan result.
            bucket.grantWrite(lambdaFunction, null, List.of("s3:PutObjectTagging"));

            // Add the Lambda function as an event target for all object created events.
            bucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(lambdaFunction));
        }
    }

    public static void main(final String[] args) {
        App app = new App();
        new ClamavLambdaStack(app, "ClamavLambdaStack", StackProps.builder()
                .description("Scan AWS S3 Objects with Clam AV in Lambda based container")
                .build());
        app.synth();
    }
}
