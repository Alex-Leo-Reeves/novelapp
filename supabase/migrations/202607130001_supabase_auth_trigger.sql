-- ──────────────────────────────────────────────────────────────────────────
--  Supabase Auth OTP Integration
--  Trigger: auto-create novel_users row when a new auth.users sign up
-- ──────────────────────────────────────────────────────────────────────────

-- Function: auto-create public.novel_users from auth.users
create or replace function public.handle_new_auth_user()
returns trigger
language plpgsql
security definer set search_path = public
as $$
declare
  v_username text;
begin
  -- Extract username from user_metadata, or derive from email
  v_username := coalesce(
    new.raw_user_meta_data ->> 'username',
    split_part(new.email, '@', 1),
    'Reader'
  );

  insert into public.novel_users (
    id,
    username,
    email,
    password_salt,      -- not used with Supabase Auth, keep as empty
    password_hash,      -- not used with Supabase Auth, keep as empty
    recovery_secret_hash, -- not used with Supabase Auth
    plan,
    billing_status,
    paid_until,
    created_at
  ) values (
    new.id,
    v_username,
    new.email,
    '',
    '',
    null,
    'free',
    'none',
    null,
    new.created_at
  )
  -- If the row already exists (migrated user), just update the email/username
  on conflict (id) do update set
    email = excluded.email,
    username = case
      when public.novel_users.username = 'Anonymous' or public.novel_users.username = '' or public.novel_users.username is null
      then excluded.username
      else public.novel_users.username
    end;

  -- Also create a default user state
  insert into public.novel_user_states (user_id, state, updated_at)
  values (new.id, '{"favorites":[],"readHistory":[],"watchHistory":[],"searchHistory":[],"updatedAt":0}'::jsonb, now())
  on conflict (user_id) do nothing;

  return new;
end;
$$;

-- Drop the trigger if it already exists, then create it
drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_auth_user();

-- ──────────────────────────────────────────────────────────────────────────
--  Grant necessary permissions for the trigger function
--  (handles the auth.users → public.novel_users insert)
-- ──────────────────────────────────────────────────────────────────────────
grant usage on schema public to service_role;
grant all on public.novel_users to service_role;
grant all on public.novel_user_states to service_role;

-- ──────────────────────────────────────────────────────────────────────────
--  Allow the trigger function to read auth.users
--  It runs as security definer so it can access auth schema
-- ──────────────────────────────────────────────────────────────────────────
-- Note: The trigger is owned by the table owner (supabase_admin) and
-- runs with security definer. No extra grant needed for auth.users access
-- since the trigger function has the same permissions as the table owner.

-- Notify PostgREST to reload schema cache
notify pgrst, 'reload schema';
