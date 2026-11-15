from fastapi import APIRouter, Depends

from app.api.auth import require_token

router = APIRouter(dependencies=[Depends(require_token)])


@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
