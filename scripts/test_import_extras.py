import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "import_extras", Path(__file__).resolve().parent / "import-extras.py"
)
import_extras = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(import_extras)


FIXTURES = Path(__file__).resolve().parent / "testdata" / "cleanyourtv" / "examples"


def write_fixture(rel: str, debloat_name: str, content: str, tv: dict | None = None) -> Path:
    folder = FIXTURES / rel
    folder.mkdir(parents=True, exist_ok=True)
    (folder / debloat_name).write_bytes(content.encode("utf-8") if isinstance(content, str) else content)
    if tv is not None:
        (folder / "tv.json").write_text(json.dumps(tv) + "\n", encoding="utf-8")
    return folder


class ParseDebloatTest(unittest.TestCase):
    def test_utf8_bom_and_note(self) -> None:
        raw = b"\xef\xbb\xbfcom.example.foo # HDMI note\n"
        entries = import_extras.parse_debloat(import_extras.decode_text(raw))
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0]["package"], "com.example.foo")
        self.assertEqual(entries[0]["note"], "HDMI note")

    def test_utf16_bom(self) -> None:
        text = "com.example.bar\n"
        raw = text.encode("utf-16")
        entries = import_extras.parse_debloat(import_extras.decode_text(raw))
        self.assertEqual([e["package"] for e in entries], ["com.example.bar"])

    def test_crlf(self) -> None:
        text = "com.example.a\r\ncom.example.b\r\n"
        entries = import_extras.parse_debloat(import_extras.decode_text(text.encode("utf-8")))
        self.assertEqual([e["package"] for e in entries], ["com.example.a", "com.example.b"])

    def test_optional_line(self) -> None:
        text = "com.example.on\n# com.example.off # kept\n"
        entries = import_extras.parse_debloat(text)
        self.assertEqual(entries[0]["optional"], False)
        self.assertEqual(entries[1]["optional"], True)
        self.assertEqual(entries[1]["note"], "kept")

    def test_header_sentence_ignored(self) -> None:
        text = "# This is not a package line\ncom.example.real\n"
        entries = import_extras.parse_debloat(text)
        self.assertEqual([e["package"] for e in entries], ["com.example.real"])

    def test_core_dropped(self) -> None:
        text = "android\ncom.example.ok\n"
        entries = import_extras.parse_debloat(text)
        self.assertEqual([e["package"] for e in entries], ["com.example.ok"])

    def test_home_and_setup_flags(self) -> None:
        text = (
            "com.google.android.apps.tv.launcherx\n"
            "com.google.android.tungsten.setupwraith\n"
            "com.example.other\n"
        )
        entries = import_extras.parse_debloat(text)
        by = {e["package"]: e for e in entries}
        self.assertTrue(by["com.google.android.apps.tv.launcherx"]["home"])
        self.assertTrue(by["com.google.android.tungsten.setupwraith"]["setup"])
        self.assertFalse(by["com.example.other"]["home"])

    def test_dedupe_first_wins(self) -> None:
        text = "com.example.a # one\ncom.example.a # two\n"
        entries = import_extras.parse_debloat(text)
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0]["note"], "one")


class ImportCheckoutTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.checkout = Path(self.tmp.name)
        self.examples = self.checkout / "examples"
        self.examples.mkdir()
        (self.checkout / "LICENSE").write_text("MIT\n", encoding="utf-8")
        subprocess.check_call(["git", "init", "-q"], cwd=self.checkout)
        subprocess.check_call(
            ["git", "-c", "user.email=test@test", "-c", "user.name=test", "commit", "--allow-empty", "-q", "-m", "init"],
            cwd=self.checkout,
        )

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_variant_and_match(self) -> None:
        tv = {"tv": "Test TV", "os": "Android TV 11", "brand": "test"}
        folder = self.examples / "fixture-box"
        folder.mkdir()
        (folder / "tv.json").write_text(json.dumps(tv), encoding="utf-8")
        (folder / "debloat.txt").write_text("com.example.a\n", encoding="utf-8")
        (folder / "debloat-keep-cast.txt").write_text("com.example.b\n", encoding="utf-8")
        out = Path(self.tmp.name) / "assets"
        match = {"fixture-box": {"manufacturer": "m", "model": "mod", "device": "dev"}}
        commit = import_extras.import_checkout(self.checkout, out, match)
        self.assertTrue(commit)
        index = json.loads((out / "extras" / "index.json").read_text(encoding="utf-8"))
        ids = [(e["id"], e["variant"], e["file"]) for e in index["lists"]]
        self.assertEqual(
            ids,
            [
                ("fixture-box", "default", "fixture-box.json"),
                ("fixture-box", "keep-cast", "fixture-box--keep-cast.json"),
            ],
        )
        self.assertEqual(index["lists"][0]["match"], match["fixture-box"])

    def test_match_only_for_listed_ids(self) -> None:
        tv = {"tv": "T", "os": "O", "brand": "b"}
        for name in ("matched", "unmatched"):
            folder = self.examples / name
            folder.mkdir()
            (folder / "tv.json").write_text(json.dumps(tv), encoding="utf-8")
            (folder / "debloat.txt").write_text("com.example.x\n", encoding="utf-8")
        out = Path(self.tmp.name) / "assets2"
        match = {"matched": {"manufacturer": "a", "model": "b", "device": "c"}}
        import_extras.import_checkout(self.checkout, out, match)
        index = json.loads((out / "extras" / "index.json").read_text(encoding="utf-8"))
        by_id = {e["id"]: e["match"] for e in index["lists"]}
        self.assertEqual(by_id["matched"], match["matched"])
        self.assertIsNone(by_id["unmatched"])

    def test_deterministic_output(self) -> None:
        tv = {"tv": "T", "os": "O", "brand": "b"}
        folder = self.examples / "stable"
        folder.mkdir()
        (folder / "tv.json").write_text(json.dumps(tv), encoding="utf-8")
        (folder / "debloat.txt").write_text("com.z.last\ncom.a.first\n", encoding="utf-8")
        out_a = Path(self.tmp.name) / "a"
        out_b = Path(self.tmp.name) / "b"
        import_extras.import_checkout(self.checkout, out_a, {})
        import_extras.import_checkout(self.checkout, out_b, {})
        for name in ("index.json", "stable.json"):
            a = (out_a / "extras" / name).read_bytes()
            b = (out_b / "extras" / name).read_bytes()
            self.assertEqual(a, b)


if __name__ == "__main__":
    unittest.main()