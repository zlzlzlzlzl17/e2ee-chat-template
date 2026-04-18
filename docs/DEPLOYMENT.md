# Deployment

## Server Requirements

- Node.js 20+ recommended
- A Linux VM, VPS, or home server with HTTPS
- A reverse proxy such as Nginx or Caddy
- Persistent storage for the SQLite database, uploads, and APK releases

## Environment

Copy `.env.example` to `.env` and replace every placeholder:

- `JWT_SECRET`: required, long random secret
- `MANAGE_HOST`: management UI hostname
- `DB_PATH`: SQLite database path
- `UPLOAD_DIR`: uploaded media path
- `APP_RELEASE_DIR`: APK release storage path
- `SEED_DEMO_USERS`: optional, set to `true` only if you want demo users on first boot
- `FCM_*`: your Firebase Cloud Messaging server credentials
- `CALL_*`: your STUN/TURN configuration

## Recommended Layout

If you run the server from `e2ee_chat_web`, the default relative paths work:

- `./data/chat.sqlite`
- `./uploads`
- `./app_release`

## First Boot

On first boot, the server creates:

- a default management admin: `admin` / `change-this-manage-password`

By default, no normal chat users are created.

If you set `SEED_DEMO_USERS=true` before first boot, the server also creates:

- `alice` / `alice-change-me`
- `bob` / `bob-change-me`
- `carol` / `carol-change-me`

Use those only for local testing, then rotate or remove them.

The server also keeps a `POST /register_request` flow plus management approval endpoints, so you can build your own onboarding flow without changing the backend contract.

## Reverse Proxy

- Serve the main app over HTTPS
- Route the management host to the same Node service, but use a separate hostname
- Preserve `Host` / `X-Forwarded-Host`
- Support WebSocket upgrades

## Persistence

Back up at least:

- the SQLite database
- uploaded media
- APK release files

Do not commit runtime data back into the repository.
