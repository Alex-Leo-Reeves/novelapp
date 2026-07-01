create extension if not exists pgcrypto;

create table if not exists public.novel_users (
  id uuid primary key default gen_random_uuid(),
  username text not null,
  email text not null unique,
  password_salt text not null,
  password_hash text not null,
  recovery_secret_hash text unique,
  plan text not null default 'free',
  billing_status text not null default 'none',
  paid_until timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.novel_sessions (
  token_hash text primary key,
  user_id uuid not null references public.novel_users(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);

create table if not exists public.novel_user_states (
  user_id uuid primary key references public.novel_users(id) on delete cascade,
  state jsonb not null default '{"favorites":[],"readHistory":[],"watchHistory":[],"searchHistory":[],"updatedAt":0}'::jsonb,
  updated_at timestamptz not null default now()
);

create table if not exists public.novel_billing_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references public.novel_users(id) on delete set null,
  provider text not null default 'flutterwave',
  tx_ref text unique,
  transaction_id text,
  status text not null,
  amount numeric(12,2),
  currency text,
  raw jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index if not exists novel_sessions_user_id_idx on public.novel_sessions(user_id);
create index if not exists novel_sessions_expires_at_idx on public.novel_sessions(expires_at);
create index if not exists novel_billing_events_user_id_idx on public.novel_billing_events(user_id);
create index if not exists novel_billing_events_transaction_id_idx on public.novel_billing_events(transaction_id);

create or replace function public.novelapp_touch_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists novel_users_touch_updated_at on public.novel_users;
create trigger novel_users_touch_updated_at
before update on public.novel_users
for each row execute function public.novelapp_touch_updated_at();

drop trigger if exists novel_user_states_touch_updated_at on public.novel_user_states;
create trigger novel_user_states_touch_updated_at
before update on public.novel_user_states
for each row execute function public.novelapp_touch_updated_at();

alter table public.novel_users enable row level security;
alter table public.novel_sessions enable row level security;
alter table public.novel_user_states enable row level security;
alter table public.novel_billing_events enable row level security;

grant usage on schema public to service_role;
grant select, insert, update, delete on public.novel_users to service_role;
grant select, insert, update, delete on public.novel_sessions to service_role;
grant select, insert, update, delete on public.novel_user_states to service_role;
grant select, insert, update, delete on public.novel_billing_events to service_role;
