import pytest
from sqlalchemy import text
from sqlalchemy.exc import DBAPIError
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import Organization, Site
from tests.helpers import create_organization_and_site


async def test_default_included_slots_and_generated_max_devices(db_session: AsyncSession):
    _, site = await create_organization_and_site(db_session)
    assert (site.included_device_slots, site.extra_device_slots, site.max_devices) == (6, 0, 6)


async def test_extra_and_included_slots_flow_into_max_devices(db_session: AsyncSession):
    _, site = await create_organization_and_site(db_session)
    site.extra_device_slots = 3
    site.included_device_slots = 4
    await db_session.commit()
    await db_session.refresh(site)
    assert site.max_devices == 7


async def test_max_devices_cannot_be_assigned(db_session: AsyncSession):
    org = Organization(organization_code="O", name="O")
    db_session.add(org)
    await db_session.commit()
    db_session.add(Site(site_code="S", organization_id=org.id, max_devices=99))
    with pytest.raises(DBAPIError):  # Postgres: cannot insert into a generated column
        await db_session.commit()
    await db_session.rollback()
    _, site = await create_organization_and_site(db_session, "O2", "S2")
    with pytest.raises(DBAPIError):
        await db_session.execute(text("update sites set max_devices = 99 where id = :i"), {"i": site.id})


async def test_negative_included_slots_rejected(db_session: AsyncSession):
    _, site = await create_organization_and_site(db_session)
    site.included_device_slots = -1
    with pytest.raises(DBAPIError):
        await db_session.commit()
