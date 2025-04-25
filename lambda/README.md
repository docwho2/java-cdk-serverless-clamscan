# Lambda: ClamAV Virus Scanning Function

This module contains the AWS Lambda implementation for scanning S3 objects for viruses using **ClamAV**. Built with **Java 21**, this high-performance, serverless function uses a **container-based deployment** and leverages the **AWS SDK v2 Async Client with CRT (Common Runtime)** for optimal performance.

---

## 🚀 Features

- ✅ **Java 21** with virtual thread readiness
- 🔬 **ClamAV** integration (with up-to-date virus definitions)
- ☁️ **Asynchronous S3 interactions** via `S3AsyncClient` + CRT (zero-copy, event-driven I/O)
- 🐳 **Container-based Lambda deployment** using ARM64 base image (faster cold starts, lower cost)
- 🧠 **Smart object tagging**: adds `clamav-status` metadata (`INFECTED` / `OK`) after scan
- ⚡ **Parallel processing**: Uses `CompletableFuture` for high concurrency
- 🧼 **/tmp-safe**: Streams S3 content directly to `/tmp`, deletes after scan
- 🔒 No sensitive info logged; logs include scan result and file key only

---

## ⚙️ How It Works

1. **Triggered by S3 Event Notification**
2. **Downloads file** to Lambda `/tmp` using `S3AsyncClient`
3. **Executes `clamscan`** in a native container image with preloaded virus definitions
4. **Parses output** to detect infection
5. **Tags file** in-place with `clamav-status=OK` or `INFECTED`

---

## 📈 Performance Highlights

- CRT-backed async client reduces memory footprint and CPU usage
- ARM64 base image improves cold start performance up to **30%**
- Designed to scale horizontally across parallel S3 triggers

---

## 📁 Build Output

The `target/lambda-1.0.jar` file is automatically copied to the CDK module during Maven build to be included in the container image.

---

## 🧰 Technologies Used

- Java 21
- AWS SDK v2 with CRT
- Log4j2
- Maven Shade Plugin
- ClamAV
- Docker & AWS Lambda Container Images
