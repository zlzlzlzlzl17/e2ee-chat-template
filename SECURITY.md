# Security Notes

## Scope

This document explains the current security model of the `e2eechat` template.
It is meant to describe how the repository works today, not to make an audited-security claim.

This project uses a custom Android-side E2EE design for direct messages, group messages, and encrypted attachments. It should be treated as a practical self-hosted system, not as a formally reviewed secure-messaging protocol suite.

## What Is Encrypted

The intended E2EE-protected content is:

- direct-message text payloads
- group-message text payloads
- attachment file contents
- attachment metadata encrypted under a file key
- wrapped attachment keys sent through the direct or group encrypted channel

The backend is expected to store ciphertext for E2EE messages and ciphertext-backed attachment payloads rather than plaintext chat content.

## Device Identity

Each Android device has a long-lived signing identity:

- The signing keypair is generated through `AndroidKeyStore`.
- The public identity key is uploaded to the server.
- A fingerprint / safety code is derived from `userCode + deviceId + publicKey`.
- Signed direct-message prekeys and group-key envelopes are verified against this identity key.

This gives the app a notion of device identity, but it does not by itself solve the problem of malicious server-side key substitution. Manual safety-code verification is still important if you want stronger identity assurance.

## Direct-Message Design

The current direct-message path is a custom client-side design with two major pieces:

- signed identity and prekey publication
- a ratcheting encrypted session for message payloads

In the current implementation:

- a device publishes a signing identity, an ECDH identity key, a signed prekey, and optional one-time prekeys
- the sender verifies signatures on the recipient bundle before use
- the current `v2` path derives initial session material from multiple ECDH exchanges in an X3DH-like pattern
- subsequent messages derive per-message keys from ratcheting chain state
- payload encryption uses AES-GCM with associated data bound to conversation and device/session metadata

There is also legacy `v1` compatibility code in the Android client, but fresh deployments should think of the current path as the `v2` custom direct-message scheme.

Important wording note:

- this is inspired by well-known secure-messaging patterns
- it is not a claim that the repository implements the full Signal protocol or has Signal-equivalent guarantees

## Group-Message Design

Group messaging uses a sender-key style approach:

- a sending device creates or reuses a per-group sender key
- that sender key is wrapped separately for recipient devices
- each wrapped envelope is encrypted to the recipient device and signed by the sender device identity
- recipients import the sender key and then decrypt group-message payloads locally
- per-message keys are derived from the sender key and a counter

This is efficient for group traffic, but the security properties depend on correct sender-key distribution and on the integrity of device identity material.

## Attachment Design

Attachments are handled separately from text messages:

- the client generates a random file key
- the file bytes are encrypted client-side before upload
- attachment metadata is encrypted under the same file key
- the file key itself is wrapped through either the direct-message or group-message encrypted path
- the server stores the encrypted upload plus encrypted metadata/wrapped-key payloads

This means the server is not supposed to learn attachment plaintext, but it still learns some metadata such as upload timing, ciphertext size, and attachment category.

## Trust Boundaries

### The Android client is trusted for plaintext handling

Plaintext is created, encrypted, decrypted, and displayed on the client.
If the Android device is compromised, rooted, backed up insecurely, or inspected through malware, E2EE cannot protect that endpoint.

### The server is trusted for routing and state management

The backend is trusted to:

- authenticate users
- manage conversations and memberships
- distribute public identity material and prekeys
- store ciphertext
- store and serve encrypted attachment blobs
- trigger push notifications and call wakeups

The server is not supposed to have message plaintext, but it still controls important state and can influence key distribution.

### What the server can still see

Even with E2EE enabled, the server can still observe or control metadata such as:

- account identifiers and usernames
- conversation membership and group structure
- device IDs, public identity keys, and fingerprints
- message timestamps and ordering
- whether a message is encrypted or not
- ciphertext sizes
- attachment kind, attachment URL, and ciphertext size
- push-token registration data if FCM is enabled
- call signaling and delivery metadata

The management portal and server operator also control administrative actions such as account approval, account deletion, group state changes, APK uploads, and server-side data deletion.

### Key-directory trust is a real boundary

The server acts as the key directory for device identities and prekeys.
Clients verify signatures on key material, but those signatures are anchored in the identity keys the server provides.

That means:

- a malicious or compromised server can attempt identity-key or prekey substitution
- manual fingerprint / safety-code verification is the main mitigation available in this design
- there is no external key transparency log or independent verification service in this repository

## Local Secret Storage

The Android app does not protect all secrets equally.

Better protected:

- the long-lived signing identity private key is stored in `AndroidKeyStore`

Less protected:

- direct ECDH private keys
- one-time prekeys
- sender keys
- ratchet/session state
- auth token
- saved login username/password

These values are stored through regular app `SharedPreferences` storage, not a hardened encrypted-secret store.
That is an important limitation of the current template and should be understood before production use.

## Push, Calls, and External Services

If you enable FCM or voice-call infrastructure:

- Google / FCM can observe push-delivery metadata
- notification text for encrypted messages is intentionally generic, but sender/conversation metadata still exists
- TURN / STUN infrastructure may expose network metadata related to calls

These integrations improve usability, but they are outside the narrow E2EE payload boundary.

## Limitations

- No professional cryptography audit is included.
- No formal proof or protocol verification is provided.
- No claim of Signal-equivalent security should be made.
- Metadata protection is not a design goal.
- The server is a trusted coordination point for identities, memberships, and push delivery.
- The Android app currently stores several sensitive values in app preferences.
- Only the Android client is part of the intended E2EE model in this template; the web chat UI is disabled.
- A compromised client device defeats endpoint security regardless of transport or server design.

## Non-Goals

This repository does not currently aim to provide:

- anonymous or metadata-resistant messaging
- audited or formally verified secure messaging
- key transparency or multi-party key consistency proofs
- deniable messaging guarantees
- secure enclave / hardware-backed protection for all session secrets
- a complete multi-platform client security model

## Recommended Operating Practices

- Use HTTPS for both chat and management hosts.
- Verify important contacts through safety codes when identity assurance matters.
- Disable sample users in production.
- Protect server backups, `.env`, SQLite data, and uploaded ciphertext files.
- Treat rooted devices, debug builds, and insecure device backups as high risk.
- Review custom changes carefully before relying on them for sensitive communication.
