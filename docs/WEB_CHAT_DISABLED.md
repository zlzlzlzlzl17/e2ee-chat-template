# Disabled Web Chat Notes

This repository still contains a legacy browser chat client under `e2ee_chat_web/public/`, but that client is intentionally disabled in the current template build.

The active web surface in this repository is the management UI in `e2ee_chat_web/manage_public/`.

## Current State

The browser chat client is disabled in two important ways:

1. Non-management host requests do not serve the legacy web chat files.
2. `/api/*` routes are protected by `appClientOnly`, which currently only allows the Android and old Windows client headers.

In practice, that means:

- browser chat is not available to end users
- the management UI still works
- the legacy browser chat files are kept only as a reference for private forks

## Why It Was Left In The Repository

The old browser client can still be useful as reference material for:

- UI experiments
- private fork restoration
- API behavior examples
- message rendering or attachment flow exploration

It is not part of the default public template runtime.

## Legacy Files That Still Exist

The legacy browser chat client currently lives here:

- `e2ee_chat_web/public/index.html`
- `e2ee_chat_web/public/app.js`
- `e2ee_chat_web/public/style.css`
- `e2ee_chat_web/public/manifest.json`
- `e2ee_chat_web/public/sw.js`

The old bundled PWA icons were removed from the template because they should not be reused in a public repository. If you restore the browser client in a private fork, add your own icons and update `manifest.json`.

## How The Disable Works

Current server behavior is roughly:

- management host -> serve `manage_public/index.html`
- other GET/HEAD requests -> serve a disabled landing page
- `/api/*` -> allow only approved app client headers

The key server-side controls are in `e2ee_chat_web/server.js`:

- `APP_CLIENT_VALUES` only allows `"android-app"` and `"windows-app"`
- `app.use("/api", appClientOnly)` blocks generic browser access to the chat API
- the final catch-all route returns `DISABLED_CHAT_HTML` for non-management hosts

## How To Restore The Browser Chat In A Private Fork

Restoring the legacy web chat is possible, but it is not just a static file switch. You need to make both routing and client-identity decisions.

### 1. Reintroduce Browser Access To The Public Web Files

Add a public web directory constant in `e2ee_chat_web/server.js`, for example:

```js
const PUBLIC_DIR = path.join(__dirname, "public");
```

Then reintroduce a non-management static or file-serving path for that directory.

For example, your private fork could:

- mount `PUBLIC_DIR` as static files for non-management hosts, or
- serve `public/index.html` from the non-management branch of the final catch-all

If you only return `public/index.html` without restoring static delivery for `app.js`, `style.css`, `manifest.json`, and `sw.js`, the old web client will still be broken.

### 2. Decide How The Browser Client Should Be Identified

Right now the server only allows:

- `android-app`
- `windows-app`

If you want the browser chat client to call `/api/*`, you need to either:

- add a new allowed header value such as `web-app`, or
- relax or redesign the `appClientOnly` gate for your private fork

The safer restoration path is usually:

1. add a dedicated browser client value in `server.js`
2. include that header from `e2ee_chat_web/public/app.js`

That keeps the server-side distinction explicit instead of silently opening the API to every browser request.

### 3. Update The Browser Client Fetch Layer

The legacy browser app in `e2ee_chat_web/public/app.js` currently calls `/api/*`, but it does not participate in the current app-client header allowlist model.

If you restore browser chat, update its fetch helper so requests send the same client-identification header expected by the server.

### 4. Review Authentication, Push, And Service Worker Behavior

If you restore the browser client, review these legacy pieces before trusting it in your fork:

- login flow
- message history loading
- attachment upload and download
- push subscription flow
- service worker behavior
- manifest metadata

The current template does not guarantee that the old browser flow matches the current Android-focused runtime assumptions.

### 5. Add Your Own Icons

The old PWA icon files were removed on purpose.

If you restore the browser client:

1. create your own icon assets
2. place them under `e2ee_chat_web/public/`
3. update `e2ee_chat_web/public/manifest.json`

Do not reuse brand assets that you do not own or have permission to distribute.

## How To Fully Remove The Legacy Browser Chat

If you want the repository to stop carrying the old browser chat code at all, you can remove the legacy `public/` assets.

### Minimum Removal

Delete these files:

- `e2ee_chat_web/public/index.html`
- `e2ee_chat_web/public/app.js`
- `e2ee_chat_web/public/style.css`
- `e2ee_chat_web/public/manifest.json`
- `e2ee_chat_web/public/sw.js`

With the current server behavior, deleting those files does not break the management UI, because the management UI is served from `manage_public/`.

### Keep Or Replace The Disabled Landing Page

After deleting the legacy browser client files, you still have two choices:

- keep the disabled landing page embedded in `server.js`
- replace that landing page with a simpler 404, 410, or redirect behavior

The current disabled landing page is controlled by `DISABLED_CHAT_HTML` in `e2ee_chat_web/server.js`.

### Optional Further Cleanup

If you are certain you will never restore browser chat, you can also:

- remove comments or docs that mention the legacy browser client
- remove stale web-chat-specific notes from future docs
- simplify any remaining public-web branding decisions around PWA behavior
