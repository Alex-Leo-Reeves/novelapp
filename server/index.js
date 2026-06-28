const crypto = require("crypto");
const fs = require("fs");
const http = require("http");
const path = require("path");
const { URL } = require("url");

const PORT = Number(process.env.PORT || 3000);
const DATA_DIR = process.env.DATA_DIR || path.join(process.cwd(), "server-data");
const DATA_FILE = path.join(DATA_DIR, "auth.json");
const SITE_DIR = path.join(process.cwd(), "site");
const SESSION_DAYS = 30;
const PASSWORD_ITERATIONS = 210000;
const PASSWORD_KEY_LENGTH = 32;
const PASSWORD_DIGEST = "sha256";

const MIME_TYPES = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".apk": "application/vnd.android.package-archive",
  ".ipa": "application/octet-stream",
  ".msi": "application/octet-stream",
  ".txt": "text/plain; charset=utf-8"
};

function ensureDataFile() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  if (!fs.existsSync(DATA_FILE)) {
    writeData({ users: [], sessions: [] });
  }
}

function readData() {
  ensureDataFile();
  return JSON.parse(fs.readFileSync(DATA_FILE, "utf8"));
}

function writeData(data) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const tmpFile = `${DATA_FILE}.tmp`;
  fs.writeFileSync(tmpFile, JSON.stringify(data, null, 2));
  fs.renameSync(tmpFile, DATA_FILE);
}

function normalizeEmail(email) {
  return String(email || "").trim().toLowerCase();
}

function publicUser(user) {
  return {
    username: user.username,
    email: user.email
  };
}

function hashPassword(password, salt = crypto.randomBytes(16).toString("base64")) {
  const hash = crypto
    .pbkdf2Sync(String(password), salt, PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH, PASSWORD_DIGEST)
    .toString("base64");

  return { salt, hash };
}

function passwordMatches(password, salt, expectedHash) {
  const actual = Buffer.from(hashPassword(password, salt).hash, "base64");
  const expected = Buffer.from(expectedHash, "base64");

  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
}

function createSession(data, userId) {
  const token = crypto.randomBytes(32).toString("base64url");
  const tokenHash = crypto.createHash("sha256").update(token).digest("hex");
  const now = Date.now();
  const expiresAt = now + SESSION_DAYS * 24 * 60 * 60 * 1000;

  data.sessions = data.sessions.filter((session) => session.expiresAt > now);
  data.sessions.push({
    tokenHash,
    userId,
    createdAt: new Date(now).toISOString(),
    expiresAt
  });

  return token;
}

function getBearerToken(request) {
  const header = request.headers.authorization || "";
  const [scheme, token] = header.split(" ");
  return scheme && scheme.toLowerCase() === "bearer" ? token : null;
}

function findSessionUser(data, token) {
  if (!token) return null;

  const now = Date.now();
  const tokenHash = crypto.createHash("sha256").update(token).digest("hex");
  const session = data.sessions.find(
    (item) => item.tokenHash === tokenHash && item.expiresAt > now
  );

  if (!session) return null;
  return data.users.find((user) => user.id === session.userId) || null;
}

function removeSession(data, token) {
  if (!token) return;
  const tokenHash = crypto.createHash("sha256").update(token).digest("hex");
  data.sessions = data.sessions.filter((session) => session.tokenHash !== tokenHash);
}

function sendJson(response, statusCode, payload) {
  response.writeHead(statusCode, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store"
  });
  response.end(JSON.stringify(payload));
}

function sendError(response, statusCode, message) {
  sendJson(response, statusCode, { error: message });
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    let body = "";

    request.on("data", (chunk) => {
      body += chunk;
      if (body.length > 64 * 1024) {
        request.destroy();
        reject(new Error("Request body is too large."));
      }
    });

    request.on("end", () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch {
        reject(new Error("Invalid JSON body."));
      }
    });

    request.on("error", reject);
  });
}

async function handleRegister(request, response) {
  const body = await readBody(request);
  const username = String(body.username || "").trim();
  const email = normalizeEmail(body.email);
  const password = String(body.password || "");

  if (username.length < 2) return sendError(response, 400, "Username must be at least 2 characters.");
  if (!email.includes("@") || !email.includes(".")) return sendError(response, 400, "Enter a valid email.");
  if (password.length < 6) return sendError(response, 400, "Password must be at least 6 characters.");

  const data = readData();
  if (data.users.some((user) => user.email === email)) {
    return sendError(response, 409, "An account with this email already exists.");
  }

  const passwordRecord = hashPassword(password);
  const user = {
    id: crypto.randomUUID(),
    username,
    email,
    passwordSalt: passwordRecord.salt,
    passwordHash: passwordRecord.hash,
    createdAt: new Date().toISOString()
  };
  data.users.push(user);
  const token = createSession(data, user.id);
  writeData(data);

  return sendJson(response, 201, { token, user: publicUser(user) });
}

async function handleLogin(request, response) {
  const body = await readBody(request);
  const email = normalizeEmail(body.email);
  const password = String(body.password || "");
  const data = readData();
  const user = data.users.find((item) => item.email === email);

  if (!user || !passwordMatches(password, user.passwordSalt, user.passwordHash)) {
    return sendError(response, 401, "Email or password is incorrect.");
  }

  const token = createSession(data, user.id);
  writeData(data);

  return sendJson(response, 200, { token, user: publicUser(user) });
}

function handleMe(request, response) {
  const data = readData();
  const user = findSessionUser(data, getBearerToken(request));

  if (!user) return sendError(response, 401, "Session expired. Please sign in again.");
  return sendJson(response, 200, { user: publicUser(user) });
}

function handleLogout(request, response) {
  const data = readData();
  removeSession(data, getBearerToken(request));
  writeData(data);
  return sendJson(response, 200, { ok: true });
}

async function handleApi(request, response, pathname) {
  try {
    if (request.method === "POST" && pathname === "/api/auth/register") {
      return await handleRegister(request, response);
    }
    if (request.method === "POST" && pathname === "/api/auth/login") {
      return await handleLogin(request, response);
    }
    if (request.method === "GET" && pathname === "/api/auth/me") {
      return handleMe(request, response);
    }
    if (request.method === "POST" && pathname === "/api/auth/logout") {
      return handleLogout(request, response);
    }

    return sendError(response, 404, "API route not found.");
  } catch (error) {
    return sendError(response, 400, error.message || "Request failed.");
  }
}

function serveStatic(request, response, pathname) {
  const safePath = path
    .normalize(decodeURIComponent(pathname))
    .replace(/^(\.\.(\/|\\|$))+/, "");
  const requestedPath = safePath === "/" ? "/index.html" : safePath;
  const filePath = path.join(SITE_DIR, requestedPath);
  const resolvedPath = fs.existsSync(filePath) && fs.statSync(filePath).isFile()
    ? filePath
    : path.join(SITE_DIR, "index.html");
  const ext = path.extname(resolvedPath).toLowerCase();

  response.writeHead(200, {
    "content-type": MIME_TYPES[ext] || "application/octet-stream"
  });
  fs.createReadStream(resolvedPath).pipe(response);
}

const server = http.createServer((request, response) => {
  const url = new URL(request.url, `http://${request.headers.host || "localhost"}`);

  if (url.pathname === "/health") {
    return sendJson(response, 200, { ok: true });
  }
  if (url.pathname.startsWith("/api/")) {
    return handleApi(request, response, url.pathname);
  }

  return serveStatic(request, response, url.pathname);
});

ensureDataFile();
server.listen(PORT, () => {
  console.log(`NovelApp server listening on ${PORT}`);
});
