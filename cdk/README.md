# CDK Module – Infrastructure for Serverless ClamAV Scanning

This module defines and deploys the AWS infrastructure that powers the ClamAV virus scanning Lambda using AWS CDK in Java.

---

## 🚀 What It Does

- **Deploys a container-based Lambda function** that scans S3 uploads for viruses using ClamAV.
- **Sets up an S3 event trigger** so new files automatically invoke the scanner.
- **Tags each file** with a `scan-status` tag (`CLEAN` or `INFECTED`) after scanning (depending on config)

---

## 🔧 How It Works

- Uses a **Dockerfile** to build a Lambda image with:
  - ClamAV binaries and libraries
  - Latest virus definitions from `freshclam`
  - Your Lambda JAR (`lambda-1.0.jar`)
- The image is deployed via `DockerImageAsset` and used in a `DockerImageFunction`.

---

## 📦 Build Integration

- The Lambda project builds `lambda-1.0.jar` and copies it into this module under `lambda-jar/`.
- Docker uses this copied JAR when building the container.

---

## 🧱 Stack Resources

- ✅ Lambda function (container-based, Java 21, ARM64)
- ✅ S3 bucket with event notification trigger
- ✅ IAM roles with scoped permissions for tag access

---

## 🛠 Tech Stack

- **AWS CDK (Java)**
- **Java 21 Lambda Runtime**
- **Docker (multi-stage build)**
- **ARM64 container image**
- **ClamAV (Ubuntu-based)**

---

## 📌 Notes

- Using **ARM64** improves cold start times and reduces Lambda cost.
- Containerized ClamAV runs fully isolated from AWS-managed runtimes.
