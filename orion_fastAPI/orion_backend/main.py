import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from orion_backend.api.devices import router as devices_router
from orion_backend.api.enrollment import router as enrollment_router
from orion_backend.config import get_settings
from orion_backend.db.base import build_engine, build_sessionmaker
from orion_backend.errors import OrionDeviceError

logger = logging.getLogger("orion_backend")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    engine = build_engine(settings.database_url)
    app.state.engine = engine
    app.state.sessionmaker = build_sessionmaker(engine)
    try:
        yield
    finally:
        await engine.dispose()


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(title="Orion Device Enrollment Service", lifespan=lifespan)

    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origin_list,
        allow_credentials=False,
        allow_methods=["GET", "POST", "DELETE", "UPDATE"],
        allow_headers=["Content-Type"],
    )

    @app.exception_handler(OrionDeviceError)
    async def handle_device_error(request: Request, exc: OrionDeviceError) -> JSONResponse:
        # Never log exc.message here beyond what OrionDeviceError already
        # exposes — subclasses only ever describe device_id/status, never a
        # credential or its hash.
        return JSONResponse(
            status_code=exc.http_status,
            content={"detail": exc.message, "error_code": exc.error_code},
        )

    app.include_router(devices_router)
    app.include_router(enrollment_router)
    return app


app = create_app()
