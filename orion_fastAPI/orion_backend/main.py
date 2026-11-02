import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from orion_backend.api.devices import router as devices_router
from orion_backend.api.enrollment import router as enrollment_router
from orion_backend.config import Settings, get_settings
from orion_backend.db.base import build_engine, build_sessionmaker
from orion_backend.errors import OrionDeviceError

logger = logging.getLogger("orion_backend")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings: Settings = app.state.settings
    engine = build_engine(settings.database_url)
    app.state.engine = engine
    app.state.sessionmaker = build_sessionmaker(engine)
    try:
        yield
    finally:
        await engine.dispose()


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    app = FastAPI(title="Orion Device Enrollment Service", lifespan=lifespan)
    app.state.settings = settings

    # CORS is not authentication; it only governs which browser origins may
    # call the API. Origins are explicit (CORS_ORIGINS, empty = none) and the
    # only method the remaining public routes use is POST.
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origin_list,
        allow_credentials=False,
        allow_methods=["POST"],
        allow_headers=["Content-Type"],
    )

    @app.exception_handler(OrionDeviceError)
    async def handle_device_error(request: Request, exc: OrionDeviceError) -> JSONResponse:
        # Never log exc.message here beyond what OrionDeviceError already
        # exposes — subclasses only ever describe device_id/status/codes,
        # never a credential, an authorization secret, or a hash of either.
        return JSONResponse(
            status_code=exc.http_status,
            content={"detail": exc.message, "error_code": exc.error_code},
        )

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        # FastAPI's default 422 body echoes the submitted input, which here
        # can be an enrollment authorization or a device credential. Report
        # only which fields failed, never their values.
        fields = [
            ".".join(str(part) for part in error.get("loc", ())) for error in exc.errors()
        ]
        return JSONResponse(
            status_code=422,
            content={
                "detail": "Invalid request body.",
                "error_code": "invalid_request",
                "fields": fields,
            },
        )

    app.include_router(devices_router)
    app.include_router(enrollment_router)
    return app


app = create_app()
