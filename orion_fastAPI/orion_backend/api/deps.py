from collections.abc import AsyncIterator

from fastapi import Request
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.services.credential_hasher import Argon2CredentialHasher, CredentialHasher

# One hasher instance is enough: PasswordHasher itself holds no mutable
# per-call state, and constructing it repeatedly would re-read Argon2
# parameters on every request for no benefit.
_hasher = Argon2CredentialHasher()


async def get_db(request: Request) -> AsyncIterator[AsyncSession]:
    sessionmaker = request.app.state.sessionmaker
    async with sessionmaker() as session:
        yield session


def get_credential_hasher() -> CredentialHasher:
    return _hasher
