-- Orion MVP — products (price source of truth) + purchase line items
-- =============================================================================
-- WHY: create-payment-intent previously trusted the CLIENT for the charge
-- amount, which is manipulable. These tables give the server its OWN prices so
-- the Edge Function computes the amount from trusted data; the client may only
-- reference products by id + quantity, never set a price or total.
--
--   products        — catalog with server-authoritative prices. Managed by a
--                     trusted backend (service_role); consumers have read-only
--                     access to ACTIVE products.
--   purchase_items  — the line items of a purchase, each capturing a price
--                     SNAPSHOT at purchase time so history is stable even if the
--                     catalog price later changes. Written only by the backend.

-- =============================================================================
-- products
-- =============================================================================
create table public.products (
  id          uuid          primary key default gen_random_uuid(),
  sku         text          not null,
  name        text          not null,
  description text,
  -- numeric(12,2): exact money, no float rounding.
  unit_price  numeric(12,2) not null,
  currency    char(3)       not null default 'USD',
  is_active   boolean       not null default true,
  created_at  timestamptz   not null default now(),
  updated_at  timestamptz   not null default now(),

  constraint uq_products_sku      unique (sku),
  constraint sku_not_blank        check (length(btrim(sku)) > 0),
  constraint name_not_blank       check (length(btrim(name)) > 0),
  constraint unit_price_non_neg   check (unit_price >= 0),
  constraint products_currency_fmt check (currency ~ '^[A-Z]{3}$')
);

comment on table  public.products is 'Product catalog. Server-authoritative prices; the source of truth for charge amounts. Written only by a trusted backend.';
comment on column public.products.unit_price is 'Price per unit in the product currency. numeric(12,2) for exact money.';
comment on column public.products.is_active is 'Only active products are purchasable / visible to consumers.';

create trigger trg_products_set_updated_at
  before update on public.products
  for each row
  execute function public.set_updated_at();

alter table public.products enable row level security;

-- Consumers may read only ACTIVE products (the catalog). No client writes: the
-- catalog is managed by the backend via the service_role key (bypasses RLS).
create policy "select_active_products"
  on public.products
  for select
  to authenticated
  using (is_active);

-- Fast catalog listing (the common query filters by is_active).
create index idx_products_is_active on public.products (is_active) where is_active;

-- =============================================================================
-- purchase_items
-- =============================================================================
create table public.purchase_items (
  id            uuid          primary key default gen_random_uuid(),

  -- Parent order. Items die with their purchase.
  purchase_id   uuid          not null references public.purchases (id) on delete cascade,

  -- Which product this line was. Nullable + SET NULL so deleting a catalog
  -- product does not destroy historical orders; the snapshot fields preserve
  -- what was actually bought.
  product_id    uuid          references public.products (id) on delete set null,

  -- Snapshots captured at purchase time (immutable history).
  name_snapshot text          not null,
  unit_price    numeric(12,2) not null,
  quantity      integer       not null,
  line_total    numeric(12,2) not null,
  currency      char(3)       not null default 'USD',
  created_at    timestamptz   not null default now(),

  constraint pi_name_not_blank      check (length(btrim(name_snapshot)) > 0),
  constraint pi_unit_price_non_neg  check (unit_price >= 0),
  constraint pi_quantity_positive   check (quantity > 0),
  constraint pi_line_total_non_neg  check (line_total >= 0),
  constraint pi_currency_fmt        check (currency ~ '^[A-Z]{3}$')
);

comment on table  public.purchase_items is 'Line items per purchase, with price snapshots taken at purchase time. Written only by the backend; users have read-only access to items on their own purchases.';
comment on column public.purchase_items.name_snapshot is 'Product name at purchase time (survives catalog changes / deletion).';
comment on column public.purchase_items.unit_price is 'Product unit price at purchase time (snapshot).';

alter table public.purchase_items enable row level security;

-- Users may read line items only for purchases they own. No client writes.
create policy "select_own_purchase_items"
  on public.purchase_items
  for select
  to authenticated
  using (
    exists (
      select 1
      from public.purchases p
      where p.id = purchase_items.purchase_id
        and p.user_id = auth.uid()
    )
  );

-- Most common access: fetch all items for a purchase.
create index idx_purchase_items_purchase_id on public.purchase_items (purchase_id);
-- FK helper for product deletes / product-level reporting.
create index idx_purchase_items_product_id  on public.purchase_items (product_id)
  where product_id is not null;
