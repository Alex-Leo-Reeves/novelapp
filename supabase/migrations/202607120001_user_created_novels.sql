-- ──────────────────────────────────────────────────────────────────────────
--  User-Created Novels: public write-your-own-novel feature
--  Allows users to create novels with chapters and publish them publicly
-- ──────────────────────────────────────────────────────────────────────────

-- Published/created novels by users
create table if not exists public.user_created_novels (
  id            uuid primary key default gen_random_uuid(),
  author_id     uuid not null references public.novel_users(id) on delete cascade,
  author_name   text not null default 'Anonymous',
  title         text not null,
  cover_url     text not null default '',
  description   text not null default '',
  status        text not null default 'draft' check (status in ('draft', 'published')),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);

-- Chapters for each user-created novel
create table if not exists public.user_novel_chapters (
  id              uuid primary key default gen_random_uuid(),
  novel_id        uuid not null references public.user_created_novels(id) on delete cascade,
  chapter_number  int not null,
  title           text not null default '',
  content         text not null default '',
  created_at      timestamptz not null default now(),
  unique (novel_id, chapter_number)
);

-- Indexes
create index if not exists user_created_novels_author_idx   on public.user_created_novels(author_id);
create index if not exists user_created_novels_status_idx   on public.user_created_novels(status);
create index if not exists user_created_novels_updated_idx  on public.user_created_novels(updated_at desc);
create index if not exists user_novel_chapters_novel_idx    on public.user_novel_chapters(novel_id);
create index if not exists user_novel_chapters_number_idx   on public.user_novel_chapters(novel_id, chapter_number);

-- Row Level Security
alter table public.user_created_novels enable row level security;
alter table public.user_novel_chapters enable row level security;

-- Anyone can read published novels
create policy "Anyone can read published novels"
  on public.user_created_novels for select
  using (status = 'published');

-- Anyone can read chapters of published novels
create policy "Anyone can read chapters of published novels"
  on public.user_novel_chapters for select
  using (exists (
    select 1 from public.user_created_novels
    where id = novel_id and status = 'published'
  ));

-- Users can manage their own novels
create policy "Users manage own novels"
  on public.user_created_novels for all
  using (author_id = auth.uid());

-- Users can manage their own chapters
create policy "Users manage own chapters"
  on public.user_novel_chapters for all
  using (exists (
    select 1 from public.user_created_novels
    where id = novel_id and author_id = auth.uid()
  ));

-- Grant service_role full access
grant select, insert, update, delete on public.user_created_novels  to service_role;
grant select, insert, update, delete on public.user_novel_chapters  to service_role;
