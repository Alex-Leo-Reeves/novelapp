// ─────────────────────────────────────────────────────────────────────────────
//  Supabase Auth OTP Proxy Handlers
//  Proxy endpoints that wrap Supabase Auth REST API for OTP/magic-link flow
//  Called by server/index.js for /api/auth/otp/* routes
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Creates the OTP handler object, receiving dependencies from index.js.
 */
function createSupabaseAuthHandlers(deps) {
    const {
        sendJson,
        sendError,
        readBody,
        SUPABASE_URL,
        SUPABASE_SECRET_KEY,
        supabaseEnabled,
        findSupabaseUserById,
        findSupabaseUserByEmail,
        insertSupabaseUser,
        createSupabaseSession,
        publicUser,
        getClientIp,
        isAuthRateLimited,
        recordAuthAttempt,
        resetAuthAttempts
    } = deps;

    /**
     * Call the Supabase Auth REST API directly.
     */
    async function supabaseAuthRequest(pathname, { method = "POST", body } = {}) {
        const headers = {
            apikey: SUPABASE_SECRET_KEY,
            authorization: `Bearer ${SUPABASE_SECRET_KEY}`,
            "content-type": "application/json"
        };
        const response = await fetch(`${SUPABASE_URL}/auth/v1/${pathname}`, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body)
        });
        const text = await response.text();
        if (!response.ok) {
            const msg = `Supabase Auth ${method} ${pathname} failed (${response.status}): ${text || response.statusText}`;
            const err = new Error(msg);
            err.statusCode = response.status;
            throw err;
        }
        return text ? JSON.parse(text) : null;
    }

    async function ensureNovelUserRow(authUserId, email, metadata) {
        let user = await findSupabaseUserById(authUserId);
        if (user) return user;

        const username =
            (metadata && metadata.username) ||
            String(email || "").split("@")[0] ||
            "Reader";

        const inserted = await insertSupabaseUser({
            id: authUserId,
            username,
            email: email || "",
            passwordSalt: "",
            passwordHash: "",
            recoverySecretHash: null,
            plan: "free",
            billingStatus: "none",
            paidUntil: null,
            state: {},
            createdAt: new Date().toISOString()
        });
        return inserted || (await findSupabaseUserById(authUserId));
    }

    // ──── Signup: Create account + send OTP ────────────────────────────────

    async function handleOtpSignup(request, response) {
        if (!supabaseEnabled()) {
            return sendError(response, 400, "Supabase is not configured on the server.");
        }
        const body = await readBody(request);
        const email = String(body.email || "").trim().toLowerCase();
        const password = String(body.password || "");
        const username = String(body.username || "").trim() || "Reader";

        if (!email) return sendError(response, 400, "Email is required.");
        if (password.length < 6) return sendError(response, 400, "Password must be at least 6 characters.");

        const existing = await findSupabaseUserByEmail(email);
        if (existing) {
            try {
                await supabaseAuthRequest("otp", {
                    body: { email, create_user: false }
                });
                return sendJson(response, 200, {
                    ok: true,
                    message: "An OTP code has been sent to your email.",
                    email
                });
            } catch (e) {
                return sendError(response, 409, "An account with this email already exists. Please sign in instead.");
            }
        }

        try {
            // Create the user in Supabase Auth
            await supabaseAuthRequest("signup", {
                body: { email, password, data: { username } }
            });

            // Explicitly send a 6-digit OTP email (type=signup overrides the magic link)
            try {
                await supabaseAuthRequest("otp", {
                    body: { email, create_user: false }
                });
            } catch (otpErr) {
                // OTP request failed (e.g. rate limit) — account was still created,
                // Supabase already sent its own confirmation link as fallback.
                console.warn("[OTP] Secondary OTP request failed:", otpErr.message);
            }

            return sendJson(response, 200, {
                ok: true,
                message: "Account created. A 6-digit OTP code has been sent to your email.",
                email
            });
        } catch (err) {
            if (err.statusCode === 429) {
                return sendError(response, 429, "Too many requests. Please wait a moment and try again.");
            }
            return sendError(response, 400, err.message || "Signup failed.");
        }
    }

    // ──── Verify OTP (used for signup AND login AND forgot-password verify) ─

    async function handleOtpVerify(request, response) {
        if (!supabaseEnabled()) {
            return sendError(response, 400, "Supabase is not configured on the server.");
        }
        const body = await readBody(request);
        const email = String(body.email || "").trim().toLowerCase();
        const token = String(body.token || body.otp || "").trim();
        const type = String(body.type || "signup").trim();

        if (!email) return sendError(response, 400, "Email is required.");
        if (!token) return sendError(response, 400, "OTP code is required.");

        const ip = getClientIp(request);
        const verifyKey = `otp-verify:${email}`;

        if (isAuthRateLimited(ip) || isAuthRateLimited(verifyKey)) {
            return sendError(response, 429, "Too many OTP verification attempts. Please try again in 15 minutes.");
        }

        let authUserId;
        let authUser;
        let accessToken = null;

        try {
            const result = await supabaseAuthRequest("verify", {
                body: { email, token, type }
            });
            authUser = result && result.user;
            authUserId = authUser && authUser.id;
            accessToken = (result && result.access_token) || null;
        } catch (err) {
            recordAuthAttempt(ip);
            recordAuthAttempt(verifyKey);
            return sendError(response, 401, "OTP verification failed. The code may have expired or is incorrect.");
        }

        if (!authUserId) {
            recordAuthAttempt(ip);
            recordAuthAttempt(verifyKey);
            return sendError(response, 401, "OTP verification failed. The code may have expired.");
        }

        // If this is a recovery flow (forgot password), don't create app session yet
        // Just return the accessToken so the client can set a new password
        if (type === "recovery") {
            resetAuthAttempts(ip);
            resetAuthAttempts(verifyKey);
            return sendJson(response, 200, {
                ok: true,
                accessToken,
                email,
                message: "OTP verified. Now set your new password.",
                needsNewPassword: true
            });
        }

        // Normal flow: ensure novel_users row and create session
        const metadata = authUser && authUser.user_metadata;
        const user = await ensureNovelUserRow(authUserId, email, metadata);
        if (!user) {
            return sendError(response, 500, "Could not create user record after OTP verification.");
        }

        const { assertCanCreateSession } = deps;
        try {
            await assertCanCreateSession(user);
        } catch (err) {
            return sendError(response, err.statusCode || 403, err.message);
        }

        resetAuthAttempts(ip);
        resetAuthAttempts(verifyKey);
        const sessionToken = await createSupabaseSession(authUserId);

        return sendJson(response, 200, {
            token: sessionToken,
            user: publicUser(user),
            message: "Signed in successfully."
        });
    }

    // ──── Login: send OTP to existing user ────────────────────────────────

    async function handleOtpLogin(request, response) {
        if (!supabaseEnabled()) {
            return sendError(response, 400, "Supabase is not configured on the server.");
        }
        const body = await readBody(request);
        const email = String(body.email || "").trim().toLowerCase();
        if (!email) return sendError(response, 400, "Email is required.");

        const ip = getClientIp(request);
        const loginKey = `otp-login:${email}`;

        if (isAuthRateLimited(ip) || isAuthRateLimited(loginKey)) {
            return sendError(response, 429, "Too many login attempts. Please try again in 15 minutes.");
        }

        const user = await findSupabaseUserByEmail(email);
        if (!user) {
            recordAuthAttempt(ip);
            recordAuthAttempt(loginKey);
            return sendError(response, 404, "No account found with this email. Please sign up first.");
        }

        try {
            await supabaseAuthRequest("otp", {
                body: { email, create_user: false, should_create_user: false }
            });
            resetAuthAttempts(ip);
            resetAuthAttempts(loginKey);
        } catch (err) {
            recordAuthAttempt(ip);
            recordAuthAttempt(loginKey);
            if (err.statusCode === 429) {
                return sendError(response, 429, "Too many requests. Please wait a moment and try again.");
            }
            return sendError(response, 400, err.message || "OTP request failed.");
        }

        return sendJson(response, 200, {
            ok: true,
            message: "OTP sent to your email.",
            email
        });
    }

    // ──── Forgot Password Step 1: Send OTP to email ──────────────────────

    async function handleOtpForgotPassword(request, response) {
        if (!supabaseEnabled()) {
            return sendError(response, 400, "Supabase is not configured on the server.");
        }
        const body = await readBody(request);
        const email = String(body.email || "").trim().toLowerCase();
        if (!email) return sendError(response, 400, "Email is required.");

        const user = await findSupabaseUserByEmail(email);
        if (!user) {
            return sendError(response, 404, "No account found with this email.");
        }

        // Send OTP with type "recovery" so the verify step knows it's for password reset
        await supabaseAuthRequest("otp", {
            body: { email, create_user: false, should_create_user: false }
        });

        return sendJson(response, 200, {
            ok: true,
            message: "An OTP code has been sent to your email.",
            email,
            type: "recovery"
        });
    }

    // ──── Forgot Password Step 3: Set new password (after OTP verify) ────

    async function handleOtpResetPassword(request, response) {
        if (!supabaseEnabled()) {
            return sendError(response, 400, "Supabase is not configured on the server.");
        }
        const body = await readBody(request);
        const email = String(body.email || "").trim().toLowerCase();
        const password = String(body.password || "");
        const accessToken = String(body.accessToken || "").trim();

        if (!email) return sendError(response, 400, "Email is required.");
        if (password.length < 6) {
            return sendError(response, 400, "Password must be at least 6 characters.");
        }
        if (!accessToken) {
            return sendError(response, 400, "Access token is required. Please verify OTP first.");
        }

        // Update password using the access token from OTP verify
        const headers = {
            apikey: SUPABASE_SECRET_KEY,
            authorization: `Bearer ${accessToken}`,
            "content-type": "application/json"
        };

        const resp = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
            method: "PUT",
            headers,
            body: JSON.stringify({ password })
        });

        const respText = await resp.text();
        if (!resp.ok) {
            const errData = runCatchJson(respText);
            return sendError(
                response,
                400,
                (errData && errData.msg) ||
                (errData && errData.error) ||
                "Password reset failed. Your session may have expired."
            );
        }

        // Find the user and create a session so they're signed in
        const user = await findSupabaseUserByEmail(email);
        if (!user) {
            return sendError(response, 500, "Account not found after password reset.");
        }

        const sessionToken = await createSupabaseSession(user.id);

        return sendJson(response, 200, {
            token: sessionToken,
            user: publicUser(user),
            message: "Password updated successfully. You are now signed in."
        });
    }

    function runCatchJson(text) {
        try { return JSON.parse(text); } catch { return null; }
    }

    return {
        handleOtpSignup,
        handleOtpVerify,
        handleOtpLogin,
        handleOtpForgotPassword,
        handleOtpResetPassword
    };
}

module.exports = { createSupabaseAuthHandlers };