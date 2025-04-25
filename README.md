# Java CDK Serverless ClamAV Scanner

A streamlined, Java-powered solution for scanning files uploaded to Amazon S3 using ClamAV — deployed with AWS CDK and optimized for performance, simplicity, and production-readiness.

---

## 💡 Why This Project?

The inspiration for this project came from reviewing the [AWS Labs cdk-serverless-clamscan](https://github.com/awslabs/cdk-serverless-clamscan) repository. While that solution is powerful and flexible, it also comes with significant complexity:

| AWS Labs Solution                                  | This Project                                            |
|----------------------------------------------------|---------------------------------------------------------|
| Projen + Typescript + Python + Node.js + Lambda    | ✅ Pure Java (Java 21) for both Lambda and CDK          |
| ClamAV DB stored in S3 + loaded at runtime         | ✅ ClamAV DB bundled in Docker image at build time      |
| Multiple constructs and wiring layers              | ✅ Single CDK stack, minimal moving parts               |
| Multiple language bindings (JSII)                  | ✅ Simple Java Maven modules, fast to understand & run  |
| EventBridge + SNS integration baked in             | ✅ Focused S3 trigger → Lambda tagging flow             |

> This repo exists because not every virus scan pipeline needs an entire JSII-powered TypeScript library.  
> Sometimes, you just need **high-performance, maintainable infrastructure** in a language your backend team already uses.

---

## 🚀 What It Does

- Scans uploaded S3 objects for viruses using **ClamAV**
- Tags infected/clean files with a `clamav-status` object tag
- Uses **Java 21**, optimized with **AWS SDK v2 + CRT-based async S3 client**
- Deploys via **container-based AWS Lambda** using **ARM64** for speed and cost efficiency

---

## 🛠 Tech Stack

- **Java 21** — modern, high-performance backend language
- **AWS SDK v2 Async (CRT)** — blazing-fast, non-blocking I/O
- **CDK (Java)** — type-safe infrastructure-as-code
- **Docker** — multi-stage image with ClamAV + latest definitions
- **Lambda** — serverless + scalable compute

---

## 🧩 Modules

| Module     | Purpose                                                  |
|------------|----------------------------------------------------------|
| `lambda/`  | Java-based ClamAV Lambda function                        |
| `cdk/`     | CDK stack that provisions S3, Lambda, and IAM roles      |
| `integration-test/` | Optional: test framework to validate scan pipeline |

---

## 🏎 Performance & Cost

- Uses **ARM64** Lambda base image for faster cold starts and lower runtime cost
- **ClamAV definitions** are embedded at Docker build time — no S3 download needed at runtime
- **Only tags infected files by default** (configurable via env var)
- **No public internet required** for virus definitions or dependency download at runtime

---
