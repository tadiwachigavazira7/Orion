// Service-role Supabase client. This BYPASSES Row Level Security and must only
// ever run server-side inside an Edge Function — never expose the service role
// key to any client. It is used for the privileged writes the app itself is not
// allowed to make (inserting purchases, syncing payment_methods).
import { createClient, type SupabaseClient } from 'npm:@supabase/supabase-js@2';

export function createAdminClient(): SupabaseClient {
  const supabaseUrl = Deno.env.get('SUPABASE_URL');
  const serviceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

  if (!supabaseUrl || !serviceRoleKey) {
    throw new Error('SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY is not set.');
  }

  return createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}
