-- ──────────────────────────────────────────────────────────────────────────
--  AI Novel Generation: community novels + per-user monthly credit tracking
-- ──────────────────────────────────────────────────────────────────────────

-- Community-published AI novels
create table if not exists public.novel_ai_generated (
  id            uuid primary key default gen_random_uuid(),
  author_id     uuid references public.novel_users(id) on delete set null,
  author_name   text not null default 'Anonymous',
  title         text not null,
  cover_url     text not null default '',
  content       text not null default '',
  type          text not null default 'short',      -- 'short' | 'long'
  source_novels jsonb not null default '[]'::jsonb, -- [{id, title, coverUrl, sourceName}]
  genres        text not null default '',
  word_count    int not null default 0,
  published     boolean not null default true,
  created_at    timestamptz not null default now()
);

create index if not exists novel_ai_generated_author_idx  on public.novel_ai_generated(author_id);
create index if not exists novel_ai_generated_created_idx on public.novel_ai_generated(created_at desc);
create index if not exists novel_ai_generated_type_idx    on public.novel_ai_generated(type);

-- Per-user monthly AI generation credits
-- Keyed by (user_id, month) where month is 'YYYY-MM'
create table if not exists public.novel_ai_credits (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references public.novel_users(id) on delete cascade,
  month      text not null,        -- e.g. '2026-07'
  used_short int not null default 0,
  used_long  int not null default 0,
  updated_at timestamptz not null default now(),
  unique (user_id, month)
);

create index if not exists novel_ai_credits_user_month_idx on public.novel_ai_credits(user_id, month);

-- Grant service_role full access to new tables
grant select, insert, update, delete on public.novel_ai_generated to service_role;
grant select, insert, update, delete on public.novel_ai_credits    to service_role;
