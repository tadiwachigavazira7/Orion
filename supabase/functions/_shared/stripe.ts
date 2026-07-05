// Shared Stripe client for all Edge Functions.
//
// The secret key is read from the Edge Function environment (set via
// `supabase secrets set STRIPE_SECRET_KEY=sk_test_...`). It is NEVER shipped to
// the client and must never be prefixed EXPO_PUBLIC_.
import Stripe from 'npm:stripe@17.7.0';

const stripeSecretKey = Deno.env.get('STRIPE_SECRET_KEY');
if (!stripeSecretKey) {
  // Fail fast and loudly at cold start rather than silently mis-charging later.
  throw new Error('STRIPE_SECRET_KEY is not set in the Edge Function environment.');
}

// Deno uses the fetch-based HTTP client (the default Node client is unavailable).
// apiVersion is intentionally left to the SDK default so the pinned SDK and API
// version stay in lockstep; bump the SDK to move API versions deliberately.
export const stripe = new Stripe(stripeSecretKey, {
  httpClient: Stripe.createFetchHttpClient(),
});

// The Stripe API version the MOBILE SDK expects for ephemeral keys. This is
// SEPARATE from the server SDK version and MUST match the version bundled with
// @stripe/stripe-react-native in the app. Update both together.
export const MOBILE_STRIPE_API_VERSION = '2024-06-20';
