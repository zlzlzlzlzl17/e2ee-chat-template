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

const PORT = process.env.PORT || 3000;
const JWT_SECRET = process.env.JWT_SECRET || "CHANGE_THIS_TO_A_LONG_RANDOM_SECRET";
const DB_PATH = process.env.DB_PATH || path.join(__dirname, "data", "chat.sqlite");
const UPLOAD_DIR = process.env.UPLOAD_DIR || path.join(__dirname, "uploads");
const APP_RELEASE_DIR = process.env.APP_RELEASE_DIR || path.join(__dirname, "app_release");
const MANAGE_HOST = (process.env.MANAGE_HOST || "manage.example.com").toLowerCase();
const MANAGE_COOKIE_NAME = "e2ee_chat_manage_token";
const APP_CLIENT_HEADER = "x-e2ee-chat-client";
const APP_CLIENT_VALUES = new Set(["android-app", "windows-app"]);
const MOBILE_CLIENT_VALUE = "android-app";
const DESKTOP_CLIENT_VALUE = "windows-app";
const MANAGE_PUBLIC_DIR = path.join(__dirname, "manage_public");
const RELEASE_CHANNEL_STABLE = "stable";
const RELEASE_CHANNEL_PRERELEASE = "prerelease";
const RELEASE_CHANNEL_VALUES = new Set([RELEASE_CHANNEL_STABLE, RELEASE_CHANNEL_PRERELEASE]);

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
const SEED_DEMO_USERS = /^(1|true|yes)$/i.test(String(process.env.SEED_DEMO_USERS || "").trim());

const RETENTION_MS = 3 * 24 * 60 * 60 * 1000;
const MAX_PLAINTEXT_LEN = 2000;
const MAX_CIPHERTEXT_LEN = 40000;
const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;
const MAX_MANAGE_APK_BYTES = 256 * 1024 * 1024;
const MAX_REPLY_LEN = 1200;
const MAX_MENTIONS = 12;
const MANAGE_LOGIN_MAX_FAILURES = 3;
const MANAGE_LOGIN_COOLDOWN_MS = 5 * 60 * 1000;
const MANAGE_TOTP_DIGITS = 6;
const MANAGE_TOTP_PERIOD = 30;
const MANAGE_TOTP_SECRET_BYTES = 20;
const MANAGE_TOTP_CHALLENGE_TTL_MS = 5 * 60 * 1000;
const MANAGE_TOTP_CHALLENGE_MAX_ATTEMPTS = 5;
const MAX_CALL_SIGNAL_TEXT_LEN = 64 * 1024;
const CALL_INVITE_TTL_MS = 30 * 1000;
const CALL_INVITE_FCM_FALLBACK_DELAY_MS = 2 * 1000;
const MOBILE_FOREGROUND_PRESENCE_TTL_MS = 70 * 1000;

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
  title TEXT NOT NULL,
  avatar_url TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS conversations_slug_idx ON conversations(slug);

CREATE TABLE IF NOT EXISTS conversation_members (
  conversation_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
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
CREATE INDEX IF NOT EXISTS pending_call_invites_callee_idx ON pending_call_invites(callee_id, expires_at DESC);
`);

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
  if (!SEED_DEMO_USERS) return;
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

function seedManageAdminIfEmpty() {
  const count = db.prepare("SELECT COUNT(*) AS c FROM manage_admins").get().c;
  if (count > 0) return;

  db.prepare(
    "INSERT INTO manage_admins (username, password_hash, session_id, updated_at) VALUES (?, ?, ?, ?)"
  ).run(
    "admin",
    bcrypt.hashSync("change-this-manage-password", 12),
    "",
    Date.now()
  );
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

function ensureDefaultConversation() {
  let row = db.prepare("SELECT id FROM conversations WHERE slug='e2ee_chat'").get();
  if (!row) {
    const result = db
      .prepare(
        `
        INSERT INTO conversations (kind, slug, title, avatar_url, created_at)
        VALUES ('group', 'e2ee_chat', 'E2EE Chat', '', ?)
      `
      )
      .run(Date.now());
    row = { id: Number(result.lastInsertRowid) };
  }

  const conversationId = row.id;
  for (const user of db.prepare("SELECT id FROM users").all()) {
    db.prepare(
      `
      INSERT INTO conversation_members (conversation_id, user_id)
      VALUES (?, ?)
      ON CONFLICT(conversation_id, user_id) DO NOTHING
    `
    ).run(conversationId, user.id);
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

function getDefaultConversationId() {
  return db.prepare("SELECT id FROM conversations WHERE slug='e2ee_chat'").get().id;
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
      INSERT INTO conversation_members (conversation_id, user_id)
      VALUES (?, ?)
      ON CONFLICT(conversation_id, user_id) DO NOTHING
    `
    ).run(row.id, userId);
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
ensureDefaultConversation();
ensureAllDirectConversations();

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

function getAllowedUsernames() {
  return new Set(db.prepare("SELECT username FROM users").all().map((row) => row.username));
}

function sanitizeMentions(raw) {
  const parsed = Array.isArray(raw) ? raw : safeJsonParse(raw || "[]", []);
  if (!Array.isArray(parsed)) return "[]";

  const allowed = getAllowedUsernames();
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
      conversation_id: row.conversation_id || getDefaultConversationId(),
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
  const defaultConversationId = getDefaultConversationId();
  return getConversationReadStates(defaultConversationId);
}

function getConversationReadStates(conversationId) {
  return db
    .prepare(
      `
        SELECT u.user_code, u.username, COALESCE(r.last_read_message_id, 0) AS last_read_message_id
        FROM users u
        LEFT JOIN conversation_read_states r
          ON r.user_id = u.id
         AND r.conversation_id = ?
        ORDER BY u.username
    `
    )
    .all(conversationId);
}

function getConversationDeliveryStates(conversationId) {
  return db
    .prepare(
      `
        SELECT u.user_code, u.username, COALESCE(d.last_delivered_message_id, 0) AS last_delivered_message_id
        FROM users u
        LEFT JOIN conversation_delivery_states d
          ON d.user_id = u.id
         AND d.conversation_id = ?
        ORDER BY u.username
    `
    )
    .all(conversationId);
}

function upsertReadState(userId, lastReadMessageId) {
  const defaultConversationId = getDefaultConversationId();
  upsertConversationReadState(defaultConversationId, userId, lastReadMessageId);
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

  if (conversationId === getDefaultConversationId()) {
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
  const resolvedId = Number(conversationId) || getDefaultConversationId();
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
      SELECT u.id, u.user_code, u.username, u.color, u.avatar_url, u.is_admin
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
  if (message.kind === "text") return message.e2ee ? "Encrypted message" : String(message.payload || "").slice(0, 80);
  if (message.kind === "image" || message.kind === "photo") return "Image";
  if (message.kind === "audio") return "Voice note";
  if (message.kind === "file") return "File";
  return message.kind || "";
}

function getConversationSummaries(userId) {
  ensureAllDirectConversations();
  const rows = db
    .prepare(
      `
      SELECT c.id, c.kind, c.slug, c.title, c.avatar_url,
             COALESCE(crs.last_read_message_id, 0) AS last_read_message_id
      FROM conversations c
      JOIN conversation_members m ON m.conversation_id = c.id
      LEFT JOIN conversation_read_states crs
        ON crs.conversation_id = c.id
       AND crs.user_id = ?
      WHERE m.user_id = ?
        ORDER BY CASE WHEN c.slug = 'e2ee_chat' THEN 0 ELSE 1 END, c.id
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
      title,
      avatar_url: avatarUrl,
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
ensureDefaultConversation();
  ensureAllDirectConversations();
  db.prepare(
    "UPDATE registration_requests SET status='approved', review_note='', reviewed_at=?, reviewed_by=? WHERE id=?"
  ).run(Date.now(), reviewedBy, request.id);
  return getUserById(userId);
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
    ? `e2ee_chat-prerelease-${version}(pre).apk`
    : `e2ee_chat-release-${version}.apk`;
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
    .run(conversation.id, now, now + RETENTION_MS, info.id, info.username, info.color, payload);

  const message = messageRowToWire({
    id: result.lastInsertRowid,
    conversation_id: conversation.id,
    ts: now,
    expires_at: now + RETENTION_MS,
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
  if (
    !inputPayload ||
    inputPayload.v !== 1 ||
    typeof inputPayload.meta !== "string" ||
    !inputPayload.enc ||
    typeof inputPayload.enc.salt !== "string" ||
    typeof inputPayload.enc.iv !== "string"
  ) {
    return res.status(400).json({ error: "bad_payload" });
  }

  const info = db.prepare("SELECT id, username, color FROM users WHERE id=?").get(req.auth.uid);
  if (!info) return res.status(401).json({ error: "unauthorized" });

  const now = Date.now();
  const payload = JSON.stringify({
    v: 1,
    attachment: {
      url: `/uploads/${req.file.filename}`,
      file: req.file.filename,
      kind,
      cipherSize: req.file.size,
    },
    enc: {
      salt: inputPayload.enc.salt.slice(0, 128),
      iv: inputPayload.enc.iv.slice(0, 128),
    },
    meta: inputPayload.meta.slice(0, MAX_CIPHERTEXT_LEN),
  });

  const replyTo = sanitizeReplyTo(req.body.reply_to);
  const mentions = sanitizeMentions(req.body.mentions);

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
      now + RETENTION_MS,
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
    expires_at: now + RETENTION_MS,
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

function broadcast(obj) {
  const data = JSON.stringify(obj);
  for (const ws of clients) {
    if (ws.readyState === WebSocket.OPEN) ws.send(data);
  }
}

function isConversationMember(userId, conversationId) {
  return !!db
    .prepare("SELECT 1 FROM conversation_members WHERE conversation_id=? AND user_id=?")
    .get(conversationId, userId);
}

function broadcastToConversation(conversationId, obj) {
  const data = JSON.stringify({ ...obj, conversation_id: conversationId });
  for (const ws of clients) {
    if (ws.readyState !== WebSocket.OPEN) continue;
    if (!isConversationMember(ws.auth?.uid, conversationId)) continue;
    ws.send(data);
  }
}

function broadcastToUser(userId, obj) {
  const data = JSON.stringify(obj);
  let delivered = 0;
  for (const ws of clients) {
    if (ws.readyState !== WebSocket.OPEN) continue;
    if (ws.auth?.uid !== userId) continue;
    ws.send(data);
    delivered += 1;
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
    const mentions = sanitizeMentions(msg.mentions);
    const now = Date.now();
    const expiresAt = now + RETENTION_MS;
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
        AND m.kind != 'recalled'
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
