# shared-model

This module defines shared Java model classes used across multiple subprojects.

## Purpose

- Centralizes common enums and data types such as `ScanStatus` used in both:
  - Lambda processing code
  - Integration test code
- Reduces duplication across projects.
- Ensures consistent data handling and tagging conventions across modules.

## Features

- Defines the `ScanStatus` enum, representing the virus scan results:
  - `CLEAN`
  - `INFECTED`
  - `FILE_SIZE_EXCEEED`
  - `SCANNING`
  - `ERROR`
- Safe to use across Lambda and other Java-based utilities.

## Usage

Add a Maven dependency on this module from other modules (e.g., `lambda`, `integration-test`):

```xml
<dependency>
    <groupId>cloud.cleo.clamav</groupId>
    <artifactId>shared-model</artifactId>
    <version>1.0</version>
</dependency>
```

## Notes

- Keep the `shared-model` module free from AWS SDK, logging, or other heavy dependencies.
- Intended only for plain Java data structures.

