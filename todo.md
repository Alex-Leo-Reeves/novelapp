# Supabase Auth OTP Implementation

- [x] Install Supabase MCP server
- [ x] Phase 1: SQL trigger for auto-creating novel_users from auth.users
- [ ] Phase 2: Add Supabase Auth endpoints to server (OTP signup, OTP login, forgot/reset password)
- [ ] Phase 3: Create migration script for existing users
- [ ] Phase 4: Update Kotlin client AuthApi for OTP flow
- [ ] Phase 5: Remove old custom auth code (after migration complete)

Good, Option B it is. Let me now implement the server-side proxy auth endpoints. This is a significant change — I'll add the new endpoints alongside the existing ones so both systems can coexist during migration.

The file was truncated. Let me take a cleaner approach — create a separate module for Supabase Auth handlers (same pattern as wwe-handlers.js)

for each user in novel_users, create a Supabase Auth account
import { createClient } from '@supabase/supabase-js'
const admin = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, { auth: { autoRefreshToken: false } })
success

Migration script for existing users

for (const user of existingUsers) {
  await admin.auth.admin.createUser({
    email: user.email,
    password: 'temporary-password', // they'll use forgot password
    email_confirm: true,
    user_metadata: { username: user.username }
  })
}

Failed to run sql query: ERROR:  42601: syntax error at or near "for"
LINE 1: for each user in novel_users, create a Supabase Auth account
        ^