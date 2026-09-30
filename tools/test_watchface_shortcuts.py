import math
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]


class WatchfaceShortcutsTest(unittest.TestCase):
    def setUp(self):
        self.face = ET.parse(ROOT / "watchface/src/main/res/raw/watchface.xml").getroot()

    def test_shortcuts_only_open_explicit_input_forms(self):
        for group_name, alias in (
            ("carbs_shortcut", "WatchCarbsShortcut"),
            ("activity_shortcut", "WatchActivityShortcut"),
        ):
            group = self.face.find(f".//Group[@name='{group_name}']")
            launches = group.findall(".//Launch")
            self.assertEqual(3, len(launches))
            self.assertEqual(
                {f"info.nightscout.androidaps/app.aaps.wear.interaction.actions.{alias}"},
                {item.attrib["target"] for item in launches},
            )

    def test_targets_fit_round_screen_and_do_not_cover_glucose(self):
        radius = int(self.face.attrib["width"]) / 2
        glucose = self.face.find(".//ComplicationSlot")
        glucose_bottom = int(glucose.attrib["y"]) + int(glucose.attrib["height"])
        previous_right = 0
        for name in ("carbs_shortcut", "activity_shortcut"):
            group = self.face.find(f".//Group[@name='{name}']")
            x, y, width, height = (int(group.attrib[key]) for key in ("x", "y", "width", "height"))
            self.assertGreaterEqual(width, 120)
            self.assertGreaterEqual(height, 80)
            self.assertGreater(y, glucose_bottom)
            self.assertGreater(x, previous_right)
            previous_right = x + width
            for cx, cy in ((x, y), (x + width, y), (x, y + height), (x + width, y + height)):
                self.assertLessEqual(math.hypot(cx - radius, cy - radius), radius)

    def test_icons_exist_and_shortcuts_hide_in_ambient_mode(self):
        for image in self.face.findall(".//Image"):
            self.assertTrue((ROOT / f"watchface/src/main/res/drawable-nodpi/{image.attrib['resource']}.png").is_file())
        for name in ("carbs_shortcut", "activity_shortcut"):
            variant = self.face.find(f".//Group[@name='{name}']/Variant")
            self.assertEqual({"mode": "AMBIENT", "target": "alpha", "value": "0"}, variant.attrib)

    def test_expiry_is_above_glucose_and_urgent_is_bolder_and_larger(self):
        expiry = self.face.find(".//ComplicationSlot[@name='sensor_expiry']")
        glucose = self.face.find(".//ComplicationSlot[@name='glucose']")
        self.assertLessEqual(int(expiry.attrib["y"]) + int(expiry.attrib["height"]), int(glucose.attrib["y"]))
        normal = expiry.find(".//Default//Font")
        urgent = expiry.find(".//Compare//Font")
        self.assertEqual("BOLD", urgent.attrib["weight"])
        self.assertGreater(int(urgent.attrib["size"]), int(normal.attrib["size"]))
        self.assertNotEqual(normal.attrib["color"], urgent.attrib["color"])


if __name__ == "__main__":
    unittest.main()
