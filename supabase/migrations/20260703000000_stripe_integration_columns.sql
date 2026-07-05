-- Orion — Stripe integration columns
-- Adds the two stable references the Stripe Edge Functions need. No new tables
-- and no RLS changes: both columns sit on existing, already-RLS-protected tables.
--
--   profiles.stripe_customer_id        — one Stripe Customer per user, reused by
--                                        create-setup-intent / create-payment-intent
--                                        so we never create duplicate customers.
--   purchases.stripe_payment_intent_id — links a purchase to its Stripe
--                                        PaymentIntent so stripe-webhook can
--                                        finalize the correct order idempotently.
--
-- Neither value is a secret (they are opaque ids; nothing can be charged without
-- the server-only STRIPE_SECRET_KEY), so exposing stripe_customer_id to the owner
-- via the existing profiles RLS is acceptable.

-- =============================================================================
-- profiles.stripe_customer_id
-- =============================================================================
alter table public.profiles
  add column if not exists stripe_customer_id text;

comment on column public.profiles.stripe_customer_id is
  'Stripe Customer id (cus_...). Set server-side by the Stripe Edge Functions; one per user.';

-- One profile per Stripe customer (partial: many rows may be null pre-onboarding).
create unique index if not exists uq_profiles_stripe_customer_id
  on public.profiles (stripe_customer_id)
  where stripe_customer_id is not null;

-- =============================================================================
-- purchases.stripe_payment_intent_id
-- =============================================================================
alter table public.purchases
  add column if not exists stripe_payment_intent_id text;

comment on column public.purchases.stripe_payment_intent_id is
  'Stripe PaymentIntent id (pi_...). Set by create-payment-intent; used by stripe-webhook to finalize status.';

-- One purchase per PaymentIntent; also the lookup key the webhook matches on.
create unique index if not exists uq_purchases_stripe_payment_intent_id
  on public.purchases (stripe_payment_intent_id)
  where stripe_payment_intent_id is not null;
