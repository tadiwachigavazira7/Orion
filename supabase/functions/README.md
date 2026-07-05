# Stripe Edge Functions

Three functions handle Stripe payments. Business rules live server-side; the app
never sees the service role key or the Stripe secret key.

| Function | Auth | Purpose |
| --- | --- | --- |
| `create-setup-intent` | Supabase JWT | Save-card flow. Returns a SetupIntent + ephemeral key for the mobile PaymentSheet. |
| `create-payment-intent` | Supabase JWT | Creates a PaymentIntent + a `pending` `purchases` row. |
| `stripe-webhook` | Stripe signature (no JWT) | The only place purchases are finalized and cards are synced. |

## Flow

1. **Save a card** → app calls `create-setup-intent` → PaymentSheet collects the
   card → Stripe fires `setup_intent.succeeded` → `stripe-webhook` writes the
   tokenized card to `public.payment_methods`.
2. **Pay** → app calls `create-payment-intent` (optionally with a saved
   `payment_methods.id`) → a `pending` purchase is inserted → the charge runs →
   Stripe fires `payment_intent.succeeded|payment_failed` → `stripe-webhook`
   moves the purchase to `completed` / `failed`.

Purchases are **never** finalized by the client — only by the signature-verified
webhook. This matches the RLS design (users have read-only access to purchases).

## Required migration

`supabase/migrations/20260703000000_stripe_integration_columns.sql` adds
`profiles.stripe_customer_id` and `purchases.stripe_payment_intent_id`. Apply it
before deploying:

```bash
supabase db push
```

## Secrets (never committed)

```bash
supabase secrets set STRIPE_SECRET_KEY=sk_test_...
supabase secrets set STRIPE_WEBHOOK_SECRET=whsec_...
supabase secrets set STRIPE_PUBLISHABLE_KEY=pk_test_...   # optional, safe to expose
```

`SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SUPABASE_SERVICE_ROLE_KEY` are provided
to Edge Functions automatically.

## Deploy

```bash
supabase functions deploy create-setup-intent
supabase functions deploy create-payment-intent
supabase functions deploy stripe-webhook
```

Then register the webhook URL
(`https://<project-ref>.functions.supabase.co/stripe-webhook`) in the Stripe
Dashboard, subscribing to: `payment_intent.succeeded`,
`payment_intent.payment_failed`, `payment_intent.processing`,
`payment_intent.canceled`, `setup_intent.succeeded`, `payment_method.detached`.
Copy the signing secret into `STRIPE_WEBHOOK_SECRET`.

## ⚠️ Known MVP security gap — trusted amount source

`create-payment-intent` currently trusts the **client-supplied** order amount
(see `deriveOrderAmounts`). A client can therefore choose what it pays. This is
acceptable in Stripe **test mode only**. Before production, replace it with a
server-side price source (products / cart / RFID-scan-session table) so the
amount is computed from trusted data and the client can only reference line
items by id.

## Mobile SDK version

`MOBILE_STRIPE_API_VERSION` in `_shared/stripe.ts` must match the Stripe API
version bundled with `@stripe/stripe-react-native`. Update both together.
