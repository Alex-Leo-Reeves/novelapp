-- ──────────────────────────────────────────────────────────────────────────
--  Fix: Add cover_prompt column if missing (for tables created before the
--  202607050001 migration's CREATE TABLE IF NOT EXISTS ran with a different
--  schema version of novel_ai_generated).
-- ──────────────────────────────────────────────────────────────────────────

alter table if exists public.novel_ai_generated
  add column if not exists cover_prompt text not null default '';

-- Refresh the PostgREST schema cache so it picks up the new column
notify pgrst, 'reload schema';
