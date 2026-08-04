begin;

with tenants_missing_public_menu as (
    select distinct c.tenant_id
    from menu.categories c
    where c.is_active = true
      and c.deleted_at is null
      and not exists (
          select 1
          from menu.menus m
          where m.tenant_id = c.tenant_id
            and m.is_global = true
            and m.is_active = true
            and m.deleted_at is null
      )
)
insert into menu.menus (
    tenant_id,
    name,
    description,
    is_global,
    is_active,
    sort_order
)
select
    tenant_id,
    'Menú principal',
    null,
    true,
    true,
    0
from tenants_missing_public_menu;

with default_public_menu as (
    select distinct on (m.tenant_id)
        m.tenant_id,
        m.menu_id
    from menu.menus m
    where m.is_global = true
      and m.is_active = true
      and m.deleted_at is null
    order by m.tenant_id, m.sort_order asc, m.menu_id asc
), publishable_categories as (
    select
        dpm.menu_id,
        c.tenant_id,
        c.category_id,
        row_number() over (
            partition by c.tenant_id
            order by c.sort_order asc, c.category_id asc
        )::integer as sort_order
    from default_public_menu dpm
    join menu.categories c
      on c.tenant_id = dpm.tenant_id
     and c.is_active = true
     and c.deleted_at is null
)
insert into menu.menu_categories (
    menu_id,
    tenant_id,
    category_id,
    sort_order,
    is_active,
    deleted_at,
    deleted_by_user_id
)
select
    menu_id,
    tenant_id,
    category_id,
    sort_order,
    true,
    null,
    null
from publishable_categories
on conflict (menu_id, category_id) do update set
    sort_order = excluded.sort_order,
    is_active = true,
    deleted_at = null,
    deleted_by_user_id = null,
    updated_at = now();

commit;
