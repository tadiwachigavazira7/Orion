// Resolves the authenticated Supabase user from the incoming request's JWT.
//
// The caller's Authorization header is forwarded to a Supabase client created
// with the ANON key, so getUser() validates the token exactly as RLS would.
// This proves *who* is calling before we do any privileged (service-role) work.
import { createClient, type User } from 'npm:@supabase/supabase-js@2';

export async function getUserFromRequest(req: Request): Promise<User | null> {
  const authHeader = req.headers.get('Authorization');
  if (!authHeader) return null;

  const supabaseUrl = Deno.env.get('SUPABASE_URL');
  const anonKey = Deno.env.get('SUPABASE_ANON_KEY');
  if (!supabaseUrl || !anonKey) {
    throw new Error('SUPABASE_URL or SUPABASE_ANON_KEY is not set.');
  }

  const client = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: authHeader } },
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const { data, error } = await client.auth.getUser();
  if (error || !data.user) return null;
  return data.user;
}
