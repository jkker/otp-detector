# OTP Detector

An Android LSPosed/libxposed module that watches Google Voice notifications for OTP messages and copies the detected code to the clipboard.

## Features
- Google Voice-focused notification hook
- Body-only OTP extraction
- OTP-aware matching with duplicate suppression

## Requirements
- Java 21
- Android SDK
- mise
- LSPosed/libxposed-compatible framework with API 102 support

## Setup

```bash
git clone git@github.com:jkker/otp-detector.git
cd otp-detector
mise install
```

Open the project in Android Studio or use the CLI tasks below.

## Tasks

```bash
mise run build
mise run test
mise run lint
mise run clean
```

`mise run build` produces a local debug APK. This repository intentionally does not include release-signing or secret-management automation.
