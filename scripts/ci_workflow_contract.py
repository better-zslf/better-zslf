"""Resolve the actual test workflow maintained by the separate Pro CI repository."""
import os
from pathlib import Path


def test_workflow_path() -> Path:
    value = os.environ.get("BETTER_ZSLF_TEST_WORKFLOW", "")
    if not value:
        raise ValueError("Set BETTER_ZSLF_TEST_WORKFLOW to the absolute pro-ci/.github/workflows/tests.yml path")
    path = Path(value)
    if not path.is_absolute() or path.name != "tests.yml" or path.parent.name != "workflows" or path.parent.parent.name != ".github":
        raise ValueError("BETTER_ZSLF_TEST_WORKFLOW must identify the actual absolute Pro CI tests.yml workflow")
    if not path.is_file():
        raise ValueError("The migrated Pro CI test workflow is missing")
    return path


def read_test_workflow() -> str:
    return test_workflow_path().read_text(encoding="utf-8")
