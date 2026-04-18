# Android Setup

## Requirements

- Android Studio
- Android SDK for the configured compile target
- A Firebase project if you want FCM
- Your own signing key if you want release builds

## Open The Project

Open `e2ee_chat_app` in Android Studio.

`local.properties` is intentionally ignored and should stay local to each machine.

## Firebase

This template does not ship with a Firebase config.

If you need push notifications:

1. Create your own Firebase project.
2. Download your own `google-services.json`.
3. Place it at `e2ee_chat_app/app/google-services.json`.

## Signing

Use `e2ee_chat_app/keystore.properties.example` as the starting point for your local signing config.

Create a local `keystore.properties` file with your own values and your own `.jks` file. Do not commit either one.

## Default Server URL

The Android app ships with `https://chat.example.com` as a placeholder default. Change it in the app UI or update the source for your own deployment.

## Localization

The Android app currently ships with English and Chinese as template example locales.

Treat those built-in strings as starter content, not as a fixed product requirement. For your own fork, you can:

- replace the existing copy with your own language set
- remove one of the example locales
- extend the app to support additional locales

The current Android localization entry point is `e2ee_chat_app/app/src/main/java/com/example/chat/AppStrings.kt`.

If you also use the public web client, keep its strings in sync by updating the web `I18N` map in `e2ee_chat_web/public/app.js`.

## Registration Flow

The backend still supports registration requests and admin approval, but the included Android app does not ship with a registration screen.

For this template, assume one of these patterns:

- use pre-created accounts
- seed demo users for local testing only
- build your own registration/request UI on top of the existing backend endpoint

## Package Name

The current Android `applicationId` remains `com.example.chat` as a neutral template default. Rename it if your deployment requires a custom package identity.
