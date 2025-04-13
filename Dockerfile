# Builder stage: use Ubuntu to install ClamAV and run freshclam.
FROM --platform=linux/arm64 ubuntu:20.04 AS builder

# Prevent interactive prompts.
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update -y \
    && apt-get install -y clamav clamav-freshclam clamav-daemon p7zip-full \
    && apt-get clean


# Create a directory for the definitions and run freshclam to update them.
RUN mkdir -p /tmp/clamav_defs && \
    chmod -R 777 /tmp/clamav_defs && \
    freshclam --stdout --datadir=/tmp/clamav_defs && \
    cp -R /tmp/clamav_defs /tmp/clamav_defs_output

# (Optional) Also copy the clamscan and freshclam executables if needed.
RUN cp /usr/bin/clamscan /tmp/ && cp /usr/bin/freshclam /tmp/


# Builder stage: copy all required shared libraries into /tmp/clamav_libs
RUN mkdir -p /tmp/clamav_libs && \
    cp /lib/aarch64-linux-gnu/libclamav.so.* /tmp/clamav_libs/ && \
    cp /lib/aarch64-linux-gnu/libjson-c.so.* /tmp/clamav_libs/ && \
    cp /lib/aarch64-linux-gnu/libbz2.so.* /tmp/clamav_libs/ && \
    cp /lib/aarch64-linux-gnu/libltdl.so.* /tmp/clamav_libs/ && \
    cp /lib/aarch64-linux-gnu/libxml2.so.* /tmp/clamav_libs/ && \
    cp /lib/aarch64-linux-gnu/libmspack.so.* /tmp/clamav_libs/ && \
    cp /lib/aarch64-linux-gnu/libcrypto.so.* /tmp/clamav_libs/ && \
    cp /lib/aarch64-linux-gnu/libz.so.* /tmp/clamav_libs/ && \
    cp /lib/aarch64-linux-gnu/libtfm.so.* /tmp/clamav_libs/ && \
    cp /lib/aarch64-linux-gnu/libpcre2-8.so.* /tmp/clamav_libs/ && \
    cp /lib/aarch64-linux-gnu/liblzma.so.* /tmp/clamav_libs/


# Final stage: use the AWS Lambda Java 21 base image.
FROM --platform=linux/arm64 public.ecr.aws/lambda/java:21 AS final


# Copy the ClamAV executables from the builder stage.
COPY --from=builder /tmp/clamscan /usr/bin/clamscan
COPY --from=builder /tmp/freshclam /usr/bin/freshclam

# Copy the virus definitions updated by freshclam.
COPY --from=builder /tmp/clamav_defs_output /var/task/clamav_defs


# Copy all ClamAV shared libraries from the builder stage.
COPY --from=builder /tmp/clamav_libs /usr/lib/clamav_libs

# Set the dynamic linker search path to include the directory with the ClamAV libraries.
ENV LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:/usr/lib/clamav_libs"

# Copy your Lambda function JAR into the image.
COPY lambda/target/lambda.jar ${LAMBDA_TASK_ROOT}/lib/


# Specify the Lambda handler (in the format Package.Class::method).
CMD [ "cloud.cleo.clamav.lambda.MyLambdaHandler::handleRequest" ]

