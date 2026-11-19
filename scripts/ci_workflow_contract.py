"""Resolve this checkout's real public workflow; an explicit external override is optional."""
import os
from pathlib import Path


def test_workflow_path() -> Path:
    value = os.environ.get("BETTER_ZSLF_TEST_WORKFLOW", "")
    path = Path(value) if value else Path(__file__).resolve().parents[1] / ".github/workflows/tests.yml"
    if not path.is_absolute() or path.name != "tests.yml" or path.parent.name != "workflows" or path.parent.parent.name != ".github":
        raise ValueError("BETTER_ZSLF_TEST_WORKFLOW must identify an actual absolute .github/workflows/tests.yml workflow")
    if not path.is_file():
        raise ValueError("The requested test workflow is missing; no fixture or private checkout fallback is used")
    return path


def read_test_workflow() -> str:
    return test_workflow_path().read_text(encoding="utf-8")
