# App Features

This document describes the current Android app and the server-backed features it already uses in this template. It is intentionally scoped to the code that exists today, not a future roadmap.

## Positioning

- Android is the primary end-user client in this template.
- The active web surface is the management UI, not a full browser chat client.
- The app is designed for self-hosted personal deployments and private experiments.

## Account And Session Basics

- Username/password login against the self-hosted server
- Remembered login credentials stored locally on the device
- Change password from inside the app
- Change username from inside the app
- Upload and update a profile avatar
- Local app preferences for language, display mode, and dynamic colors

## Conversations And Messaging

- One default group conversation created by the server on first boot
- Direct conversations between users
- Conversation list with unread counters and last-message previews
- Message history loading with pagination
- Read state and delivery state syncing
- Mentions and reply-to metadata on messages
- Message recall support for the sender or an admin

## Content Types

- Plain text messages
- Encrypted text messages when E2EE mode is enabled
- Encrypted file, image, and audio attachments when E2EE mode is enabled
- Plain photo upload path still exists on the server for non-E2EE flows
- Voice note style audio attachment sending

## E2EE Controls

- A locally stored shared `room secret`
- A local E2EE on/off toggle
- Client-side encryption and decryption for supported message bodies and attachments

This is a shared-secret design, not an audited multi-device secure messenger protocol. See [E2EE Implementation Notes](E2EE_IMPLEMENTATION.md) for the actual model and its limits.

## Notifications And Background Behavior

- Firebase Cloud Messaging registration on supported Android setups
- Message notifications and background wake-up flow
- Delivery/read state refresh after sync
- Incoming call restoration and notification handling

## Voice Call Support

- Basic voice call flow for direct conversations
- Incoming call invites and accept/decline actions
- Foreground notification and call controls
- Audio route switching for speaker, earpiece, and Bluetooth when available

Voice calling is still experimental in this template.

## Release And Update Flow

- The app can query stable and prerelease release channels from the server
- The management UI can upload APKs for those channels
- The Android app can check for newer builds exposed by the server

## Admin-Adjacent Server Capabilities Used By The App

- Registration request flow exists on the server, but the shipped Android app does not expose a registration screen by default
- User lists and conversation membership are served by the backend
- Push token registration and cleanup are handled through app APIs

## What This Template Does Not Promise

- No claim of production-grade security hardening
- No guarantee of delivery on every Android vendor/device combination
- No multi-platform parity claim
- No professional security audit claim
