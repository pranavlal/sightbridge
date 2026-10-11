"""Meaningful checks for extraction of externally supplied archives."""
import tempfile
import unittest
from pathlib import Path
import zipfile

from acquire import extract


class ExtractionTests(unittest.TestCase):
    def test_rejects_parent_traversal_without_writing_outside_target(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "unsafe.zip"
            with zipfile.ZipFile(archive, "w") as z:
                z.writestr("../outside.txt", "untrusted")
            with self.assertRaisesRegex(ValueError, "Unsafe archive path"):
                extract(archive, root / "extracted")
            self.assertFalse((root / "outside.txt").exists())
            self.assertFalse((root / "extracted/.extracted.json").exists())

    def test_rejects_symbolic_links(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "unsafe.zip"
            info = zipfile.ZipInfo("link")
            info.create_system = 3
            info.external_attr = 0o120777 << 16
            with zipfile.ZipFile(archive, "w") as z:
                z.writestr(info, "../../outside")
            with self.assertRaisesRegex(ValueError, "symbolic link"):
                extract(archive, root / "extracted")

    def test_extracts_valid_archive_and_records_completion(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "safe.zip"
            with zipfile.ZipFile(archive, "w") as z:
                z.writestr("folder/sample.txt", "sample")
            extract(archive, root / "extracted")
            self.assertEqual((root / "extracted/folder/sample.txt").read_text(), "sample")
            self.assertTrue((root / "extracted/.extracted.json").exists())


if __name__ == "__main__":
    unittest.main()
