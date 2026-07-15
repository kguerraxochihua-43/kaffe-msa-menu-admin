alter table menu.lkp_addons
    add column if not exists is_default boolean not null default false;

with ranked_defaults as (
    select
        addon_id,
        row_number() over (
            partition by tenant_id, addon_group_id
            order by sort_order asc, addon_id asc
        ) as rn
    from menu.lkp_addons
    where is_default = true
      and is_active = true
      and deleted_at is null
)
update menu.lkp_addons a
set is_default = false,
    updated_at = now()
from ranked_defaults rd
where a.addon_id = rd.addon_id
  and rd.rn > 1;

create unique index if not exists uq_menu_lkp_addons_one_active_default
    on menu.lkp_addons (tenant_id, addon_group_id)
    where is_default = true
      and is_active = true
      and deleted_at is null;

with milk_defaults as (
    select
        a.addon_id,
        row_number() over (
            partition by a.tenant_id, a.addon_group_id
            order by a.sort_order asc, a.addon_id asc
        ) as rn
    from menu.lkp_addons a
    join menu.lkp_addon_groups g
      on g.addon_group_id = a.addon_group_id
     and g.tenant_id = a.tenant_id
    where g.deleted_at is null
      and a.deleted_at is null
      and g.name ilike 'tipo de leche'
      and (
          a.name ilike 'entera'
          or a.name ilike 'leche entera'
      )
      and not exists (
          select 1
          from menu.lkp_addons existing_default
          where existing_default.tenant_id = a.tenant_id
            and existing_default.addon_group_id = a.addon_group_id
            and existing_default.is_default = true
            and existing_default.is_active = true
            and existing_default.deleted_at is null
      )
)
update menu.lkp_addons a
set is_default = true,
    updated_at = now()
from milk_defaults md
where a.addon_id = md.addon_id
  and md.rn = 1;
