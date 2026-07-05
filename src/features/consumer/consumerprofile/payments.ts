import type { SupabaseClient } from '@supabase/supabase-js';
import { supabase } from '../../../lib/supabase';
import { isValidCheckoutAmount, unwrapInvokeResult } from './paymentsCore';

// Client service for the Stripe Edge Functions. This is the app-side counterpart
// to supabase/functions/{create-setup-intent,create-payment-intent}. It only
// starts flows and returns the secrets/ids the Stripe mobile SDK needs — it never
// sees card data, the service role key, or the Stripe secret key.
//
// The Supabase client is injected (defaulting to the shared instance) so this
// service can be exercised with a fake client in tests.

// Mirror of the create-setup-intent Edge Function response.
export interface SetupIntentResult {
  setupIntentClientSecret: string;
  ephemeralKeySecret: string;
  customerId: string;
  publishableKey: string | null;
}

// Mirror of the create-payment-intent Edge Function response.
export interface PaymentIntentResult {
  clientSecret: string;
  paymentIntentStatus: string;
  purchaseId: string;
  orderNumber: string;
  customerId: string;
}

export interface CheckoutInput {
  subtotal: number;
  tax: number;
  total: number;
  currency?: string;
  // A saved card (public.payment_methods.id) to charge, or omit to collect a new one.
  paymentMethodId?: string;
}

// Starts the save-card flow. Present the returned secret with the Stripe mobile
// PaymentSheet; the card is persisted to payment_methods by the stripe-webhook.
export async function createSetupIntent(
  client: SupabaseClient = supabase,
): Promise<SetupIntentResult> {
  const { data, error } = await client.functions.invoke<SetupIntentResult>(
    'create-setup-intent',
    { body: {} },
  );
  return unwrapInvokeResult('create-setup-intent', data, error);
}

// Starts a charge. Returns the PaymentIntent client secret for the app to
// confirm, plus the created purchase's id/order number.
export async function createPaymentIntent(
  input: CheckoutInput,
  client: SupabaseClient = supabase,
): Promise<PaymentIntentResult> {
  if (!isValidCheckoutAmount(input.subtotal, input.tax, input.total)) {
    // Fail fast before the network round-trip; the server re-validates anyway.
    throw new Error('Invalid checkout amount');
  }

  const { data, error } = await client.functions.invoke<PaymentIntentResult>(
    'create-payment-intent',
    { body: input },
  );
  return unwrapInvokeResult('create-payment-intent', data, error);
}
