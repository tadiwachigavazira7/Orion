// Edge Function: create-setup-intent
// -----------------------------------------------------------------------------
// Starts the "save a card" flow. Returns everything the Stripe mobile
// PaymentSheet needs to collect and tokenize a card WITHOUT the app ever
// touching the raw PAN/CVV. The card is persisted to public.payment_methods by
// the stripe-webhook function on `setup_intent.succeeded` — not here — because
// only the webhook is a trustworthy confirmation that the card was attached.
//
// Auth: requires a valid Supabase JWT (verify_jwt = true, the default).
import { corsHeaders, jsonResponse } from '../_shared/cors.ts';
import { stripe, MOBILE_STRIPE_API_VERSION } from '../_shared/stripe.ts';
import { createAdminClient } from '../_shared/supabaseAdmin.ts';
import { getUserFromRequest } from '../_shared/auth.ts';
import { getOrCreateStripeCustomer } from '../_shared/customer.ts';

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders });
  }
  if (req.method !== 'POST') {
    return jsonResponse({ error: 'Method not allowed' }, 405);
  }

  try {
    const user = await getUserFromRequest(req);
    if (!user) {
      return jsonResponse({ error: 'Unauthorized' }, 401);
    }

    const admin = createAdminClient();
    const customerId = await getOrCreateStripeCustomer(admin, user.id, user.email);

    // Ephemeral key authorizes the mobile SDK to act on this customer for a
    // short window. Its apiVersion MUST match the mobile SDK (see stripe.ts).
    const ephemeralKey = await stripe.ephemeralKeys.create(
      { customer: customerId },
      { apiVersion: MOBILE_STRIPE_API_VERSION },
    );

    // usage: 'off_session' so the saved card can later be charged without the
    // customer present (matches how create-payment-intent reuses saved cards).
    const setupIntent = await stripe.setupIntents.create({
      customer: customerId,
      usage: 'off_session',
      metadata: { user_id: user.id },
    });

    return jsonResponse({
      setupIntentClientSecret: setupIntent.client_secret,
      ephemeralKeySecret: ephemeralKey.secret,
      customerId,
      // publishable key is safe to return; the client needs it to init Stripe.
      publishableKey: Deno.env.get('STRIPE_PUBLISHABLE_KEY') ?? null,
    });
  } catch (error) {
    console.error('[create-setup-intent] error:', error);
    return jsonResponse({ error: 'Failed to create setup intent' }, 500);
  }
});
