// Resolves the single Stripe Customer for a given app user, creating it on first
// use and persisting the id on profiles.stripe_customer_id so it is reused
// across every future SetupIntent / PaymentIntent (avoids duplicate customers).
import type { SupabaseClient } from 'npm:@supabase/supabase-js@2';
import { stripe } from './stripe.ts';

export async function getOrCreateStripeCustomer(
  admin: SupabaseClient,
  userId: string,
  email: string | undefined,
): Promise<string> {
  const { data: profile, error } = await admin
    .from('profiles')
    .select('stripe_customer_id')
    .eq('id', userId)
    .single();

  if (error) {
    throw new Error(`Failed to load profile for user ${userId}: ${error.message}`);
  }

  if (profile?.stripe_customer_id) {
    return profile.stripe_customer_id;
  }

  // No customer yet — create one and remember it. metadata.user_id lets us map
  // Stripe webhook events (which carry the customer, not our user id) back to
  // the owning user.
  const customer = await stripe.customers.create({
    email,
    metadata: { user_id: userId },
  });

  const { error: updateError } = await admin
    .from('profiles')
    .update({ stripe_customer_id: customer.id })
    .eq('id', userId);

  if (updateError) {
    // The customer exists in Stripe but we failed to persist it. Surface this —
    // silently continuing would create a duplicate customer on the next call.
    throw new Error(
      `Created Stripe customer ${customer.id} but failed to persist it: ${updateError.message}`,
    );
  }

  return customer.id;
}
