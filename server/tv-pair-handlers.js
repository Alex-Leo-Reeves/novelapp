// ─────────────────────────────────────────────────────────────────────────────
//  TV ↔ Phone Pairing (QR login) API handlers
//  Loaded by server/index.js for /api/tv-pair/*
//
//  Flow:
//    1. TV calls POST /api/tv-pair/start  → gets { pairId, code, qrContent }
//    2. TV shows a QR code (qrContent) + the 4-digit code on screen and polls
//       GET /api/tv-pair/status?pair=...
//    3. Phone scans the QR:
//         a. Android app installed → opens novelapp://pair?pair=..&code=..
//            → app calls POST /api/tv-pair/approve with a Bearer session token
//         b. No app installed → QR opens the web page
//            → user signs in with email + password → POST /api/tv-pair/approve
//    4. Approval ALWAYS goes through assertCanCreateSession(user) — the same
//       strict device limit as login — and issues a fresh session token.
//       The TV's first successful status poll consumes the token exactly once.
// ─────────────────────────────────────────────────────────────────────────────

const crypto = require("crypto");

function createTvPairHandlers(deps) {
    const {
        sendJson,
        sendError,
        readBody,
        PUBLIC_APP_URL,
        supabaseEnabled,
        findSupabaseUserById,
        createSupabaseSession,
        createSession,
        readData,
        writeData,
        assertCanCreateSession,
        publicUser,
        getBearerToken,
        getSessionUser,
        seedPremiumUserInFile,
        findSupabaseUserByEmail,
        passwordMatches,
        normalizeEmail
    } = deps;

    const TV_PAIR_TTL_MS = 5 * 60 * 1000; // how long a pair stays valid
    const TV_PAIR_TOKEN_TTL_MS = 90 * 1000; // how long the issued token may be claimed
    const TV_PAIR_START_LIMIT = 5; // starts per IP per minute
    const _tvPairRequests = new Map();
    const _tvPairStartLog = new Map(); // ip -> timestamps

    function hashTvPairCode(code) {
        return crypto.createHash("sha256")
            .update(`novelapp-tv-pair-v1:${String(code || "").trim()}`)
            .digest("hex");
    }

    function pruneTvPairs() {
        const now = Date.now();
        for (const [pairId, record] of _tvPairRequests) {
            if (record.expiresAt < now) _tvPairRequests.delete(pairId);
        }
    }

    function rateLimitTvPairStart(ip) {
        const now = Date.now();
        const windowStart = now - 60 * 1000;
        const entries = (_tvPairStartLog.get(ip) || []).filter((t) => t > windowStart);
        if (entries.length >= TV_PAIR_START_LIMIT) return false;
        entries.push(now);
        _tvPairStartLog.set(ip, entries);
        return true;
    }

    async function handleTvPairStart(request, response) {
        const ip = request.headers["x-forwarded-for"] || request.socket.remoteAddress || "unknown";
        if (!rateLimitTvPairStart(ip)) {
            return sendError(response, 429, "Too many pairing requests. Wait a moment and try again.");
        }
        const pairId = crypto.randomUUID();
        const code = String(Math.floor(1000 + Math.random() * 9000)); // 4-digit
        const now = Date.now();
        _tvPairRequests.set(pairId, {
            pairId,
            codeHash: hashTvPairCode(code),
            approvedUserId: null,
            token: null,
            expiresAt: now + TV_PAIR_TTL_MS,
            createdAt: new Date(now).toISOString()
        });
        pruneTvPairs();
        const qrContent = `${PUBLIC_APP_URL}/tv-pair.html?pair=${encodeURIComponent(pairId)}&code=${code}`;
        return sendJson(response, 200, {
            ok: true,
            pairId,
            code,
            qrContent,
            expiresInSeconds: Math.floor(TV_PAIR_TTL_MS / 1000)
        });
    }

    async function handleTvPairStatus(request, response, requestUrl) {
        const pairId = String(requestUrl.searchParams.get("pair") || "").trim();
        if (!pairId) return sendError(response, 400, "pair id is required.");
        const record = _tvPairRequests.get(pairId);
        pruneTvPairs();
        if (!record || record.expiresAt < Date.now()) {
            return sendJson(response, 200, { status: "expired" });
        }
        if (record.token && record.approvedUserId) {
            // Consume the token on the first successful poll — exactly one issue.
            _tvPairRequests.delete(pairId);
            const user = supabaseEnabled() ?
                await findSupabaseUserById(record.approvedUserId) :
                (() => {
                    const data = readData();
                    return data.users.find((u) => u.id === record.approvedUserId) || null;
                })();
            if (!user) return sendJson(response, 200, { status: "expired" });
            return sendJson(response, 200, {
                status: "approved",
                token: record.token,
                user: publicUser(user)
            });
        }
        return sendJson(response, 200, { status: "pending" });
    }

    // Core approval shared by the app deep-link and the web fallback.
    async function approveTvPairCore({ pairId, code, user }) {
        if (!pairId || !code) {
            const error = new Error("pair id and code are required.");
            error.statusCode = 400;
            throw error;
        }
        const record = _tvPairRequests.get(pairId);
        pruneTvPairs();
        if (!record || record.expiresAt < Date.now()) {
            const error = new Error("This pairing code has expired. Start again from the TV.");
            error.statusCode = 410;
            throw error;
        }
        if (record.token) {
            const error = new Error("This pairing code has already been used.");
            error.statusCode = 409;
            throw error;
        }
        if (hashTvPairCode(code) !== record.codeHash) {
            const error = new Error("The pairing code does not match. Check the code on your TV.");
            error.statusCode = 401;
            throw error;
        }
        // Strict device limit — identical enforcement to login.
        await assertCanCreateSession(user);
        const token = supabaseEnabled() ?
            await createSupabaseSession(user.id) :
            (() => {
                const data = readData();
                const nextToken = createSession(data, user.id);
                writeData(data);
                return nextToken;
            })();
        record.approvedUserId = user.id;
        record.token = token;
        record.expiresAt = Math.min(record.expiresAt, Date.now() + TV_PAIR_TOKEN_TTL_MS);
        return { token, user };
    }

    async function handleTvPairApprove(request, response) {
        const body = await readBody(request);
        const pairId = String(body.pair || "").trim();
        const code = String(body.code || "").trim();

        // Two approval paths:
        // 1. Bearer token (Android app deep-link) — the signed-in user approves.
        // 2. email + password (web fallback) — sign in and approve in one step.
        const bearerToken = getBearerToken(request);
        let user = null;
        if (bearerToken) {
            user = supabaseEnabled() ?
                await getSessionUser(bearerToken) :
                getSessionUser(readData(), bearerToken);
            if (!user) return sendError(response, 401, "Session expired. Please sign in again in the app.");
        } else {
            const email = normalizeEmail(body.email);
            const password = String(body.password || "");
            if (!email || !password) {
                return sendError(
                    response,
                    400,
                    "Sign in with your email and password, or open the NovelApp to approve."
                );
            }
            if (supabaseEnabled()) {
                const candidate = await findSupabaseUserByEmail(email);
                if (!candidate || !passwordMatches(password, candidate.passwordSalt, candidate.passwordHash)) {
                    return sendError(response, 401, "Email or password is incorrect.");
                }
                user = candidate;
            } else {
                const data = readData();
                seedPremiumUserInFile(data);
                writeData(data);
                const candidate = data.users.find((u) => u.email === email);
                if (!candidate || !passwordMatches(password, candidate.passwordSalt, candidate.passwordHash)) {
                    return sendError(response, 401, "Email or password is incorrect.");
                }
                user = candidate;
            }
        }

        try {
            await approveTvPairCore({ pairId, code, user });
        } catch (error) {
            return sendError(response, error.statusCode || 403, error.message);
        }
        return sendJson(response, 200, { ok: true, message: "TV signed in." });
    }

    return {
        handleTvPairStart,
        handleTvPairStatus,
        handleTvPairApprove
    };
}module.exports = { createTvPairHandlers };
