# Deployment Guide

## Overview

This repository is designed for fresh deployments of the current E2EE Chat template.
Do not treat it as an in-place upgrade for the older `familychat` deployment format.

Recommended production layout:

- `chat.example.com`: Android app API endpoint
- `manage.example.com`: management portal

Both hostnames can point to the same Node.js process behind a reverse proxy.
The management portal is only served when the request host matches `MANAGE_HOST`.

## Prerequisites

- A Linux server or VM with persistent storage
- DNS records for your chat host and management host
- HTTPS certificates for both hosts
- Node.js 20 LTS or another recent Node.js LTS release
- `npm`
- A reverse proxy such as Nginx, Caddy, or Traefik

Optional:

- Firebase project and service-account credentials for FCM
- TURN server credentials for more reliable voice calls across NATs

## 1. Upload the Repository

Clone the repository onto the server:

```bash
git clone <your-repo-url> e2eechat-template
cd e2eechat-template
```

## 2. Install Backend Dependencies

```bash
cd e2eechat_web
npm install --omit=dev
```

## 3. Create the Backend Environment File

Copy the template:

```bash
cp .env.example .env
```

Set the required values:

- `PORT`
  Internal Node.js port. `3000` is fine when using a reverse proxy.
- `JWT_SECRET`
  Use a long random secret.
- `DB_PATH`
  SQLite database path. Keep it on persistent storage.
- `UPLOAD_DIR`
  Directory for uploaded media.
- `APP_RELEASE_DIR`
  Directory for APK uploads from the management portal.
- `MANAGE_HOST`
  Must exactly match your management domain, for example `manage.example.com`.
- `MANAGE_INITIAL_USERNAME`
  Username for the first management admin on a fresh database.
- `MANAGE_INITIAL_PASSWORD`
  Password for the first management admin on a fresh database.

Optional values:

- `SEED_SAMPLE_USERS=true`
  Only for demos or local testing.
- `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FCM_PRIVATE_KEY`
  Required for Firebase push notifications.
- `CALL_STUN_URLS`, `CALL_TURN_URLS`, `CALL_TURN_USERNAME`, `CALL_TURN_CREDENTIAL`
  Voice-call relay configuration.
- `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`
  Optional web push fields.

The server creates the database parent directory, upload directory, and app-release directory automatically if they do not exist.

## 4. Run the Backend

For a first manual test:

```bash
npm start
```

The server listens on `0.0.0.0:$PORT`.

## 5. Run It as a Service

Example `systemd` unit at `/etc/systemd/system/e2eechat.service`:

```ini
[Unit]
Description=E2EE Chat backend
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/e2eechat-template/e2eechat_web
ExecStart=/usr/bin/npm start
Restart=always
RestartSec=5
User=e2eechat
Group=e2eechat
Environment=NODE_ENV=production

[Install]
WantedBy=multi-user.target
```

Then enable and start it:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now e2eechat
sudo systemctl status e2eechat
```

Adjust `WorkingDirectory`, `User`, and `Group` for your server.

## 6. Put a Reverse Proxy in Front

Example Nginx config:

```nginx
upstream e2eechat_backend {
    server 127.0.0.1:3000;
}

server {
    server_name chat.example.com;

    location / {
        proxy_pass http://e2eechat_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}

server {
    server_name manage.example.com;

    location / {
        proxy_pass http://e2eechat_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

Important:

- Proxy both domains to the same backend process.
- Preserve the original host header.
- Terminate TLS at the proxy and serve the public endpoints over HTTPS.

## 7. First-Run Checklist

1. Start the backend with the bootstrap admin credentials in `.env`.
2. Open `https://manage.example.com/`.
3. Sign in with `MANAGE_INITIAL_USERNAME` and `MANAGE_INITIAL_PASSWORD`.
4. Change the admin password immediately.
5. Enable TOTP in the management portal.
6. Remove or blank the bootstrap admin variables from `.env` after the first successful setup if you do not want them left in place.

## 8. Configure the Android App

Copy the template file:

```bash
cd ../e2eechat_app
cp template.properties.example template.properties
```

Set:

```properties
defaultServerUrl=https://chat.example.com
```

Optional Android setup:

- If you use FCM, place the real `google-services.json` in `e2eechat_app/app/google-services.json`.
- If you want signed release builds, create `e2eechat_app/keystore.properties`.
- See `e2eechat_app/keystore.properties.example` for the required keys.
- See `e2eechat_app/RELEASE_SIGNING_GUIDE_ZH.md` for the existing signing walkthrough.

## 9. Build the Android App

Debug build:

```bash
cd e2eechat_app
./gradlew assembleDebug
```

Release build:

```bash
cd e2eechat_app
./gradlew assembleRelease
```

The app expects the backend URL from `template.properties`, so make sure that file exists before building a distributable app.

## 10. Backups and Persistence

Back up at least:

- your SQLite database file
- `uploads/`
- `app_release/` if you use APK distribution from the portal
- your `.env`
- your Android signing keystore, stored outside Git

## 11. Production Hardening Checklist

- Use long random secrets for `JWT_SECRET` and admin passwords.
- Enforce HTTPS for both the chat host and the management host.
- Run the service under a dedicated non-root user.
- Keep write permissions limited to the database and upload directories.
- Disable `SEED_SAMPLE_USERS` in production.
- Configure a TURN server if voice calls must work reliably across networks.
- Monitor logs and keep regular backups.
