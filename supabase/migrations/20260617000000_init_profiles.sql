-- Orion MVP — consumer profiles (base migration)
-- One row per consumer user, 1:1 with auth.users.
--
-- Auth itself (signup/login/logout/password reset) is handled entirely by
-- Supabase Auth. This table holds only app profile data. Email is intentionally
-- NOT duplicated here — it lives in auth.users to avoid drift.
--
-- This is the base migration: it also defines the shared set_updated_at()
-- trigger function reused by later migrations (payment_methods, purchases, ...).

-- =============================================================================
-- Shared helper: keep updated_at fresh on any row update
-- =============================================================================
create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

comment on function public.set_updated_at is 'Trigger function: sets updated_at = now() before each row update. Shared across tables.';

-- =============================================================================
-- Table
-- =============================================================================
create table public.profiles (
  id            uuid        primary key references auth.users (id) on delete cascade,
  full_name     text,
  username      text,
  phone_number  text,
  date_of_birth date,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),

  constraint uq_profiles_username unique (username)
);

comment on table  public.profiles is 'Consumer profile data, 1:1 with auth.users. No email (lives in auth.users) and no avatar.';
comment on column public.profiles.id is 'FK to auth.users(id); also the profile PK (1:1).';

-- =============================================================================
-- Keep updated_at fresh
-- =============================================================================
create trigger trg_profiles_set_updated_at
  before update on public.profiles
  for each row
  execute function public.set_updated_at();

-- =============================================================================
-- Auto-create a profile row when a new auth user signs up
-- =============================================================================
-- security definer so it can insert into public.profiles regardless of the
-- caller. Reads optional profile fields from the signup metadata.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.profiles (id, full_name, username, phone_number, date_of_birth)
  values (
    new.id,
    new.raw_user_meta_data ->> 'full_name',
    new.raw_user_meta_data ->> 'username',
    new.raw_user_meta_data ->> 'phone_number',
    (new.raw_user_meta_data ->> 'date_of_birth')::date
  );
  return new;
end;
$$;

comment on function public.handle_new_user is 'Creates a public.profiles row for each new auth.users signup, pulling optional fields from raw_user_meta_data.';

create trigger on_auth_user_created
  after insert on auth.users
  for each row
  execute function public.handle_new_user();

-- =============================================================================
-- Row Level Security — owner-only access (no DELETE; account deletion is an
-- admin / Edge Function concern)
-- =============================================================================
alter table public.profiles enable row level security;

create policy "select_own_profile"
  on public.profiles
  for select
  to authenticated
  using (auth.uid() = id);

create policy "insert_own_profile"
  on public.profiles
  for insert
  to authenticated
  with check (auth.uid() = id);

create policy "update_own_profile"
  on public.profiles
  for update
  to authenticated
  using (auth.uid() = id)
  with check (auth.uid() = id);

-- =============================================================================
-- Indexes
-- =============================================================================
-- PK already indexes id. uq_profiles_username covers username lookups.
