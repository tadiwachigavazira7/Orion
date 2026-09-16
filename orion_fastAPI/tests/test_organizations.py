import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import Organization


async def test_organization_inserts_successfully(db_session: AsyncSession):
    org = Organization(organization_code="TEST-ORG-001", name="Test Organization")
    db_session.add(org)
    await db_session.commit()

    result = await db_session.execute(
        select(Organization).where(Organization.organization_code == "TEST-ORG-001")
    )
    saved = result.scalar_one()

    assert saved.name == "Test Organization"
    assert saved.id is not None
    assert saved.created_at is not None
    assert saved.updated_at is not None


async def test_duplicate_organization_code_is_rejected(db_session: AsyncSession):
    db_session.add(Organization(organization_code="TEST-ORG-001", name="Test Organization"))
    await db_session.commit()

    db_session.add(Organization(organization_code="TEST-ORG-001", name="Different Name"))
    with pytest.raises(IntegrityError):
        await db_session.commit()


async def test_missing_organization_code_is_rejected(db_session: AsyncSession):
    db_session.add(Organization(organization_code=None, name="Test Organization"))
    with pytest.raises(IntegrityError):
        await db_session.commit()


async def test_missing_name_is_rejected(db_session: AsyncSession):
    db_session.add(Organization(organization_code="TEST-ORG-002", name=None))
    with pytest.raises(IntegrityError):
        await db_session.commit()
