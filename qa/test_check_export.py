"""Run with python3 -m unittest discover -s qa from the Android project (requires Pillow)."""
from io import BytesIO
import unittest
from zipfile import ZipFile

from PIL import Image
from check_export import check_zip


class ManualRotationTest(unittest.TestCase):
    def archive(self, manual, leak=False):
        stream = BytesIO()
        with ZipFile(stream, "w") as archive:
            status = "Complete\n" + ("Mode: Manual screenshots\n" if manual else "")
            archive.writestr("status.txt", status)
            for number, (width, height) in enumerate([(48, 80), (80, 48)], 1):
                image = Image.new("RGB", (width, height), "white")
                image.paste("black", (0, 0, width, height // 4))
                if leak and number == 2:
                    image.putpixel((width - 1, height // 4 - 1), (220, 220, 220))
                frame = BytesIO()
                image.save(frame, "PNG")
                archive.writestr(f"screenshots/{number:08d}.png", frame.getvalue())
        stream.seek(0)
        return stream

    def test_manual_rotation_preserves_each_native_size(self):
        self.assertEqual(check_zip(self.archive(True), "0,0,1,.25"),
                         "2 full-size PNGs at 48 x 80, 80 x 48")

    def test_rotation_checks_the_landscape_mask_edge(self):
        with self.assertRaisesRegex(ValueError, "mask pixel at screenshot 2"):
            check_zip(self.archive(True, leak=True), "0,0,1,.25")

    def test_automatic_history_still_requires_consistent_dimensions(self):
        with self.assertRaisesRegex(ValueError, "size at screenshot 2"):
            check_zip(self.archive(False), None)


if __name__ == "__main__":
    unittest.main()
