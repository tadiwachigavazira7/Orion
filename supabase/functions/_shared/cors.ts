// Shared CORS headers for browser/mobile callers hitting the Edge Functions.
// Tighten Access-Control-Allow-Origin to your app's origin(s) before production
// if you serve the app from the web; native mobile clients are unaffected.
export const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers':
    'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

// Small helper for consistent JSON responses with CORS applied.
export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, 'Content-Type': 'application/json' },
  });
}
