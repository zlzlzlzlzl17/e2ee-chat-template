# E2EE Chat Template

Self-hosted chat template for fresh deployments, built around:

- `e2eechat_app`: Android client
- `e2eechat_web`: Node.js / Express / SQLite backend
- built-in management portal on a separate host such as `manage.example.com`

The repository is designed for people who want an Android-first, self-hosted private chat stack with end-to-end encrypted messaging and attachments. The browser chat UI is intentionally disabled in this template; the main client is the Android app.

## Project Overview

This project combines:

- an Android Compose app for messaging, attachments, notifications, and voice calls
- a Node.js backend for authentication, message routing, file storage, push delivery, and management APIs
- a management portal for registrations, account review, APK distribution, and admin operations

It is intended for fresh installations of the current `e2eechat` format, not as a drop-in upgrade for the older `familychat` repository layout.

## Features

- Direct chats and group chats
- End-to-end encrypted text messages
- Encrypted image, file, and audio attachments
- Per-device identity keys, fingerprints, and safety-code change notices
- Signed prekeys and one-time prekeys for direct-message setup
- Group sender-key distribution per recipient device
- FCM push notifications and WebRTC voice calls
- Management portal with TOTP support
- APK upload and release distribution from the management portal

## E2EE Overview

At a high level, the current Android app handles encryption on the client side:

- Each device creates a long-lived signing identity and publishes signed key material for other devices.
- Direct chats use a client-side session setup plus per-message key derivation for encrypted payloads.
- Group chats use a sender-key model. A sender key is wrapped separately for recipient devices and then used to encrypt group messages.
- Attachments use a random file key. The file payload and its metadata are encrypted on the client, and the file key is wrapped through the direct or group E2EE channel.
- The server stores ciphertext, public identity material, and delivery metadata, but it is not supposed to hold the plaintext message body or plaintext attachment content.

This is a custom implementation intended for practical self-hosting, not a claim of audited Signal-equivalent protocol assurance. See [SECURITY.md](SECURITY.md) for the trust model, implementation notes, and limitations.

## Use Cases

This template is a good fit for:

- family or private community servers
- small teams, clubs, or friend groups that want to self-host
- Android-first private chat deployments
- learning, experimentation, and custom product prototyping

It is a poor fit for:

- high-risk environments that require audited, formally reviewed secure messaging
- deployments that need strong metadata protection
- situations where you cannot trust the server operator at all

## Documentation

- [DEPLOYMENT.md](DEPLOYMENT.md): deployment and operations
- [SECURITY.md](SECURITY.md): E2EE design, trust boundaries, limitations, and non-goals
- [e2eechat_app/RELEASE_SIGNING_GUIDE_ZH.md](e2eechat_app/RELEASE_SIGNING_GUIDE_ZH.md): Android release-signing guide in Chinese
