# E2EE Implementation Notes

This document explains the encryption model currently implemented in this template. It is written to describe the code as it exists today, without claiming a stronger security posture than the project actually has.

## Positioning

- This repository includes experimental end-to-end encrypted messaging and attachment handling.
- It is not a professionally audited secure messenger.
- It should not be described as Signal-grade or WhatsApp-grade security.

## High-Level Model

The current design is based on a shared secret entered in the Android app.

- The app stores a local `room secret` in its preferences.
- The app also stores a local `E2EE enabled` toggle.
- When E2EE is enabled, the client encrypts supported content before sending it to the server.
- Other participants need the same shared secret out of band in order to decrypt that content.

Important limitation:

- The current `room secret` is app-global local state, not a per-conversation ratcheting key schedule.
- There is no built-in in-app key agreement, identity verification ceremony, or automatic key rotation flow.

## Text Message Encryption

For encrypted text messages, the Android client performs encryption locally before upload/transmission.

Per message, the client:

1. Generates a random 16-byte salt.
2. Generates a random 12-byte IV.
3. Derives a 256-bit AES key from the shared secret with `PBKDF2WithHmacSHA256` using 200,000 iterations.
4. Encrypts the plaintext with `AES/GCM/NoPadding`.
5. Wraps the result as JSON with `v`, `salt`, `iv`, and `ct`.
6. Base64-encodes that JSON blob before sending it as the message payload.

At receive time, the Android client reverses that process locally using the same shared secret.

## Attachment Encryption

Encrypted attachments follow a similar model, but split the encrypted file bytes from encrypted metadata.

For encrypted attachments, the client:

1. Reads the local file bytes.
2. Generates a fresh random salt and IV for that attachment.
3. Derives an AES key from the shared secret with the same PBKDF2 settings.
4. Encrypts the binary bytes with AES-GCM.
5. Uploads the ciphertext file to the server.
6. Separately encrypts attachment metadata such as name, MIME type, and size.
7. Sends a message payload that references the uploaded ciphertext file plus the encrypted metadata and KDF parameters needed by another client.

When another client opens the attachment, it downloads the ciphertext file, decrypts the metadata locally, and then decrypts the file bytes locally with the shared secret.

## What The Server Stores

For E2EE message bodies and encrypted attachments, the server stores and forwards opaque encrypted payloads rather than the decrypted plaintext.

In practice, the server still knows or stores a meaningful amount of metadata, including:

- usernames, numeric ids, and app-facing `user_code` values
- conversation membership and conversation ids
- timestamps
- whether a message is flagged as E2EE
- message kind such as text, image, file, or audio
- encrypted attachment file size and stored upload path
- push tokens and some device metadata when FCM is used

For encrypted text previews, the server uses generic placeholder text rather than the decrypted message body.

## What Is Outside This E2EE Layer

This room-secret encryption layer does not cover every part of the system.

- Plaintext mode still exists for some flows when E2EE is disabled.
- The non-E2EE photo upload path stores ordinary file metadata and file content server-side.
- Registration, login, conversation metadata, and management flows are not hidden by this message encryption layer.
- Push notification infrastructure and device behavior may still reveal metadata.
- Voice calls use a separate signaling and media stack and are not described here as part of the shared-secret message encryption design.

## What This Implementation Does Not Claim

This template does not currently claim:

- a Double Ratchet style protocol
- per-device identity keys with user-visible verification
- formal forward secrecy or post-compromise security guarantees
- metadata resistance
- anonymous transport
- audited secure key backup or recovery
- endpoint compromise protection

If a device is compromised, the shared secret is exposed, or participants exchange the secret insecurely, this E2EE layer does not save the deployment.

## Practical Reading Guide

If you want to inspect the code behind this document, start here:

- Android crypto and attachment decryption: `e2ee_chat_app/app/src/main/java/com/example/chat/ChatCore.kt`
- Android send/decrypt flow and E2EE toggle handling: `e2ee_chat_app/app/src/main/java/com/example/chat/ChatViewModel.kt`
- Server-side storage and transport behavior: `e2ee_chat_web/server.js`
