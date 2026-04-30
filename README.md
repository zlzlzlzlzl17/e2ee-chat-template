# E2EE Chat Template

Self-hosted end-to-end encrypted chat template with:

- `e2eechat_web`: Node.js / Express / SQLite backend and management portal
- `e2eechat_app`: Android Compose client
- Web chat intentionally disabled; this template ships the app API and the management portal only

## Features

- Direct chats and group chats
- Image, file, and voice-message attachments
- Device identity, public keys, prekeys, and sender-key packaging
- Read states, message recall, and APK upload from the management portal
- TOTP support for management admins
- FCM push notifications and WebRTC voice calls

## Security Note

This repository is meant for personal or small-scale self-hosted deployments.
It is not a professionally audited Signal-class cryptography product, so do not market it as one.

## Quick Start

### 1. Configure the backend

Copy `e2eechat_web/.env.example` to `e2eechat_web/.env` and set at least:

- `JWT_SECRET`
- `MANAGE_HOST`
- `MANAGE_INITIAL_USERNAME`
- `MANAGE_INITIAL_PASSWORD`

On a fresh database, the server uses `MANAGE_INITIAL_USERNAME` and `MANAGE_INITIAL_PASSWORD` to bootstrap the first management admin.

Sample users are disabled by default. Set `SEED_SAMPLE_USERS=true` only if you want local demo accounts such as `alice`, `bob`, and `carol`.

### 2. Start the backend

```bash
cd e2eechat_web
npm install
npm start
```

### 3. Configure the Android client

Copy `e2eechat_app/template.properties.example` to `e2eechat_app/template.properties`:

```properties
defaultServerUrl=https://chat.example.com
```

### 4. Optional integrations

- Firebase / FCM:
  Place the real `google-services.json` at `e2eechat_app/app/google-services.json` and fill the FCM service-account values in `e2eechat_web/.env`.
- Release signing:
  Put your real signing config in `e2eechat_app/keystore.properties`.
  See `e2eechat_app/keystore.properties.example` for the expected format.
  A Chinese signing guide is available at `e2eechat_app/RELEASE_SIGNING_GUIDE_ZH.md`.

## Deployment

See [DEPLOYMENT.md](DEPLOYMENT.md) for a production-oriented deployment guide, including:

- domain layout
- backend setup
- `systemd` service example
- reverse proxy example
- Android client configuration
- first-run and hardening checklist

## Repository Hygiene

Do not commit:

- `e2eechat_web/.env`
- `e2eechat_web/data/`
- `e2eechat_web/uploads/`
- `e2eechat_web/app_release/`
- `e2eechat_app/template.properties`
- `e2eechat_app/keystore.properties`
- `e2eechat_app/app/google-services.json`
- any `.jks` or `.keystore` file
- build output, Gradle caches, or `node_modules`

