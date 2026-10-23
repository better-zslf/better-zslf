"""Resolver unit checks only; repository gates read the real external workflow."""
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ci_workflow_contract import read_test_workflow, test_workflow_path


class WorkflowContractTests(unittest.TestCase):
    def test_missing_input_is_explicit_not_a_fixture_fallback(self):
        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, "Set BETTER_ZSLF_TEST_WORKFLOW"):
            test_workflow_path()

    def test_relative_path_is_rejected(self):
        with patch.dict(os.environ, {"BETTER_ZSLF_TEST_WORKFLOW": ".github/workflows/tests.yml"}), self.assertRaisesRegex(ValueError, "absolute"):
            test_workflow_path()

    def test_wrong_filename_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".github/workflows/ci.yml"
            path.parent.mkdir(parents=True)
            path.write_text("name: fixture only\n")
            with patch.dict(os.environ, {"BETTER_ZSLF_TEST_WORKFLOW": str(path)}), self.assertRaisesRegex(ValueError, "actual absolute"):
                test_workflow_path()

    def test_actual_file_required_and_read_without_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".github/workflows/tests.yml"
            with patch.dict(os.environ, {"BETTER_ZSLF_TEST_WORKFLOW": str(path)}), self.assertRaisesRegex(ValueError, "missing"):
                test_workflow_path()
            path.parent.mkdir(parents=True)
            path.write_text("name: resolver unit fixture\n", encoding="utf-8")
            with patch.dict(os.environ, {"BETTER_ZSLF_TEST_WORKFLOW": str(path)}):
                self.assertEqual(read_test_workflow(), path.read_text())


if __name__ == "__main__":
    unittest.main()
