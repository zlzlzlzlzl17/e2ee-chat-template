const path = require("path");
const fs = require("fs");
const http = require("http");
const https = require("https");
const crypto = require("crypto");
const express = require("express");
const cookieParser = require("cookie-parser");
const WebSocket = require("ws");
const Database = require("better-sqlite3");
const bcrypt = require("bcrypt");
const jwt = require("jsonwebtoken");
const multer = require("multer");
const webpush = require("web-push");

const ENV_FILE = path.join(__dirname, ".env");
if (fs.existsSync(ENV_FILE)) {
  for (const rawLine of fs.readFileSync(ENV_FILE, "utf8").split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator <= 0) continue;
    const key = line.slice(0, separator).trim();
    let value = line.slice(separator + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (!(key in process.env)) process.env[key] = value;
  }
}

const PORT = process.env.PORT || 3000;
const JWT_SECRET = process.env.JWT_SECRET || "CHANGE_THIS_TO_A_LONG_RANDOM_SECRET";
const DB_PATH = process.env.DB_PATH || path.join(__dirname, "data", "chat.sqlite");
const UPLOAD_DIR = process.env.UPLOAD_DIR || path.join(__dirname, "uploads");
const APP_RELEASE_DIR = process.env.APP_RELEASE_DIR || path.join(__dirname, "app_release");
const MANAGE_HOST = (process.env.MANAGE_HOST || "manage.example.com").toLowerCase();
const MANAGE_COOKIE_NAME = "e2eechat_manage_token";
const APP_CLIENT_HEADER = "x-e2eechat-client";
const APP_CLIENT_VALUES = new Set(["android-app", "windows-app"]);
const MOBILE_CLIENT_VALUE = "android-app";
const DESKTOP_CLIENT_VALUE = "windows-app";
const MANAGE_PUBLIC_DIR = path.join(__dirname, "manage_public");
const RELEASE_CHANNEL_STABLE = "stable";
const RELEASE_CHANNEL_PRERELEASE = "prerelease";
const RELEASE_CHANNEL_VALUES = new Set([RELEASE_CHANNEL_STABLE, RELEASE_CHANNEL_PRERELEASE]);
const MANAGE_INITIAL_USERNAME = String(process.env.MANAGE_INITIAL_USERNAME || "").trim();
const MANAGE_INITIAL_PASSWORD = String(process.env.MANAGE_INITIAL_PASSWORD || "");
const SEED_SAMPLE_USERS = String(process.env.SEED_SAMPLE_USERS || "").trim().toLowerCase() === "true";

function splitEnvList(value) {
  return String(value || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

const VAPID_PUBLIC_KEY = process.env.VAPID_PUBLIC_KEY || "";
const VAPID_PRIVATE_KEY = process.env.VAPID_PRIVATE_KEY || "";
const VAPID_SUBJECT = process.env.VAPID_SUBJECT || "mailto:admin@example.com";
const FCM_PROJECT_ID = process.env.FCM_PROJECT_ID || "";
const FCM_CLIENT_EMAIL = process.env.FCM_CLIENT_EMAIL || "";
const FCM_PRIVATE_KEY = (process.env.FCM_PRIVATE_KEY || "").replace(/\\n/g, "\n");
const CALL_STUN_URLS = splitEnvList(process.env.CALL_STUN_URLS || "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302");
const CALL_TURN_URLS = splitEnvList(process.env.CALL_TURN_URLS || "");
const CALL_TURN_USERNAME = process.env.CALL_TURN_USERNAME || "";
const CALL_TURN_CREDENTIAL = process.env.CALL_TURN_CREDENTIAL || "";

const RETENTION_MS = 3 * 24 * 60 * 60 * 1000;
const MAX_PLAINTEXT_LEN = 2000;
const MAX_CIPHERTEXT_LEN = 40000;
const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;
const MAX_MANAGE_APK_BYTES = 256 * 1024 * 1024;
const MAX_REPLY_LEN = 1200;
const MAX_MENTIONS = 12;
const MANAGE_LOGIN_MAX_FAILURES = 3;
const MANAGE_LOGIN_COOLDOWN_MS = 5 * 60 * 1000;
const GROUP_ADMIN_LIMIT = 3;
const GROUP_MESSAGE_TTL_OPTIONS_MS = new Set([
  0,
  8 * 60 * 60 * 1000,
  24 * 60 * 60 * 1000,
  72 * 60 * 60 * 1000,
]);
const MANAGE_TOTP_DIGITS = 6;
const MANAGE_TOTP_PERIOD = 30;
const MANAGE_TOTP_SECRET_BYTES = 20;
const MANAGE_TOTP_CHALLENGE_TTL_MS = 5 * 60 * 1000;
const MANAGE_TOTP_CHALLENGE_MAX_ATTEMPTS = 5;
const MAX_CALL_SIGNAL_TEXT_LEN = 64 * 1024;
const CALL_INVITE_TTL_MS = 30 * 1000;
const CALL_INVITE_FCM_FALLBACK_DELAY_MS = 2 * 1000;
const MOBILE_FOREGROUND_PRESENCE_TTL_MS = 70 * 1000;

if (!fs.existsSync(path.dirname(DB_PATH))) fs.mkdirSync(path.dirname(DB_PATH), { recursive: true });
if (!fs.existsSync(UPLOAD_DIR)) fs.mkdirSync(UPLOAD_DIR, { recursive: true });
if (!fs.existsSync(APP_RELEASE_DIR)) fs.mkdirSync(APP_RELEASE_DIR, { recursive: true });

const app = express();
app.set("trust proxy", true);
app.use(express.json({ limit: "900kb" }));
app.use(cookieParser());
app.use(
  "/uploads",
  express.static(UPLOAD_DIR, {
    setHeaders(res) {
      res.setHeader("Cache-Control", "public, max-age=3600");
    },
  })
);
app.use(
  "/downloads",
  express.static(APP_RELEASE_DIR, {
    setHeaders(res) {
      res.setHeader("Cache-Control", "no-store");
    },
  })
);

const db = new Database(DB_PATH);
db.pragma("journal_mode = WAL");

db.exec(`
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY,
  user_code TEXT NOT NULL UNIQUE DEFAULT '',
  username TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  color TEXT NOT NULL,
  avatar_url TEXT NOT NULL DEFAULT '',
  avatar_file TEXT NOT NULL DEFAULT '',
  is_admin INTEGER NOT NULL DEFAULT 0,
  session_id TEXT NOT NULL DEFAULT '',
  desktop_session_id TEXT NOT NULL DEFAULT '',
  last_login_ip TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS conversations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL,
  slug TEXT NOT NULL DEFAULT '',
  group_code TEXT NOT NULL DEFAULT '',
  title TEXT NOT NULL,
  avatar_url TEXT NOT NULL DEFAULT '',
  message_ttl_ms INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS conversations_slug_idx ON conversations(slug);

CREATE TABLE IF NOT EXISTS conversation_members (
  conversation_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  role TEXT NOT NULL DEFAULT 'member',
  joined_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (conversation_id, user_id),
  FOREIGN KEY(conversation_id) REFERENCES conversations(id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS conversation_read_states (
  conversation_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  last_read_message_id INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (conversation_id, user_id),
  FOREIGN KEY(conversation_id) REFERENCES conversations(id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS conversation_delivery_states (
  conversation_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  last_delivered_message_id INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (conversation_id, user_id),
  FOREIGN KEY(conversation_id) REFERENCES conversations(id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER NOT NULL DEFAULT 0,
  ts INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  username TEXT NOT NULL,
  color TEXT NOT NULL,
  kind TEXT NOT NULL,
  payload TEXT NOT NULL,
  reply_to TEXT NOT NULL DEFAULT '',
  mentions TEXT NOT NULL DEFAULT '[]',
  e2ee INTEGER NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS push_subs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  endpoint TEXT NOT NULL UNIQUE,
  p256dh TEXT NOT NULL,
  auth TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS fcm_tokens (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  token TEXT NOT NULL UNIQUE,
  platform TEXT NOT NULL DEFAULT 'android',
  locale TEXT NOT NULL DEFAULT 'en',
  manufacturer TEXT NOT NULL DEFAULT '',
  model TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS manage_admins (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  session_id TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL,
  totp_secret TEXT NOT NULL DEFAULT '',
  totp_pending_secret TEXT NOT NULL DEFAULT '',
  totp_enabled INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS manage_login_attempts (
  key TEXT PRIMARY KEY,
  fail_count INTEGER NOT NULL DEFAULT 0,
  cooldown_until INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS manage_totp_challenges (
  token TEXT PRIMARY KEY,
  admin_id INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  FOREIGN KEY(admin_id) REFERENCES manage_admins(id)
);

CREATE TABLE IF NOT EXISTS registration_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  requested_username TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  request_ip TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'pending',
  review_note TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  reviewed_at INTEGER NOT NULL DEFAULT 0,
  reviewed_by TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS device_identity_keys (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  platform TEXT NOT NULL DEFAULT 'android',
  device_name TEXT NOT NULL DEFAULT '',
  key_alg TEXT NOT NULL DEFAULT '',
  public_key TEXT NOT NULL,
  fingerprint TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  UNIQUE(user_id, device_id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS direct_prekeys (
  user_id INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  key_alg TEXT NOT NULL DEFAULT '',
  identity_ecdh_public TEXT NOT NULL DEFAULT '',
  identity_ecdh_signature TEXT NOT NULL DEFAULT '',
  signed_prekey_public TEXT NOT NULL,
  signed_prekey_signature TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, device_id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS direct_one_time_prekeys (
  user_id INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  prekey_id TEXT NOT NULL,
  key_alg TEXT NOT NULL DEFAULT '',
  public_key TEXT NOT NULL,
  signature TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  claimed_at INTEGER NOT NULL DEFAULT 0,
  claimed_by_user_id INTEGER NOT NULL DEFAULT 0,
  claimed_for_conversation_id INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, device_id, prekey_id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS group_key_epochs (
  conversation_id INTEGER PRIMARY KEY,
  epoch INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(conversation_id) REFERENCES conversations(id)
);

CREATE TABLE IF NOT EXISTS group_sender_key_envelopes (
  conversation_id INTEGER NOT NULL,
  sender_user_id INTEGER NOT NULL,
  sender_device_id TEXT NOT NULL,
  recipient_user_id INTEGER NOT NULL,
  recipient_device_id TEXT NOT NULL,
  epoch INTEGER NOT NULL,
  key_id TEXT NOT NULL,
  wrapped_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (conversation_id, sender_user_id, sender_device_id, recipient_user_id, recipient_device_id, epoch, key_id),
  FOREIGN KEY(conversation_id) REFERENCES conversations(id),
  FOREIGN KEY(sender_user_id) REFERENCES users(id),
  FOREIGN KEY(recipient_user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS contact_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  requester_id INTEGER NOT NULL,
  target_user_id INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  created_at INTEGER NOT NULL,
  reviewed_at INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(requester_id) REFERENCES users(id),
  FOREIGN KEY(target_user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS group_join_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER NOT NULL,
  requester_id INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  created_at INTEGER NOT NULL,
  reviewed_at INTEGER NOT NULL DEFAULT 0,
  reviewed_by INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(conversation_id) REFERENCES conversations(id),
  FOREIGN KEY(requester_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS group_admin_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER NOT NULL,
  requester_id INTEGER NOT NULL,
  target_user_id INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  created_at INTEGER NOT NULL,
  reviewed_at INTEGER NOT NULL DEFAULT 0,
  reviewed_by INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(conversation_id) REFERENCES conversations(id),
  FOREIGN KEY(requester_id) REFERENCES users(id),
  FOREIGN KEY(target_user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS account_deletion_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  username_snapshot TEXT NOT NULL DEFAULT '',
  user_code_snapshot TEXT NOT NULL DEFAULT '',
  request_ip TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'pending',
  review_note TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  reviewed_at INTEGER NOT NULL DEFAULT 0,
  reviewed_by TEXT NOT NULL DEFAULT '',
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS app_release_state (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  version TEXT NOT NULL DEFAULT '',
  file_name TEXT NOT NULL DEFAULT '',
  original_name TEXT NOT NULL DEFAULT '',
  file_size INTEGER NOT NULL DEFAULT 0,
  uploaded_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS app_release_channels (
  channel TEXT PRIMARY KEY,
  version TEXT NOT NULL DEFAULT '',
  file_name TEXT NOT NULL DEFAULT '',
  original_name TEXT NOT NULL DEFAULT '',
  file_size INTEGER NOT NULL DEFAULT 0,
  uploaded_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS read_states (
  user_id INTEGER PRIMARY KEY,
  last_read_message_id INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS pending_call_invites (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER NOT NULL,
  caller_id INTEGER NOT NULL,
  callee_id INTEGER NOT NULL,
  caller_user_code TEXT NOT NULL DEFAULT '',
  caller_username TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  UNIQUE(callee_id, conversation_id)
);
`);

db.exec(`
CREATE INDEX IF NOT EXISTS registration_requests_status_idx ON registration_requests(status, created_at DESC);
CREATE INDEX IF NOT EXISTS device_identity_keys_user_idx ON device_identity_keys(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS direct_prekeys_user_idx ON direct_prekeys(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS direct_one_time_prekeys_available_idx ON direct_one_time_prekeys(user_id, device_id, claimed_at, created_at);
CREATE INDEX IF NOT EXISTS group_sender_key_envelopes_recipient_idx ON group_sender_key_envelopes(conversation_id, recipient_user_id, recipient_device_id, epoch, updated_at DESC);
CREATE INDEX IF NOT EXISTS group_sender_key_envelopes_sender_idx ON group_sender_key_envelopes(conversation_id, sender_user_id, sender_device_id, epoch, updated_at DESC);
CREATE INDEX IF NOT EXISTS contact_requests_target_status_idx ON contact_requests(target_user_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS contact_requests_requester_status_idx ON contact_requests(requester_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS group_join_requests_conversation_status_idx ON group_join_requests(conversation_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS group_join_requests_requester_status_idx ON group_join_requests(requester_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS group_admin_requests_conversation_status_idx ON group_admin_requests(conversation_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS group_admin_requests_requester_status_idx ON group_admin_requests(requester_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS account_deletion_requests_status_idx ON account_deletion_requests(status, created_at DESC);
CREATE INDEX IF NOT EXISTS account_deletion_requests_user_status_idx ON account_deletion_requests(user_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS pending_call_invites_callee_idx ON pending_call_invites(callee_id, expires_at DESC);
`);

try {
  db.prepare("SELECT identity_ecdh_public FROM direct_prekeys LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE direct_prekeys ADD COLUMN identity_ecdh_public TEXT NOT NULL DEFAULT '';");
}

try {
  db.prepare("SELECT identity_ecdh_signature FROM direct_prekeys LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE direct_prekeys ADD COLUMN identity_ecdh_signature TEXT NOT NULL DEFAULT '';");
}

try {
  db.prepare("SELECT reply_to FROM messages LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE messages ADD COLUMN reply_to TEXT NOT NULL DEFAULT '';");
}

try {
  db.prepare("SELECT mentions FROM messages LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE messages ADD COLUMN mentions TEXT NOT NULL DEFAULT '[]';");
}

try {
  db.prepare("SELECT user_code FROM users LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE users ADD COLUMN user_code TEXT NOT NULL DEFAULT '';");
}

try {
  db.prepare("SELECT last_login_ip FROM users LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE users ADD COLUMN last_login_ip TEXT NOT NULL DEFAULT '';");
}

try {
  db.prepare("SELECT desktop_session_id FROM users LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE users ADD COLUMN desktop_session_id TEXT NOT NULL DEFAULT '';");
  db.exec("UPDATE users SET desktop_session_id='' WHERE desktop_session_id IS NULL;");
}

const legacyStableRelease = db
  .prepare("SELECT version, file_name, original_name, file_size, uploaded_at FROM app_release_state WHERE id=1")
  .get();
if (legacyStableRelease?.version && legacyStableRelease?.file_name) {
  db.prepare(
    `
      INSERT INTO app_release_channels (channel, version, file_name, original_name, file_size, uploaded_at)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(channel) DO NOTHING
    `
  ).run(
    RELEASE_CHANNEL_STABLE,
    legacyStableRelease.version,
    legacyStableRelease.file_name,
    legacyStableRelease.original_name || legacyStableRelease.file_name,
    Number(legacyStableRelease.file_size || 0),
    Number(legacyStableRelease.uploaded_at || 0)
  );
}

try {
  db.prepare("SELECT avatar_url FROM users LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE users ADD COLUMN avatar_url TEXT NOT NULL DEFAULT '';");
}

try {
  db.prepare("SELECT avatar_file FROM users LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE users ADD COLUMN avatar_file TEXT NOT NULL DEFAULT '';");
}

try {
  db.prepare("SELECT locale FROM fcm_tokens LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE fcm_tokens ADD COLUMN locale TEXT NOT NULL DEFAULT 'en';");
}

try {
  db.prepare("SELECT manufacturer FROM fcm_tokens LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE fcm_tokens ADD COLUMN manufacturer TEXT NOT NULL DEFAULT '';");
}

try {
  db.prepare("SELECT model FROM fcm_tokens LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE fcm_tokens ADD COLUMN model TEXT NOT NULL DEFAULT '';");
}

try {
  db.prepare("SELECT conversation_id FROM messages LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE messages ADD COLUMN conversation_id INTEGER NOT NULL DEFAULT 0;");
}

try {
  db.prepare("SELECT group_code FROM conversations LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE conversations ADD COLUMN group_code TEXT NOT NULL DEFAULT '';");
}

try {
  db.prepare("SELECT message_ttl_ms FROM conversations LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE conversations ADD COLUMN message_ttl_ms INTEGER NOT NULL DEFAULT 0;");
}

try {
  db.prepare("SELECT role FROM conversation_members LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE conversation_members ADD COLUMN role TEXT NOT NULL DEFAULT 'member';");
}

try {
  db.prepare("SELECT joined_at FROM conversation_members LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE conversation_members ADD COLUMN joined_at INTEGER NOT NULL DEFAULT 0;");
}

db.exec("CREATE UNIQUE INDEX IF NOT EXISTS conversations_group_code_idx ON conversations(group_code) WHERE group_code <> '';");

try {
  db.prepare("SELECT totp_secret FROM manage_admins LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE manage_admins ADD COLUMN totp_secret TEXT NOT NULL DEFAULT '';");
}

try {
  db.prepare("SELECT totp_pending_secret FROM manage_admins LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE manage_admins ADD COLUMN totp_pending_secret TEXT NOT NULL DEFAULT '';");
}

try {
  db.prepare("SELECT totp_enabled FROM manage_admins LIMIT 1").get();
} catch {
  db.exec("ALTER TABLE manage_admins ADD COLUMN totp_enabled INTEGER NOT NULL DEFAULT 0;");
}

function seedUsersIfEmpty() {
  if (!SEED_SAMPLE_USERS) return;
  const count = db.prepare("SELECT COUNT(*) AS c FROM users").get().c;
  if (count > 0) return;

  const users = [
    { username: "alice", password: "alice-change-me", color: "#128c7e", is_admin: 1 },
    { username: "bob", password: "bob-change-me", color: "#34b7f1", is_admin: 0 },
    { username: "carol", password: "carol-change-me", color: "#f39c12", is_admin: 0 },
  ];

  const insert = db.prepare(
    "INSERT INTO users (user_code, username, password_hash, color, is_admin, session_id, last_login_ip) VALUES (?, ?, ?, ?, ?, ?, ?)"
  );

  for (const user of users) {
    insert.run(
      generateUniqueUserCode(),
      user.username,
      bcrypt.hashSync(user.password, 12),
      user.color,
      user.is_admin,
      "",
      ""
    );
  }
}

function generateUniqueUserCode() {
  for (let i = 0; i < 1000; i += 1) {
    const code = String(crypto.randomInt(0, 100000000)).padStart(8, "0");
    const exists = db.prepare("SELECT 1 AS ok FROM users WHERE user_code=?").get(code);
    if (!exists) return code;
  }
  throw new Error("unable_to_generate_user_code");
}

function ensureUserCodes() {
  const rows = db.prepare("SELECT id, user_code FROM users ORDER BY id").all();
  const update = db.prepare("UPDATE users SET user_code=? WHERE id=?");
  for (const row of rows) {
    if (String(row.user_code || "").match(/^\d{8}$/)) continue;
    update.run(generateUniqueUserCode(), row.id);
  }
  db.exec("CREATE UNIQUE INDEX IF NOT EXISTS users_user_code_idx ON users(user_code);");
}

function generateUniqueGroupCode() {
  for (let i = 0; i < 1000; i += 1) {
    const code = String(crypto.randomInt(0, 10000000000)).padStart(10, "0");
    const exists = db.prepare("SELECT 1 AS ok FROM conversations WHERE group_code=?").get(code);
    if (!exists) return code;
  }
  throw new Error("unable_to_generate_group_code");
}

function ensureGroupCodes() {
  const rows = db.prepare("SELECT id, group_code FROM conversations WHERE kind='group' ORDER BY id").all();
  const update = db.prepare("UPDATE conversations SET group_code=? WHERE id=?");
  for (const row of rows) {
    if (String(row.group_code || "").match(/^\d{10}$/)) continue;
    update.run(generateUniqueGroupCode(), row.id);
  }
}

function ensureGroupAdmins() {
  db.prepare(
    `
      UPDATE conversation_members
      SET role='admin'
      WHERE conversation_id IN (SELECT id FROM conversations WHERE kind='group')
        AND user_id IN (SELECT id FROM users WHERE is_admin=1)
        AND role != 'owner'
    `
  ).run();

  const groups = db.prepare("SELECT id FROM conversations WHERE kind='group' ORDER BY id").all();
  const hasOwner = db.prepare(
    "SELECT 1 AS ok FROM conversation_members WHERE conversation_id=? AND role='owner' LIMIT 1"
  );
  const fallbackOwner = db.prepare(
    `
      SELECT m.user_id
      FROM conversation_members m
      JOIN users u ON u.id = m.user_id
      WHERE m.conversation_id=?
      ORDER BY u.is_admin DESC, m.joined_at, m.user_id
      LIMIT 1
    `
  );
  const promote = db.prepare("UPDATE conversation_members SET role='owner' WHERE conversation_id=? AND user_id=?");
  for (const group of groups) {
    if (hasOwner.get(group.id)) continue;
    const member = fallbackOwner.get(group.id);
    if (member) promote.run(group.id, member.user_id);
  }
}

function seedManageAdminIfEmpty() {
  const count = db.prepare("SELECT COUNT(*) AS c FROM manage_admins").get().c;
  if (count > 0) return;

  if (!MANAGE_INITIAL_USERNAME || !MANAGE_INITIAL_PASSWORD) {
    console.warn(
      "Manage admin bootstrap skipped: set MANAGE_INITIAL_USERNAME and MANAGE_INITIAL_PASSWORD to initialize the first admin."
    );
    return;
  }
  if (MANAGE_INITIAL_PASSWORD.length < 8) {
    console.warn("Manage admin bootstrap skipped: MANAGE_INITIAL_PASSWORD must be at least 8 characters.");
    return;
  }

  db.prepare(
    "INSERT INTO manage_admins (username, password_hash, session_id, updated_at) VALUES (?, ?, ?, ?)"
  ).run(
    MANAGE_INITIAL_USERNAME,
    bcrypt.hashSync(MANAGE_INITIAL_PASSWORD, 12),
    "",
    Date.now()
  );
  console.log(`Bootstrapped manage admin: ${MANAGE_INITIAL_USERNAME}`);
}

function getRequestHost(req) {
  const forwarded = String(req.headers["x-forwarded-host"] || req.headers.host || "")
    .split(",")[0]
    .trim();
  return forwarded.split(":")[0].toLowerCase();
}

function getClientIp(req) {
  return String(req.ip || req.headers["x-forwarded-for"] || req.socket?.remoteAddress || "")
    .split(",")[0]
    .trim();
}

function isManageHost(req) {
  return getRequestHost(req) === MANAGE_HOST;
}

function isAppClient(req) {
  return APP_CLIENT_VALUES.has(String(req.headers[APP_CLIENT_HEADER] || "").toLowerCase());
}

function getClientType(req) {
  const raw = String(req.headers?.[APP_CLIENT_HEADER] || "").toLowerCase();
  return raw === DESKTOP_CLIENT_VALUE ? "desktop" : "mobile";
}

function getSessionColumnForClientType(clientType) {
  return clientType === "desktop" ? "desktop_session_id" : "session_id";
}

function getSessionIdForClient(user, clientType) {
  return clientType === "desktop" ? user.desktop_session_id : user.session_id;
}

function normalizePresenceState(value) {
  return value === "background" ? "background" : "foreground";
}

function setSocketPresence(ws, state) {
  ws.presence_state = normalizePresenceState(state);
  ws.presence_updated_at = Date.now();
}

function isSocketOnline(ws, now = Date.now()) {
  if (ws.readyState !== WebSocket.OPEN || !ws.auth) return false;
  if ((ws.auth.client_type || "mobile") === "desktop") return true;
  if (normalizePresenceState(ws.presence_state) !== "foreground") return false;
  return now - Number(ws.presence_updated_at || 0) <= MOBILE_FOREGROUND_PRESENCE_TTL_MS;
}

function manageHostOnly(req, res, next) {
  if (!isManageHost(req)) return res.status(404).send("Not found");
  next();
}

function appClientOnly(req, res, next) {
  if (!isAppClient(req)) return res.status(403).json({ error: "app_only" });
  next();
}

function ensureReadState(userId) {
  db.prepare(
    `
    INSERT INTO read_states (user_id, last_read_message_id, updated_at)
    VALUES (?, 0, ?)
    ON CONFLICT(user_id) DO NOTHING
  `
  ).run(userId, Date.now());
}

function ensureE2eeConversation() {
  let row = db.prepare("SELECT id FROM conversations WHERE slug='e2eechat'").get();
  if (!row) {
    const result = db
      .prepare(
        `
        INSERT INTO conversations (kind, slug, group_code, title, avatar_url, created_at)
        VALUES ('group', 'e2eechat', ?, 'E2EE Chat', '', ?)
      `
      )
      .run(generateUniqueGroupCode(), Date.now());
    row = { id: Number(result.lastInsertRowid) };
  }

  const conversationId = row.id;
  for (const user of db.prepare("SELECT id, is_admin FROM users").all()) {
    db.prepare(
      `
      INSERT INTO conversation_members (conversation_id, user_id, role, joined_at)
      VALUES (?, ?, ?, ?)
      ON CONFLICT(conversation_id, user_id) DO NOTHING
    `
    ).run(conversationId, user.id, user.is_admin ? "admin" : "member", Date.now());
    ensureConversationReadState(conversationId, user.id);
  }

  db.prepare("UPDATE messages SET conversation_id=? WHERE conversation_id=0").run(conversationId);
  return conversationId;
}

function ensureConversationReadState(conversationId, userId) {
  db.prepare(
    `
    INSERT INTO conversation_read_states (conversation_id, user_id, last_read_message_id, updated_at)
    VALUES (?, ?, 0, ?)
    ON CONFLICT(conversation_id, user_id) DO NOTHING
  `
  ).run(conversationId, userId, Date.now());
}

function currentGroupKeyEpoch(conversationId) {
  const now = Date.now();
  db.prepare(
    "INSERT INTO group_key_epochs (conversation_id, epoch, updated_at) VALUES (?, 1, ?) ON CONFLICT(conversation_id) DO NOTHING"
  ).run(conversationId, now);
  const row = db.prepare("SELECT epoch FROM group_key_epochs WHERE conversation_id=?").get(conversationId);
  return Math.max(Number(row?.epoch || 1), 1);
}

function rotateGroupKeyEpoch(conversationId) {
  const group = db.prepare("SELECT id FROM conversations WHERE id=? AND kind='group'").get(conversationId);
  if (!group) return currentGroupKeyEpoch(conversationId);
  const now = Date.now();
  currentGroupKeyEpoch(conversationId);
  db.prepare("UPDATE group_key_epochs SET epoch=epoch+1, updated_at=? WHERE conversation_id=?").run(now, conversationId);
  return currentGroupKeyEpoch(conversationId);
}

function rotateAndBroadcastGroupKeyEpoch(conversationId) {
  const epoch = rotateGroupKeyEpoch(conversationId);
  broadcastToConversation(conversationId, { type: "group_key_epoch_rotated", conversation_id: conversationId, epoch });
  return epoch;
}

function getGroupConversationsForUser(userId) {
  return db
    .prepare(
      `
      SELECT c.id, c.title
      FROM conversations c
      JOIN conversation_members cm ON cm.conversation_id = c.id
      WHERE c.kind='group' AND cm.user_id=?
      ORDER BY c.id
      `
    )
    .all(userId);
}

function rotateGroupKeyEpochsForUser(userId) {
  const rows = getGroupConversationsForUser(userId);
  for (const row of rows) {
    rotateAndBroadcastGroupKeyEpoch(row.id);
  }
}

function getE2eeConversationId() {
  return db.prepare("SELECT id FROM conversations WHERE slug='e2eechat'").get().id;
}

function ensureDirectConversation(userAId, userBId) {
  const ids = [Number(userAId), Number(userBId)].sort((a, b) => a - b);
  const slug = `direct:${ids[0]}:${ids[1]}`;
  let row = db.prepare("SELECT id FROM conversations WHERE slug=?").get(slug);
  if (!row) {
    const users = db
      .prepare("SELECT id, username FROM users WHERE id IN (?, ?) ORDER BY username")
      .all(ids[0], ids[1]);
    const title = users.map((user) => user.username).join(" & ");
    const result = db
      .prepare(
        `
        INSERT INTO conversations (kind, slug, title, avatar_url, created_at)
        VALUES ('direct', ?, ?, '', ?)
      `
      )
      .run(slug, title, Date.now());
    row = { id: Number(result.lastInsertRowid) };
  }

  for (const userId of ids) {
    db.prepare(
      `
      INSERT INTO conversation_members (conversation_id, user_id, role, joined_at)
      VALUES (?, ?, 'member', ?)
      ON CONFLICT(conversation_id, user_id) DO NOTHING
    `
    ).run(row.id, userId, Date.now());
    ensureConversationReadState(row.id, userId);
  }

  return row.id;
}

function ensureAllDirectConversations() {
  const users = db.prepare("SELECT id FROM users ORDER BY id").all();
  for (let i = 0; i < users.length; i += 1) {
    for (let j = i + 1; j < users.length; j += 1) {
      ensureDirectConversation(users[i].id, users[j].id);
    }
  }
}

seedUsersIfEmpty();
ensureUserCodes();
seedManageAdminIfEmpty();
for (const row of db.prepare("SELECT id FROM users").all()) ensureReadState(row.id);
ensureE2eeConversation();
ensureGroupCodes();
ensureGroupAdmins();

function signToken(user, clientType = "mobile") {
  return jwt.sign(
    {
      uid: user.id,
      user_code: user.user_code || "",
      username: user.username,
      color: user.color,
      is_admin: !!user.is_admin,
      sid: getSessionIdForClient(user, clientType),
      client_type: clientType,
    },
    JWT_SECRET,
    { expiresIn: "7d" }
  );
}

function signManageToken(admin) {
  return jwt.sign(
    {
      mid: admin.id,
      username: admin.username,
      sid: admin.session_id,
      type: "manage",
    },
    JWT_SECRET,
    { expiresIn: "12h" }
  );
}

function verifyToken(token) {
  return jwt.verify(token, JWT_SECRET);
}

function authFromReq(req) {
  const hdr = req.headers.authorization || "";
  const token = hdr.startsWith("Bearer ") ? hdr.slice(7) : null;
  if (!token) return null;
  try {
    return verifyToken(token);
  } catch {
    return null;
  }
}

function authMiddleware(req, res, next) {
  const auth = authFromReq(req);
  if (!auth) return res.status(401).json({ error: "unauthorized" });

  const clientType = getClientType(req);
  if ((auth.client_type || "mobile") !== clientType) {
    return res.status(401).json({ error: "session_expired" });
  }

  const user = db.prepare("SELECT session_id, desktop_session_id FROM users WHERE id=?").get(auth.uid);
  const sessionId = user ? getSessionIdForClient(user, clientType) : "";
  if (!user || sessionId !== auth.sid) {
    return res.status(401).json({ error: "session_expired" });
  }

  req.auth = auth;
  next();
}

function manageAuthFromReq(req) {
  const token = req.cookies?.[MANAGE_COOKIE_NAME];
  if (!token) return null;
  try {
    const decoded = verifyToken(token);
    if (decoded.type !== "manage") return null;
    return decoded;
  } catch {
    return null;
  }
}

function manageCookieOptions(req) {
  const proto = String(req.headers["x-forwarded-proto"] || req.protocol || "").split(",")[0].trim();
  return {
    httpOnly: true,
    sameSite: "lax",
    secure: proto === "https",
    path: "/",
  };
}

function manageAuthMiddleware(req, res, next) {
  const auth = manageAuthFromReq(req);
  if (!auth) return res.status(401).json({ error: "unauthorized" });

  const admin = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE id=?"
    )
    .get(auth.mid);
  if (!admin || admin.session_id !== auth.sid) {
    res.clearCookie(MANAGE_COOKIE_NAME, { path: "/" });
    return res.status(401).json({ error: "session_expired" });
  }

  req.manageAuth = auth;
  req.manageAdmin = admin;
  next();
}

function getManageLoginAttemptKey(req, username) {
  const normalizedUsername = String(username || "").trim().toLowerCase();
  const ip = String(getClientIp(req) || "unknown").toLowerCase();
  return `${normalizedUsername}|${ip}`;
}

function getManageLoginAttempt(key) {
  return db
    .prepare("SELECT key, fail_count, cooldown_until, updated_at FROM manage_login_attempts WHERE key=?")
    .get(key);
}

function resetManageLoginAttempt(key) {
  db.prepare("DELETE FROM manage_login_attempts WHERE key=?").run(key);
}

function registerFailedManageLogin(key) {
  const now = Date.now();
  const existing = getManageLoginAttempt(key);
  const previousCooldownUntil = Number(existing?.cooldown_until || 0);
  const previousUpdatedAt = Number(existing?.updated_at || 0);
  const isStillCooling = previousCooldownUntil > now;
  const isExpiredWindow =
    (!isStillCooling && previousCooldownUntil > 0) ||
    (previousUpdatedAt > 0 && now - previousUpdatedAt >= MANAGE_LOGIN_COOLDOWN_MS);
  const baseFailCount = isStillCooling ? Number(existing?.fail_count || 0) : isExpiredWindow ? 0 : Number(existing?.fail_count || 0);
  const failCount = baseFailCount + 1;
  const cooldownUntil =
    failCount >= MANAGE_LOGIN_MAX_FAILURES
      ? now + MANAGE_LOGIN_COOLDOWN_MS
      : 0;

  db.prepare(
    `
    INSERT INTO manage_login_attempts (key, fail_count, cooldown_until, updated_at)
    VALUES (?, ?, ?, ?)
    ON CONFLICT(key) DO UPDATE SET
      fail_count = excluded.fail_count,
      cooldown_until = excluded.cooldown_until,
      updated_at = excluded.updated_at
  `
  ).run(key, failCount, cooldownUntil, now);

  return {
    fail_count: failCount,
    cooldown_until: cooldownUntil,
    retry_after_ms: Math.max(cooldownUntil - now, 0),
  };
}

const BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

function encodeBase32(buffer) {
  let bits = 0;
  let value = 0;
  let output = "";
  for (const byte of buffer) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      output += BASE32_ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) {
    output += BASE32_ALPHABET[(value << (5 - bits)) & 31];
  }
  return output;
}

function decodeBase32(input) {
  const normalized = String(input || "").toUpperCase().replace(/[^A-Z2-7]/g, "");
  let bits = 0;
  let value = 0;
  const output = [];
  for (const char of normalized) {
    const idx = BASE32_ALPHABET.indexOf(char);
    if (idx < 0) continue;
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      output.push((value >>> (bits - 8)) & 255);
      bits -= 8;
    }
  }
  return Buffer.from(output);
}

function generateManageTotpSecret() {
  return encodeBase32(crypto.randomBytes(MANAGE_TOTP_SECRET_BYTES));
}

function hotp(secret, counter) {
  const key = decodeBase32(secret);
  const message = Buffer.alloc(8);
  const bigCounter = BigInt(counter);
  message.writeUInt32BE(Number((bigCounter >> 32n) & 0xffffffffn), 0);
  message.writeUInt32BE(Number(bigCounter & 0xffffffffn), 4);
  const digest = crypto.createHmac("sha1", key).update(message).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const code =
    ((digest[offset] & 0x7f) << 24) |
    ((digest[offset + 1] & 0xff) << 16) |
    ((digest[offset + 2] & 0xff) << 8) |
    (digest[offset + 3] & 0xff);
  return String(code % 10 ** MANAGE_TOTP_DIGITS).padStart(MANAGE_TOTP_DIGITS, "0");
}

function verifyTotpCode(secret, code) {
  const normalizedCode = String(code || "").replace(/\s+/g, "");
  if (!/^\d{6}$/.test(normalizedCode) || !secret) return false;
  const nowCounter = Math.floor(Date.now() / 1000 / MANAGE_TOTP_PERIOD);
  for (let offset = -1; offset <= 1; offset += 1) {
    if (hotp(secret, nowCounter + offset) === normalizedCode) return true;
  }
  return false;
}

function buildManageTotpLabel(username) {
  return `E2EE Chat (${username})`;
}

function buildManageTotpUri(username, secret) {
  const label = encodeURIComponent(buildManageTotpLabel(username));
  const issuer = encodeURIComponent("E2EE Chat");
  return `otpauth://totp/${label}?secret=${encodeURIComponent(secret)}&issuer=${issuer}&algorithm=SHA1&digits=${MANAGE_TOTP_DIGITS}&period=${MANAGE_TOTP_PERIOD}`;
}

function createManageTotpChallenge(adminId) {
  const token = crypto.randomBytes(24).toString("hex");
  const now = Date.now();
  db.prepare("DELETE FROM manage_totp_challenges WHERE admin_id=?").run(adminId);
  db.prepare(
    `
    INSERT INTO manage_totp_challenges (token, admin_id, attempts, created_at, expires_at)
    VALUES (?, ?, 0, ?, ?)
  `
  ).run(token, adminId, now, now + MANAGE_TOTP_CHALLENGE_TTL_MS);
  return token;
}

function getManageTotpChallenge(token) {
  return db
    .prepare("SELECT token, admin_id, attempts, created_at, expires_at FROM manage_totp_challenges WHERE token=?")
    .get(token);
}

function consumeManageTotpChallenge(token) {
  db.prepare("DELETE FROM manage_totp_challenges WHERE token=?").run(token);
}

function registerFailedManageTotpChallenge(token) {
  const challenge = getManageTotpChallenge(token);
  if (!challenge) return { attempts: 0, locked: true };
  const attempts = Number(challenge.attempts || 0) + 1;
  if (attempts >= MANAGE_TOTP_CHALLENGE_MAX_ATTEMPTS) {
    consumeManageTotpChallenge(token);
    return { attempts, locked: true };
  }
  db.prepare("UPDATE manage_totp_challenges SET attempts=? WHERE token=?").run(attempts, token);
  return { attempts, locked: false };
}

if (VAPID_PUBLIC_KEY && VAPID_PRIVATE_KEY) {
  webpush.setVapidDetails(VAPID_SUBJECT, VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY);
} else {
  console.log("Push disabled: missing VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY");
}

const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, UPLOAD_DIR),
  filename: (req, file, cb) => {
    const ext = (path.extname(file.originalname) || ".bin").slice(0, 12);
    cb(null, `${Date.now()}_${crypto.randomBytes(8).toString("hex")}${ext}`);
  },
});

const upload = multer({
  storage,
  limits: { fileSize: MAX_UPLOAD_BYTES },
});

const manageApkUpload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: MAX_MANAGE_APK_BYTES },
});

function safeJsonParse(text, fallback) {
  try {
    return JSON.parse(text);
  } catch {
    return fallback;
  }
}

function sanitizeReplyTo(raw) {
  if (!raw) return "";
  if (typeof raw !== "string") return "";
  const text = raw.slice(0, MAX_REPLY_LEN);
  const parsed = safeJsonParse(text, null);
  if (!parsed || typeof parsed !== "object") return "";

  const out = {
    id: Number(parsed.id) || 0,
    username: typeof parsed.username === "string" ? parsed.username.slice(0, 40) : "",
    color: typeof parsed.color === "string" ? parsed.color.slice(0, 20) : "",
    preview: typeof parsed.preview === "string" ? parsed.preview.slice(0, 160) : "",
  };

  return out.id ? JSON.stringify(out) : "";
}

function getAllowedUsernames(conversationId = 0) {
  if (conversationId > 0) {
    return new Set(
      db
        .prepare(
          `
          SELECT u.username
          FROM conversation_members cm
          JOIN users u ON u.id = cm.user_id
          WHERE cm.conversation_id = ?
        `
        )
        .all(conversationId)
        .map((row) => row.username)
    );
  }
  return new Set(db.prepare("SELECT username FROM users").all().map((row) => row.username));
}

function sanitizeMentions(raw, conversationId = 0) {
  const parsed = Array.isArray(raw) ? raw : safeJsonParse(raw || "[]", []);
  if (!Array.isArray(parsed)) return "[]";

  const allowed = getAllowedUsernames(Number(conversationId) || 0);
  const unique = [];

  for (const value of parsed) {
    if (typeof value !== "string") continue;
    const username = value.trim().replace(/^@+/, "").slice(0, 32);
    if (!username || !allowed.has(username) || unique.includes(username)) continue;
    unique.push(username);
    if (unique.length >= MAX_MENTIONS) break;
  }

  return JSON.stringify(unique);
}

function extractAttachmentFile(payload, kind) {
  const data = safeJsonParse(payload, null);
  if (!data || typeof data !== "object") return null;
  if (kind === "photo" && typeof data.file === "string") return data.file;
  if (data.attachment && typeof data.attachment.file === "string") return data.attachment.file;
  return null;
}

function messageRowToWire(row) {
  const currentUser =
    Number(row.user_id || 0) > 0
      ? db.prepare("SELECT user_code, username, color, avatar_url, is_admin FROM users WHERE id=?").get(row.user_id)
      : null;
  return {
    id: row.id,
    conversation_id: row.conversation_id || getE2eeConversationId(),
    ts: row.ts,
    expires_at: row.expires_at,
    user_code: currentUser?.user_code || "",
    username: currentUser?.username || row.username,
    color: currentUser?.color || row.color,
    avatar_url: currentUser?.avatar_url || "",
    kind: row.kind,
    payload: row.payload,
    e2ee: row.e2ee,
    reply_to: row.reply_to || "",
    mentions: safeJsonParse(row.mentions || "[]", []),
  };
}

function conversationExpiresAt(conversation, now = Date.now()) {
  const ttl = Number(conversation?.message_ttl_ms || 0);
  return ttl > 0 ? now + ttl : now + RETENTION_MS;
}

function insertSystemMessage(conversationId, userId, kind, payloadObject) {
  const user = getUserById(userId);
  const now = Date.now();
  const conversation = db.prepare("SELECT * FROM conversations WHERE id=?").get(conversationId);
  if (!conversation || !user) return null;
  const payload = JSON.stringify(payloadObject || {});
  const result = db
    .prepare(
      `
      INSERT INTO messages (conversation_id, ts, expires_at, user_id, username, color, kind, payload, reply_to, mentions, e2ee)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', '[]', 0)
    `
    )
    .run(conversationId, now, conversationExpiresAt(conversation, now), user.id, user.username, user.color, kind, payload);
  const row = db
    .prepare(
      `
      SELECT id, conversation_id, ts, expires_at, username, color, kind, payload, e2ee, reply_to, mentions
      FROM messages
      WHERE id=?
    `
    )
    .get(result.lastInsertRowid);
  const message = messageRowToWire(row);
  broadcastToConversation(conversationId, { type: "chat", ...message });
  return message;
}

function emitDeviceSessionNotices(userId, deviceId, deviceName, noticeType, oldFingerprint = "", newFingerprint = "") {
  if (!noticeType) return;
  const user = getUserById(userId);
  if (!user) return;
  const directConversations = db
    .prepare(
      `
      SELECT c.id
      FROM conversations c
      JOIN conversation_members m ON m.conversation_id = c.id
      WHERE c.kind='direct' AND m.user_id=?
    `
    )
    .all(userId);
  const groupConversations = getGroupConversationsForUser(userId);
  const payload = {
    type: noticeType,
    user_code: user.user_code || "",
    username: user.username,
    device_id: deviceId,
    device_name: deviceName || "",
    old_fingerprint: oldFingerprint || "",
    new_fingerprint: newFingerprint || "",
  };
  for (const conversation of directConversations) {
    insertSystemMessage(conversation.id, userId, "security_notice", payload);
  }
  for (const conversation of groupConversations) {
    insertSystemMessage(conversation.id, userId, "security_notice", payload);
  }
  if (noticeType === "safety_code_changed" || noticeType === "device_added" || noticeType === "device_removed") {
    rotateGroupKeyEpochsForUser(userId);
  }
}

function emitDeviceSafetyChangeNotices(userId, deviceId, deviceName, oldFingerprint, newFingerprint) {
  if (!oldFingerprint || !newFingerprint || oldFingerprint === newFingerprint) return;
  emitDeviceSessionNotices(userId, deviceId, deviceName, "safety_code_changed", oldFingerprint, newFingerprint);
}

function emitDeviceAddedNotices(userId, deviceId, deviceName, fingerprint) {
  emitDeviceSessionNotices(userId, deviceId, deviceName, "device_added", "", fingerprint || "");
}

function emitDeviceRemovedNotices(userId, deviceId, deviceName, fingerprint) {
  emitDeviceSessionNotices(userId, deviceId, deviceName, "device_removed", fingerprint || "", "");
}

function getUsers() {
  return db
    .prepare("SELECT id, user_code, username, color, avatar_url, is_admin, last_login_ip FROM users ORDER BY username")
    .all()
    .map((row) => ({
      id: row.id,
      user_code: row.user_code,
      username: row.username,
      color: row.color,
      avatar_url: row.avatar_url || "",
      is_admin: !!row.is_admin,
      last_login_ip: row.last_login_ip || "",
    }));
}

function getReadStates() {
  const e2eeConversationId = getE2eeConversationId();
  return getConversationReadStates(e2eeConversationId);
}

function getConversationReadStates(conversationId) {
  return db
    .prepare(
      `
        SELECT u.user_code, u.username, COALESCE(r.last_read_message_id, 0) AS last_read_message_id
        FROM conversation_members m
        JOIN users u ON u.id = m.user_id
        LEFT JOIN conversation_read_states r
          ON r.user_id = u.id
         AND r.conversation_id = ?
        WHERE m.conversation_id = ?
        ORDER BY u.username
    `
    )
    .all(conversationId, conversationId);
}

function getConversationDeliveryStates(conversationId) {
  return db
    .prepare(
      `
        SELECT u.user_code, u.username, COALESCE(d.last_delivered_message_id, 0) AS last_delivered_message_id
        FROM conversation_members m
        JOIN users u ON u.id = m.user_id
        LEFT JOIN conversation_delivery_states d
          ON d.user_id = u.id
         AND d.conversation_id = ?
        WHERE m.conversation_id = ?
        ORDER BY u.username
    `
    )
    .all(conversationId, conversationId);
}

function upsertReadState(userId, lastReadMessageId) {
  const e2eeConversationId = getE2eeConversationId();
  upsertConversationReadState(e2eeConversationId, userId, lastReadMessageId);
  db.prepare(
    `
    INSERT INTO read_states (user_id, last_read_message_id, updated_at)
    VALUES (?, ?, ?)
    ON CONFLICT(user_id) DO UPDATE SET
      last_read_message_id = CASE
        WHEN excluded.last_read_message_id > read_states.last_read_message_id
        THEN excluded.last_read_message_id
        ELSE read_states.last_read_message_id
      END,
      updated_at = CASE
        WHEN excluded.last_read_message_id > read_states.last_read_message_id
        THEN excluded.updated_at
        ELSE read_states.updated_at
      END
  `
  ).run(userId, lastReadMessageId, Date.now());
}

function upsertConversationReadState(conversationId, userId, lastReadMessageId) {
  db.prepare(
    `
    INSERT INTO conversation_read_states (conversation_id, user_id, last_read_message_id, updated_at)
    VALUES (?, ?, ?, ?)
    ON CONFLICT(conversation_id, user_id) DO UPDATE SET
      last_read_message_id = CASE
        WHEN excluded.last_read_message_id > conversation_read_states.last_read_message_id
        THEN excluded.last_read_message_id
        ELSE conversation_read_states.last_read_message_id
      END,
      updated_at = CASE
        WHEN excluded.last_read_message_id > conversation_read_states.last_read_message_id
        THEN excluded.updated_at
        ELSE conversation_read_states.updated_at
      END
  `
  ).run(conversationId, userId, lastReadMessageId, Date.now());

  if (conversationId === getE2eeConversationId()) {
    db.prepare(
      `
      INSERT INTO read_states (user_id, last_read_message_id, updated_at)
      VALUES (?, ?, ?)
      ON CONFLICT(user_id) DO UPDATE SET
        last_read_message_id = CASE
          WHEN excluded.last_read_message_id > read_states.last_read_message_id
          THEN excluded.last_read_message_id
          ELSE read_states.last_read_message_id
        END,
        updated_at = CASE
          WHEN excluded.last_read_message_id > read_states.last_read_message_id
          THEN excluded.updated_at
          ELSE read_states.updated_at
        END
    `
    ).run(userId, lastReadMessageId, Date.now());
  }
}

function upsertConversationDeliveryState(conversationId, userId, lastDeliveredMessageId) {
  db.prepare(
    `
    INSERT INTO conversation_delivery_states (conversation_id, user_id, last_delivered_message_id, updated_at)
    VALUES (?, ?, ?, ?)
    ON CONFLICT(conversation_id, user_id) DO UPDATE SET
      last_delivered_message_id = CASE
        WHEN excluded.last_delivered_message_id > conversation_delivery_states.last_delivered_message_id
        THEN excluded.last_delivered_message_id
        ELSE conversation_delivery_states.last_delivered_message_id
      END,
      updated_at = CASE
        WHEN excluded.last_delivered_message_id > conversation_delivery_states.last_delivered_message_id
        THEN excluded.updated_at
        ELSE conversation_delivery_states.updated_at
      END
  `
  ).run(conversationId, userId, lastDeliveredMessageId, Date.now());
}

function getUserById(userId) {
  return db
    .prepare("SELECT id, user_code, username, color, avatar_url, is_admin, last_login_ip FROM users WHERE id=?")
    .get(userId);
}

function getConversationForUser(userId, conversationId) {
  const resolvedId = Number(conversationId) || getE2eeConversationId();
  return db
    .prepare(
      `
      SELECT c.*
      FROM conversations c
      JOIN conversation_members m
        ON m.conversation_id = c.id
      WHERE c.id = ? AND m.user_id = ?
    `
    )
    .get(resolvedId, userId);
}

function getConversationMembers(conversationId) {
  return db
    .prepare(
      `
      SELECT u.id, u.user_code, u.username, u.color, u.avatar_url, u.is_admin, m.role, m.joined_at
      FROM conversation_members m
      JOIN users u ON u.id = m.user_id
      WHERE m.conversation_id = ?
      ORDER BY u.username
    `
    )
    .all(conversationId);
}

function getDirectConversationPeer(conversationId, userId) {
  return getConversationMembers(conversationId).find((member) => member.id !== userId) || null;
}

function getOwnedGroupsBlockingAccountDeletion(userId) {
  return db
    .prepare(
      `
      SELECT c.id, c.title, c.group_code, COUNT(other.user_id) AS other_member_count
      FROM conversations c
      JOIN conversation_members owner
        ON owner.conversation_id = c.id
       AND owner.user_id = ?
       AND owner.role = 'owner'
      LEFT JOIN conversation_members other
        ON other.conversation_id = c.id
       AND other.user_id != ?
      WHERE c.kind = 'group'
      GROUP BY c.id, c.title, c.group_code
      HAVING COUNT(other.user_id) > 0
      ORDER BY c.title, c.id
    `
    )
    .all(userId, userId);
}

function cleanupConversation(conversationId) {
  const rows = db
    .prepare("SELECT id, kind, payload FROM messages WHERE conversation_id=? AND kind IN ('photo', 'image', 'audio', 'file')")
    .all(conversationId);
  let deletedMessages = 0;
  const cleanupRows = () => {
    deletedMessages = db.prepare("DELETE FROM messages WHERE conversation_id=?").run(conversationId).changes;
    db.prepare("DELETE FROM conversation_read_states WHERE conversation_id=?").run(conversationId);
    db.prepare("DELETE FROM conversation_delivery_states WHERE conversation_id=?").run(conversationId);
    db.prepare("DELETE FROM group_sender_key_envelopes WHERE conversation_id=?").run(conversationId);
    db.prepare("DELETE FROM group_key_epochs WHERE conversation_id=?").run(conversationId);
    db.prepare("DELETE FROM group_join_requests WHERE conversation_id=?").run(conversationId);
    db.prepare("DELETE FROM group_admin_requests WHERE conversation_id=?").run(conversationId);
    db.prepare("DELETE FROM conversation_members WHERE conversation_id=?").run(conversationId);
    db.prepare("DELETE FROM conversations WHERE id=?").run(conversationId);
    clearPendingCallInvitesForConversation(conversationId);
  };
  if (db.inTransaction) cleanupRows();
  else db.transaction(cleanupRows)();
  const deletedFiles = deleteAttachmentFiles(rows);
  return { deletedFiles, deletedMessages };
}

function promoteGroupAdminIfNeeded(conversationId) {
  const group = db.prepare("SELECT id FROM conversations WHERE id=? AND kind='group'").get(conversationId);
  if (!group) return;
  const hasOwner = db
    .prepare("SELECT 1 AS ok FROM conversation_members WHERE conversation_id=? AND role='owner' LIMIT 1")
    .get(conversationId);
  if (hasOwner) return;
  const nextMember = db
    .prepare(
      `
        SELECT user_id
        FROM conversation_members
        WHERE conversation_id=?
        ORDER BY CASE role WHEN 'admin' THEN 0 ELSE 1 END, joined_at, user_id
        LIMIT 1
      `
    )
    .get(conversationId);
  if (nextMember) {
    db.prepare("UPDATE conversation_members SET role='owner' WHERE conversation_id=? AND user_id=?")
      .run(conversationId, nextMember.user_id);
  }
}

function clearConversationHistory(conversationId) {
  const rows = db
    .prepare("SELECT id, kind, payload FROM messages WHERE conversation_id=? AND kind IN ('photo', 'image', 'audio', 'file')")
    .all(conversationId);
  const deletedFiles = deleteAttachmentFiles(rows);
  const deletedMessages = db.prepare("DELETE FROM messages WHERE conversation_id=?").run(conversationId).changes;
  const now = Date.now();
  db.prepare("UPDATE conversation_read_states SET last_read_message_id=0, updated_at=? WHERE conversation_id=?").run(now, conversationId);
  db.prepare("UPDATE conversation_delivery_states SET last_delivered_message_id=0, updated_at=? WHERE conversation_id=?").run(now, conversationId);
  return { deletedFiles, deletedMessages };
}

function directConversationSlug(userAId, userBId) {
  const ids = [Number(userAId), Number(userBId)].sort((a, b) => a - b);
  return `direct:${ids[0]}:${ids[1]}`;
}

function getDirectConversationBetween(userAId, userBId) {
  return db.prepare("SELECT * FROM conversations WHERE kind='direct' AND slug=?").get(directConversationSlug(userAId, userBId));
}

function getGroupRole(userId, conversationId) {
  const member = db
    .prepare("SELECT role FROM conversation_members WHERE conversation_id=? AND user_id=?")
    .get(conversationId, userId);
  return member?.role || "";
}

function isGroupOwner(userId, conversationId) {
  return getGroupRole(userId, conversationId) === "owner";
}

function isGroupAdmin(userId, conversationId) {
  const role = getGroupRole(userId, conversationId);
  return role === "owner" || role === "admin";
}

function countGroupAdmins(conversationId) {
  const row = db
    .prepare("SELECT COUNT(*) AS c FROM conversation_members WHERE conversation_id=? AND role='admin'")
    .get(conversationId);
  return Number(row?.c || 0);
}

function groupAdminRequestToWire(row) {
  if (!row) return null;
  return {
    id: row.id,
    conversation_id: row.conversation_id,
    status: row.status,
    created_at: Number(row.created_at || 0),
    reviewed_at: Number(row.reviewed_at || 0),
    requester_user_code: row.requester_user_code || "",
    requester_username: row.requester_username || "",
    target_user_code: row.target_user_code || "",
    target_username: row.target_username || "",
  };
}

function isConversationMember(userId, conversationId) {
  return !!db
    .prepare("SELECT 1 AS ok FROM conversation_members WHERE conversation_id=? AND user_id=?")
    .get(conversationId, userId);
}

function normalizeUserCode(value) {
  return String(value || "").replace(/\D/g, "").slice(0, 8);
}

function normalizeGroupCode(value) {
  return String(value || "").replace(/\D/g, "").slice(0, 10);
}

function contactRequestToWire(row, viewerId) {
  if (!row) return null;
  const incoming = Number(row.target_user_id) === Number(viewerId);
  return {
    id: row.id,
    direction: incoming ? "incoming" : "outgoing",
    status: row.status,
    created_at: Number(row.created_at || 0),
    reviewed_at: Number(row.reviewed_at || 0),
    user_code: incoming ? row.requester_user_code : row.target_user_code,
    username: incoming ? row.requester_username : row.target_username,
    color: incoming ? row.requester_color : row.target_color,
    avatar_url: incoming ? row.requester_avatar_url || "" : row.target_avatar_url || "",
  };
}

function groupJoinRequestToWire(row, viewerId) {
  if (!row) return null;
  return {
    id: row.id,
    direction: Number(row.requester_id) === Number(viewerId) ? "outgoing" : "incoming",
    status: row.status,
    created_at: Number(row.created_at || 0),
    reviewed_at: Number(row.reviewed_at || 0),
    group_code: row.group_code || "",
    title: row.title || "",
    avatar_url: row.avatar_url || "",
    requester_user_code: row.requester_user_code || "",
    requester_username: row.requester_username || "",
    requester_color: row.requester_color || "",
    requester_avatar_url: row.requester_avatar_url || "",
  };
}

function upsertPendingCallInvite(conversationId, caller, callee) {
  const now = Date.now();
  db.prepare(
    `
      INSERT INTO pending_call_invites (
        conversation_id,
        caller_id,
        callee_id,
        caller_user_code,
        caller_username,
        created_at,
        expires_at
      )
      VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(callee_id, conversation_id) DO UPDATE SET
        caller_id=excluded.caller_id,
        caller_user_code=excluded.caller_user_code,
        caller_username=excluded.caller_username,
        created_at=excluded.created_at,
        expires_at=excluded.expires_at
    `
  ).run(
    conversationId,
    caller.id,
    callee.id,
    caller.user_code || "",
    caller.username || "",
    now,
    now + CALL_INVITE_TTL_MS
  );
}

function takePendingCallInvitesForUser(userId) {
  const now = Date.now();
  db.prepare("DELETE FROM pending_call_invites WHERE expires_at <= ?").run(now);
  const rows = db
    .prepare(
      `
        SELECT id, conversation_id, caller_id, callee_id, caller_user_code, caller_username, created_at, expires_at
        FROM pending_call_invites
        WHERE callee_id = ?
        ORDER BY created_at ASC
      `
    )
    .all(userId);
  if (rows.length) {
    db.prepare("DELETE FROM pending_call_invites WHERE callee_id = ?").run(userId);
  }
  return rows;
}

function clearPendingCallInvitesForConversation(conversationId) {
  db.prepare("DELETE FROM pending_call_invites WHERE conversation_id = ?").run(conversationId);
  clearPendingCallFallback(conversationId);
}

const pendingCallFallbackTimers = new Map();

function pendingCallFallbackKey(conversationId, calleeId) {
  return `${conversationId}:${calleeId}`;
}

function clearPendingCallFallback(conversationId, calleeId = null) {
  if (calleeId == null) {
    for (const [key, timer] of pendingCallFallbackTimers.entries()) {
      if (!key.startsWith(`${conversationId}:`)) continue;
      clearTimeout(timer);
      pendingCallFallbackTimers.delete(key);
    }
    return;
  }
  const key = pendingCallFallbackKey(conversationId, calleeId);
  const timer = pendingCallFallbackTimers.get(key);
  if (timer) {
    clearTimeout(timer);
    pendingCallFallbackTimers.delete(key);
  }
}

function schedulePendingCallFallback(conversationId, caller, callee, createdAt) {
  const key = pendingCallFallbackKey(conversationId, callee.id);
  clearPendingCallFallback(conversationId, callee.id);
  const timer = setTimeout(async () => {
    pendingCallFallbackTimers.delete(key);
    const stillPending = db
      .prepare("SELECT 1 FROM pending_call_invites WHERE conversation_id = ? AND callee_id = ? LIMIT 1")
      .get(conversationId, callee.id);
    if (!stillPending) return;
    const fcmResult = await sendFcmIncomingCallToUser(callee.id, {
      conversationId,
      peerUserCode: caller.user_code || "",
      peerUsername: caller.username,
      createdAt,
    });
    logCallDebug("fallback_fcm_attempted", {
      conversationId,
      callerId: caller.id,
      calleeId: callee.id,
      delivered: !!fcmResult?.delivered,
      reason: fcmResult?.reason || "",
    });
  }, CALL_INVITE_FCM_FALLBACK_DELAY_MS);
  pendingCallFallbackTimers.set(key, timer);
}

function getCallIceServers() {
  const items = [];
  if (CALL_STUN_URLS.length) {
    items.push({ urls: CALL_STUN_URLS });
  }
  if (CALL_TURN_URLS.length && CALL_TURN_USERNAME && CALL_TURN_CREDENTIAL) {
    items.push({
      urls: CALL_TURN_URLS,
      username: CALL_TURN_USERNAME,
      credential: CALL_TURN_CREDENTIAL,
    });
  }
  return items;
}

function getConversationTitleForUser(conversation, userId) {
  if (conversation.kind !== "direct") return conversation.title;
  const other = getConversationMembers(conversation.id).find((member) => member.id !== userId);
  return other?.username || conversation.title;
}

function getConversationAvatarForUser(conversation, userId) {
  if (conversation.kind !== "direct") return conversation.avatar_url || "";
  const other = getConversationMembers(conversation.id).find((member) => member.id !== userId);
  return other?.avatar_url || "";
}

function buildConversationPreview(message) {
  if (!message) return "";
  if (message.kind === "recalled") return "Message recalled";
  if (message.kind === "attachment_cleared") return "Attachment removed";
  if (message.kind === "security_notice") return "Safety code changed";
  if (message.kind === "text") return message.e2ee ? "Encrypted message" : String(message.payload || "").slice(0, 80);
  if (message.kind === "image" || message.kind === "photo") return "Image";
  if (message.kind === "audio") return "Voice note";
  if (message.kind === "file") return "File";
  return message.kind || "";
}

function getConversationSummaries(userId) {
  const rows = db
    .prepare(
      `
      SELECT c.id, c.kind, c.slug, c.group_code, c.title, c.avatar_url,
             COALESCE(crs.last_read_message_id, 0) AS last_read_message_id
      FROM conversations c
      JOIN conversation_members m ON m.conversation_id = c.id
      LEFT JOIN conversation_read_states crs
        ON crs.conversation_id = c.id
       AND crs.user_id = ?
      WHERE m.user_id = ?
      ORDER BY CASE WHEN c.slug = 'e2eechat' THEN 0 ELSE 1 END, c.id
    `
    )
    .all(userId, userId);

  return rows.map((conversation) => {
    const lastMessage = db
      .prepare(
        `
        SELECT id, conversation_id, ts, expires_at, username, color, kind, payload, e2ee, reply_to, mentions
        FROM messages
        WHERE conversation_id = ?
        ORDER BY id DESC
        LIMIT 1
      `
      )
      .get(conversation.id);
    const unreadCount = db
      .prepare(
        `
        SELECT COUNT(*) AS c
        FROM messages
        WHERE conversation_id = ?
          AND id > ?
          AND user_id != ?
          AND kind IN ('text', 'image', 'photo', 'file', 'audio')
      `
      )
      .get(conversation.id, conversation.last_read_message_id || 0, userId).c;

    const title = getConversationTitleForUser(conversation, userId);
    const avatarUrl = getConversationAvatarForUser(conversation, userId);
    const directOther = conversation.kind === "direct"
      ? getConversationMembers(conversation.id).find((member) => member.id !== userId)
      : null;

    return {
      id: conversation.id,
      kind: conversation.kind,
      slug: conversation.slug,
      group_code: conversation.group_code || "",
      title,
      avatar_url: avatarUrl,
      direct_user_code: directOther?.user_code || "",
      direct_username: directOther?.username || "",
      last_message_ts: lastMessage?.ts || 0,
      last_message_preview: buildConversationPreview(lastMessage),
      unread_count: unreadCount,
      last_read_message_id: conversation.last_read_message_id || 0,
    };
  });
}

function getOnlineUsers() {
  const unique = new Map();
  const now = Date.now();
  for (const ws of clients) {
    if (!isSocketOnline(ws, now)) continue;
    if (!unique.has(ws.auth.uid)) {
      const user = getUserById(ws.auth.uid);
      unique.set(ws.auth.uid, {
        id: ws.auth.uid,
        user_code: user?.user_code || ws.auth.user_code || "",
        username: user?.username || ws.auth.username,
        color: user?.color || ws.auth.color,
        is_admin: !!(user?.is_admin ?? ws.auth.is_admin),
        ip: ws.clientIp || "",
        client_type: ws.auth.client_type || "mobile",
      });
    }
  }
  return Array.from(unique.values()).sort((a, b) => a.username.localeCompare(b.username));
}

function getPathSize(targetPath) {
  if (!fs.existsSync(targetPath)) return 0;
  const stat = fs.statSync(targetPath);
  if (!stat.isDirectory()) return stat.size;
  let total = 0;
  for (const entry of fs.readdirSync(targetPath, { withFileTypes: true })) {
    total += getPathSize(path.join(targetPath, entry.name));
  }
  return total;
}

function getStorageSummary() {
  const stats = fs.statfsSync(path.dirname(DB_PATH));
  const blockSize = Number(stats.bsize || stats.frsize || 0);
  const totalBytes = Number(stats.blocks || 0) * blockSize;
  const freeBytes = Number(stats.bavail || stats.bfree || 0) * blockSize;
  const usedBytes = Math.max(totalBytes - freeBytes, 0);
  return {
    total_bytes: totalBytes,
    used_bytes: usedBytes,
    available_bytes: freeBytes,
    uploads_bytes: getPathSize(UPLOAD_DIR),
    database_bytes: getPathSize(DB_PATH),
    project_path: path.dirname(DB_PATH),
  };
}

function isValidUsername(username) {
  return /^[A-Za-z0-9_\-\u4e00-\u9fff]{2,24}$/.test(String(username || "").trim());
}

function pickUserColor() {
  const palette = ["#128c7e", "#34b7f1", "#f39c12", "#8e44ad", "#e67e22", "#16a085"];
  return palette[crypto.randomInt(0, palette.length)];
}

function normalizeDeviceId(value) {
  return String(value || "").trim().slice(0, 80);
}

function isValidDeviceId(value) {
  return /^[A-Za-z0-9._:-]{8,80}$/.test(normalizeDeviceId(value));
}

function normalizePublicKey(value) {
  return String(value || "").trim().replace(/\s+/g, "").slice(0, 4096);
}

function deviceKeyFingerprint(publicKey) {
  return crypto.createHash("sha256").update(String(publicKey || ""), "utf8").digest("hex");
}

function deviceIdentityToWire(row) {
  if (!row) return null;
  return {
    user_code: row.user_code || "",
    username: row.username || "",
    device_id: row.device_id || "",
    platform: row.platform || "",
    device_name: row.device_name || "",
    key_alg: row.key_alg || "",
    public_key: row.public_key || "",
    fingerprint: row.fingerprint || "",
    created_at: Number(row.created_at || 0),
    updated_at: Number(row.updated_at || 0),
    last_seen_at: Number(row.last_seen_at || 0),
  };
}

function getPendingRegistrationRequests() {
  return db
    .prepare(
      `
      SELECT id, requested_username, request_ip, status, review_note, created_at, reviewed_at, reviewed_by
      FROM registration_requests
      WHERE status='pending'
      ORDER BY created_at ASC
    `
    )
    .all();
}

function getPendingAccountDeletionRequests() {
  return db
    .prepare(
      `
      SELECT adr.id, adr.user_id, adr.username_snapshot, adr.user_code_snapshot,
             adr.request_ip, adr.status, adr.review_note, adr.created_at,
             u.username, u.user_code, u.last_login_ip
      FROM account_deletion_requests adr
      LEFT JOIN users u ON u.id = adr.user_id
      WHERE adr.status='pending'
      ORDER BY adr.created_at ASC
    `
    )
    .all()
    .map((row) => ({
      id: row.id,
      user_id: row.user_id,
      username: row.username || row.username_snapshot || "",
      user_code: row.user_code || row.user_code_snapshot || "",
      request_ip: row.request_ip || "",
      last_login_ip: row.last_login_ip || "",
      created_at: Number(row.created_at || 0),
    }));
}

function getRegisteredUsersForManage() {
  return db
    .prepare(
      `
      SELECT id, user_code, username, is_admin, last_login_ip, color, avatar_url
      FROM users
      ORDER BY username
    `
    )
    .all()
    .map((row) => ({
      id: row.id,
      user_code: row.user_code,
      username: row.username,
      is_admin: !!row.is_admin,
      last_login_ip: row.last_login_ip || "",
      color: row.color,
      avatar_url: row.avatar_url || "",
    }));
}

function finalizeApprovedRegistration(requestId, reviewedBy) {
  const request = db
    .prepare(
      `
      SELECT id, requested_username, password_hash, request_ip, status
      FROM registration_requests
      WHERE id=?
    `
    )
    .get(requestId);
  if (!request || request.status !== "pending") return null;
  if (db.prepare("SELECT 1 AS ok FROM users WHERE username=?").get(request.requested_username)) {
    db.prepare(
      "UPDATE registration_requests SET status='rejected', review_note=?, reviewed_at=?, reviewed_by=? WHERE id=?"
    ).run("username_taken", Date.now(), reviewedBy, request.id);
    return { error: "username_taken" };
  }

  const info = {
    user_code: generateUniqueUserCode(),
    username: request.requested_username,
    password_hash: request.password_hash,
    color: pickUserColor(),
    request_ip: request.request_ip || "",
  };

  const insertResult = db
    .prepare(
      `
      INSERT INTO users (user_code, username, password_hash, color, avatar_url, avatar_file, is_admin, session_id, last_login_ip)
      VALUES (?, ?, ?, ?, '', '', 0, '', ?)
    `
    )
    .run(info.user_code, info.username, info.password_hash, info.color, info.request_ip);
  const userId = Number(insertResult.lastInsertRowid);
  ensureReadState(userId);
  ensureE2eeConversation();
  db.prepare(
    "UPDATE registration_requests SET status='approved', review_note='', reviewed_at=?, reviewed_by=? WHERE id=?"
  ).run(Date.now(), reviewedBy, request.id);
  return getUserById(userId);
}

function deleteUserAccount(userId) {
  const user = getUserById(userId);
  if (!user) return null;

  const blockingOwnedGroups = getOwnedGroupsBlockingAccountDeletion(userId);
  if (blockingOwnedGroups.length) {
    return { error: "owner_groups_block_deletion", groups: blockingOwnedGroups };
  }

  return db.transaction(() => {
    const directConversations = db
      .prepare(
        `
        SELECT c.id
        FROM conversations c
        JOIN conversation_members m ON m.conversation_id = c.id
        WHERE m.user_id=? AND c.kind='direct'
      `
      )
      .all(userId);
    for (const conversation of directConversations) {
      cleanupConversation(conversation.id);
    }

    const groupConversations = db
      .prepare(
        `
        SELECT c.id
        FROM conversations c
        JOIN conversation_members m ON m.conversation_id = c.id
        WHERE m.user_id=? AND c.kind='group'
      `
      )
      .all(userId);
    for (const conversation of groupConversations) {
      db.prepare("DELETE FROM conversation_members WHERE conversation_id=? AND user_id=?").run(conversation.id, userId);
      db.prepare("DELETE FROM conversation_read_states WHERE conversation_id=? AND user_id=?").run(conversation.id, userId);
      db.prepare("DELETE FROM conversation_delivery_states WHERE conversation_id=? AND user_id=?").run(conversation.id, userId);
      const count = db
        .prepare("SELECT COUNT(*) AS c FROM conversation_members WHERE conversation_id=?")
        .get(conversation.id).c;
      if (Number(count || 0) <= 0) {
        cleanupConversation(conversation.id);
      } else {
        promoteGroupAdminIfNeeded(conversation.id);
        rotateAndBroadcastGroupKeyEpoch(conversation.id);
      }
    }

    db.prepare("DELETE FROM contact_requests WHERE requester_id=? OR target_user_id=?").run(userId, userId);
    db.prepare("DELETE FROM group_join_requests WHERE requester_id=? OR reviewed_by=?").run(userId, userId);
    db.prepare("DELETE FROM group_admin_requests WHERE requester_id=? OR target_user_id=? OR reviewed_by=?").run(userId, userId, userId);
    db.prepare("DELETE FROM group_sender_key_envelopes WHERE sender_user_id=? OR recipient_user_id=?").run(userId, userId);
    db.prepare("DELETE FROM direct_one_time_prekeys WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM direct_prekeys WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM device_identity_keys WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM fcm_tokens WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM push_subs WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM read_states WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM conversation_read_states WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM conversation_delivery_states WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM pending_call_invites WHERE caller_id=? OR callee_id=?").run(userId, userId);
    db.prepare("DELETE FROM account_deletion_requests WHERE user_id=?").run(userId);

    if (user.avatar_file) {
      try {
        fs.unlinkSync(path.join(UPLOAD_DIR, user.avatar_file));
      } catch {}
    }

    db.prepare("DELETE FROM users WHERE id=?").run(userId);
    return user;
  })();
}

function finalizeApprovedAccountDeletion(requestId, reviewedBy) {
  const request = db
    .prepare("SELECT * FROM account_deletion_requests WHERE id=? AND status='pending'")
    .get(requestId);
  if (!request) return null;
  const deletedUser = deleteUserAccount(request.user_id);
  if (deletedUser?.error) return deletedUser;
  db.prepare(
    "UPDATE account_deletion_requests SET status='approved', review_note='', reviewed_at=?, reviewed_by=? WHERE id=?"
  ).run(Date.now(), reviewedBy, requestId);
  return deletedUser || {
    id: request.user_id,
    username: request.username_snapshot || "",
    user_code: request.user_code_snapshot || "",
  };
}

function isValidReleaseVersion(version) {
  return /^v\d+\.\d+\.\d+$/.test(String(version || "").trim());
}

function extractReleaseVersion(value) {
  const match = String(value || "").match(/v\d+\.\d+\.\d+/i);
  return match ? match[0].toLowerCase().replace(/^v/, "v") : "";
}

function normalizeReleaseChannel(value, fallback = RELEASE_CHANNEL_STABLE) {
  const raw = String(value || "").trim().toLowerCase();
  if (raw === "pre" || raw === "preview" || raw === "beta" || raw === "test") return RELEASE_CHANNEL_PRERELEASE;
  if (RELEASE_CHANNEL_VALUES.has(raw)) return raw;
  return fallback;
}

function inferReleaseChannel(value) {
  return /(?:^|[^a-z])pre(?:-|\s*)?release(?:[^a-z]|$)|(?:^|[^a-z])prerelease(?:[^a-z]|$)|[\(（]pre[\)）]/i.test(String(value || ""))
    ? RELEASE_CHANNEL_PRERELEASE
    : RELEASE_CHANNEL_STABLE;
}

function releaseVersionLabel(channel, version) {
  if (!version) return "";
  return channel === RELEASE_CHANNEL_PRERELEASE && !/\(pre\)$/i.test(version) ? `${version}(pre)` : version;
}

function buildReleaseFileName(channel, version) {
  return channel === RELEASE_CHANNEL_PRERELEASE
    ? `e2eechat-prerelease-${version}(pre).apk`
    : `e2eechat-release-${version}.apk`;
}

function getAppReleaseState(channel = RELEASE_CHANNEL_STABLE) {
  const releaseChannel = normalizeReleaseChannel(channel);
  const row = db
    .prepare("SELECT version, file_name, original_name, file_size, uploaded_at FROM app_release_channels WHERE channel=?")
    .get(releaseChannel);
  if (!row || !row.version || !row.file_name) return null;
  return {
    channel: releaseChannel,
    version: row.version,
    version_label: releaseVersionLabel(releaseChannel, row.version),
    file_name: row.file_name,
    original_name: row.original_name || row.file_name,
    file_size: Number(row.file_size || 0),
    uploaded_at: Number(row.uploaded_at || 0),
    download_url: `/downloads/${encodeURIComponent(row.file_name)}`,
  };
}

function getAllAppReleaseStates() {
  return {
    stable: getAppReleaseState(RELEASE_CHANNEL_STABLE),
    prerelease: getAppReleaseState(RELEASE_CHANNEL_PRERELEASE),
  };
}

function manageAdminToPublic(admin) {
  return {
    username: admin.username,
    totp_enabled: !!admin.totp_enabled,
  };
}

function clearAppReleaseFile(fileName) {
  if (!fileName) return;
  try {
    fs.unlinkSync(path.join(APP_RELEASE_DIR, fileName));
  } catch {}
}

function storeAppRelease(channel, version, originalName, fileBuffer) {
  const releaseChannel = normalizeReleaseChannel(channel);
  const previous = getAppReleaseState(releaseChannel);
  const fileName = buildReleaseFileName(releaseChannel, version);
  const targetPath = path.join(APP_RELEASE_DIR, fileName);
  const uploadedAt = Date.now();
  fs.writeFileSync(targetPath, fileBuffer);
  db.prepare(
    `
      INSERT INTO app_release_channels (channel, version, file_name, original_name, file_size, uploaded_at)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(channel) DO UPDATE SET
        version = excluded.version,
        file_name = excluded.file_name,
        original_name = excluded.original_name,
        file_size = excluded.file_size,
        uploaded_at = excluded.uploaded_at
    `
  ).run(releaseChannel, version, fileName, originalName || fileName, fileBuffer.length, uploadedAt);
  if (releaseChannel === RELEASE_CHANNEL_STABLE) {
    db.prepare(
      `
        INSERT INTO app_release_state (id, version, file_name, original_name, file_size, uploaded_at)
        VALUES (1, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          version = excluded.version,
          file_name = excluded.file_name,
          original_name = excluded.original_name,
          file_size = excluded.file_size,
          uploaded_at = excluded.uploaded_at
      `
    ).run(version, fileName, originalName || fileName, fileBuffer.length, uploadedAt);
  }
  if (previous?.file_name && previous.file_name !== fileName) {
    clearAppReleaseFile(previous.file_name);
  }
  return getAppReleaseState(releaseChannel);
}

function deleteAttachmentFiles(rows) {
  let deletedFiles = 0;
  for (const row of rows) {
    const file = extractAttachmentFile(row.payload, row.kind);
    if (!file) continue;
    try {
      fs.unlinkSync(path.join(UPLOAD_DIR, file));
      deletedFiles += 1;
    } catch {}
  }
  return deletedFiles;
}

function clearAttachmentMessages() {
  const rows = db
    .prepare("SELECT id, kind, payload FROM messages WHERE kind IN ('photo', 'image', 'audio', 'file')")
    .all();
  const deletedFiles = deleteAttachmentFiles(rows);
  const changedMessages = db.prepare(
    `
      UPDATE messages
      SET kind='attachment_cleared',
          payload='',
          reply_to='',
          mentions='[]',
          e2ee=0
      WHERE kind IN ('photo', 'image', 'audio', 'file')
    `
  ).run().changes;
  return { deletedFiles, changedMessages };
}

function clearAllHistory() {
  const rows = db
    .prepare("SELECT id, kind, payload FROM messages WHERE kind IN ('photo', 'image', 'audio', 'file')")
    .all();
  const deletedFiles = deleteAttachmentFiles(rows);
  const deletedMessages = db.prepare("DELETE FROM messages").run().changes;
  const now = Date.now();
  db.prepare("UPDATE read_states SET last_read_message_id=0, updated_at=?").run(now);
  db.prepare("UPDATE conversation_read_states SET last_read_message_id=0, updated_at=?").run(now);
  db.prepare("UPDATE conversation_delivery_states SET last_delivered_message_id=0, updated_at=?").run(now);
  return { deletedFiles, deletedMessages };
}

app.use("/manage_static", manageHostOnly, express.static(MANAGE_PUBLIC_DIR, { extensions: ["html"] }));

app.post("/manage_api/login", manageHostOnly, (req, res) => {
  const username = typeof req.body?.username === "string" ? req.body.username.trim() : "";
  const password = typeof req.body?.password === "string" ? req.body.password : "";
  if (!username || !password) return res.status(400).json({ error: "bad_request" });
  const attemptKey = getManageLoginAttemptKey(req, username);
  const existingAttempt = getManageLoginAttempt(attemptKey);
  const retryAfterMs = Math.max(Number(existingAttempt?.cooldown_until || 0) - Date.now(), 0);
  if (retryAfterMs > 0) {
    return res.status(429).json({ error: "cooldown_active", retry_after_ms: retryAfterMs });
  }

  const admin = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE username=?"
    )
    .get(username);
  if (!admin || !bcrypt.compareSync(password, admin.password_hash)) {
    const failed = registerFailedManageLogin(attemptKey);
    if (failed.retry_after_ms > 0) {
      return res.status(429).json({
        error: "cooldown_active",
        retry_after_ms: failed.retry_after_ms,
      });
    }
    return res.status(401).json({
      error: "invalid_credentials",
      remaining_attempts: Math.max(MANAGE_LOGIN_MAX_FAILURES - failed.fail_count, 0),
    });
  }
  resetManageLoginAttempt(attemptKey);
  if (admin.totp_enabled && admin.totp_secret) {
    const challengeToken = createManageTotpChallenge(admin.id);
    return res.json({
      ok: true,
      requires_totp: true,
      challenge_token: challengeToken,
      username: admin.username,
    });
  }

  const nextSessionId = crypto.randomBytes(16).toString("hex");
  db.prepare("UPDATE manage_admins SET session_id=?, updated_at=? WHERE id=?").run(nextSessionId, Date.now(), admin.id);
  const updated = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE id=?"
    )
    .get(admin.id);

  res.cookie(MANAGE_COOKIE_NAME, signManageToken(updated), manageCookieOptions(req));
  res.json({ ok: true, username: updated.username, requires_totp: false });
});

app.post("/manage_api/login_totp", manageHostOnly, (req, res) => {
  const challengeToken = typeof req.body?.challenge_token === "string" ? req.body.challenge_token.trim() : "";
  const code = typeof req.body?.code === "string" ? req.body.code.trim() : "";
  if (!challengeToken || !code) return res.status(400).json({ error: "bad_request" });

  const challenge = getManageTotpChallenge(challengeToken);
  if (!challenge) return res.status(401).json({ error: "invalid_or_expired_challenge" });
  if (Number(challenge.expires_at || 0) <= Date.now()) {
    consumeManageTotpChallenge(challengeToken);
    return res.status(401).json({ error: "invalid_or_expired_challenge" });
  }

  const admin = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE id=?"
    )
    .get(challenge.admin_id);
  if (!admin || !admin.totp_enabled || !admin.totp_secret) {
    consumeManageTotpChallenge(challengeToken);
    return res.status(401).json({ error: "totp_not_enabled" });
  }

  if (!verifyTotpCode(admin.totp_secret, code)) {
    const failed = registerFailedManageTotpChallenge(challengeToken);
    return res.status(failed.locked ? 429 : 401).json({
      error: failed.locked ? "totp_challenge_locked" : "invalid_totp_code",
      remaining_attempts: Math.max(MANAGE_TOTP_CHALLENGE_MAX_ATTEMPTS - failed.attempts, 0),
    });
  }

  consumeManageTotpChallenge(challengeToken);
  const nextSessionId = crypto.randomBytes(16).toString("hex");
  db.prepare("UPDATE manage_admins SET session_id=?, updated_at=? WHERE id=?").run(nextSessionId, Date.now(), admin.id);
  const updated = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE id=?"
    )
    .get(admin.id);
  res.cookie(MANAGE_COOKIE_NAME, signManageToken(updated), manageCookieOptions(req));
  res.json({ ok: true, username: updated.username });
});

app.post("/manage_api/logout", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const nextSessionId = crypto.randomBytes(16).toString("hex");
  db.prepare("UPDATE manage_admins SET session_id=?, updated_at=? WHERE id=?").run(nextSessionId, Date.now(), req.manageAdmin.id);
  res.clearCookie(MANAGE_COOKIE_NAME, { path: "/" });
  res.json({ ok: true });
});

app.get("/manage_api/me", manageHostOnly, manageAuthMiddleware, (req, res) => {
  res.json(manageAdminToPublic(req.manageAdmin));
});

app.get("/manage_api/totp_status", manageHostOnly, manageAuthMiddleware, (req, res) => {
  res.json(manageAdminToPublic(req.manageAdmin));
});

app.post("/manage_api/totp/setup", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const pendingSecret = generateManageTotpSecret();
  db.prepare("UPDATE manage_admins SET totp_pending_secret=?, updated_at=? WHERE id=?").run(
    pendingSecret,
    Date.now(),
    req.manageAdmin.id
  );
  res.json({
    ok: true,
    secret: pendingSecret,
    otpauth_url: buildManageTotpUri(req.manageAdmin.username, pendingSecret),
    label: buildManageTotpLabel(req.manageAdmin.username),
  });
});

app.post("/manage_api/totp/enable", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const code = typeof req.body?.code === "string" ? req.body.code.trim() : "";
  if (!code) return res.status(400).json({ error: "bad_request" });

  const admin = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE id=?"
    )
    .get(req.manageAdmin.id);
  const pendingSecret = String(admin?.totp_pending_secret || "");
  if (!pendingSecret) return res.status(400).json({ error: "totp_setup_not_started" });
  if (!verifyTotpCode(pendingSecret, code)) return res.status(401).json({ error: "invalid_totp_code" });

  db.prepare(
    "UPDATE manage_admins SET totp_secret=?, totp_pending_secret='', totp_enabled=1, updated_at=? WHERE id=?"
  ).run(pendingSecret, Date.now(), req.manageAdmin.id);
  const updated = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE id=?"
    )
    .get(req.manageAdmin.id);
  res.json({ ok: true, admin: manageAdminToPublic(updated) });
});

app.post("/manage_api/totp/disable", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const currentPassword = typeof req.body?.current_password === "string" ? req.body.current_password : "";
  const code = typeof req.body?.code === "string" ? req.body.code.trim() : "";
  if (!currentPassword || !code) return res.status(400).json({ error: "bad_request" });
  if (!req.manageAdmin.totp_enabled || !req.manageAdmin.totp_secret) {
    return res.status(400).json({ error: "totp_not_enabled" });
  }
  if (!bcrypt.compareSync(currentPassword, req.manageAdmin.password_hash)) {
    return res.status(401).json({ error: "invalid_current_password" });
  }
  if (!verifyTotpCode(req.manageAdmin.totp_secret, code)) {
    return res.status(401).json({ error: "invalid_totp_code" });
  }

  db.prepare(
    "UPDATE manage_admins SET totp_secret='', totp_pending_secret='', totp_enabled=0, updated_at=? WHERE id=?"
  ).run(Date.now(), req.manageAdmin.id);
  const updated = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE id=?"
    )
    .get(req.manageAdmin.id);
  res.json({ ok: true, admin: manageAdminToPublic(updated) });
});

app.get("/manage_api/overview", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const storage = getStorageSummary();
  const onlineUsers = getOnlineUsers();
  const registeredUsers = getRegisteredUsersForManage();
  const pendingRequests = getPendingRegistrationRequests();
  const pendingAccountDeletionRequests = getPendingAccountDeletionRequests();
  const messageStats = db
    .prepare(
      `
      SELECT
        COUNT(*) AS total_messages,
        SUM(CASE WHEN kind IN ('photo', 'image', 'audio', 'file') THEN 1 ELSE 0 END) AS attachment_messages
      FROM messages
    `
    )
    .get();

  res.json({
    admin: manageAdminToPublic(req.manageAdmin),
    storage,
    online_users: onlineUsers,
    online_count: onlineUsers.length,
    registered_users: registeredUsers,
    pending_registration_requests: pendingRequests,
    pending_account_deletion_requests: pendingAccountDeletionRequests,
    total_messages: Number(messageStats?.total_messages || 0),
    attachment_messages: Number(messageStats?.attachment_messages || 0),
    app_release: getAppReleaseState(RELEASE_CHANNEL_STABLE),
    app_prerelease: getAppReleaseState(RELEASE_CHANNEL_PRERELEASE),
  });
});

app.post("/manage_api/registrations/:id/approve", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const requestId = Math.max(Number(req.params.id || 0), 0);
  if (!requestId) return res.status(400).json({ error: "bad_request_id" });
  const approved = finalizeApprovedRegistration(requestId, req.manageAdmin.username);
  if (!approved) return res.status(404).json({ error: "request_not_found" });
  if (approved.error === "username_taken") return res.status(409).json({ error: "username_taken" });
  res.json({ ok: true, user: approved });
});

app.post("/manage_api/registrations/:id/reject", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const requestId = Math.max(Number(req.params.id || 0), 0);
  const note = typeof req.body?.note === "string" ? req.body.note.trim().slice(0, 120) : "";
  if (!requestId) return res.status(400).json({ error: "bad_request_id" });
  const row = db.prepare("SELECT status FROM registration_requests WHERE id=?").get(requestId);
  if (!row || row.status !== "pending") return res.status(404).json({ error: "request_not_found" });
  db.prepare(
    "UPDATE registration_requests SET status='rejected', review_note=?, reviewed_at=?, reviewed_by=? WHERE id=?"
  ).run(note || "rejected", Date.now(), req.manageAdmin.username, requestId);
  res.json({ ok: true });
});

app.post("/manage_api/account_deletions/:id/approve", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const requestId = Math.max(Number(req.params.id || 0), 0);
  if (!requestId) return res.status(400).json({ error: "bad_request_id" });
  const request = db.prepare("SELECT * FROM account_deletion_requests WHERE id=? AND status='pending'").get(requestId);
  if (!request) return res.status(404).json({ error: "request_not_found" });
  let deleted;
  try {
    deleted = finalizeApprovedAccountDeletion(requestId, req.manageAdmin.username);
  } catch (error) {
    console.error("[manage] account_deletion_approve_failed", { requestId, userId: request.user_id, error: error?.message });
    return res.status(500).json({ error: "account_deletion_failed" });
  }
  if (deleted?.error === "owner_groups_block_deletion") {
    return res.status(409).json({ error: deleted.error, groups: deleted.groups || [] });
  }
  if (!deleted) return res.status(404).json({ error: "request_not_found" });
  broadcastToUser(request.user_id, { type: "force_logout", reason: "account_deleted" });
  broadcast({ type: "user_deleted", user_code: deleted?.user_code || request.user_code_snapshot || "" });
  res.json({ ok: true, user: deleted });
});

app.post("/manage_api/account_deletions/:id/reject", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const requestId = Math.max(Number(req.params.id || 0), 0);
  const note = typeof req.body?.note === "string" ? req.body.note.trim().slice(0, 120) : "";
  if (!requestId) return res.status(400).json({ error: "bad_request_id" });
  const row = db.prepare("SELECT status FROM account_deletion_requests WHERE id=?").get(requestId);
  if (!row || row.status !== "pending") return res.status(404).json({ error: "request_not_found" });
  db.prepare(
    "UPDATE account_deletion_requests SET status='rejected', review_note=?, reviewed_at=?, reviewed_by=? WHERE id=?"
  ).run(note || "rejected", Date.now(), req.manageAdmin.username, requestId);
  res.json({ ok: true });
});

app.post("/manage_api/users/:id/username", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const userId = Math.max(Number(req.params.id || 0), 0);
  const username = typeof req.body?.username === "string" ? req.body.username.trim() : "";
  if (!userId || !isValidUsername(username)) return res.status(400).json({ error: "invalid_username" });
  const existing = db.prepare("SELECT id FROM users WHERE username=?").get(username);
  if (existing && existing.id !== userId) return res.status(409).json({ error: "username_taken" });
  const user = getUserById(userId);
  if (!user) return res.status(404).json({ error: "user_not_found" });

  db.prepare("UPDATE users SET username=? WHERE id=?").run(username, userId);
  const updated = getUserById(userId);
  broadcast({
    type: "user_updated",
    user: {
      user_code: updated.user_code || "",
      username: updated.username,
      color: updated.color,
      avatar_url: updated.avatar_url || "",
      is_admin: !!updated.is_admin,
    },
  });
  res.json({ ok: true, user: updated });
});

app.post("/manage_api/app_release", manageHostOnly, manageAuthMiddleware, manageApkUpload.single("apk"), (req, res) => {
  if (!req.file) return res.status(400).json({ error: "no_file" });
  const requestedVersion = typeof req.body?.version === "string" ? req.body.version.trim() : "";
  const requestedChannel = typeof req.body?.channel === "string" ? req.body.channel.trim() : "";
  const version = extractReleaseVersion(requestedVersion) || extractReleaseVersion(req.file.originalname);
  if (!isValidReleaseVersion(version)) {
    return res.status(400).json({ error: "invalid_version" });
  }
  const ext = path.extname(req.file.originalname || "").toLowerCase();
  if (ext !== ".apk") {
    return res.status(400).json({ error: "apk_only" });
  }

  const channel = normalizeReleaseChannel(requestedChannel, inferReleaseChannel(req.file.originalname));
  const release = storeAppRelease(channel, version, req.file.originalname || "", req.file.buffer);
  res.json({
    ok: true,
    channel,
    app_release: release,
    app_releases: getAllAppReleaseStates(),
  });
});

app.post("/manage_api/clear_files", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const result = clearAttachmentMessages();
  broadcast({ type: "attachments_cleared", ts: Date.now(), by: req.manageAdmin.username });
  res.json({ ok: true, ...result });
});

app.post("/manage_api/clear_history", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const result = clearAllHistory();
  broadcast({ type: "history_deleted", ts: Date.now(), by: req.manageAdmin.username });
  broadcast({ type: "read_snapshot", items: getReadStates() });
  res.json({ ok: true, ...result });
});

app.post("/manage_api/change_password", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const currentPassword = typeof req.body?.current_password === "string" ? req.body.current_password : "";
  const newPassword = typeof req.body?.new_password === "string" ? req.body.new_password : "";
  if (!bcrypt.compareSync(currentPassword, req.manageAdmin.password_hash)) {
    return res.status(401).json({ error: "invalid_current_password" });
  }
  if (newPassword.length < 8) return res.status(400).json({ error: "password_too_short" });

  const nextSessionId = crypto.randomBytes(16).toString("hex");
  const passwordHash = bcrypt.hashSync(newPassword, 12);
  db.prepare("UPDATE manage_admins SET password_hash=?, session_id=?, updated_at=? WHERE id=?").run(
    passwordHash,
    nextSessionId,
    Date.now(),
    req.manageAdmin.id
  );
  const updated = db
    .prepare("SELECT id, username, password_hash, session_id, updated_at FROM manage_admins WHERE id=?")
    .get(req.manageAdmin.id);
  res.cookie(MANAGE_COOKIE_NAME, signManageToken(updated), manageCookieOptions(req));
  res.json({ ok: true });
});

app.post("/register_request", (req, res) => {
  const username = typeof req.body?.username === "string" ? req.body.username.trim() : "";
  const password = typeof req.body?.password === "string" ? req.body.password : "";
  if (!isValidUsername(username) || password.length < 8) {
    return res.status(400).json({ error: "invalid_registration_data" });
  }
  if (db.prepare("SELECT 1 AS ok FROM users WHERE username=?").get(username)) {
    return res.status(409).json({ error: "username_taken" });
  }
  if (
    db.prepare("SELECT 1 AS ok FROM registration_requests WHERE requested_username=? AND status='pending'").get(username)
  ) {
    return res.status(409).json({ error: "request_already_pending" });
  }

  db.prepare(
    `
    INSERT INTO registration_requests (requested_username, password_hash, request_ip, status, review_note, created_at, reviewed_at, reviewed_by)
    VALUES (?, ?, ?, 'pending', '', ?, 0, '')
  `
  ).run(username, bcrypt.hashSync(password, 12), getClientIp(req), Date.now());

  res.json({ ok: true, status: "pending" });
});

app.use("/api", appClientOnly);

app.get("/api/push_public_key", (req, res) => {
  res.json({ publicKey: VAPID_PUBLIC_KEY || "" });
});

app.post("/api/login", (req, res) => {
  const { username, password } = req.body || {};
  if (typeof username !== "string" || typeof password !== "string") {
    return res.status(400).json({ error: "bad_request" });
  }

  const user = db.prepare("SELECT * FROM users WHERE username=?").get(username.trim());
  if (!user) return res.status(401).json({ error: "invalid_credentials" });
  if (!bcrypt.compareSync(password, user.password_hash)) {
    return res.status(401).json({ error: "invalid_credentials" });
  }

  const clientType = getClientType(req);
  const sessionColumn = getSessionColumnForClientType(clientType);
  const newSid = crypto.randomBytes(16).toString("hex");
  db.prepare(`UPDATE users SET ${sessionColumn}=?, last_login_ip=? WHERE id=?`).run(newSid, getClientIp(req), user.id);
  ensureReadState(user.id);

  const updated = db.prepare("SELECT * FROM users WHERE id=?").get(user.id);
  forceLogoutUser(updated.id, clientType);

  res.json({
    token: signToken(updated, clientType),
    user_code: updated.user_code || "",
    username: updated.username,
    color: updated.color,
    avatar_url: updated.avatar_url || "",
    is_admin: !!updated.is_admin,
  });
});

app.get("/api/me", authMiddleware, (req, res) => {
  const user = getUserById(req.auth.uid);
  res.json({
    user_code: user?.user_code || req.auth.user_code || "",
    username: user?.username || req.auth.username,
    color: user?.color || req.auth.color,
    avatar_url: user?.avatar_url || "",
    is_admin: !!(user?.is_admin ?? req.auth.is_admin),
  });
});

app.post("/api/account_deletion_request", authMiddleware, (req, res) => {
  const user = getUserById(req.auth.uid);
  if (!user) return res.status(404).json({ error: "user_not_found" });
  const existing = db
    .prepare("SELECT id FROM account_deletion_requests WHERE user_id=? AND status='pending' ORDER BY id DESC LIMIT 1")
    .get(user.id);
  if (existing) return res.json({ ok: true, status: "pending", request_id: existing.id });

  const result = db
    .prepare(
      `
      INSERT INTO account_deletion_requests (
        user_id, username_snapshot, user_code_snapshot, request_ip, status, review_note, created_at, reviewed_at, reviewed_by
      )
      VALUES (?, ?, ?, ?, 'pending', '', ?, 0, '')
    `
    )
    .run(user.id, user.username, user.user_code || "", getClientIp(req), Date.now());
  res.json({ ok: true, status: "pending", request_id: Number(result.lastInsertRowid) });
});

app.post("/api/device_identity", authMiddleware, (req, res) => {
  const deviceId = normalizeDeviceId(req.body?.device_id);
  const publicKey = normalizePublicKey(req.body?.public_key);
  const platform = String(req.body?.platform || "android").trim().slice(0, 32) || "android";
  const deviceName = String(req.body?.device_name || "").trim().slice(0, 120);
  const keyAlg = String(req.body?.key_alg || "").trim().slice(0, 64);
  if (!isValidDeviceId(deviceId) || publicKey.length < 80 || !keyAlg) {
    return res.status(400).json({ error: "bad_device_identity" });
  }

  const now = Date.now();
  const existingDeviceCount = Number(
    db.prepare("SELECT COUNT(*) AS c FROM device_identity_keys WHERE user_id=?").get(req.auth.uid)?.c || 0
  );
  const fingerprint = deviceKeyFingerprint(publicKey);
  const previousIdentity = db
    .prepare("SELECT fingerprint FROM device_identity_keys WHERE user_id=? AND device_id=?")
    .get(req.auth.uid, deviceId);
  db.prepare(
    `
      INSERT INTO device_identity_keys (
        user_id, device_id, platform, device_name, key_alg, public_key, fingerprint, created_at, updated_at, last_seen_at
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(user_id, device_id) DO UPDATE SET
        platform=excluded.platform,
        device_name=excluded.device_name,
        key_alg=excluded.key_alg,
        public_key=excluded.public_key,
        fingerprint=excluded.fingerprint,
        updated_at=excluded.updated_at,
        last_seen_at=excluded.last_seen_at
    `
  ).run(req.auth.uid, deviceId, platform, deviceName, keyAlg, publicKey, fingerprint, now, now, now);
  if (previousIdentity) {
    emitDeviceSafetyChangeNotices(req.auth.uid, deviceId, deviceName, previousIdentity.fingerprint || "", fingerprint);
  } else if (existingDeviceCount > 0) {
    emitDeviceAddedNotices(req.auth.uid, deviceId, deviceName, fingerprint);
  }
  broadcastToUser(req.auth.uid, { type: "devices_changed", device_id: deviceId });

  const row = db
    .prepare(
      `
        SELECT d.*, u.user_code, u.username
        FROM device_identity_keys d
        JOIN users u ON u.id = d.user_id
        WHERE d.user_id=? AND d.device_id=?
      `
    )
    .get(req.auth.uid, deviceId);
  res.json({ ok: true, item: deviceIdentityToWire(row) });
});

app.post("/api/direct_prekey", authMiddleware, (req, res) => {
  const deviceId = normalizeDeviceId(req.body?.device_id);
  const identityEcdhPublic = normalizePublicKey(req.body?.identity_ecdh_public);
  const identityEcdhSignature = normalizePublicKey(req.body?.identity_ecdh_signature);
  const signedPrekeyPublic = normalizePublicKey(req.body?.signed_prekey_public);
  const signedPrekeySignature = normalizePublicKey(req.body?.signed_prekey_signature);
  const keyAlg = String(req.body?.key_alg || "").trim().slice(0, 64);
  if (
    !isValidDeviceId(deviceId) ||
    signedPrekeyPublic.length < 80 ||
    signedPrekeySignature.length < 40 ||
    (identityEcdhPublic && identityEcdhPublic.length < 80) ||
    (identityEcdhSignature && identityEcdhSignature.length < 40) ||
    !keyAlg
  ) {
    return res.status(400).json({ error: "bad_direct_prekey" });
  }
  const identity = db
    .prepare("SELECT id FROM device_identity_keys WHERE user_id=? AND device_id=?")
    .get(req.auth.uid, deviceId);
  if (!identity) return res.status(400).json({ error: "device_identity_required" });

  const now = Date.now();
  const oneTimePrekeys = Array.isArray(req.body?.one_time_prekeys) ? req.body.one_time_prekeys : [];
  const sanitizedOneTimePrekeys = oneTimePrekeys
    .slice(0, 50)
    .map((item) => ({
      id: String(item?.id || "").trim().slice(0, 80),
      publicKey: normalizePublicKey(item?.public_key),
      signature: normalizePublicKey(item?.signature),
    }))
    .filter((item) => item.id && item.publicKey.length >= 80 && item.signature.length >= 40);

  db.transaction(() => {
    db.prepare(
      `
        INSERT INTO direct_prekeys (
          user_id, device_id, key_alg, identity_ecdh_public, identity_ecdh_signature,
          signed_prekey_public, signed_prekey_signature, created_at, updated_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, device_id) DO UPDATE SET
          key_alg=excluded.key_alg,
          identity_ecdh_public=excluded.identity_ecdh_public,
          identity_ecdh_signature=excluded.identity_ecdh_signature,
          signed_prekey_public=excluded.signed_prekey_public,
          signed_prekey_signature=excluded.signed_prekey_signature,
          updated_at=excluded.updated_at
      `
    ).run(
      req.auth.uid,
      deviceId,
      keyAlg,
      identityEcdhPublic,
      identityEcdhSignature,
      signedPrekeyPublic,
      signedPrekeySignature,
      now,
      now
    );

    const insertOneTimePrekey = db.prepare(
      `
        INSERT OR IGNORE INTO direct_one_time_prekeys (
          user_id, device_id, prekey_id, key_alg, public_key, signature, created_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?)
      `
    );
    for (const item of sanitizedOneTimePrekeys) {
      insertOneTimePrekey.run(req.auth.uid, deviceId, item.id, keyAlg, item.publicKey, item.signature, now);
    }
  })();
  res.json({ ok: true, one_time_prekey_count: sanitizedOneTimePrekeys.length });
});

app.get("/api/device_identities", authMiddleware, (req, res) => {
  const rows = db
    .prepare(
      `
        SELECT d.*, u.user_code, u.username
        FROM device_identity_keys d
        JOIN users u ON u.id = d.user_id
        ORDER BY u.username, d.updated_at DESC
      `
    )
    .all();
  res.json({ items: rows.map(deviceIdentityToWire) });
});

app.get("/api/devices", authMiddleware, (req, res) => {
  const rows = db
    .prepare(
      `
        SELECT d.*, u.user_code, u.username
        FROM device_identity_keys d
        JOIN users u ON u.id = d.user_id
        WHERE d.user_id=?
        ORDER BY d.last_seen_at DESC, d.updated_at DESC
      `
    )
    .all(req.auth.uid);
  res.json({ items: rows.map(deviceIdentityToWire) });
});

app.delete("/api/devices/:deviceId", authMiddleware, (req, res) => {
  const deviceId = normalizeDeviceId(req.params.deviceId);
  const currentDeviceId = normalizeDeviceId(req.query.current_device_id);
  if (!isValidDeviceId(deviceId)) return res.status(400).json({ error: "bad_device_id" });
  if (currentDeviceId && deviceId === currentDeviceId) return res.status(400).json({ error: "cannot_remove_current_device" });
  const row = db
    .prepare("SELECT * FROM device_identity_keys WHERE user_id=? AND device_id=?")
    .get(req.auth.uid, deviceId);
  if (!row) return res.status(404).json({ error: "device_not_found" });

  db.transaction(() => {
    db.prepare("DELETE FROM group_sender_key_envelopes WHERE (sender_user_id=? AND sender_device_id=?) OR (recipient_user_id=? AND recipient_device_id=?)")
      .run(req.auth.uid, deviceId, req.auth.uid, deviceId);
    db.prepare("DELETE FROM direct_one_time_prekeys WHERE user_id=? AND device_id=?").run(req.auth.uid, deviceId);
    db.prepare("DELETE FROM direct_prekeys WHERE user_id=? AND device_id=?").run(req.auth.uid, deviceId);
    db.prepare("DELETE FROM device_identity_keys WHERE user_id=? AND device_id=?").run(req.auth.uid, deviceId);
  })();
  emitDeviceRemovedNotices(req.auth.uid, deviceId, row.device_name || "", row.fingerprint || "");
  broadcastToUser(req.auth.uid, { type: "devices_changed", device_id: deviceId });
  res.json({ ok: true, device_id: deviceId });
});

app.get("/api/conversations/:id/direct_prekeys", authMiddleware, (req, res) => {
  const conversation = getConversationForUser(req.auth.uid, req.params.id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "direct") return res.status(400).json({ error: "not_direct_conversation" });
  const rows = db
    .prepare(
      `
      SELECT u.id AS user_id, u.user_code, u.username,
             dik.device_id, dik.device_name, dik.key_alg AS identity_key_alg,
             dik.public_key AS identity_public_key, dik.fingerprint AS identity_fingerprint,
             dp.key_alg AS prekey_alg, dp.identity_ecdh_public, dp.identity_ecdh_signature,
             dp.signed_prekey_public, dp.signed_prekey_signature,
             dp.updated_at
      FROM conversation_members cm
      JOIN users u ON u.id = cm.user_id
      JOIN device_identity_keys dik ON dik.user_id = u.id
      JOIN direct_prekeys dp ON dp.user_id = u.id AND dp.device_id = dik.device_id
      WHERE cm.conversation_id = ?
        AND cm.user_id != ?
      ORDER BY dp.updated_at DESC
      `
    )
    .all(conversation.id, req.auth.uid);
  const claimOneTimePrekey = db.transaction((userId, deviceId) => {
    const row = db
      .prepare(
        `
          SELECT prekey_id, public_key, signature
          FROM direct_one_time_prekeys
          WHERE user_id=? AND device_id=? AND claimed_at=0
          ORDER BY created_at ASC
          LIMIT 1
        `
      )
      .get(userId, deviceId);
    if (!row) return null;
    db.prepare(
      `
        UPDATE direct_one_time_prekeys
        SET claimed_at=?, claimed_by_user_id=?, claimed_for_conversation_id=?
        WHERE user_id=? AND device_id=? AND prekey_id=? AND claimed_at=0
      `
    ).run(Date.now(), req.auth.uid, conversation.id, userId, deviceId, row.prekey_id);
    return row;
  });
  res.json({
    items: rows.map((row) => {
      const oneTimePrekey = claimOneTimePrekey(row.user_id, row.device_id);
      return {
        user_code: row.user_code || "",
        username: row.username || "",
        device_id: row.device_id || "",
        device_name: row.device_name || "",
        identity_key_alg: row.identity_key_alg || "",
        identity_public_key: row.identity_public_key || "",
        identity_fingerprint: row.identity_fingerprint || "",
        prekey_alg: row.prekey_alg || "",
        identity_ecdh_public: row.identity_ecdh_public || "",
        identity_ecdh_signature: row.identity_ecdh_signature || "",
        signed_prekey_public: row.signed_prekey_public || "",
        signed_prekey_signature: row.signed_prekey_signature || "",
        one_time_prekey_id: oneTimePrekey?.prekey_id || "",
        one_time_prekey_public: oneTimePrekey?.public_key || "",
        one_time_prekey_signature: oneTimePrekey?.signature || "",
        updated_at: Number(row.updated_at || 0),
      };
    }),
  });
});

app.get("/api/conversations/:id/group_sender_keys", authMiddleware, (req, res) => {
  const conversation = getConversationForUser(req.auth.uid, req.params.id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  const epoch = currentGroupKeyEpoch(conversation.id);
  const recipientDeviceId = normalizeDeviceId(req.query.device_id);
  const devices = db
    .prepare(
      `
      SELECT u.id AS user_id, u.user_code, u.username,
             dik.device_id, dik.device_name, dik.key_alg AS identity_key_alg,
             dik.public_key AS identity_public_key, dik.fingerprint AS identity_fingerprint,
             dp.key_alg AS prekey_alg, dp.identity_ecdh_public, dp.identity_ecdh_signature,
             dp.updated_at
      FROM conversation_members cm
      JOIN users u ON u.id = cm.user_id
      JOIN device_identity_keys dik ON dik.user_id = u.id
      JOIN direct_prekeys dp ON dp.user_id = u.id AND dp.device_id = dik.device_id
      WHERE cm.conversation_id=?
      ORDER BY u.username, dik.updated_at DESC
      `
    )
    .all(conversation.id);
  const rows = isValidDeviceId(recipientDeviceId)
    ? db
        .prepare(
          `
          SELECT gske.conversation_id, gske.sender_device_id, gske.recipient_device_id,
                 gske.epoch, gske.key_id, gske.wrapped_key, gske.updated_at,
                 sender.user_code AS sender_user_code, sender.username AS sender_username,
                 dik.device_name AS sender_device_name,
                 dik.public_key AS sender_identity_public_key,
                 dik.fingerprint AS sender_identity_fingerprint
          FROM group_sender_key_envelopes gske
          JOIN users sender ON sender.id = gske.sender_user_id
          LEFT JOIN device_identity_keys dik ON dik.user_id = gske.sender_user_id AND dik.device_id = gske.sender_device_id
          JOIN conversation_members cm ON cm.conversation_id = gske.conversation_id AND cm.user_id = gske.sender_user_id
          WHERE gske.conversation_id=? AND gske.epoch=? AND gske.recipient_user_id=? AND gske.recipient_device_id=?
          ORDER BY sender.username, gske.updated_at DESC
          `
        )
        .all(conversation.id, epoch, req.auth.uid, recipientDeviceId)
    : [];
  res.json({
    epoch,
    devices: devices.map((row) => ({
      user_code: row.user_code || "",
      username: row.username || "",
      device_id: row.device_id || "",
      device_name: row.device_name || "",
      identity_key_alg: row.identity_key_alg || "",
      identity_public_key: row.identity_public_key || "",
      identity_fingerprint: row.identity_fingerprint || "",
      prekey_alg: row.prekey_alg || "",
      identity_ecdh_public: row.identity_ecdh_public || "",
      identity_ecdh_signature: row.identity_ecdh_signature || "",
      updated_at: Number(row.updated_at || 0),
    })),
    items: rows.map((row) => ({
      conversation_id: Number(row.conversation_id || conversation.id),
      sender_user_code: row.sender_user_code || "",
      sender_username: row.sender_username || "",
      device_id: row.sender_device_id || "",
      sender_device_name: row.sender_device_name || "",
      sender_identity_public_key: row.sender_identity_public_key || "",
      sender_identity_fingerprint: row.sender_identity_fingerprint || "",
      recipient_device_id: row.recipient_device_id || "",
      epoch: Number(row.epoch || epoch),
      key_id: row.key_id || "",
      wrapped_key: row.wrapped_key || "",
      updated_at: Number(row.updated_at || 0),
    })),
  });
});

app.post("/api/conversations/:id/group_sender_key", authMiddleware, (req, res) => {
  const conversation = getConversationForUser(req.auth.uid, req.params.id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  const epoch = Math.max(Number(req.body?.epoch || 0), 0);
  const currentEpoch = currentGroupKeyEpoch(conversation.id);
  if (epoch !== currentEpoch) return res.status(409).json({ error: "stale_group_epoch", epoch: currentEpoch });
  const deviceId = normalizeDeviceId(req.body?.device_id);
  const keyId = String(req.body?.key_id || "").trim().slice(0, 80);
  const envelopes = Array.isArray(req.body?.envelopes) ? req.body.envelopes.slice(0, 100) : [];
  if (!isValidDeviceId(deviceId) || !keyId || envelopes.length <= 0) {
    return res.status(400).json({ error: "bad_group_sender_key" });
  }
  const senderIdentity = db
    .prepare("SELECT 1 AS ok FROM device_identity_keys WHERE user_id=? AND device_id=?")
    .get(req.auth.uid, deviceId);
  if (!senderIdentity) return res.status(400).json({ error: "device_identity_required" });
  const now = Date.now();
  const insertEnvelope = db.prepare(
    `
      INSERT INTO group_sender_key_envelopes (
        conversation_id, sender_user_id, sender_device_id, recipient_user_id, recipient_device_id,
        epoch, key_id, wrapped_key, created_at, updated_at
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(conversation_id, sender_user_id, sender_device_id, recipient_user_id, recipient_device_id, epoch, key_id)
      DO UPDATE SET
        wrapped_key=excluded.wrapped_key,
        updated_at=excluded.updated_at
    `
  );
  let stored = 0;
  db.transaction(() => {
    for (const envelope of envelopes) {
      const recipientCode = normalizeUserCode(envelope?.recipient_user_code);
      const recipientDeviceId = normalizeDeviceId(envelope?.recipient_device_id);
      const wrappedKey = String(envelope?.wrapped_key || "").trim().slice(0, MAX_CIPHERTEXT_LEN);
      if (!recipientCode || !isValidDeviceId(recipientDeviceId) || wrappedKey.length < 20) continue;
      const recipient = db
        .prepare(
          `
          SELECT u.id
          FROM users u
          JOIN conversation_members cm ON cm.user_id = u.id AND cm.conversation_id = ?
          JOIN device_identity_keys dik ON dik.user_id = u.id AND dik.device_id = ?
          JOIN direct_prekeys dp ON dp.user_id = u.id AND dp.device_id = dik.device_id
          WHERE u.user_code = ?
          `
        )
        .get(conversation.id, recipientDeviceId, recipientCode);
      if (!recipient) continue;
      insertEnvelope.run(
        conversation.id,
        req.auth.uid,
        deviceId,
        recipient.id,
        recipientDeviceId,
        epoch,
        keyId,
        wrappedKey,
        now,
        now
      );
      stored += 1;
    }
  })();
  if (stored <= 0) return res.status(400).json({ error: "bad_group_sender_key" });
  res.json({ ok: true, epoch, stored });
});

app.get("/api/users", authMiddleware, (req, res) => {
  res.json({ items: getUsers() });
});

app.get("/api/app_release", authMiddleware, (req, res) => {
  const channel = normalizeReleaseChannel(req.query.channel, RELEASE_CHANNEL_STABLE);
  res.json({ item: getAppReleaseState(channel), channel });
});

app.get("/api/call_config", authMiddleware, (req, res) => {
  res.json({ items: getCallIceServers() });
});

app.get("/api/conversations", authMiddleware, (req, res) => {
  res.json({ items: getConversationSummaries(req.auth.uid) });
});

app.delete("/api/conversations/:id", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind === "direct") {
    const members = getConversationMembers(conversation.id);
    cleanupConversation(conversation.id);
    for (const member of members) {
      broadcastToUser(member.id, { type: "conversation_removed", conversation_id: conversation.id, kind: "direct" });
    }
    return res.json({ ok: true });
  }

  if (conversation.kind === "group") {
    if (!isGroupOwner(req.auth.uid, conversation.id)) {
      return res.status(403).json({ error: "owner_required" });
    }
    const members = getConversationMembers(conversation.id);
    cleanupConversation(conversation.id);
    for (const member of members) {
      broadcastToUser(member.id, { type: "conversation_removed", conversation_id: conversation.id, kind: "group" });
    }
    return res.json({ ok: true });
  }

  return res.status(400).json({ error: "unsupported_conversation_kind" });
});

app.post("/api/conversations/:id/leave", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (isGroupOwner(req.auth.uid, conversation.id)) {
    return res.status(403).json({ error: "owner_cannot_leave_group" });
  }

  db.prepare("DELETE FROM conversation_members WHERE conversation_id=? AND user_id=?").run(conversation.id, req.auth.uid);
  db.prepare("DELETE FROM conversation_read_states WHERE conversation_id=? AND user_id=?").run(conversation.id, req.auth.uid);
  db.prepare("DELETE FROM conversation_delivery_states WHERE conversation_id=? AND user_id=?").run(conversation.id, req.auth.uid);
  db.prepare("DELETE FROM group_join_requests WHERE conversation_id=? AND requester_id=?").run(conversation.id, req.auth.uid);
  db.prepare("DELETE FROM group_admin_requests WHERE conversation_id=? AND (requester_id=? OR target_user_id=?)").run(conversation.id, req.auth.uid, req.auth.uid);
  const count = db.prepare("SELECT COUNT(*) AS c FROM conversation_members WHERE conversation_id=?").get(conversation.id).c;
  if (Number(count || 0) <= 0) {
    cleanupConversation(conversation.id);
  } else {
    promoteGroupAdminIfNeeded(conversation.id);
    rotateAndBroadcastGroupKeyEpoch(conversation.id);
  }
  broadcastToUser(req.auth.uid, { type: "conversation_removed", conversation_id: conversation.id, kind: "group" });
  broadcastToConversation(conversation.id, { type: "conversation_members_changed" });
  res.json({ ok: true });
});

app.get("/api/conversations/:id/manage", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });

  const members = getConversationMembers(conversation.id).map((member) => ({
    user_code: member.user_code || "",
    username: member.username,
    color: member.color,
    avatar_url: member.avatar_url || "",
    role: member.role || "member",
    joined_at: Number(member.joined_at || 0),
  }));
  const ownRole = conversation.kind === "group" ? getGroupRole(req.auth.uid, conversation.id) : "";
  const pendingJoinRequests = conversation.kind === "group" && isGroupAdmin(req.auth.uid, conversation.id)
    ? db
      .prepare(
        `
        SELECT gjr.*, c.group_code, c.title, c.avatar_url,
               u.user_code AS requester_user_code, u.username AS requester_username,
               u.color AS requester_color, u.avatar_url AS requester_avatar_url
        FROM group_join_requests gjr
        JOIN conversations c ON c.id = gjr.conversation_id
        JOIN users u ON u.id = gjr.requester_id
        WHERE gjr.conversation_id=? AND gjr.status='pending'
        ORDER BY gjr.created_at DESC
      `
      )
      .all(conversation.id)
      .map((row) => groupJoinRequestToWire(row, req.auth.uid))
    : [];
  const pendingAdminRequests = conversation.kind === "group" && isGroupOwner(req.auth.uid, conversation.id)
    ? db
      .prepare(
        `
        SELECT gar.*,
               ru.user_code AS requester_user_code, ru.username AS requester_username,
               tu.user_code AS target_user_code, tu.username AS target_username
        FROM group_admin_requests gar
        JOIN users ru ON ru.id = gar.requester_id
        JOIN users tu ON tu.id = gar.target_user_id
        WHERE gar.conversation_id=? AND gar.status='pending'
        ORDER BY gar.created_at DESC
      `
      )
      .all(conversation.id)
      .map(groupAdminRequestToWire)
    : [];
  const groupKeyEpoch = conversation.kind === "group" ? currentGroupKeyEpoch(conversation.id) : 0;
  const groupDeviceCount = conversation.kind === "group"
    ? Number(
        db
          .prepare(
            `
            SELECT COUNT(*) AS c
            FROM conversation_members cm
            JOIN device_identity_keys dik ON dik.user_id = cm.user_id
            WHERE cm.conversation_id=?
            `
          )
          .get(conversation.id)?.c || 0
      )
    : 0;
  const groupReadyDeviceCount = conversation.kind === "group"
    ? Number(
        db
          .prepare(
            `
            SELECT COUNT(*) AS c
            FROM conversation_members cm
            JOIN device_identity_keys dik ON dik.user_id = cm.user_id
            JOIN direct_prekeys dp ON dp.user_id = dik.user_id AND dp.device_id = dik.device_id
            WHERE cm.conversation_id=?
            `
          )
          .get(conversation.id)?.c || 0
      )
    : 0;

  res.json({
    conversation: {
      id: conversation.id,
      kind: conversation.kind,
      title: getConversationTitleForUser(conversation, req.auth.uid),
      group_code: conversation.group_code || "",
      avatar_url: conversation.kind === "direct" ? getConversationAvatarForUser(conversation, req.auth.uid) : conversation.avatar_url || "",
      message_ttl_ms: Number(conversation.message_ttl_ms || 0),
      own_role: ownRole,
      can_manage: conversation.kind === "direct" || isGroupAdmin(req.auth.uid, conversation.id),
      can_manage_owner: conversation.kind === "group" && isGroupOwner(req.auth.uid, conversation.id),
      admin_count: conversation.kind === "group" ? countGroupAdmins(conversation.id) : 0,
      admin_limit: GROUP_ADMIN_LIMIT,
      key_epoch: groupKeyEpoch,
      key_device_count: groupDeviceCount,
      key_ready_device_count: groupReadyDeviceCount,
    },
    members,
    pending_join_requests: pendingJoinRequests,
    pending_admin_requests: pendingAdminRequests,
  });
});

app.post("/api/conversations/:id/clear_history", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind === "group" && !isGroupAdmin(req.auth.uid, conversation.id)) {
    return res.status(403).json({ error: "not_group_admin" });
  }

  const result = clearConversationHistory(conversation.id);
  if (conversation.kind === "group") {
    rotateAndBroadcastGroupKeyEpoch(conversation.id);
  }
  broadcastToConversation(conversation.id, {
    type: "conversation_history_deleted",
    conversation_id: conversation.id,
    by: req.auth.username,
    ts: Date.now(),
  });
  res.json({ ok: true, ...result });
});

app.post("/api/conversations/:id/title", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupAdmin(req.auth.uid, conversation.id)) return res.status(403).json({ error: "not_group_admin" });
  const title = String(req.body?.title || "").trim().slice(0, 64);
  if (!title) return res.status(400).json({ error: "bad_group_title" });
  db.prepare("UPDATE conversations SET title=? WHERE id=?").run(title, conversation.id);
  broadcastToConversation(conversation.id, { type: "conversation_updated", conversation_id: conversation.id });
  res.json({ ok: true, title });
});

app.post("/api/conversations/:id/expiration", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupAdmin(req.auth.uid, conversation.id)) return res.status(403).json({ error: "not_group_admin" });
  const ttlMs = Number(req.body?.message_ttl_ms || 0);
  if (!GROUP_MESSAGE_TTL_OPTIONS_MS.has(ttlMs)) return res.status(400).json({ error: "bad_message_ttl" });
  db.prepare("UPDATE conversations SET message_ttl_ms=? WHERE id=?").run(ttlMs, conversation.id);
  broadcastToConversation(conversation.id, { type: "conversation_updated", conversation_id: conversation.id });
  res.json({ ok: true, message_ttl_ms: ttlMs });
});

app.post("/api/conversations/:id/avatar", authMiddleware, upload.single("avatar"), (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupAdmin(req.auth.uid, conversation.id)) return res.status(403).json({ error: "not_group_admin" });
  if (!req.file) return res.status(400).json({ error: "no_file" });

  if (typeof conversation.avatar_url === "string" && conversation.avatar_url.startsWith("/uploads/")) {
    try {
      const currentFile = path.basename(conversation.avatar_url);
      if (currentFile) {
        fs.unlinkSync(path.join(UPLOAD_DIR, currentFile));
      }
    } catch {}
  }

  const avatarUrl = `/uploads/${req.file.filename}`;
  db.prepare("UPDATE conversations SET avatar_url=? WHERE id=?").run(avatarUrl, conversation.id);
  broadcastToConversation(conversation.id, { type: "conversation_updated", conversation_id: conversation.id });
  res.json({ ok: true, avatar_url: avatarUrl });
});

app.post("/api/conversations/:id/members/:userCode/add", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupAdmin(req.auth.uid, conversation.id)) return res.status(403).json({ error: "not_group_admin" });

  const targetCode = normalizeUserCode(req.params.userCode);
  if (!/^\d{8}$/.test(targetCode)) return res.status(400).json({ error: "bad_user_code" });
  const target = db.prepare("SELECT id, user_code, username FROM users WHERE user_code=?").get(targetCode);
  if (!target || Number(target.id) === Number(req.auth.uid)) return res.status(404).json({ error: "user_not_found" });

  if (isConversationMember(target.id, conversation.id)) {
    return res.json({ ok: true, status: "already_member", conversation_id: conversation.id });
  }
  if (!getDirectConversationBetween(req.auth.uid, target.id)) {
    return res.status(403).json({ error: "not_contact" });
  }

  db.prepare(
    "INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?) ON CONFLICT(conversation_id, user_id) DO NOTHING"
  ).run(conversation.id, target.id, Date.now());
  ensureConversationReadState(conversation.id, target.id);
  db.prepare("DELETE FROM group_join_requests WHERE conversation_id=? AND requester_id=?").run(conversation.id, target.id);
  db.prepare("DELETE FROM group_admin_requests WHERE conversation_id=? AND (requester_id=? OR target_user_id=?)").run(conversation.id, target.id, target.id);
  rotateAndBroadcastGroupKeyEpoch(conversation.id);
  broadcastToUser(target.id, { type: "conversation_members_changed", conversation_id: conversation.id, kind: "group" });
  broadcastToConversation(conversation.id, { type: "conversation_members_changed" });
  res.json({ ok: true, status: "added", conversation_id: conversation.id, user_code: target.user_code || "" });
});

app.post("/api/conversations/:id/members/:userCode/remove", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupAdmin(req.auth.uid, conversation.id)) return res.status(403).json({ error: "not_group_admin" });

  const targetCode = normalizeUserCode(req.params.userCode);
  const target = db.prepare("SELECT id, username FROM users WHERE user_code=?").get(targetCode);
  if (!target || Number(target.id) === Number(req.auth.uid)) return res.status(404).json({ error: "user_not_found" });
  const targetRole = getGroupRole(target.id, conversation.id);
  if (targetRole === "owner") return res.status(403).json({ error: "cannot_remove_owner" });
  if (targetRole === "admin" && !isGroupOwner(req.auth.uid, conversation.id)) {
    return res.status(403).json({ error: "owner_required" });
  }

  db.prepare("DELETE FROM conversation_members WHERE conversation_id=? AND user_id=?").run(conversation.id, target.id);
  db.prepare("DELETE FROM conversation_read_states WHERE conversation_id=? AND user_id=?").run(conversation.id, target.id);
  db.prepare("DELETE FROM conversation_delivery_states WHERE conversation_id=? AND user_id=?").run(conversation.id, target.id);
  db.prepare("DELETE FROM group_join_requests WHERE conversation_id=? AND requester_id=?").run(conversation.id, target.id);
  db.prepare("DELETE FROM group_admin_requests WHERE conversation_id=? AND (requester_id=? OR target_user_id=?)").run(conversation.id, target.id, target.id);
  rotateAndBroadcastGroupKeyEpoch(conversation.id);
  broadcastToUser(target.id, { type: "conversation_removed", conversation_id: conversation.id, kind: "group" });
  broadcastToConversation(conversation.id, { type: "conversation_members_changed" });
  res.json({ ok: true });
});

app.post("/api/conversations/:id/transfer_owner", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupOwner(req.auth.uid, conversation.id)) return res.status(403).json({ error: "owner_required" });

  const targetCode = normalizeUserCode(req.body?.target_user_code);
  if (!/^\d{8}$/.test(targetCode)) return res.status(400).json({ error: "bad_user_code" });

  const target = db.prepare(
    `
      SELECT u.id, u.user_code, u.username, cm.role
      FROM users u
      JOIN conversation_members cm ON cm.user_id = u.id
      WHERE cm.conversation_id = ? AND u.user_code = ?
      LIMIT 1
    `
  ).get(conversation.id, targetCode);
  if (!target) return res.status(404).json({ error: "user_not_found" });
  if (Number(target.id) === Number(req.auth.uid)) return res.status(400).json({ error: "cannot_transfer_to_self" });
  if (target.role === "owner") return res.json({ ok: true, status: "already_owner" });

  const currentAdminCount = countGroupAdmins(conversation.id);
  const targetAdminContribution = target.role === "admin" ? 1 : 0;
  const previousOwnerRole = currentAdminCount - targetAdminContribution < GROUP_ADMIN_LIMIT ? "admin" : "member";

  db.transaction(() => {
    db.prepare("UPDATE conversation_members SET role=? WHERE conversation_id=? AND user_id=?")
      .run(previousOwnerRole, conversation.id, req.auth.uid);
    db.prepare("UPDATE conversation_members SET role='owner' WHERE conversation_id=? AND user_id=?")
      .run(conversation.id, target.id);
    db.prepare(
      `
        DELETE FROM group_admin_requests
        WHERE conversation_id=?
          AND (
            requester_id IN (?, ?)
            OR target_user_id IN (?, ?)
          )
      `
    ).run(conversation.id, req.auth.uid, target.id, req.auth.uid, target.id);
  })();

  broadcastToConversation(conversation.id, { type: "conversation_members_changed" });
  res.json({ ok: true, status: "transferred", previous_owner_role: previousOwnerRole, target_user_code: target.user_code });
});

app.post("/api/conversations/:id/admin_requests", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupAdmin(req.auth.uid, conversation.id)) return res.status(403).json({ error: "not_group_admin" });

  const targetCode = normalizeUserCode(req.body?.target_user_code);
  const target = db
    .prepare(
      `
      SELECT u.id, m.role
      FROM users u
      JOIN conversation_members m ON m.user_id=u.id
      WHERE u.user_code=? AND m.conversation_id=?
    `
    )
    .get(targetCode, conversation.id);
  if (!target) return res.status(404).json({ error: "user_not_found" });
  if (target.role === "owner" || target.role === "admin") return res.json({ ok: true, status: "already_admin" });
  if (countGroupAdmins(conversation.id) >= GROUP_ADMIN_LIMIT) return res.status(409).json({ error: "admin_limit_reached" });

  if (isGroupOwner(req.auth.uid, conversation.id)) {
    db.prepare("UPDATE conversation_members SET role='admin' WHERE conversation_id=? AND user_id=?").run(conversation.id, target.id);
    broadcastToConversation(conversation.id, { type: "conversation_members_changed" });
    return res.json({ ok: true, status: "approved" });
  }

  const pending = db
    .prepare(
      "SELECT id FROM group_admin_requests WHERE conversation_id=? AND target_user_id=? AND status='pending' ORDER BY id DESC LIMIT 1"
    )
    .get(conversation.id, target.id);
  if (pending) return res.json({ ok: true, status: "pending", request_id: pending.id });

  const result = db
    .prepare(
      "INSERT INTO group_admin_requests (conversation_id, requester_id, target_user_id, status, created_at, reviewed_at, reviewed_by) VALUES (?, ?, ?, 'pending', ?, 0, 0)"
    )
    .run(conversation.id, req.auth.uid, target.id, Date.now());
  res.json({ ok: true, status: "pending", request_id: Number(result.lastInsertRowid) });
});

app.post("/api/conversations/:id/admins/:userCode/remove", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupOwner(req.auth.uid, conversation.id)) return res.status(403).json({ error: "owner_required" });

  const targetCode = normalizeUserCode(req.params.userCode);
  if (!/^\d{8}$/.test(targetCode)) return res.status(400).json({ error: "bad_user_code" });
  const target = db
    .prepare(
      `
      SELECT u.id, u.user_code, u.username, m.role
      FROM users u
      JOIN conversation_members m ON m.user_id=u.id
      WHERE u.user_code=? AND m.conversation_id=?
      LIMIT 1
    `
    )
    .get(targetCode, conversation.id);
  if (!target) return res.status(404).json({ error: "user_not_found" });
  if (target.role === "owner") return res.status(403).json({ error: "cannot_remove_owner" });
  if (target.role !== "admin") return res.json({ ok: true, status: "already_member", target_user_code: target.user_code || "" });

  db.transaction(() => {
    db.prepare("UPDATE conversation_members SET role='member' WHERE conversation_id=? AND user_id=? AND role='admin'")
      .run(conversation.id, target.id);
    db.prepare("DELETE FROM group_admin_requests WHERE conversation_id=? AND target_user_id=?")
      .run(conversation.id, target.id);
  })();

  broadcastToConversation(conversation.id, { type: "conversation_members_changed" });
  res.json({ ok: true, status: "removed", target_user_code: target.user_code || "" });
});

app.post("/api/group_admin_requests/:id/approve", authMiddleware, (req, res) => {
  const requestId = Number(req.params.id || 0);
  const request = db.prepare("SELECT * FROM group_admin_requests WHERE id=? AND status='pending'").get(requestId);
  if (!request) return res.status(404).json({ error: "request_not_found" });
  if (!isGroupOwner(req.auth.uid, request.conversation_id)) return res.status(403).json({ error: "owner_required" });
  if (countGroupAdmins(request.conversation_id) >= GROUP_ADMIN_LIMIT) return res.status(409).json({ error: "admin_limit_reached" });
  db.prepare("UPDATE group_admin_requests SET status='approved', reviewed_at=?, reviewed_by=? WHERE id=?").run(Date.now(), req.auth.uid, requestId);
  db.prepare("UPDATE conversation_members SET role='admin' WHERE conversation_id=? AND user_id=? AND role='member'").run(request.conversation_id, request.target_user_id);
  broadcastToConversation(request.conversation_id, { type: "conversation_members_changed" });
  res.json({ ok: true });
});

app.post("/api/group_admin_requests/:id/reject", authMiddleware, (req, res) => {
  const requestId = Number(req.params.id || 0);
  const request = db.prepare("SELECT * FROM group_admin_requests WHERE id=? AND status='pending'").get(requestId);
  if (!request) return res.status(404).json({ error: "request_not_found" });
  if (!isGroupOwner(req.auth.uid, request.conversation_id)) return res.status(403).json({ error: "owner_required" });
  db.prepare("UPDATE group_admin_requests SET status='rejected', reviewed_at=?, reviewed_by=? WHERE id=?").run(Date.now(), req.auth.uid, requestId);
  res.json({ ok: true });
});

app.get("/api/contacts/lookup", authMiddleware, (req, res) => {
  const userCode = normalizeUserCode(req.query.user_code);
  if (!/^\d{8}$/.test(userCode)) return res.status(400).json({ error: "bad_user_code" });

  const target = db
    .prepare("SELECT id, user_code, username, color, avatar_url FROM users WHERE user_code=?")
    .get(userCode);
  if (!target || Number(target.id) === Number(req.auth.uid)) {
    return res.status(404).json({ error: "user_not_found" });
  }

  const direct = getDirectConversationBetween(req.auth.uid, target.id);
  const outgoing = db
    .prepare(
      "SELECT status FROM contact_requests WHERE requester_id=? AND target_user_id=? AND status='pending' ORDER BY id DESC LIMIT 1"
    )
    .get(req.auth.uid, target.id);
  const incoming = db
    .prepare(
      "SELECT id, status FROM contact_requests WHERE requester_id=? AND target_user_id=? AND status='pending' ORDER BY id DESC LIMIT 1"
    )
    .get(target.id, req.auth.uid);

  res.json({
    user: {
      user_code: target.user_code,
      username: target.username,
      color: target.color,
      avatar_url: target.avatar_url || "",
    },
    conversation_id: direct?.id || 0,
    is_contact: !!direct,
    outgoing_pending: !!outgoing,
    incoming_request_id: incoming?.id || 0,
    incoming_pending: !!incoming,
  });
});

app.get("/api/contact_requests", authMiddleware, (req, res) => {
  const rows = db
    .prepare(
      `
      SELECT cr.*,
             ru.user_code AS requester_user_code, ru.username AS requester_username,
             ru.color AS requester_color, ru.avatar_url AS requester_avatar_url,
             tu.user_code AS target_user_code, tu.username AS target_username,
             tu.color AS target_color, tu.avatar_url AS target_avatar_url
      FROM contact_requests cr
      JOIN users ru ON ru.id = cr.requester_id
      JOIN users tu ON tu.id = cr.target_user_id
      WHERE (cr.requester_id=? OR cr.target_user_id=?)
        AND cr.status='pending'
      ORDER BY cr.created_at DESC
    `
    )
    .all(req.auth.uid, req.auth.uid);
  res.json({ items: rows.map((row) => contactRequestToWire(row, req.auth.uid)) });
});

app.post("/api/contact_requests", authMiddleware, (req, res) => {
  const userCode = normalizeUserCode(req.body?.user_code);
  if (!/^\d{8}$/.test(userCode)) return res.status(400).json({ error: "bad_user_code" });

  const target = db
    .prepare("SELECT id, user_code, username, color, avatar_url FROM users WHERE user_code=?")
    .get(userCode);
  if (!target || Number(target.id) === Number(req.auth.uid)) {
    return res.status(404).json({ error: "user_not_found" });
  }

  const existing = getDirectConversationBetween(req.auth.uid, target.id);
  if (existing) return res.json({ ok: true, status: "connected", conversation_id: existing.id });

  const incoming = db
    .prepare(
      "SELECT id FROM contact_requests WHERE requester_id=? AND target_user_id=? AND status='pending' ORDER BY id DESC LIMIT 1"
    )
    .get(target.id, req.auth.uid);
  if (incoming) return res.status(409).json({ error: "incoming_request_pending", request_id: incoming.id });

  const outgoing = db
    .prepare(
      "SELECT id FROM contact_requests WHERE requester_id=? AND target_user_id=? AND status='pending' ORDER BY id DESC LIMIT 1"
    )
    .get(req.auth.uid, target.id);
  if (outgoing) return res.json({ ok: true, status: "pending", request_id: outgoing.id });

  const result = db
    .prepare("INSERT INTO contact_requests (requester_id, target_user_id, status, created_at, reviewed_at) VALUES (?, ?, 'pending', ?, 0)")
    .run(req.auth.uid, target.id, Date.now());
  res.json({ ok: true, status: "pending", request_id: Number(result.lastInsertRowid) });
});

app.post("/api/contact_requests/:id/approve", authMiddleware, (req, res) => {
  const requestId = Number(req.params.id || 0);
  const request = db
    .prepare("SELECT * FROM contact_requests WHERE id=? AND target_user_id=? AND status='pending'")
    .get(requestId, req.auth.uid);
  if (!request) return res.status(404).json({ error: "request_not_found" });
  db.prepare("UPDATE contact_requests SET status='approved', reviewed_at=? WHERE id=?").run(Date.now(), requestId);
  const conversationId = ensureDirectConversation(request.requester_id, request.target_user_id);
  res.json({ ok: true, conversation_id: conversationId });
});

app.post("/api/contact_requests/:id/reject", authMiddleware, (req, res) => {
  const requestId = Number(req.params.id || 0);
  const request = db
    .prepare("SELECT * FROM contact_requests WHERE id=? AND target_user_id=? AND status='pending'")
    .get(requestId, req.auth.uid);
  if (!request) return res.status(404).json({ error: "request_not_found" });
  db.prepare("UPDATE contact_requests SET status='rejected', reviewed_at=? WHERE id=?").run(Date.now(), requestId);
  res.json({ ok: true });
});

app.get("/api/groups/lookup", authMiddleware, (req, res) => {
  const groupCode = normalizeGroupCode(req.query.group_code);
  if (!/^\d{10}$/.test(groupCode)) return res.status(400).json({ error: "bad_group_code" });

  const group = db
    .prepare("SELECT id, group_code, title, avatar_url FROM conversations WHERE kind='group' AND group_code=?")
    .get(groupCode);
  if (!group) return res.status(404).json({ error: "group_not_found" });

  const member = isConversationMember(req.auth.uid, group.id);
  const pending = db
    .prepare(
      "SELECT id FROM group_join_requests WHERE conversation_id=? AND requester_id=? AND status='pending' ORDER BY id DESC LIMIT 1"
    )
    .get(group.id, req.auth.uid);
  res.json({
    group: {
      conversation_id: group.id,
      group_code: group.group_code,
      title: group.title,
      avatar_url: group.avatar_url || "",
    },
    is_member: member,
    pending_request_id: pending?.id || 0,
    pending: !!pending,
  });
});

app.post("/api/groups", authMiddleware, (req, res) => {
  const title = String(req.body?.title || "").trim().slice(0, 64);
  if (title.length < 1) return res.status(400).json({ error: "bad_group_title" });
  const groupCode = generateUniqueGroupCode();
  const now = Date.now();
  const result = db
    .prepare("INSERT INTO conversations (kind, slug, group_code, title, avatar_url, created_at) VALUES ('group', ?, ?, ?, '', ?)")
    .run(`group:${groupCode}`, groupCode, title, now);
  const conversationId = Number(result.lastInsertRowid);
  db.prepare(
    "INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'owner', ?)"
  ).run(conversationId, req.auth.uid, now);
  ensureConversationReadState(conversationId, req.auth.uid);
  res.json({ ok: true, conversation_id: conversationId, group_code: groupCode, title });
});

app.get("/api/group_join_requests", authMiddleware, (req, res) => {
  const rows = db
    .prepare(
      `
      SELECT gjr.*, c.group_code, c.title, c.avatar_url,
             u.user_code AS requester_user_code, u.username AS requester_username,
             u.color AS requester_color, u.avatar_url AS requester_avatar_url
      FROM group_join_requests gjr
      JOIN conversations c ON c.id = gjr.conversation_id
      JOIN users u ON u.id = gjr.requester_id
      WHERE gjr.status='pending'
        AND (
          gjr.requester_id=?
          OR EXISTS (
            SELECT 1 FROM conversation_members m
            WHERE m.conversation_id=gjr.conversation_id
              AND m.user_id=?
              AND m.role IN ('owner', 'admin')
          )
          OR EXISTS (SELECT 1 FROM users admin_user WHERE admin_user.id=? AND admin_user.is_admin=1)
        )
      ORDER BY gjr.created_at DESC
    `
    )
    .all(req.auth.uid, req.auth.uid, req.auth.uid);
  res.json({ items: rows.map((row) => groupJoinRequestToWire(row, req.auth.uid)) });
});

app.post("/api/group_join_requests", authMiddleware, (req, res) => {
  const groupCode = normalizeGroupCode(req.body?.group_code);
  if (!/^\d{10}$/.test(groupCode)) return res.status(400).json({ error: "bad_group_code" });
  const group = db
    .prepare("SELECT id FROM conversations WHERE kind='group' AND group_code=?")
    .get(groupCode);
  if (!group) return res.status(404).json({ error: "group_not_found" });
  if (isConversationMember(req.auth.uid, group.id)) {
    return res.json({ ok: true, status: "member", conversation_id: group.id });
  }
  const pending = db
    .prepare("SELECT id FROM group_join_requests WHERE conversation_id=? AND requester_id=? AND status='pending' ORDER BY id DESC LIMIT 1")
    .get(group.id, req.auth.uid);
  if (pending) return res.json({ ok: true, status: "pending", request_id: pending.id });

  const result = db
    .prepare("INSERT INTO group_join_requests (conversation_id, requester_id, status, created_at, reviewed_at, reviewed_by) VALUES (?, ?, 'pending', ?, 0, 0)")
    .run(group.id, req.auth.uid, Date.now());
  res.json({ ok: true, status: "pending", request_id: Number(result.lastInsertRowid) });
});

app.post("/api/group_join_requests/:id/approve", authMiddleware, (req, res) => {
  const requestId = Number(req.params.id || 0);
  const request = db.prepare("SELECT * FROM group_join_requests WHERE id=? AND status='pending'").get(requestId);
  if (!request) return res.status(404).json({ error: "request_not_found" });
  if (!isGroupAdmin(req.auth.uid, request.conversation_id)) return res.status(403).json({ error: "not_group_admin" });

  db.prepare("UPDATE group_join_requests SET status='approved', reviewed_at=?, reviewed_by=? WHERE id=?")
    .run(Date.now(), req.auth.uid, requestId);
  db.prepare(
    "INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?) ON CONFLICT(conversation_id, user_id) DO NOTHING"
  ).run(request.conversation_id, request.requester_id, Date.now());
  ensureConversationReadState(request.conversation_id, request.requester_id);
  rotateAndBroadcastGroupKeyEpoch(request.conversation_id);
  broadcastToConversation(request.conversation_id, { type: "conversation_members_changed" });
  broadcastToUser(request.requester_id, { type: "conversation_members_changed", conversation_id: request.conversation_id, kind: "group" });
  res.json({ ok: true, conversation_id: request.conversation_id });
});

app.post("/api/group_join_requests/:id/reject", authMiddleware, (req, res) => {
  const requestId = Number(req.params.id || 0);
  const request = db.prepare("SELECT * FROM group_join_requests WHERE id=? AND status='pending'").get(requestId);
  if (!request) return res.status(404).json({ error: "request_not_found" });
  if (!isGroupAdmin(req.auth.uid, request.conversation_id)) return res.status(403).json({ error: "not_group_admin" });
  db.prepare("UPDATE group_join_requests SET status='rejected', reviewed_at=?, reviewed_by=? WHERE id=?")
    .run(Date.now(), req.auth.uid, requestId);
  res.json({ ok: true });
});

app.get("/api/read_states", authMiddleware, (req, res) => {
  const conversation = req.query.conversation_id
    ? getConversationForUser(req.auth.uid, req.query.conversation_id)
    : null;
  res.json({ items: conversation ? getConversationReadStates(conversation.id) : getReadStates() });
});

app.get("/api/delivery_states", authMiddleware, (req, res) => {
  const conversation = req.query.conversation_id
    ? getConversationForUser(req.auth.uid, req.query.conversation_id)
    : null;
  if (!conversation) return res.json({ items: [] });
  res.json({ items: getConversationDeliveryStates(conversation.id) });
});

app.get("/api/history", authMiddleware, (req, res) => {
  const conversation = getConversationForUser(req.auth.uid, req.query.conversation_id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });

  const sinceId = Math.max(Number(req.query.since_id || 0), 0);
  const beforeId = Math.max(Number(req.query.before_id || 0), 0);
  const limit = Math.min(Math.max(Number(req.query.limit || 200), 1), 500);
  const pageSize = limit + 1;
  let rows = [];

  if (beforeId > 0) {
    rows = db
      .prepare(
        `
        SELECT id, conversation_id, ts, expires_at, username, color, kind, payload, e2ee, reply_to, mentions
        FROM messages
        WHERE conversation_id = ? AND id < ?
        ORDER BY id DESC
        LIMIT ?
      `
      )
      .all(conversation.id, beforeId, pageSize);
    const hasMore = rows.length > limit;
    if (hasMore) rows = rows.slice(0, limit);
    rows.reverse();
    const items = rows.map(messageRowToWire);
    const latestDelivered = rows.length > 0 ? rows[rows.length - 1].id : 0;
    if (latestDelivered > 0) {
      markDeliveredForUser(conversation.id, req.auth.uid, latestDelivered);
    }
    return res.json({ items, has_more: hasMore });
  }

  if (sinceId > 0) {
    rows = db
      .prepare(
        `
        SELECT id, conversation_id, ts, expires_at, username, color, kind, payload, e2ee, reply_to, mentions
        FROM messages
        WHERE conversation_id = ? AND id > ?
        ORDER BY id ASC
        LIMIT ?
      `
      )
      .all(conversation.id, sinceId, pageSize);
    const hasMore = rows.length > limit;
    if (hasMore) rows = rows.slice(0, limit);
    const items = rows.map(messageRowToWire);
    const latestDelivered = rows.length > 0 ? rows[rows.length - 1].id : 0;
    if (latestDelivered > 0) {
      markDeliveredForUser(conversation.id, req.auth.uid, latestDelivered);
    }
    return res.json({ items, has_more: hasMore });
  }

  rows = db
    .prepare(
      `
      SELECT id, conversation_id, ts, expires_at, username, color, kind, payload, e2ee, reply_to, mentions
      FROM messages
      WHERE conversation_id = ?
      ORDER BY id DESC
      LIMIT ?
    `
    )
    .all(conversation.id, pageSize);
  const hasMore = rows.length > limit;
  if (hasMore) rows = rows.slice(0, limit);
  rows.reverse();

  const items = rows.map(messageRowToWire);
  const latestDelivered = rows.length > 0 ? rows[rows.length - 1].id : 0;
  if (latestDelivered > 0) {
    markDeliveredForUser(conversation.id, req.auth.uid, latestDelivered);
  }
  res.json({ items, has_more: hasMore });
});

app.post("/api/delete_history", authMiddleware, (req, res) => {
  if (!req.auth.is_admin) return res.status(403).json({ error: "forbidden" });
  clearAllHistory();

  broadcast({ type: "history_deleted", ts: Date.now(), by: req.auth.username });
  broadcast({ type: "read_snapshot", items: getReadStates() });
  res.json({ ok: true });
});

app.post("/api/change_password", authMiddleware, (req, res) => {
  const { new_password: newPassword } = req.body || {};
  if (typeof newPassword !== "string" || newPassword.length < 8) {
    return res.status(400).json({ error: "password_too_short" });
  }

  const hash = bcrypt.hashSync(newPassword, 12);
  const newMobileSid = crypto.randomBytes(16).toString("hex");
  const newDesktopSid = crypto.randomBytes(16).toString("hex");
  db.prepare("UPDATE users SET password_hash=?, session_id=?, desktop_session_id=? WHERE id=?").run(
    hash,
    newMobileSid,
    newDesktopSid,
    req.auth.uid
  );
  forceLogoutUser(req.auth.uid);
  res.json({ ok: true });
});

app.post("/api/username", authMiddleware, (req, res) => {
  const username = typeof req.body?.username === "string" ? req.body.username.trim() : "";
  if (!isValidUsername(username)) {
    return res.status(400).json({ error: "invalid_username" });
  }
  const existing = db.prepare("SELECT id FROM users WHERE username=?").get(username);
  if (existing && existing.id !== req.auth.uid) {
    return res.status(409).json({ error: "username_taken" });
  }

  db.prepare("UPDATE users SET username=? WHERE id=?").run(username, req.auth.uid);
  const updated = getUserById(req.auth.uid);
  broadcast({
    type: "user_updated",
    user: {
      user_code: updated.user_code || "",
      username: updated.username,
      color: updated.color,
      avatar_url: updated.avatar_url || "",
      is_admin: !!updated.is_admin,
    },
  });
  res.json({
    ok: true,
    user: {
      user_code: updated.user_code || "",
      username: updated.username,
      color: updated.color,
      avatar_url: updated.avatar_url || "",
      is_admin: !!updated.is_admin,
    },
  });
});

app.post("/api/avatar", authMiddleware, upload.single("avatar"), (req, res) => {
  if (!req.file) return res.status(400).json({ error: "no_file" });

  const user = db.prepare("SELECT avatar_file FROM users WHERE id=?").get(req.auth.uid);
  if (!user) return res.status(401).json({ error: "unauthorized" });

  if (user.avatar_file) {
    try {
      fs.unlinkSync(path.join(UPLOAD_DIR, user.avatar_file));
    } catch {}
  }

  const avatarUrl = `/uploads/${req.file.filename}`;
  db.prepare("UPDATE users SET avatar_url=?, avatar_file=? WHERE id=?").run(avatarUrl, req.file.filename, req.auth.uid);
  const updatedUser = getUserById(req.auth.uid);
  broadcast({
    type: "user_updated",
    user: {
      user_code: updatedUser.user_code || "",
      username: updatedUser.username,
      color: updatedUser.color,
      avatar_url: updatedUser.avatar_url || "",
      is_admin: !!updatedUser.is_admin,
    },
  });
  res.json({ ok: true, avatar_url: avatarUrl });
});

app.post("/api/messages/:id/recall", authMiddleware, (req, res) => {
  const messageId = Math.max(Number(req.params.id || 0), 0);
  if (!messageId) return res.status(400).json({ error: "bad_message_id" });

  const message = db
    .prepare(
      `
      SELECT id, conversation_id, user_id, kind, payload
      FROM messages
      WHERE id = ?
    `
    )
    .get(messageId);
  if (!message) return res.status(404).json({ error: "message_not_found" });

  const conversation = getConversationForUser(req.auth.uid, message.conversation_id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (message.user_id !== req.auth.uid && !req.auth.is_admin) {
    return res.status(403).json({ error: "forbidden" });
  }

  const file = extractAttachmentFile(message.payload, message.kind);
  if (file) {
    try {
      fs.unlinkSync(path.join(UPLOAD_DIR, file));
    } catch {}
  }

  db.prepare(
    `
    UPDATE messages
    SET kind='recalled',
        payload='',
        reply_to='',
        mentions='[]',
        e2ee=0
    WHERE id=?
  `
  ).run(messageId);

  const updated = db
    .prepare(
      `
      SELECT id, conversation_id, ts, expires_at, username, color, kind, payload, e2ee, reply_to, mentions
      FROM messages
      WHERE id = ?
    `
    )
    .get(messageId);

  broadcastToConversation(conversation.id, { type: "message_recalled", message: messageRowToWire(updated) });
  res.json({ ok: true, message: messageRowToWire(updated) });
});

app.post("/api/push_subscribe", authMiddleware, (req, res) => {
  const sub = req.body || {};
  if (!sub.endpoint || !sub.keys || !sub.keys.p256dh || !sub.keys.auth) {
    return res.status(400).json({ error: "bad_subscription" });
  }

  db.prepare(
    `
    INSERT INTO push_subs (user_id, endpoint, p256dh, auth, created_at)
    VALUES (?, ?, ?, ?, ?)
    ON CONFLICT(endpoint) DO UPDATE SET
      user_id=excluded.user_id,
      p256dh=excluded.p256dh,
      auth=excluded.auth
  `
  ).run(req.auth.uid, sub.endpoint, sub.keys.p256dh, sub.keys.auth, Date.now());

  res.json({ ok: true });
});

app.post("/api/fcm_register", authMiddleware, (req, res) => {
  const token = typeof req.body?.token === "string" ? req.body.token.trim() : "";
  const locale = typeof req.body?.locale === "string" ? req.body.locale.trim() : "";
  const manufacturer = typeof req.body?.manufacturer === "string" ? req.body.manufacturer.trim() : "";
  const model = typeof req.body?.model === "string" ? req.body.model.trim() : "";
  if (!token) return res.status(400).json({ error: "bad_token" });
  const now = Date.now();
  db.prepare(
    `
    INSERT INTO fcm_tokens (user_id, token, platform, locale, manufacturer, model, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(token) DO UPDATE SET
      user_id=excluded.user_id,
      platform=excluded.platform,
      locale=excluded.locale,
      manufacturer=excluded.manufacturer,
      model=excluded.model,
      updated_at=excluded.updated_at
  `
  ).run(
    req.auth.uid,
    token,
    "android",
    /^zh/i.test(locale) ? "zh" : "en",
    manufacturer.slice(0, 40),
    model.slice(0, 80),
    now,
    now
  );
  logFcmDebug("token_registered", {
    userId: req.auth.uid,
    token: maskFcmToken(token),
    manufacturer: manufacturer.slice(0, 40),
    model: model.slice(0, 80),
  });
  res.json({ ok: true });
});

app.post("/api/fcm_unregister", authMiddleware, (req, res) => {
  const token = typeof req.body?.token === "string" ? req.body.token.trim() : "";
  if (!token) return res.status(400).json({ error: "bad_token" });
  db.prepare("DELETE FROM fcm_tokens WHERE token = ? AND user_id = ?").run(token, req.auth.uid);
  logFcmDebug("token_unregistered", {
    userId: req.auth.uid,
    token: maskFcmToken(token),
  });
  res.json({ ok: true });
});

app.post("/api/upload_photo", authMiddleware, upload.single("photo"), (req, res) => {
  if (!req.file) return res.status(400).json({ error: "no_file" });
  const conversation = getConversationForUser(req.auth.uid, req.body.conversation_id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });

  const now = Date.now();
  const expiresAt = conversationExpiresAt(conversation, now);
  const info = db.prepare("SELECT id, username, color FROM users WHERE id=?").get(req.auth.uid);
  if (!info) return res.status(401).json({ error: "unauthorized" });

  const payload = JSON.stringify({
    url: `/uploads/${req.file.filename}`,
    file: req.file.filename,
    mime: req.file.mimetype,
    size: req.file.size,
    name: req.file.originalname || "photo",
  });

  const result = db
    .prepare(
      `
      INSERT INTO messages (conversation_id, ts, expires_at, user_id, username, color, kind, payload, reply_to, mentions, e2ee)
      VALUES (?, ?, ?, ?, ?, ?, 'photo', ?, '', '[]', 0)
    `
    )
    .run(conversation.id, now, expiresAt, info.id, info.username, info.color, payload);

  const message = messageRowToWire({
    id: result.lastInsertRowid,
    conversation_id: conversation.id,
    ts: now,
    expires_at: expiresAt,
    user_id: info.id,
    username: info.username,
    color: info.color,
    kind: "photo",
    payload,
    e2ee: 0,
    reply_to: "",
    mentions: "[]",
  });

  broadcastToConversation(conversation.id, { type: "chat", ...message });
  sendPushToConversation(conversation.id, info.id, `${info.username} sent a photo`);
  sendFcmToConversation(
    conversation.id,
    info.id,
    conversation.kind === "direct" ? info.username : conversation.title,
    `${info.username} sent a photo`,
    {
      messageId: Number(result.lastInsertRowid),
      createdAt: now,
      kind: "photo",
    }
  );
  res.json({ ok: true, message });
});

app.post("/api/upload_attachment", authMiddleware, upload.single("attachment"), (req, res) => {
  if (!req.file) return res.status(400).json({ error: "no_file" });
  const conversation = getConversationForUser(req.auth.uid, req.body.conversation_id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });

  const kind = typeof req.body.kind === "string" ? req.body.kind.trim() : "";
  if (!["image", "file", "audio"].includes(kind)) {
    return res.status(400).json({ error: "bad_kind" });
  }

  const inputPayload = safeJsonParse(req.body.payload || "", null);
  const payloadVersion = Number(inputPayload?.v || 0);
  if (
    !inputPayload ||
    payloadVersion !== 3 ||
    typeof inputPayload.meta !== "string" ||
    !inputPayload.enc ||
    typeof inputPayload.enc.iv !== "string" ||
    !inputPayload.key_wrap ||
    typeof inputPayload.key_wrap.payload !== "string"
  ) {
    return res.status(400).json({ error: "bad_payload" });
  }

  const info = db.prepare("SELECT id, username, color FROM users WHERE id=?").get(req.auth.uid);
  if (!info) return res.status(401).json({ error: "unauthorized" });

  const now = Date.now();
  const expiresAt = conversationExpiresAt(conversation, now);
  const encPayload = {
    iv: inputPayload.enc.iv.slice(0, 128),
    v: 3,
    kdf: String(inputPayload.enc.kdf || "HKDF-SHA256").slice(0, 64),
    aead: String(inputPayload.enc.aead || "AES-256-GCM").slice(0, 64),
    stream: String(inputPayload.enc.stream || "file-key-gcm").slice(0, 64),
  };
  const outputPayload = {
    v: 3,
    attachment: {
      url: `/uploads/${req.file.filename}`,
      file: req.file.filename,
      kind,
      cipherSize: req.file.size,
    },
    enc: encPayload,
    meta: inputPayload.meta.slice(0, MAX_CIPHERTEXT_LEN),
    key_wrap: {
      scheme: String(inputPayload.key_wrap.scheme || "").slice(0, 40),
      payload: String(inputPayload.key_wrap.payload || "").slice(0, MAX_CIPHERTEXT_LEN),
    },
  };
  const payload = JSON.stringify(outputPayload);

  const replyTo = sanitizeReplyTo(req.body.reply_to);
  const mentions = sanitizeMentions(req.body.mentions, conversation.id);

  const result = db
    .prepare(
      `
      INSERT INTO messages (conversation_id, ts, expires_at, user_id, username, color, kind, payload, reply_to, mentions, e2ee)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
    `
    )
    .run(
      conversation.id,
      now,
      expiresAt,
      info.id,
      info.username,
      info.color,
      kind,
      payload,
      replyTo,
      mentions
    );

  const message = messageRowToWire({
    id: result.lastInsertRowid,
    conversation_id: conversation.id,
    ts: now,
    expires_at: expiresAt,
    user_id: info.id,
    username: info.username,
    color: info.color,
    kind,
    payload,
    e2ee: 1,
    reply_to: replyTo,
    mentions,
  });

  broadcastToConversation(conversation.id, { type: "chat", ...message });
  sendPushToConversation(conversation.id, info.id, `${info.username} sent an encrypted ${kind}`);
  sendFcmToConversation(
    conversation.id,
    info.id,
    conversation.kind === "direct" ? info.username : conversation.title,
    `${info.username} sent an encrypted ${kind}`,
    {
      messageId: Number(result.lastInsertRowid),
      createdAt: now,
      kind,
    }
  );
  res.json({ ok: true, message });
});

const server = http.createServer(app);
const wss = new WebSocket.Server({ noServer: true });
const clients = new Set();

function safeSocketSend(ws, data) {
  try {
    ws.send(data);
    return true;
  } catch (error) {
    console.warn("[ws] send_failed", { userId: ws.auth?.uid || null, error: error?.message || String(error) });
    try {
      ws.terminate();
    } catch {}
    clients.delete(ws);
    return false;
  }
}

function broadcast(obj) {
  const data = JSON.stringify(obj);
  let delivered = 0;
  for (const ws of clients) {
    if (ws.readyState !== WebSocket.OPEN) continue;
    if (safeSocketSend(ws, data)) delivered += 1;
  }
  return delivered;
}

function isConversationMember(userId, conversationId) {
  return !!db
    .prepare("SELECT 1 FROM conversation_members WHERE conversation_id=? AND user_id=?")
    .get(conversationId, userId);
}

function broadcastToConversation(conversationId, obj) {
  const data = JSON.stringify({ ...obj, conversation_id: conversationId });
  let delivered = 0;
  for (const ws of clients) {
    if (ws.readyState !== WebSocket.OPEN) continue;
    if (!isConversationMember(ws.auth?.uid, conversationId)) continue;
    if (safeSocketSend(ws, data)) delivered += 1;
  }
  return delivered;
}

function broadcastToUser(userId, obj) {
  const data = JSON.stringify(obj);
  let delivered = 0;
  for (const ws of clients) {
    if (ws.readyState !== WebSocket.OPEN) continue;
    if (ws.auth?.uid !== userId) continue;
    if (safeSocketSend(ws, data)) delivered += 1;
  }
  return delivered;
}

function normalizeCallSignalPayload(value) {
  if (!value || typeof value !== "object") return null;
  const text = JSON.stringify(value);
  if (!text || text.length > MAX_CALL_SIGNAL_TEXT_LEN) return null;
  return value;
}

function markDeliveredForUser(conversationId, userId, messageId) {
  const before =
    db
      .prepare(
        "SELECT COALESCE(last_delivered_message_id, 0) AS last_delivered_message_id FROM conversation_delivery_states WHERE conversation_id=? AND user_id=?"
      )
      .get(conversationId, userId)?.last_delivered_message_id || 0;
  upsertConversationDeliveryState(conversationId, userId, messageId);
  const after =
    db
      .prepare(
        "SELECT COALESCE(last_delivered_message_id, 0) AS last_delivered_message_id FROM conversation_delivery_states WHERE conversation_id=? AND user_id=?"
      )
      .get(conversationId, userId)?.last_delivered_message_id || 0;
  if (after > before) {
    const user = getUserById(userId);
    if (user) {
      broadcastToConversation(conversationId, {
        type: "delivered_receipt",
        conversation_id: conversationId,
        user_code: user.user_code || "",
        username: user.username,
        last_delivered_message_id: after,
      });
    }
  }
}

function forceLogoutUser(userId, clientType = null) {
  for (const ws of clients) {
    if (ws.auth?.uid !== userId || ws.readyState !== WebSocket.OPEN) continue;
    if (clientType && (ws.auth.client_type || "mobile") !== clientType) continue;
    try {
      ws.send(JSON.stringify({ type: "force_logout" }));
    } catch {}
    try {
      ws.close();
    } catch {}
  }
}

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (url.pathname !== "/ws") return socket.destroy();
  if (!isAppClient(req)) return socket.destroy();

  const token = url.searchParams.get("token");
  if (!token) return socket.destroy();

  let auth;
  try {
    auth = verifyToken(token);
  } catch {
    return socket.destroy();
  }

  const clientType = getClientType(req);
  if ((auth.client_type || "mobile") !== clientType) return socket.destroy();

  const user = db.prepare("SELECT session_id, desktop_session_id FROM users WHERE id=?").get(auth.uid);
  const sessionId = user ? getSessionIdForClient(user, clientType) : "";
  if (!user || sessionId !== auth.sid) return socket.destroy();

  wss.handleUpgrade(req, socket, head, (ws) => {
    ws.auth = auth;
    ws.auth.client_type = clientType;
    ws.clientIp = getClientIp(req);
    wss.emit("connection", ws);
  });
});

function heartbeat() {
  this.isAlive = true;
  this.last_pong_at = Date.now();
}

wss.on("connection", (ws) => {
  ws.isAlive = true;
  ws.last_pong_at = Date.now();
  setSocketPresence(ws, (ws.auth.client_type || "mobile") === "desktop" ? "foreground" : "background");
  ws.on("pong", heartbeat);
  clients.add(ws);

  const pendingInvites = takePendingCallInvitesForUser(ws.auth.uid);
  for (const invite of pendingInvites) {
    clearPendingCallFallback(invite.conversation_id, ws.auth.uid);
    try {
      ws.send(
        JSON.stringify({
          type: "call_invite",
          conversation_id: invite.conversation_id,
          user_code: invite.caller_user_code || "",
          username: invite.caller_username || "",
          ts: invite.created_at,
        })
      );
    } catch {}
  }

  ws.on("message", async (buf) => {
    if (buf.length > 600 * 1024) return;

    let msg;
    try {
      msg = JSON.parse(buf.toString("utf8"));
    } catch {
      return;
    }

    const user = db.prepare("SELECT session_id, desktop_session_id FROM users WHERE id=?").get(ws.auth.uid);
    const sessionId = user ? getSessionIdForClient(user, ws.auth.client_type || "mobile") : "";
    if (!user || sessionId !== ws.auth.sid) {
      try {
        ws.send(JSON.stringify({ type: "force_logout" }));
      } catch {}
      try {
        ws.close();
      } catch {}
      return;
    }

    if (msg.type === "presence") {
      setSocketPresence(ws, msg.state);
      return;
    }

    if (msg.type === "delivered") {
      const conversation = getConversationForUser(ws.auth.uid, msg.conversation_id);
      if (!conversation) return;
      const highest = db
        .prepare("SELECT COALESCE(MAX(id), 0) AS maxId FROM messages WHERE conversation_id=?")
        .get(conversation.id).maxId;
      const lastDeliveredMessageId = Math.min(Math.max(Number(msg.last_delivered_message_id || 0), 0), highest);
      markDeliveredForUser(conversation.id, ws.auth.uid, lastDeliveredMessageId);
      return;
    }

    if (msg.type === "read") {
      const conversation = getConversationForUser(ws.auth.uid, msg.conversation_id);
      if (!conversation) return;
      const highest = db
        .prepare("SELECT COALESCE(MAX(id), 0) AS maxId FROM messages WHERE conversation_id=?")
        .get(conversation.id).maxId;
      const lastReadMessageId = Math.min(Math.max(Number(msg.last_read_message_id || 0), 0), highest);
      upsertConversationReadState(conversation.id, ws.auth.uid, lastReadMessageId);
        broadcastToConversation(conversation.id, {
          type: "read_receipt",
          conversation_id: conversation.id,
          user_code: ws.auth.user_code || "",
          username: ws.auth.username,
          last_read_message_id: db
            .prepare(
            "SELECT last_read_message_id FROM conversation_read_states WHERE conversation_id=? AND user_id=?"
          )
          .get(conversation.id, ws.auth.uid).last_read_message_id,
      });
      return;
    }

    if (String(msg.type || "").startsWith("call_")) {
      const conversation = getConversationForUser(ws.auth.uid, msg.conversation_id);
      if (!conversation || conversation.kind !== "direct") return;
      const peer = getDirectConversationPeer(conversation.id, ws.auth.uid);
      if (!peer) return;

      const baseEvent = {
        type: String(msg.type),
        conversation_id: conversation.id,
        user_code: ws.auth.user_code || "",
        username: ws.auth.username,
      };

      if (msg.type === "call_invite") {
        const createdAt = Date.now();
        upsertPendingCallInvite(conversation.id, {
          id: ws.auth.uid,
          user_code: ws.auth.user_code || "",
          username: ws.auth.username,
        }, peer);
        const delivered = broadcastToUser(peer.id, {
          ...baseEvent,
          ts: createdAt,
        });
        logCallDebug("invite_socket_delivery", {
          conversationId: conversation.id,
          callerId: ws.auth.uid,
          calleeId: peer.id,
          deliveredSockets: delivered,
        });
        if (!delivered) {
          clearPendingCallFallback(conversation.id, peer.id);
          const fcmResult = await sendFcmIncomingCallToUser(peer.id, {
            conversationId: conversation.id,
            peerUserCode: ws.auth.user_code || "",
            peerUsername: ws.auth.username,
            createdAt,
          });
          logCallDebug("invite_direct_fcm_attempted", {
            conversationId: conversation.id,
            callerId: ws.auth.uid,
            calleeId: peer.id,
            delivered: !!fcmResult?.delivered,
            reason: fcmResult?.reason || "",
          });
          if (!fcmResult?.delivered) {
            clearPendingCallInvitesForConversation(conversation.id);
            logCallWarn("invite_unavailable", {
              conversationId: conversation.id,
              callerId: ws.auth.uid,
              calleeId: peer.id,
              reason: fcmResult?.reason || "unknown",
            });
            broadcastToUser(ws.auth.uid, {
              type: "call_unavailable",
              conversation_id: conversation.id,
              user_code: peer.user_code || "",
              username: peer.username,
            });
          }
        } else {
          logCallDebug("invite_fallback_scheduled", {
            conversationId: conversation.id,
            callerId: ws.auth.uid,
            calleeId: peer.id,
            delayMs: CALL_INVITE_FCM_FALLBACK_DELAY_MS,
          });
          schedulePendingCallFallback(conversation.id, {
            user_code: ws.auth.user_code || "",
            username: ws.auth.username,
          }, peer, createdAt);
        }
        return;
      }

      if (msg.type === "call_accept" || msg.type === "call_reject" || msg.type === "call_busy" || msg.type === "call_hangup") {
        const hadPendingInvite = !!db
          .prepare("SELECT 1 FROM pending_call_invites WHERE conversation_id = ? AND callee_id = ? LIMIT 1")
          .get(conversation.id, peer.id);
        clearPendingCallFallback(conversation.id);
        clearPendingCallInvitesForConversation(conversation.id);
        logCallDebug("signal_relay", {
          type: msg.type,
          conversationId: conversation.id,
          fromUserId: ws.auth.uid,
          toUserId: peer.id,
          hadPendingInvite,
        });
        broadcastToUser(peer.id, baseEvent);
        if (msg.type === "call_hangup" && hadPendingInvite) {
          await sendFcmCallHangupToUser(peer.id, conversation.id);
        }
        return;
      }

      if (msg.type === "call_offer" || msg.type === "call_answer") {
        const description = normalizeCallSignalPayload(msg.description);
        if (!description || typeof description.type !== "string" || typeof description.sdp !== "string") return;
        logCallDebug("signal_relay", {
          type: msg.type,
          conversationId: conversation.id,
          fromUserId: ws.auth.uid,
          toUserId: peer.id,
          descriptionType: description.type,
          sdpLength: String(description.sdp || "").length,
        });
        broadcastToUser(peer.id, {
          ...baseEvent,
          description,
        });
        return;
      }

      if (msg.type === "call_ice") {
        const candidate = normalizeCallSignalPayload(msg.candidate);
        if (!candidate) return;
        logCallDebug("signal_relay", {
          type: msg.type,
          conversationId: conversation.id,
          fromUserId: ws.auth.uid,
          toUserId: peer.id,
          sdpMid: candidate.sdpMid || "",
          sdpMLineIndex: Number(candidate.sdpMLineIndex ?? -1),
        });
        broadcastToUser(peer.id, {
          ...baseEvent,
          candidate,
        });
        return;
      }

      return;
    }

    if (msg.type !== "chat") return;
    if ((msg.kind || "text") !== "text") return;

    const info = db.prepare("SELECT id, username, color FROM users WHERE id=?").get(ws.auth.uid);
    if (!info) return;
    const conversation = getConversationForUser(ws.auth.uid, msg.conversation_id);
    if (!conversation) return;

    const payload = typeof msg.payload === "string" ? msg.payload : "";
    if (!payload) return;

    const replyTo = sanitizeReplyTo(msg.reply_to);
    const mentions = sanitizeMentions(msg.mentions, conversation.id);
    const now = Date.now();
    const expiresAt = conversationExpiresAt(conversation, now);
    const e2ee = msg.e2ee ? 1 : 0;

    let storedPayload = payload;
    if (!e2ee) {
      storedPayload = payload.trim().slice(0, MAX_PLAINTEXT_LEN);
      if (!storedPayload) return;
    } else if (storedPayload.length > MAX_CIPHERTEXT_LEN) {
      return;
    }

    const result = db
      .prepare(
        `
        INSERT INTO messages (conversation_id, ts, expires_at, user_id, username, color, kind, payload, reply_to, mentions, e2ee)
        VALUES (?, ?, ?, ?, ?, ?, 'text', ?, ?, ?, ?)
      `
      )
      .run(
        conversation.id,
        now,
        expiresAt,
        info.id,
        info.username,
        info.color,
        storedPayload,
        replyTo,
        mentions,
        e2ee
      );

    const out = messageRowToWire({
      id: result.lastInsertRowid,
      conversation_id: conversation.id,
      ts: now,
      expires_at: expiresAt,
      user_id: info.id,
      username: info.username,
      color: info.color,
      kind: "text",
      payload: storedPayload,
      e2ee,
      reply_to: replyTo,
      mentions,
    });

    broadcastToConversation(conversation.id, { type: "chat", ...out });
    const pushText = e2ee
      ? `${info.username} sent an encrypted message`
      : `${info.username}: ${storedPayload.slice(0, 80)}`;
    sendPushToConversation(conversation.id, info.id, pushText);
    sendFcmToConversation(
      conversation.id,
      info.id,
      conversation.kind === "direct" ? info.username : conversation.title,
      pushText,
      {
        messageId: Number(result.lastInsertRowid),
        createdAt: now,
        kind: e2ee ? "encrypted_text" : "text",
      }
    );
  });

  ws.on("close", () => {
    clients.delete(ws);
  });
});

setInterval(() => {
  for (const ws of clients) {
    if (ws.isAlive === false) {
      try {
        ws.terminate();
      } catch {}
      clients.delete(ws);
      continue;
    }
    ws.isAlive = false;
    try {
      ws.ping();
    } catch {}
  }
}, 25000);

setInterval(() => {
  const now = Date.now();
  const rows = db.prepare("SELECT kind, payload FROM messages WHERE expires_at <= ? AND kind != 'text'").all(now);

  for (const row of rows) {
    const file = extractAttachmentFile(row.payload, row.kind);
    if (!file) continue;
    try {
      fs.unlinkSync(path.join(UPLOAD_DIR, file));
    } catch {}
  }

  const deleted = db.prepare("DELETE FROM messages WHERE expires_at <= ?").run(now).changes;
  if (deleted > 0) broadcast({ type: "expired_cleanup", ts: now });
}, 60 * 60 * 1000);

async function sendPushToConversation(conversationId, senderUserId, bodyText) {
  if (!VAPID_PUBLIC_KEY || !VAPID_PRIVATE_KEY) return;

  const subs = db
    .prepare(
      `
      SELECT ps.endpoint, ps.p256dh, ps.auth
      FROM push_subs ps
      JOIN conversation_members cm ON cm.user_id = ps.user_id
      WHERE cm.conversation_id = ?
        AND ps.user_id != ?
    `
    )
    .all(conversationId, senderUserId);

  const conversation = db.prepare("SELECT title FROM conversations WHERE id=?").get(conversationId);
  const payload = JSON.stringify({ title: conversation?.title || "E2EE Chat", body: bodyText, url: "/" });

  for (const subRow of subs) {
    const sub = {
      endpoint: subRow.endpoint,
      keys: { p256dh: subRow.p256dh, auth: subRow.auth },
    };

    try {
      await webpush.sendNotification(sub, payload);
    } catch {
      try {
        db.prepare("DELETE FROM push_subs WHERE endpoint=?").run(subRow.endpoint);
      } catch {}
    }
  }
}

let fcmAccessTokenCache = { token: "", expiresAt: 0 };

function countUnreadMessagesForUser(userId) {
  const row = db
    .prepare(
      `
      SELECT COUNT(*) AS c
      FROM messages m
      JOIN conversation_members cm ON cm.conversation_id = m.conversation_id
      LEFT JOIN conversation_read_states rs
        ON rs.conversation_id = m.conversation_id
       AND rs.user_id = ?
      WHERE cm.user_id = ?
        AND m.user_id != ?
        AND m.kind IN ('text', 'image', 'photo', 'file', 'audio')
        AND m.id > COALESCE(rs.last_read_message_id, 0)
    `
    )
    .get(userId, userId, userId);
  return Math.max(Number(row?.c || 0), 0);
}

function buildLocalizedPushPayload(locale, unreadCount) {
  const normalized = /^zh/i.test(String(locale || "").trim()) ? "zh" : "en";
  const count = Math.max(Number(unreadCount || 0), 1);
  return {
    title: "E2EE Chat",
    body:
      normalized === "zh"
        ? count <= 1
          ? "有新消息"
          : `有 ${count} 条新消息`
        : count <= 1
          ? "New message"
          : `${count} new messages`,
    locale: normalized,
    unreadCount: count,
  };
}

function buildUnreadPushText(locale, unreadCount) {
  const normalized = /^zh/i.test(String(locale || "").trim()) ? "zh" : "en";
  const count = Math.max(Number(unreadCount || 0), 1);
  return {
    title: "E2EE Chat",
    body: normalized === "zh" ? (count <= 1 ? "有新消息" : `有 ${count} 条新消息`) : (count <= 1 ? "New message" : `${count} new messages`),
    locale: normalized,
    unreadCount: count,
  };
}

function maskFcmToken(token) {
  const text = String(token || "");
  if (!text) return "(empty)";
  if (text.length <= 12) return text;
  return `${text.slice(0, 6)}...${text.slice(-6)}`;
}

function logCallDebug(event, details = {}) {
  console.log(`[call] ${event}`, details);
}

function logCallWarn(event, details = {}) {
  console.warn(`[call] ${event}`, details);
}

function logFcmDebug(event, details = {}) {
  console.log(`[fcm] ${event}`, details);
}

function logFcmWarn(event, details = {}) {
  console.warn(`[fcm] ${event}`, details);
}

function hasFcmConfig() {
  return !!(FCM_PROJECT_ID && FCM_CLIENT_EMAIL && FCM_PRIVATE_KEY);
}

function postJson(url, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const data = typeof body === "string" ? body : JSON.stringify(body);
    const req = https.request(
      url,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(data),
          ...headers,
        },
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const text = Buffer.concat(chunks).toString("utf8");
          if (res.statusCode >= 200 && res.statusCode < 300) {
            resolve(text);
          } else {
            reject(new Error(`http_${res.statusCode}:${text}`));
          }
        });
      }
    );
    req.on("error", reject);
    req.write(data);
    req.end();
  });
}

async function getFcmAccessToken() {
  const now = Date.now();
  if (fcmAccessTokenCache.token && fcmAccessTokenCache.expiresAt - 60_000 > now) {
    return fcmAccessTokenCache.token;
  }

  if (!hasFcmConfig()) return "";

  const issuedAt = Math.floor(now / 1000);
  const header = Buffer.from(JSON.stringify({ alg: "RS256", typ: "JWT" })).toString("base64url");
  const claim = Buffer.from(
    JSON.stringify({
      iss: FCM_CLIENT_EMAIL,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token",
      iat: issuedAt,
      exp: issuedAt + 3600,
    })
  ).toString("base64url");
  const unsigned = `${header}.${claim}`;
  const signature = crypto
    .createSign("RSA-SHA256")
    .update(unsigned)
    .end()
    .sign(FCM_PRIVATE_KEY)
    .toString("base64url");
  const assertion = `${unsigned}.${signature}`;

  const payload = new URLSearchParams({
    grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
    assertion,
  }).toString();

  const text = await new Promise((resolve, reject) => {
    const req = https.request(
      "https://oauth2.googleapis.com/token",
      {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          "Content-Length": Buffer.byteLength(payload),
        },
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const body = Buffer.concat(chunks).toString("utf8");
          if (res.statusCode >= 200 && res.statusCode < 300) resolve(body);
          else reject(new Error(`oauth_${res.statusCode}:${body}`));
        });
      }
    );
    req.on("error", reject);
    req.write(payload);
    req.end();
  });

  const json = JSON.parse(text);
  fcmAccessTokenCache = {
    token: json.access_token || "",
    expiresAt: now + (Number(json.expires_in || 3600) * 1000),
  };
  return fcmAccessTokenCache.token;
}

async function sendFcmPayloadToToken(accessToken, token, payload) {
  return await postJson(
    `https://fcm.googleapis.com/v1/projects/${FCM_PROJECT_ID}/messages:send`,
    payload,
    { Authorization: `Bearer ${accessToken}` }
  );
}

async function sendFcmToConversation(conversationId, senderUserId, title, bodyText, meta = {}) {
  if (!hasFcmConfig()) {
    logFcmWarn("message_batch_skipped_missing_config", {
      conversationId,
      senderUserId,
      messageId: meta.messageId || 0,
      kind: meta.kind || "text",
    });
    return;
  }

  const tokens = db
    .prepare(
      `
      SELECT ft.token, ft.user_id, ft.locale
      FROM fcm_tokens ft
      JOIN conversation_members cm ON cm.user_id = ft.user_id
      WHERE cm.conversation_id = ?
        AND ft.user_id != ?
    `
    )
    .all(conversationId, senderUserId);
  if (!tokens.length) {
    logFcmWarn("message_batch_skipped_no_tokens", {
      conversationId,
      senderUserId,
      messageId: meta.messageId || 0,
      kind: meta.kind || "text",
    });
    return;
  }

  logFcmDebug("message_batch_start", {
    conversationId,
    senderUserId,
    messageId: meta.messageId || 0,
    createdAt: meta.createdAt || 0,
    kind: meta.kind || "text",
    tokenCount: tokens.length,
  });

  let accessToken;
  try {
    accessToken = await getFcmAccessToken();
  } catch (error) {
    logFcmWarn("message_batch_oauth_failed", {
      conversationId,
      senderUserId,
      messageId: meta.messageId || 0,
      kind: meta.kind || "text",
      error: String(error?.message || error || "unknown"),
    });
    return;
  }
  if (!accessToken) {
    logFcmWarn("message_batch_empty_access_token", {
      conversationId,
      senderUserId,
      messageId: meta.messageId || 0,
      kind: meta.kind || "text",
    });
    return;
  }

  let deliveredCount = 0;
  let failedCount = 0;
  for (const row of tokens) {
    const messageText = buildUnreadPushText(row.locale, countUnreadMessagesForUser(row.user_id));
    try {
      await sendFcmPayloadToToken(
        accessToken,
        row.token,
        {
          message: {
            token: row.token,
            notification: { title: messageText.title, body: messageText.body },
            data: {
              url: "/",
              locale: messageText.locale,
              unread_count: String(messageText.unreadCount),
              title: messageText.title,
              body: messageText.body,
            },
            android: {
              priority: "high",
              notification: {
                sound: "default",
              },
            },
          },
        }
      );
      deliveredCount += 1;
      logFcmDebug("message_sent", {
        conversationId,
        senderUserId,
        recipientUserId: row.user_id,
        messageId: meta.messageId || 0,
        createdAt: meta.createdAt || 0,
        kind: meta.kind || "text",
        unreadCount: messageText.unreadCount,
        token: maskFcmToken(row.token),
      });
    } catch (error) {
      failedCount += 1;
      logFcmWarn("message_send_failed", {
        conversationId,
        senderUserId,
        recipientUserId: row.user_id,
        messageId: meta.messageId || 0,
        createdAt: meta.createdAt || 0,
        kind: meta.kind || "text",
        token: maskFcmToken(row.token),
        error: String(error?.message || error || "unknown"),
      });
      try {
        db.prepare("DELETE FROM fcm_tokens WHERE token=?").run(row.token);
      } catch {}
    }
  }
  logFcmDebug("message_batch_complete", {
    conversationId,
    senderUserId,
    messageId: meta.messageId || 0,
    kind: meta.kind || "text",
    deliveredCount,
    failedCount,
  });
}

async function sendFcmIncomingCallToUser(userId, invite) {
  if (!hasFcmConfig()) {
    logCallWarn("incoming_fcm_skipped_missing_config", {
      userId,
      conversationId: invite.conversationId,
    });
    return { delivered: false, reason: "missing_fcm_config" };
  }
  const tokens = db
    .prepare(
      `
        SELECT token, locale
        FROM fcm_tokens
        WHERE user_id = ?
      `
    )
    .all(userId);
  if (!tokens.length) {
    logCallWarn("incoming_fcm_skipped_no_tokens", {
      userId,
      conversationId: invite.conversationId,
    });
    return { delivered: false, reason: "no_fcm_tokens" };
  }

  let accessToken;
  try {
    accessToken = await getFcmAccessToken();
  } catch (error) {
    logCallWarn("incoming_fcm_oauth_failed", {
      userId,
      conversationId: invite.conversationId,
      error: String(error?.message || error || "unknown"),
    });
    return { delivered: false, reason: "oauth_failed" };
  }
  if (!accessToken) {
    logCallWarn("incoming_fcm_empty_access_token", {
      userId,
      conversationId: invite.conversationId,
    });
    return { delivered: false, reason: "empty_access_token" };
  }

  let delivered = false;
  let lastError = "";
  for (const row of tokens) {
    const isZh = /^zh/i.test(String(row.locale || "").trim());
    try {
      await sendFcmPayloadToToken(
        accessToken,
        row.token,
        {
          message: {
            token: row.token,
            data: {
              type: "incoming_call",
              conversation_id: String(invite.conversationId),
              peer_user_code: invite.peerUserCode || "",
              peer_username: invite.peerUsername,
              created_at: String(invite.createdAt),
              locale: isZh ? "zh" : "en",
            },
            android: {
              priority: "high",
              ttl: "30s",
              collapse_key: `call_${invite.conversationId}`,
            },
          },
        }
      );
      delivered = true;
      logCallDebug("incoming_fcm_sent", {
        userId,
        conversationId: invite.conversationId,
        token: maskFcmToken(row.token),
      });
    } catch (error) {
      lastError = String(error?.message || error || "unknown");
      logCallWarn("incoming_fcm_send_failed", {
        userId,
        conversationId: invite.conversationId,
        token: maskFcmToken(row.token),
        error: lastError,
      });
      try {
        db.prepare("DELETE FROM fcm_tokens WHERE token=?").run(row.token);
      } catch {}
    }
  }
  if (!delivered) {
    logCallWarn("incoming_fcm_all_tokens_failed", {
      userId,
      conversationId: invite.conversationId,
      tokenCount: tokens.length,
      lastError,
    });
  }
  return {
    delivered,
    reason: delivered ? "sent" : (lastError || "all_tokens_failed"),
  };
}

async function sendFcmCallHangupToUser(userId, conversationId) {
  if (!hasFcmConfig()) return false;
  const tokens = db
    .prepare(
      `
        SELECT token
        FROM fcm_tokens
        WHERE user_id = ?
      `
    )
    .all(userId);
  if (!tokens.length) return false;

  let accessToken;
  try {
    accessToken = await getFcmAccessToken();
  } catch {
    return false;
  }
  if (!accessToken) return false;

  let delivered = false;
  for (const row of tokens) {
    try {
      await sendFcmPayloadToToken(
        accessToken,
        row.token,
        {
          message: {
            token: row.token,
            data: {
              type: "call_hangup",
              conversation_id: String(conversationId),
            },
            android: {
              priority: "high",
              ttl: "10s",
              collapse_key: `call_${conversationId}`,
            },
          },
        }
      );
      delivered = true;
    } catch {
      try {
        db.prepare("DELETE FROM fcm_tokens WHERE token=?").run(row.token);
      } catch {}
    }
  }
  return delivered;
}

const DISABLED_CHAT_HTML = `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>E2EE Chat</title>
    <style>
      body {
        margin: 0;
        min-height: 100vh;
        display: grid;
        place-items: center;
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
        background: #efeae2;
        color: #1f2937;
      }
      main {
        width: min(92vw, 520px);
        background: #ffffff;
        border-radius: 28px;
        padding: 32px;
        box-shadow: 0 16px 40px rgba(15, 23, 42, 0.12);
      }
      h1 { margin: 0 0 12px; color: #128c7e; }
      p { margin: 0 0 10px; line-height: 1.6; }
      a { color: #128c7e; }
    </style>
  </head>
  <body>
    <main>
      <h1>E2EE Chat</h1>
      <p>Web chat has been disabled. Please use the Android app to sign in and chat.</p>
      <p>Management portal: <a href="https://${MANAGE_HOST}/">https://${MANAGE_HOST}/</a></p>
    </main>
  </body>
</html>`;

app.use((req, res) => {
  if (req.method !== "GET" && req.method !== "HEAD") {
    return res.status(404).json({ error: "not_found" });
  }
  if (isManageHost(req)) {
    return res.sendFile(path.join(MANAGE_PUBLIC_DIR, "index.html"));
  }
  return res.status(403).type("html").send(DISABLED_CHAT_HTML);
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`E2EE Chat HTTP on http://0.0.0.0:${PORT}`);
});
