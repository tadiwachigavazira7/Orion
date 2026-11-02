"""Domain-level errors for device enrollment/verification and operator actions.

Route handlers never construct HTTP responses for these cases directly —
they let the service layer raise one of these, and the FastAPI exception
handlers registered in main.py translate them into a consistent JSON shape:
{"detail": ..., "error_code": ...}. This keeps status-code decisions in one
place instead of scattered across route handlers.

The operator CLI (orion_backend/cli.py) uses the same types: it prints
error_code + message and exits nonzero. No message ever contains a
credential, an enrollment-authorization secret, or a hash of either.
"""


class OrionDeviceError(Exception):
    error_code: str = "device_error"
    http_status: int = 500

    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


class DeviceAlreadyEnrolledError(OrionDeviceError):
    error_code = "device_already_enrolled"
    http_status = 409

    def __init__(self, device_id: str):
        super().__init__(f"Device '{device_id}' is already enrolled.")


class DeviceNotFoundError(OrionDeviceError):
    error_code = "device_not_found"
    http_status = 404

    def __init__(self, device_id: str):
        super().__init__(f"Device '{device_id}' was not found.")


class DeviceRevokedError(OrionDeviceError):
    error_code = "device_revoked"
    http_status = 403

    def __init__(self, device_id: str):
        super().__init__(f"Device '{device_id}' has been revoked.")


class InvalidCredentialError(OrionDeviceError):
    error_code = "invalid_credential"
    http_status = 401

    def __init__(self, device_id: str):
        super().__init__(f"Credential for device '{device_id}' did not verify.")


class OrganizationNotFoundError(OrionDeviceError):
    error_code = "organization_not_found"
    http_status = 404

    def __init__(self, organization_code: str):
        super().__init__(f"Organization '{organization_code}' was not found.")


class SiteNotFoundError(OrionDeviceError):
    error_code = "site_not_found"
    http_status = 404

    def __init__(self, site_code: str):
        super().__init__(f"Site '{site_code}' was not found.")


class SiteOrganizationMismatchError(OrionDeviceError):
    """The site code exists, but not under the given organization.

    Kept distinct from SiteNotFoundError - not a credential-secrecy concern
    (organization/site codes are not secrets), so the more specific error is
    safe to surface and independently testable.
    """

    error_code = "site_organization_mismatch"
    http_status = 404

    def __init__(self, site_code: str, organization_code: str):
        super().__init__(
            f"Site '{site_code}' does not belong to organization '{organization_code}'."
        )


class InvalidEnrollmentAuthorizationError(OrionDeviceError):
    """The enrollment authorization is unusable for this request.

    Deliberately one generic error for every cause (unknown secret, expired,
    already consumed, invalidated, issued for another device, issued for
    another site/organization) so a caller cannot probe which check failed.
    """

    error_code = "invalid_enrollment_authorization"
    http_status = 403

    def __init__(self) -> None:
        super().__init__("Enrollment authorization is invalid, expired, or already used.")


# --- Operator (CLI-only) errors -------------------------------------------
# These are never raised by an HTTP route; http_status is set for
# completeness so the shared exception handler stays total.


class OperatorInputError(OrionDeviceError):
    error_code = "invalid_input"
    http_status = 422


class OrganizationConflictError(OrionDeviceError):
    error_code = "organization_conflict"
    http_status = 409

    def __init__(self, organization_code: str):
        super().__init__(
            f"Organization '{organization_code}' already exists with a different name; "
            "refusing to change it."
        )


class SiteAlreadyExistsError(OrionDeviceError):
    error_code = "site_already_exists"
    http_status = 409

    def __init__(self, site_code: str, organization_code: str):
        super().__init__(
            f"Site '{site_code}' already exists in organization '{organization_code}'."
        )


class DeviceResetRequiredError(OrionDeviceError):
    """An authorization was requested for a device that is ACTIVE or REVOKED.

    Re-enrollment always goes through an explicit reset first, so issuing an
    authorization can never be used to replace a live credential.
    """

    error_code = "device_reset_required"
    http_status = 409

    def __init__(self, device_id: str, status: str):
        super().__init__(
            f"Device '{device_id}' is {status}; reset it before issuing an enrollment "
            "authorization."
        )


class DeviceSiteConflictError(OrionDeviceError):
    """The device is bound to a different site/organization than requested.

    Devices are never silently moved between sites or organizations.
    """

    error_code = "device_site_conflict"
    http_status = 409

    def __init__(self, device_id: str, organization_code: str, site_code: str):
        super().__init__(
            f"Device '{device_id}' is bound to site '{site_code}' in organization "
            f"'{organization_code}'; it cannot be moved to a different site."
        )


class InvalidDeviceTransitionError(OrionDeviceError):
    error_code = "invalid_device_transition"
    http_status = 409

    def __init__(self, device_id: str, action: str, status: str):
        super().__init__(f"Cannot {action} device '{device_id}' while it is {status}.")


class ConcurrentOperationError(OrionDeviceError):
    error_code = "concurrent_operation"
    http_status = 409

    def __init__(self, what: str):
        super().__init__(f"A concurrent change conflicted with this operation ({what}); retry.")
