// Edge Function: create-payment-intent
// -----------------------------------------------------------------------------
// Creates a Stripe PaymentIntent for an order and inserts the matching (pending)
// row into public.purchases. The purchase is only ever FINALIZED to
// completed/failed by the stripe-webhook function — this function just kicks the
// charge off and records the intent.
//
// Auth: requires a valid Supabase JWT (verify_jwt = true, the default).
//
// ⚠️  SECURITY — TRUSTED AMOUNT SOURCE ⚠️
// This function currently accepts the order amount from the CLIENT (see
// deriveOrderAmounts). A malicious client can therefore choose what it pays.
// This is an MVP shortcut ONLY, acceptable in Stripe test mode. Before going to
// production this MUST be replaced with a server-side price source (a products /
// cart / RFID-scan-session table the server reads), so the amount is computed
// here from trusted data and the client can only reference line items by id.
import { corsHeaders, jsonResponse } from '../_shared/cors.ts';
import { stripe } from '../_shared/stripe.ts';
import { createAdminClient } from '../_shared/supabaseAdmin.ts';
import { getUserFromRequest } from '../_shared/auth.ts';
import { getOrCreateStripeCustomer } from '../_shared/customer.ts';
import {
  deriveOrderAmounts,
  generateOrderNumber,
  toMinorUnits,
  type OrderAmountsInput,
} from '../_shared/orderAmounts.ts';

interface CreatePaymentIntentBody extends OrderAmountsInput {
  // Optional: a saved card (public.payment_methods.id) to charge off-session.
  // If omitted, the client collects a card and confirms the returned secret.
  paymentMethodId?: string;
}

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

    let body: CreatePaymentIntentBody;
    try {
      body = await req.json();
    } catch {
      return jsonResponse({ error: 'Invalid JSON body' }, 400);
    }

    // Validate + derive the amount. See the security note at the top of the file.
    const amounts = deriveOrderAmounts(body);
    if ('error' in amounts) {
      return jsonResponse({ error: amounts.error }, 400);
    }

    const admin = createAdminClient();

    // If a saved card was chosen, confirm it belongs to THIS user before using
    // its processor token. The query is scoped by user_id so another user's card
    // id can never be charged.
    let providerPaymentMethodId: string | null = null;
    if (body.paymentMethodId) {
      const { data: card, error: cardError } = await admin
        .from('payment_methods')
        .select('id, provider_payment_method_id')
        .eq('id', body.paymentMethodId)
        .eq('user_id', user.id)
        .maybeSingle();

      if (cardError) {
        console.error('[create-payment-intent] card lookup failed:', cardError);
        return jsonResponse({ error: 'Failed to load payment method' }, 500);
      }
      if (!card) {
        return jsonResponse({ error: 'Payment method not found' }, 404);
      }
      providerPaymentMethodId = card.provider_payment_method_id;
    }

    const customerId = await getOrCreateStripeCustomer(admin, user.id, user.email);
    const orderNumber = generateOrderNumber();

    // Insert the pending purchase FIRST so we have a stable id to thread through
    // the PaymentIntent metadata (the webhook maps events back via this + the
    // stripe_payment_intent_id set below).
    const { data: purchase, error: insertError } = await admin
      .from('purchases')
      .insert({
        order_number: orderNumber,
        user_id: user.id,
        payment_method_id: body.paymentMethodId ?? null,
        status: 'pending',
        subtotal: amounts.subtotal,
        tax: amounts.tax,
        total: amounts.total,
        currency: amounts.currency,
      })
      .select('id')
      .single();

    if (insertError || !purchase) {
      console.error('[create-payment-intent] purchase insert failed:', insertError);
      return jsonResponse({ error: 'Failed to create order' }, 500);
    }

    try {
      const paymentIntent = await stripe.paymentIntents.create({
        amount: toMinorUnits(amounts.total, amounts.currency),
        currency: amounts.currency.toLowerCase(),
        customer: customerId,
        metadata: { user_id: user.id, purchase_id: purchase.id, order_number: orderNumber },
        ...(providerPaymentMethodId
          ? {
              // Charging a saved card: confirm immediately, off-session.
              payment_method: providerPaymentMethodId,
              off_session: true,
              confirm: true,
            }
          : {
              // New card: let the client confirm via the returned client secret.
              automatic_payment_methods: { enabled: true },
            }),
      });

      const { error: linkError } = await admin
        .from('purchases')
        .update({ stripe_payment_intent_id: paymentIntent.id })
        .eq('id', purchase.id);

      if (linkError) {
        // The charge exists but we couldn't link it; the webhook would then be
        // unable to finalize this row. Surface it rather than swallow.
        console.error('[create-payment-intent] failed to link payment intent:', linkError);
      }

      return jsonResponse({
        clientSecret: paymentIntent.client_secret,
        paymentIntentStatus: paymentIntent.status,
        purchaseId: purchase.id,
        orderNumber,
        customerId,
      });
    } catch (stripeError) {
      // PaymentIntent creation/confirmation failed (e.g. card declined). Mark the
      // pending order as failed so it isn't left dangling.
      console.error('[create-payment-intent] stripe error:', stripeError);
      await admin.from('purchases').update({ status: 'failed' }).eq('id', purchase.id);
      return jsonResponse(
        { error: 'Payment could not be processed', purchaseId: purchase.id },
        402,
      );
    }
  } catch (error) {
    console.error('[create-payment-intent] error:', error);
    return jsonResponse({ error: 'Failed to create payment intent' }, 500);
  }
});
