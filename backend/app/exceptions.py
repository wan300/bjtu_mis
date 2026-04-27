class SessionExpiredError(RuntimeError):
    """Raised when the exported browser session is missing or invalid."""


class SyncAlreadyRunningError(RuntimeError):
    """Raised when a sync lock is already held by another process."""
