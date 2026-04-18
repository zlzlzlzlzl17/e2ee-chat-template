# E2EE Chat Template

Self-hosted personal chat template, experimental E2EE chat starter, and reference implementation.

This repository is meant to be a starting point for private experiments and personal deployments. It is not a professionally audited secure messenger, and it should not be marketed as Signal/WhatsApp-grade security software.

## Repository Layout

- `e2ee_chat_web`: Node.js server, management UI, public web assets, uploads, and release file handling.
- `e2ee_chat_app`: Android client project.
- `docs/`: deployment, Android setup, security notes, limitations, and publishing checklist.

## Current Scope

- Self-hosted Node.js chat backend
- Android client
- Experimental end-to-end encrypted messages and attachments
- Push notifications via FCM for supported Android setups
- Basic voice call support
- Management UI for admin tasks and APK uploads

The legacy browser chat client is intentionally disabled in the current template build. The active web surface is the management UI only. See `docs/WEB_CHAT_DISABLED.md` if you want to restore it for a private fork or remove the legacy web assets entirely.

See `docs/APP_FEATURES.md` for a fuller Android/app capability summary and `docs/E2EE_IMPLEMENTATION.md` for the current shared-secret encryption model and its limits.


## Important Warnings

- Experimental project, not a finished security product
- No independent security audit
- Threat model is limited and evolving
- You are responsible for your own hosting, HTTPS, secrets, Firebase project, TURN/STUN setup, and Android signing keys

## First-Time Setup

1. Copy `.env.example` to `.env` and replace every placeholder.
2. Deploy the server from `e2ee_chat_web`.
3. Add your own `google-services.json` to `e2ee_chat_app/app/`.
4. Create your own Android signing config from `e2ee_chat_app/keystore.properties.example`.
5. Start with a fresh database and fresh upload/release directories.

On first boot, if the management table is empty, the server seeds a default admin account:

- username: `admin`
- password: `change-this-manage-password`

Change it immediately after deployment.

By default, the server does not seed normal chat users.

If you want a quick local demo, you can set `SEED_DEMO_USERS=true` before first boot to create:

- `alice` / `alice-change-me`
- `bob` / `bob-change-me`
- `carol` / `carol-change-me`

The server still keeps a registration request flow for custom onboarding, but the included Android app does not expose a registration UI by default.
If you want to add a registration UI later, see the sample integration notes in `docs/ANDROID_REGISTRATION_INTEGRATION.md`.

## User Identity Model

This project uses three different user identifiers:

- `username`: the human-facing login name used for `POST /api/login` and registration requests
- `id`: the internal numeric database id, mostly used by server-side and management routes
- `user_code`: an app-facing external user reference returned by login and used in many client payloads

In practice:

- users sign in with `username + password`
- admins and server internals often operate on numeric `id`
- the app commonly works with `user_code` plus `username`


## Localization

The current template ships with English and Chinese as built-in example locales, but they are only starter content for your own fork. You can replace them, remove them, or extend them to any language set you want for your deployment.

In the current codebase:

- Android localization is centered in `e2ee_chat_app/app/src/main/java/com/example/chat/AppStrings.kt`
- The public web client localization is centered in `e2ee_chat_web/public/app.js`

## Docs

- [App Features](docs/APP_FEATURES.md)
- [E2EE Implementation Notes](docs/E2EE_IMPLEMENTATION.md)
- [Deployment](docs/DEPLOYMENT.md)
- [Android Setup](docs/ANDROID_SETUP.md)
- [Android Registration Integration](docs/ANDROID_REGISTRATION_INTEGRATION.md)
- [Disabled Web Chat Notes](docs/WEB_CHAT_DISABLED.md)
- [Security](docs/SECURITY.md)
- [Limitations](docs/LIMITATIONS.md)

## License

This template is released under the MIT License. See [LICENSE](LICENSE).
