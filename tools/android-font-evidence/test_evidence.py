import hashlib
import json
import tempfile
import unittest
import zipfile
from collections import Counter
from pathlib import Path

import evidence


def status_counts(observations):
    top = {status: 0 for status in evidence.STATUSES}
    nodes = {status: 0 for status in evidence.STATUSES}
    for observation in observations:
        top[observation["status"]] += 1
        counter = Counter()
        evidence.count_status_nodes(observation, counter)
        for status in evidence.STATUSES:
            nodes[status] += counter[status]
    return top, nodes


def make_observation(raster_hash="raster-a", measure=64.0):
    return {
        "id": "shape.default.zh-hans-cn.cjk-body",
        "kind": "platform-shape",
        "probe": {"id": "cjk-body", "text": "中文", "direction": "ltr"},
        "request": {"construction": "default", "locale": "zh-Hans-CN"},
        "status": "observed",
        "runMetrics": {"status": "observed", "measureText": measure},
        "glyphReadback": {
            "status": "observed",
            "glyphCount": 1,
            "glyphs": [{
                "glyphId": 1,
                               "font": {
                    "file": {"path": "/system/fonts/Test.ttf", "sha256": "face-a"},
                    "ttcIndex": 0,
                    "weight": 400,
                    "slant": "upright",
                                   "axes": {"wght": 400.0},
                               },
                               "styleApplication": {
                                   "status": "observed",
                                   "fakeBold": False,
                                   "fakeItalic": False,
                                   "weightOverride": 412.0,
                                   "italicOverride": None,
                               },
                           }],
        },
        "raster": {
            "status": "observed",
            "argbSha256": raster_hash,
            "pngEntry": "renders/body.png",
        },
    }


def write_bundle(path, observations=None, mutate_manifest=None):
    observations = observations or [make_observation()]
    entries = {
        "observations.jsonl": ("\n".join(json.dumps(item) for item in observations) + "\n").encode(),
        "font-config.json": b'{"schemaVersion":1,"sources":[]}\n',
        "system-fonts.json": b'{"status":"observed","count":0,"fonts":[]}\n',
        "font-directories.json": b'{"directories":[]}\n',
        "renders/body.png": b"png",
        "summary.md": b"summary\n",
    }
    top, nodes = status_counts(observations)
    manifest = {
        "schema": evidence.SCHEMA,
        "schemaVersion": evidence.SCHEMA_VERSION,
        "collector": {"gitDirty": False},
        "device": {"fingerprint": "test/device", "sdkInt": 36},
        "capabilities": [{"id": "per-glyph-font-and-position", "status": "observed"}],
        "observationCounts": top,
        "evidenceNodeCounts": nodes,
        "entries": [
            {"name": name, "sizeBytes": len(value), "sha256": hashlib.sha256(value).hexdigest()}
            for name, value in entries.items()
        ],
    }
    if mutate_manifest:
        mutate_manifest(manifest)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("manifest.json", json.dumps(manifest))
        for name, value in entries.items():
            archive.writestr(name, value)


class EvidenceArchiveTest(unittest.TestCase):
    def test_valid_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "valid.zip"
            write_bundle(path)
            archive = evidence.EvidenceArchive(path)
            self.assertTrue(archive.valid, archive.errors)
            self.assertEqual(
                evidence.font_instances(archive.default_observation("cjk-body"))["fonts"][0]["path"],
                "/system/fonts/Test.ttf",
            )
            self.assertEqual(
                evidence.style_applications(archive.default_observation("cjk-body"))[0]["weightOverride"],
                412.0,
            )

    def test_manifest_hash_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.zip"
            write_bundle(
                path,
                mutate_manifest=lambda manifest: manifest["entries"][0].update(sha256="0" * 64),
            )
            archive = evidence.EvidenceArchive(path)
            self.assertFalse(archive.valid)
            self.assertIn("entry-hash-mismatch", {error.code for error in archive.errors})

    def test_duplicate_observation_id_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.zip"
            write_bundle(path, [make_observation(), make_observation()])
            archive = evidence.EvidenceArchive(path)
            self.assertFalse(archive.valid)
            self.assertIn("duplicate-observation-id", {error.code for error in archive.errors})

    def test_unsafe_zip_member_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unsafe.zip"
            write_bundle(path)
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("../outside.txt", "not extracted, but still invalid")
            evidence_archive = evidence.EvidenceArchive(path)
            self.assertFalse(evidence_archive.valid)
            self.assertIn("unsafe-zip-member", {error.code for error in evidence_archive.errors})

    def test_duplicate_zip_member_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate-member.zip"
            write_bundle(path)
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("summary.md", "replacement")
            evidence_archive = evidence.EvidenceArchive(path)
            self.assertFalse(evidence_archive.valid)
            self.assertIn("duplicate-zip-member", {error.code for error in evidence_archive.errors})

    def test_unsupported_shape_does_not_require_raster(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unsupported.zip"
            observation = make_observation()
            observation.pop("runMetrics")
            observation.pop("glyphReadback")
            observation.pop("raster")
            observation["status"] = "unsupported"
            observation["reason"] = "Exact weight requires a newer API"
            write_bundle(path, [observation])
            archive = evidence.EvidenceArchive(path)
            self.assertTrue(archive.valid, archive.errors)

    def test_comparison_reports_semantic_layers(self):
        with tempfile.TemporaryDirectory() as directory:
            left_path = Path(directory) / "left.zip"
            right_path = Path(directory) / "right.zip"
            write_bundle(left_path, [make_observation(raster_hash="a", measure=64.0)])
            write_bundle(right_path, [make_observation(raster_hash="b", measure=65.0)])
            comparison = evidence.compare_archives(
                evidence.EvidenceArchive(left_path),
                evidence.EvidenceArchive(right_path),
            )
            self.assertEqual(comparison["changedObservationCount"], 1)
            self.assertEqual(comparison["changedByLayer"], {"raster": 1, "runMetrics": 1})

    def test_labels_are_joined_by_archive_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            path = directory / "sample.zip"
            write_bundle(path)
            archive = evidence.EvidenceArchive(path)
            label = {
                "sampleId": "controlled-sample",
                "knownConditions": [{"id": "font-module", "description": "字体模块已启用"}],
            }
            sample = evidence.catalog_sample(archive, label)
            self.assertEqual(sample["archiveSha256"], archive.archive_sha256)
            self.assertEqual(sample["knownConditions"][0]["id"], "font-module")


if __name__ == "__main__":
    unittest.main()
