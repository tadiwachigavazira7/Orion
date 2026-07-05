// Edge Function: stripe-webhook
// -----------------------------------------------------------------------------
// The single source of truth for payment outcomes. Stripe calls this endpoint
// (not the app) after events happen, so it is the only trustworthy place to:
//   • finalize a purchase to completed / failed / processing / cancelled
//   • persist a saved card to public.payment_methods after it is attached
//   • remove a card from public.payment_methods when detached
//
// Auth: this function MUST run with verify_jwt = false (Stripe does not send a
// Supabase JWT). It is instead authenticated by verifying the Stripe signature
// against STRIPE_WEBHOOK_SECRET — an unsigned/forged request is rejected.
import type { SupabaseClient } from 'npm:@supabase/supabase-js@2';
import type Stripe from 'npm:stripe@17.7.0';
import { stripe } from '../_shared/stripe.ts';
import { createAdminClient } from '../_shared/supabaseAdmin.ts';

const webhookSecret = Deno.env.get('STRIPE_WEBHOOK_SECRET');

Deno.serve(async (req) => {
  if (req.method !== 'POST') {
    return new Response('Method not allowed', { status: 405 });
  }
  if (!webhookSecret) {
    console.error('[stripe-webhook] STRIPE_WEBHOOK_SECRET is not set.');
    return new Response('Server misconfigured', { status: 500 });
  }

  const signature = req.headers.get('stripe-signature');
  if (!signature) {
    return new Response('Missing stripe-signature header', { status: 400 });
  }

  // Verify the signature against the RAW body. constructEventAsync is the
  // Deno/fetch-compatible (async, WebCrypto) verifier — the sync variant throws.
  const rawBody = await req.text();
  let event: Stripe.Event;
  try {
    event = await stripe.webhooks.constructEventAsync(rawBody, signature, webhookSecret);
  } catch (error) {
    console.error('[stripe-webhook] signature verification failed:', error);
    return new Response('Invalid signature', { status: 400 });
  }

  const admin = createAdminClient();

  try {
    switch (event.type) {
      case 'payment_intent.succeeded':
        await finalizePurchase(admin, event.data.object as Stripe.PaymentIntent, 'completed');
        break;
      case 'payment_intent.processing':
        await finalizePurchase(admin, event.data.object as Stripe.PaymentIntent, 'processing');
        break;
      case 'payment_intent.payment_failed':
        await finalizePurchase(admin, event.data.object as Stripe.PaymentIntent, 'failed');
        break;
      case 'payment_intent.canceled':
        await finalizePurchase(admin, event.data.object as Stripe.PaymentIntent, 'cancelled');
        break;
      case 'setup_intent.succeeded':
        await saveCardFromSetupIntent(admin, event.data.object as Stripe.SetupIntent);
        break;
      case 'payment_method.detached':
        await removeCard(admin, event.data.object as Stripe.PaymentMethod);
        break;
      default:
        // Unhandled events are fine — acknowledge so Stripe stops retrying.
        console.log(`[stripe-webhook] ignoring event: ${event.type}`);
    }
  } catch (error) {
    // Return 500 so Stripe RETRIES — better than acknowledging a write we failed
    // to persist. Handlers are written to be idempotent, so retries are safe.
    console.error(`[stripe-webhook] handler for ${event.type} failed:`, error);
    return new Response('Handler error', { status: 500 });
  }

  return new Response(JSON.stringify({ received: true }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
});

// Move a purchase to a terminal/interim status. Matched by the PaymentIntent id
// recorded in create-payment-intent. Idempotent: re-delivering the same event
// simply re-sets the same status.
async function finalizePurchase(
  admin: SupabaseClient,
  paymentIntent: Stripe.PaymentIntent,
  status: 'completed' | 'processing' | 'failed' | 'cancelled',
): Promise<void> {
  const update: Record<string, unknown> = { status };
  if (status === 'completed') {
    // Stripe timestamps are unix seconds.
    update.purchased_at = new Date(paymentIntent.created * 1000).toISOString();
  }

  const { data, error } = await admin
    .from('purchases')
    .update(update)
    .eq('stripe_payment_intent_id', paymentIntent.id)
    .select('id');

  if (error) {
    throw new Error(`Failed to update purchase for ${paymentIntent.id}: ${error.message}`);
  }
  if (!data || data.length === 0) {
    // Not necessarily an error (event could arrive before the row is linked),
    // but worth logging so lost updates are visible.
    console.warn(`[stripe-webhook] no purchase matched intent ${paymentIntent.id}`);
  }
}

// Persist a newly-saved card. Runs on setup_intent.succeeded because that event
// carries our metadata.user_id (set in create-setup-intent), letting us attribute
// the card to the right user. Only safe, non-sensitive display fields + the
// processor token are stored — never PAN/CVV.
async function saveCardFromSetupIntent(
  admin: SupabaseClient,
  setupIntent: Stripe.SetupIntent,
): Promise<void> {
  const userId = setupIntent.metadata?.user_id;
  const paymentMethodId =
    typeof setupIntent.payment_method === 'string'
      ? setupIntent.payment_method
      : setupIntent.payment_method?.id;
  const customerId =
    typeof setupIntent.customer === 'string'
      ? setupIntent.customer
      : setupIntent.customer?.id;

  if (!userId || !paymentMethodId || !customerId) {
    console.warn('[stripe-webhook] setup_intent.succeeded missing user/pm/customer; skipping');
    return;
  }

  const paymentMethod = await stripe.paymentMethods.retrieve(paymentMethodId);
  const card = paymentMethod.card;

  // First saved card becomes the default.
  const { count } = await admin
    .from('payment_methods')
    .select('id', { count: 'exact', head: true })
    .eq('user_id', userId);
  const isDefault = (count ?? 0) === 0;

  // Upsert on the (user_id, provider, provider_payment_method_id) unique key so a
  // redelivered event does not create a duplicate row.
  const { error } = await admin.from('payment_methods').upsert(
    {
      user_id: userId,
      provider: 'stripe',
      provider_customer_id: customerId,
      provider_payment_method_id: paymentMethodId,
      card_brand: card?.brand ?? null,
      card_last4: card?.last4 ?? null,
      exp_month: card?.exp_month ?? null,
      exp_year: card?.exp_year ?? null,
      name_on_card: paymentMethod.billing_details?.name ?? null,
      billing_zip: paymentMethod.billing_details?.address?.postal_code ?? null,
      is_default: isDefault,
    },
    { onConflict: 'user_id,provider,provider_payment_method_id' },
  );

  if (error) {
    throw new Error(`Failed to save payment method ${paymentMethodId}: ${error.message}`);
  }
}

// Remove a card when it is detached in Stripe, keeping our table in sync.
async function removeCard(
  admin: SupabaseClient,
  paymentMethod: Stripe.PaymentMethod,
): Promise<void> {
  const { error } = await admin
    .from('payment_methods')
    .delete()
    .eq('provider_payment_method_id', paymentMethod.id);

  if (error) {
    throw new Error(`Failed to remove payment method ${paymentMethod.id}: ${error.message}`);
  }
}
