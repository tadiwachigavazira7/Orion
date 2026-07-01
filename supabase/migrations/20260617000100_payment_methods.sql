-- Orion MVP — saved payment methods
-- Stores ONLY tokenized references from a PCI-compliant processor (e.g. Stripe)
-- plus non-sensitive display fields. The raw card number (PAN), full expiration
-- secret, and CVV are NEVER stored here — they live with the processor.
-- Storing CVV is prohibited by PCI-DSS; storing the PAN is intentionally avoided.

create table public.payment_methods (
  id                          uuid        primary key default gen_random_uuid(),
  user_id                     uuid        not null references auth.users (id) on delete cascade,
  provider                    text        not null,
  provider_customer_id        text        not null,
  provider_payment_method_id  text        not null,
  card_brand                  text,
  card_last4                  text,
  exp_month                   smallint,
  exp_year                    smallint,
  name_on_card                text,
  billing_zip                 text,
  is_default                  boolean     not null default false,
  created_at                  timestamptz not null default now(),
  updated_at                  timestamptz not null default now(),

  -- Basic shape guards for the safe display fields.
  constraint card_last4_format   check (card_last4 is null or card_last4 ~ '^[0-9]{4}$'),
  constraint exp_month_range     check (exp_month is null or exp_month between 1 and 12),
  constraint exp_year_range      check (exp_year  is null or exp_year between 2000 and 2100),
  -- The same processor token should not be saved twice for the same user.
  constraint uq_user_provider_pm unique (user_id, provider, provider_payment_method_id)
);

comment on table  public.payment_methods is 'Tokenized saved cards. No PAN/CVV — only processor tokens + safe display fields.';
comment on column public.payment_methods.provider_payment_method_id is 'Processor token used to charge the card; the actual sensitive data stays with the processor.';
comment on column public.payment_methods.card_last4 is 'Last 4 digits only — safe to store under PCI-DSS for display.';

-- =============================================================================
-- Keep updated_at fresh (reuses the function created in the profiles migration)
-- =============================================================================
create trigger trg_payment_methods_set_updated_at
  before update on public.payment_methods
  for each row
  execute function public.set_updated_at();

-- =============================================================================
-- Row Level Security — owner-only access
-- =============================================================================
alter table public.payment_methods enable row level security;

create policy "select_own_payment_methods"
  on public.payment_methods
  for select
  using (auth.uid() = user_id);

create policy "insert_own_payment_methods"
  on public.payment_methods
  for insert
  with check (auth.uid() = user_id);

create policy "update_own_payment_methods"
  on public.payment_methods
  for update
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "delete_own_payment_methods"
  on public.payment_methods
  for delete
  using (auth.uid() = user_id);

-- =============================================================================
-- Indexes
-- =============================================================================
-- Fast lookup of a user's saved cards (every list query filters by user_id).
create index idx_payment_methods_user_id on public.payment_methods (user_id);

-- Enforce at most ONE default card per user (partial unique index).
create unique index uq_one_default_per_user
  on public.payment_methods (user_id)
  where is_default;
