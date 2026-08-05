#!/usr/bin/env python3
"""Validate and compare Tiqian Android font evidence bundles.

The Android collector intentionally emits observations rather than OEM conclusions. This host-side
tool keeps the same boundary: validation checks the wire evidence, catalog generation joins
reviewed collection conditions by archive hash, and comparison aligns observations by stable ID.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


SCHEMA = "org.tiqian.android-font-evidence"
SCHEMA_VERSION = 1
CATALOG_SCHEMA = "org.tiqian.android-font-evidence-catalog"
CATALOG_SCHEMA_VERSION = 1
STATUSES = ("observed", "unsupported", "error")
REQUIRED_MEMBERS = {
    "manifest.json",
    "observations.jsonl",
    "font-config.json",
    "system-fonts.json",
    "font-directories.json",
    "summary.md",
}
KEY_PROBES = (
    "cjk-body",
    "mixed-cjk-latin",
    "cjk-punctuation-context",
    "shared-punctuation-context",
    "latin",
    "greek",
    "cyrillic",
    "emoji",
)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def write_json(path: Path | None, value: Any) -> None:
    payload = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    if path is None:
        sys.stdout.write(payload)
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(payload, encoding="utf-8")


def write_text(path: Path | None, value: str) -> None:
    if not value.endswith("\n"):
        value += "\n"
    if path is None:
        sys.stdout.write(value)
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding="utf-8")


def safe_member_name(name: str) -> bool:
    pure = PurePosixPath(name)
    return bool(name) and not pure.is_absolute() and ".." not in pure.parts and "\\" not in name


def count_status_nodes(value: Any, counts: Counter[str]) -> None:
    if isinstance(value, dict):
        status = value.get("status")
        if status in STATUSES:
            counts[status] += 1
        for child in value.values():
            count_status_nodes(child, counts)
    elif isinstance(value, list):
        for child in value:
            count_status_nodes(child, counts)


@dataclass(frozen=True)
class ValidationMessage:
    code: str
    message: str

    def json(self) -> dict[str, str]:
        return {"code": self.code, "message": self.message}


class EvidenceArchive:
    def __init__(self, path: Path):
        self.path = path
        self.archive_sha256 = sha256_file(path)
        self.errors: list[ValidationMessage] = []
        self.warnings: list[ValidationMessage] = []
        self.manifest: dict[str, Any] = {}
        self.observations: list[dict[str, Any]] = []
        self.observations_by_id: dict[str, dict[str, Any]] = {}
        self.font_config: dict[str, Any] = {}
        self.system_fonts: dict[str, Any] = {}
        self.font_directories: dict[str, Any] = {}
        self._members: dict[str, bytes] = {}
        self._load_and_validate()

    def error(self, code: str, message: str) -> None:
        self.errors.append(ValidationMessage(code, message))

    def warning(self, code: str, message: str) -> None:
        self.warnings.append(ValidationMessage(code, message))

    def _load_and_validate(self) -> None:
        try:
            with zipfile.ZipFile(self.path) as archive:
                infos = archive.infolist()
                names = [info.filename for info in infos]
                duplicates = sorted(name for name, count in Counter(names).items() if count > 1)
                for name in duplicates:
                    self.error("duplicate-zip-member", f"ZIP member appears more than once: {name}")
                for name in names:
                    if not safe_member_name(name):
                        self.error("unsafe-zip-member", f"Unsafe ZIP member path: {name}")
                self._members = {name: archive.read(name) for name in names if name not in duplicates}
        except (OSError, zipfile.BadZipFile, RuntimeError) as error:
            self.error("unreadable-zip", f"Cannot read ZIP: {error}")
            return

        missing = sorted(REQUIRED_MEMBERS - self._members.keys())
        for name in missing:
            self.error("missing-required-member", f"Required member is missing: {name}")
        if missing:
            return

        self.manifest = self._read_json("manifest.json")
        self.font_config = self._read_json("font-config.json")
        self.system_fonts = self._read_json("system-fonts.json")
        self.font_directories = self._read_json("font-directories.json")
        self.observations = self._read_jsonl("observations.jsonl")
        self._validate_manifest()
        self._validate_observations()
        self._validate_references()
        self._validate_auxiliary_counts()

    def _read_json(self, name: str) -> dict[str, Any]:
        try:
            value = json.loads(self._members[name])
            if not isinstance(value, dict):
                self.error("invalid-json-root", f"{name} must contain a JSON object")
                return {}
            return value
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            self.error("invalid-json", f"Cannot parse {name}: {error}")
            return {}

    def _read_jsonl(self, name: str) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        try:
            lines = self._members[name].decode("utf-8").splitlines()
        except UnicodeDecodeError as error:
            self.error("invalid-jsonl-encoding", f"Cannot decode {name}: {error}")
            return result
        for line_number, line in enumerate(lines, start=1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as error:
                self.error("invalid-jsonl", f"{name}:{line_number}: {error}")
                continue
            if not isinstance(value, dict):
                self.error("invalid-observation", f"{name}:{line_number} must contain a JSON object")
                continue
            result.append(value)
        return result

    def _validate_manifest(self) -> None:
        if not self.manifest:
            return
        if self.manifest.get("schema") != SCHEMA:
            self.error("unsupported-schema", f"Expected schema {SCHEMA!r}")
        if self.manifest.get("schemaVersion") != SCHEMA_VERSION:
            self.error("unsupported-schema-version", f"Expected schema version {SCHEMA_VERSION}")

        collector = self.manifest.get("collector")
        if isinstance(collector, dict) and collector.get("gitDirty") is True:
            self.warning("dirty-collector-build", "Collector was built from a dirty worktree")

        entries = self.manifest.get("entries")
        if not isinstance(entries, list):
            self.error("invalid-manifest-entries", "manifest.entries must be an array")
            return
        declared_names = [entry.get("name") for entry in entries if isinstance(entry, dict)]
        duplicate_names = sorted(
            name for name, count in Counter(declared_names).items() if isinstance(name, str) and count > 1
        )
        for name in duplicate_names:
            self.error("duplicate-manifest-entry", f"manifest.entries repeats: {name}")

        declared = {name for name in declared_names if isinstance(name, str)}
        actual = set(self._members) - {"manifest.json"}
        for name in sorted(declared - actual):
            self.error("manifest-member-missing", f"Manifest declares missing member: {name}")
        for name in sorted(actual - declared):
            self.error("unlisted-zip-member", f"ZIP member is not declared by manifest: {name}")

        for entry in entries:
            if not isinstance(entry, dict):
                self.error("invalid-manifest-entry", "Every manifest.entries item must be an object")
                continue
            name = entry.get("name")
            if not isinstance(name, str) or name not in self._members:
                continue
            data = self._members[name]
            if entry.get("sizeBytes") != len(data):
                self.error("entry-size-mismatch", f"Size mismatch for {name}")
            if entry.get("sha256") != sha256_bytes(data):
                self.error("entry-hash-mismatch", f"SHA-256 mismatch for {name}")

    def _validate_observations(self) -> None:
        ids: list[str] = []
        top_counts: Counter[str] = Counter()
        node_counts: Counter[str] = Counter()
        for index, observation in enumerate(self.observations):
            observation_id = observation.get("id")
            if not isinstance(observation_id, str) or not observation_id:
                self.error("missing-observation-id", f"Observation {index + 1} has no stable id")
            else:
                ids.append(observation_id)
            status = observation.get("status")
            if status not in STATUSES:
                self.error("invalid-observation-status", f"{observation_id or index + 1}: invalid status {status!r}")
            else:
                top_counts[status] += 1
            if observation.get("kind") not in ("platform-shape", "paint-has-glyph"):
                self.error("invalid-observation-kind", f"{observation_id or index + 1}: unknown kind")
            count_status_nodes(observation, node_counts)

        for observation_id, count in sorted(Counter(ids).items()):
            if count > 1:
                self.error("duplicate-observation-id", f"Observation id appears {count} times: {observation_id}")
        self.observations_by_id = {
            observation["id"]: observation
            for observation in self.observations
            if isinstance(observation.get("id"), str)
        }

        expected_top = self.manifest.get("observationCounts")
        expected_nodes = self.manifest.get("evidenceNodeCounts")
        actual_top = {status: top_counts[status] for status in STATUSES}
        actual_nodes = {status: node_counts[status] for status in STATUSES}
        if expected_top != actual_top:
            self.error("observation-count-mismatch", f"manifest={expected_top!r}, actual={actual_top!r}")
        if expected_nodes != actual_nodes:
            self.error("evidence-node-count-mismatch", f"manifest={expected_nodes!r}, actual={actual_nodes!r}")

    def _validate_references(self) -> None:
        for observation in self.observations:
            if observation.get("kind") != "platform-shape":
                continue
            observation_id = observation.get("id", "<unknown>")
            if observation.get("status") != "observed":
                continue
            raster = observation.get("raster")
            if not isinstance(raster, dict):
                self.error("missing-raster-envelope", f"{observation_id}: raster envelope is missing")
                continue
            if raster.get("status") == "observed":
                png_entry = raster.get("pngEntry")
                if not isinstance(png_entry, str) or png_entry not in self._members:
                    self.error("missing-raster-png", f"{observation_id}: referenced PNG is missing")
                elif not png_entry.startswith("renders/") or not png_entry.endswith(".png"):
                    self.error("invalid-raster-png-path", f"{observation_id}: invalid PNG member {png_entry}")

            readback = observation.get("glyphReadback")
            if isinstance(readback, dict) and readback.get("status") == "observed":
                glyphs = readback.get("glyphs")
                if not isinstance(glyphs, list):
                    self.error("missing-glyph-list", f"{observation_id}: observed glyph readback has no glyph list")
                elif readback.get("glyphCount") != len(glyphs):
                    self.error("glyph-count-mismatch", f"{observation_id}: glyphCount does not match glyphs")

    def _validate_auxiliary_counts(self) -> None:
        if self.system_fonts.get("status") == "observed":
            fonts = self.system_fonts.get("fonts")
            if not isinstance(fonts, list):
                self.error("missing-system-font-list", "Observed system-fonts.json has no fonts array")
            elif self.system_fonts.get("count") != len(fonts):
                self.error("system-font-count-mismatch", "system-fonts.json count does not match fonts")

    @property
    def valid(self) -> bool:
        return not self.errors

    def validation_json(self) -> dict[str, Any]:
        return {
            "archiveSha256": self.archive_sha256,
            "valid": self.valid,
            "errors": [message.json() for message in self.errors],
            "warnings": [message.json() for message in self.warnings],
        }

    def default_observation(self, probe_id: str, locale: str = "zh-Hans-CN") -> dict[str, Any] | None:
        matches = [
            observation
            for observation in self.observations
            if observation.get("kind") == "platform-shape"
            and observation.get("probe", {}).get("id") == probe_id
            and observation.get("request", {}).get("construction") == "default"
            and observation.get("request", {}).get("locale") == locale
        ]
        return matches[0] if len(matches) == 1 else None


def font_instances(observation: dict[str, Any] | None) -> dict[str, Any]:
    if observation is None:
        return {"status": "missing", "fonts": []}
    readback = observation.get("glyphReadback")
    if not isinstance(readback, dict) or readback.get("status") != "observed":
        return {
            "status": readback.get("status", "missing") if isinstance(readback, dict) else "missing",
            "reason": readback.get("reason") if isinstance(readback, dict) else None,
            "fonts": [],
        }
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for glyph in readback.get("glyphs", []):
        font = glyph.get("font") or {}
        file = font.get("file") or {}
        item = {
            "path": file.get("path"),
            "sha256": file.get("sha256"),
            "ttcIndex": font.get("ttcIndex"),
            "weight": font.get("weight"),
            "slant": font.get("slant"),
            "axes": font.get("axes") or {},
            "locales": font.get("locales"),
        }
        token = canonical_json(item)
        if token not in seen:
            seen.add(token)
            result.append(item)
    return {"status": "observed", "fonts": result}


def style_applications(observation: dict[str, Any] | None) -> list[dict[str, Any]]:
    if observation is None:
        return []
    readback = observation.get("glyphReadback")
    if not isinstance(readback, dict) or readback.get("status") != "observed":
        return []
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for glyph in readback.get("glyphs", []):
        application = glyph.get("styleApplication")
        if not isinstance(application, dict):
            continue
        token = canonical_json(application)
        if token not in seen:
            seen.add(token)
            result.append(application)
    return result


def probe_summary(observation: dict[str, Any] | None) -> dict[str, Any]:
    if observation is None:
        return {"status": "missing", "fonts": []}
    return {
        "status": observation.get("status"),
        "id": observation.get("id"),
        "text": observation.get("probe", {}).get("text"),
        "runMetrics": observation.get("runMetrics"),
        "glyphFonts": font_instances(observation),
        "styleApplications": style_applications(observation),
        "raster": observation.get("raster"),
    }


def capability_map(archive: EvidenceArchive) -> dict[str, str]:
    result: dict[str, str] = {}
    for capability in archive.manifest.get("capabilities", []):
        if isinstance(capability, dict) and isinstance(capability.get("id"), str):
            result[capability["id"]] = capability.get("status", "missing")
    return result


def evidence_tier(archive: EvidenceArchive) -> str:
    body = archive.default_observation("cjk-body")
    if font_instances(body)["status"] == "observed":
        return "per-glyph-font-readback"
    if archive.system_fonts.get("status") == "observed":
        return "raster-config-and-system-font-enumeration"
    return "raster-and-config"


def observed_signals(probes: dict[str, Any]) -> list[dict[str, Any]]:
    body_fonts = probes.get("cjk-body", {}).get("glyphFonts", {}).get("fonts", [])
    body_style_applications = probes.get("cjk-body", {}).get("styleApplications", [])
    mixed_fonts = probes.get("mixed-cjk-latin", {}).get("glyphFonts", {}).get("fonts", [])
    result: list[dict[str, Any]] = []
    for font in body_fonts:
        path = font.get("path") or ""
        basename = PurePosixPath(path).name
        if path.startswith("/data/"):
            result.append({
                "id": "runtime-data-font",
                "fact": f"默认中文实际字体位于数据分区：{path}",
            })
        if "overlay" in basename.lower():
            result.append({
                "id": "overlay-named-font",
                "fact": f"默认中文实际字体文件名含 Overlay：{path}",
            })
        if re.fullmatch(r"\d+\.ttf", basename, flags=re.IGNORECASE):
            result.append({
                "id": "numeric-font-file",
                "fact": f"默认中文实际字体使用纯字重文件名：{path}",
            })
        weight = font.get("weight")
        axis = (font.get("axes") or {}).get("wght")
        if isinstance(weight, (int, float)) and isinstance(axis, (int, float)) and abs(axis - weight) > 1:
            result.append({
                "id": "default-weight-axis-remapped",
                "fact": f"默认中文报告 weight={weight:g}，实际 wght 轴={axis:g}",
            })
    body_paths = {font.get("path") for font in body_fonts if font.get("path")}
    mixed_paths = {font.get("path") for font in mixed_fonts if font.get("path")}
    if body_paths and len(mixed_paths) > len(body_paths):
        result.append({
            "id": "mixed-run-uses-additional-fonts",
            "fact": "默认中西混排使用了中文正文之外的字体实例",
        })
    for application in body_style_applications:
        override = application.get("weightOverride")
        if isinstance(override, (int, float)):
            result.append({
                "id": "default-weight-override",
                "fact": f"默认中文实际 weight override={override:g}",
            })
    return result


def catalog_sample(archive: EvidenceArchive, label: dict[str, Any]) -> dict[str, Any]:
    manifest = archive.manifest
    probes = {
        probe_id: probe_summary(archive.default_observation(probe_id))
        for probe_id in KEY_PROBES
    }
    return {
        "sampleId": label["sampleId"],
        "displayName": label.get("displayName", label["sampleId"]),
        "archiveSha256": archive.archive_sha256,
        "knownConditions": label.get("knownConditions", []),
        "validation": archive.validation_json(),
        "collector": manifest.get("collector"),
        "capturedAtUtc": manifest.get("capturedAtUtc"),
        "device": manifest.get("device"),
        "capabilities": capability_map(archive),
        "evidenceTier": evidence_tier(archive),
        "observationCounts": manifest.get("observationCounts"),
        "evidenceNodeCounts": manifest.get("evidenceNodeCounts"),
        "defaultZhHansProbes": probes,
        "observedSignals": observed_signals(probes),
    }


def changed_layers(left: dict[str, Any], right: dict[str, Any]) -> list[str]:
    layers = []
    for field in ("status", "request", "runMetrics", "glyphReadback", "raster"):
        if left.get(field) != right.get(field):
            layers.append(field)
    return layers


def compare_archives(left: EvidenceArchive, right: EvidenceArchive) -> dict[str, Any]:
    left_ids = set(left.observations_by_id)
    right_ids = set(right.observations_by_id)
    changed = []
    for observation_id in sorted(left_ids & right_ids):
        before = left.observations_by_id[observation_id]
        after = right.observations_by_id[observation_id]
        layers = changed_layers(before, after)
        if not layers:
            continue
        changed.append({
            "id": observation_id,
            "kind": before.get("kind"),
            "probe": before.get("probe", {}).get("id"),
            "construction": before.get("request", {}).get("construction"),
            "locale": before.get("request", {}).get("locale"),
            "layers": layers,
        })
    by_layer = Counter(layer for item in changed for layer in item["layers"])
    by_probe = Counter(item["probe"] for item in changed if item.get("probe"))
    return {
        "schema": "org.tiqian.android-font-evidence-comparison",
        "schemaVersion": 1,
        "left": left.validation_json(),
        "right": right.validation_json(),
        "sameDeviceFingerprint": (
            left.manifest.get("device", {}).get("fingerprint")
            == right.manifest.get("device", {}).get("fingerprint")
        ),
        "leftOnlyObservationIds": sorted(left_ids - right_ids),
        "rightOnlyObservationIds": sorted(right_ids - left_ids),
        "commonObservationCount": len(left_ids & right_ids),
        "changedObservationCount": len(changed),
        "changedByLayer": dict(sorted(by_layer.items())),
        "changedByProbe": dict(sorted(by_probe.items())),
        "changes": changed,
    }


def load_labels(path: Path) -> dict[str, dict[str, Any]]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schemaVersion") != 1 or not isinstance(value.get("samples"), list):
        raise ValueError("Label file must use schemaVersion 1 and contain samples[]")
    result: dict[str, dict[str, Any]] = {}
    sample_ids: set[str] = set()
    for sample in value["samples"]:
        digest = sample.get("archiveSha256")
        sample_id = sample.get("sampleId")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError(f"Invalid archiveSha256 in label: {digest!r}")
        if not isinstance(sample_id, str) or not sample_id:
            raise ValueError(f"Invalid sampleId for {digest}")
        if digest in result:
            raise ValueError(f"Duplicate archiveSha256 in labels: {digest}")
        if sample_id in sample_ids:
            raise ValueError(f"Duplicate sampleId in labels: {sample_id}")
        result[digest] = sample
        sample_ids.add(sample_id)
    return result


def format_font_paths(probe: dict[str, Any]) -> str:
    envelope = probe.get("glyphFonts", {})
    if envelope.get("status") != "observed":
        status = envelope.get("status", "missing")
        raster = probe.get("raster") or {}
        metrics = probe.get("runMetrics") or {}
        raster_hash = raster.get("argbSha256")
        measure = metrics.get("measureText")
        observed = []
        if isinstance(measure, (int, float)):
            observed.append(f"宽度 {measure:g}px")
        if isinstance(raster_hash, str):
            observed.append(f"raster `{raster_hash[:12]}…`")
        suffix = f"；{'，'.join(observed)}" if observed else ""
        return f"字体不可观测（{status}）{suffix}"
    paths = []
    for font in envelope.get("fonts", []):
        path = font.get("path")
        if path and path not in paths:
            paths.append(path)
    return "<br>".join(f"`{path}`" for path in paths) if paths else "已观测，但无字体路径"


def probe_font_paths(sample: dict[str, Any], probe_id: str) -> set[str]:
    fonts = sample["defaultZhHansProbes"][probe_id].get("glyphFonts", {}).get("fonts", [])
    return {font["path"] for font in fonts if isinstance(font.get("path"), str)}


def reviewed_findings(catalog: dict[str, Any], comparisons: list[dict[str, Any]]) -> list[str]:
    samples = catalog["samples"]
    findings: list[str] = []

    readable_punctuation = [
        sample
        for sample in samples
        if sample["defaultZhHansProbes"]["cjk-body"].get("glyphFonts", {}).get("status") == "observed"
        and sample["defaultZhHansProbes"]["cjk-punctuation-context"].get("glyphFonts", {}).get("status") == "observed"
    ]
    same_punctuation_paths = [
        sample
        for sample in readable_punctuation
        if probe_font_paths(sample, "cjk-body") == probe_font_paths(sample, "cjk-punctuation-context")
    ]
    if readable_punctuation:
        findings.append(
            f"逐 glyph 可读的 {len(readable_punctuation)} 份样本中，{len(same_punctuation_paths)} 份的默认中文正文与中文标点 probe 使用同一组字体路径。"
        )

    readable_emoji = [
        sample
        for sample in samples
        if sample["defaultZhHansProbes"]["emoji"].get("glyphFonts", {}).get("status") == "observed"
    ]
    emoji_paths = {
        path
        for sample in readable_emoji
        for path in probe_font_paths(sample, "emoji")
    }
    if readable_emoji and emoji_paths == {"/system/fonts/NotoColorEmoji.ttf"}:
        findings.append(
            f"逐 glyph 可读的 {len(readable_emoji)} 份样本中，默认 emoji probe 都使用 `/system/fonts/NotoColorEmoji.ttf`。"
        )

    xiaomi = [
        sample
        for sample in readable_punctuation
        if (sample.get("device") or {}).get("manufacturer") == "Xiaomi"
    ]
    if xiaomi:
        xiaomi_paths = sorted({path for sample in xiaomi for path in probe_font_paths(sample, "cjk-punctuation-context")})
        if xiaomi_paths and not any("noto" in path.lower() for path in xiaomi_paths):
            rendered = "、".join(f"`{path}`" for path in xiaomi_paths)
            findings.append(
                f"这 {len(xiaomi)} 份 API 31+ 小米样本的中文标点 probe 实际路径为 {rendered}；本批证据没有出现 Noto 接管中文标点。"
            )

    by_body_hash: dict[str, list[tuple[dict[str, Any], dict[str, Any]]]] = {}
    for sample in samples:
        body_fonts = sample["defaultZhHansProbes"]["cjk-body"].get("glyphFonts", {}).get("fonts", [])
        for font in body_fonts:
            digest = font.get("sha256")
            if digest:
                by_body_hash.setdefault(digest, []).append((sample, font))
    for digest, uses in sorted(by_body_hash.items()):
        if len(uses) < 2:
            continue
        axes = {canonical_json(font.get("axes") or {}) for _, font in uses}
        if len(axes) < 2:
            continue
        descriptions = "；".join(
            f"`{sample['sampleId']}`={json.dumps(font.get('axes') or {}, ensure_ascii=False, sort_keys=True)}"
            for sample, font in uses
        )
        findings.append(f"同一默认中文字体文件 `{digest[:12]}…` 以不同轴实例运行：{descriptions}。")

    for comparison in comparisons:
        findings.append(
            f"同 fingerprint 对照 `{comparison['leftSampleId']}` ↔ `{comparison['rightSampleId']}` 有 "
            f"{comparison['commonObservationCount']} 条共同 observation，其中 {comparison['changedObservationCount']} 条发生变化。"
        )

    for sample in samples:
        for signal in sample.get("observedSignals", []):
            if signal["id"] in ("runtime-data-font", "overlay-named-font"):
                findings.append(f"`{sample['sampleId']}`：{signal['fact']}。")
    return findings


def catalog_markdown(catalog: dict[str, Any], comparisons: list[dict[str, Any]]) -> str:
    cohort = catalog["cohort"]
    lines = [
        "# Android OEM 字体行为样本目录",
        "",
        "这份目录由证据包按稳定 observation ID 生成。它记录采集时的平台事实，不把 AOSP、OEM、",
        "主题、字体模块或提椠当前实现当成规范。`knownConditions` 来自人工提供的采集条件；",
        "`observedSignals` 只描述包内证据，不反推用户设置来源。",
        "",
        "## 校验与来源",
        "",
        f"- 样本：{cohort['archiveCount']}；结构与 manifest 校验通过：{cohort['validArchiveCount']}",
        f"- API 范围：{cohort['minimumApi']}–{cohort['maximumApi']}；具备逐 glyph 字体读回：{cohort['perGlyphArchiveCount']}",
        f"- 采集器构建身份：{json.dumps(cohort['collectorIdentities'], ensure_ascii=False, sort_keys=True)}",
        "- 原始 ZIP 不进入 Git；每个标签和派生样本都由整份 archive SHA-256 锚定。",
        "",
        "## 样本",
        "",
        "| 样本 | 设备 / API | 已知采集条件 | 证据层级 | 默认中文 | 中文标点 | 默认西文 | 观测信号 |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for sample in catalog["samples"]:
        device = sample.get("device") or {}
        conditions = sample.get("knownConditions") or []
        condition_text = "；".join(condition.get("description", condition.get("id", "")) for condition in conditions)
        signals = sample.get("observedSignals") or []
        signal_text = "；".join(signal["fact"] for signal in signals)
        probes = sample["defaultZhHansProbes"]
        lines.append(
            "| `{sample}` | {manufacturer} {model} / {api} | {conditions} | `{tier}` | {body} | {punct} | {latin} | {signals} |".format(
                sample=sample["sampleId"],
                manufacturer=device.get("manufacturer", "?"),
                model=device.get("model", "?"),
                api=device.get("sdkInt", "?"),
                conditions=condition_text or "未提供",
                tier=sample["evidenceTier"],
                body=format_font_paths(probes["cjk-body"]),
                punct=format_font_paths(probes["cjk-punctuation-context"]),
                latin=format_font_paths(probes["latin"]),
                signals=signal_text or "—",
            )
        )

    lines.extend(["", "## 同设备受控对照", ""])
    if not comparisons:
        lines.append("没有设备 fingerprint 相同的多份样本。")
    for comparison in comparisons:
        lines.extend([
            f"### `{comparison['leftSampleId']}` ↔ `{comparison['rightSampleId']}`",
            "",
            f"- 设备 fingerprint 相同：{'是' if comparison['sameDeviceFingerprint'] else '否'}",
            f"- 共同 observation：{comparison['commonObservationCount']}",
            f"- 发生变化的共同 observation：{comparison['changedObservationCount']}",
            f"- 只在左侧存在：{len(comparison['leftOnlyObservationIds'])}；只在右侧存在：{len(comparison['rightOnlyObservationIds'])}",
            f"- 变化层：{json.dumps(comparison['changedByLayer'], ensure_ascii=False, sort_keys=True)}",
            "",
        ])

    lines.extend(["## 这批证据能直接说明什么", ""])
    for finding in reviewed_findings(catalog, comparisons):
        lines.append(f"- {finding}")
    lines.append("")

    lines.extend([
        "## 使用边界",
        "",
        "- API 31 以下没有逐 glyph 字体读回；目录只保留确实观测到的栅格、宽度和配置，不能补造实际字体路径。",
        "- `PositionedGlyphs` 不提供 glyph 到 UTF-16 cluster 的映射；同一 probe 出现多份字体只能证明该 run 混用字体，不能凭顺序给每个源码字符贴标签。",
        "- 字体配置 XML 是声明证据；主题引擎、字体模块和运行时替换必须由同一次 platform shaping 读回确认。",
        "- 不同设备之间的字体文件、Android 版本、用户字重或主题状态都可能是混杂因素；只有同 fingerprint 且采集条件明确的样本适合受控归因。",
        "",
    ])
    return "\n".join(lines)


def compare_markdown(comparison: dict[str, Any]) -> str:
    lines = [
        "# Android 字体证据比较",
        "",
        f"- 同一设备 fingerprint：{'是' if comparison['sameDeviceFingerprint'] else '否'}",
        f"- 共同 observation：{comparison['commonObservationCount']}",
        f"- 变化 observation：{comparison['changedObservationCount']}",
        f"- 左侧独有：{len(comparison['leftOnlyObservationIds'])}",
        f"- 右侧独有：{len(comparison['rightOnlyObservationIds'])}",
        f"- 按证据层：{json.dumps(comparison['changedByLayer'], ensure_ascii=False, sort_keys=True)}",
        f"- 按 probe：{json.dumps(comparison['changedByProbe'], ensure_ascii=False, sort_keys=True)}",
        "",
    ]
    return "\n".join(lines)


def load_archives(paths: Iterable[str]) -> list[EvidenceArchive]:
    return [EvidenceArchive(Path(path).expanduser().resolve()) for path in paths]


def command_validate(args: argparse.Namespace) -> int:
    archives = load_archives(args.archives)
    result = {
        "schema": "org.tiqian.android-font-evidence-validation",
        "schemaVersion": 1,
        "archives": [archive.validation_json() for archive in archives],
    }
    write_json(args.output, result)
    return 0 if all(archive.valid for archive in archives) else 1


def command_compare(args: argparse.Namespace) -> int:
    left, right = load_archives([args.left, args.right])
    comparison = compare_archives(left, right)
    if args.json_output:
        write_json(args.json_output, comparison)
    if args.markdown_output or not args.json_output:
        write_text(args.markdown_output, compare_markdown(comparison))
    return 0 if left.valid and right.valid else 1


def command_catalog(args: argparse.Namespace) -> int:
    archives = load_archives(args.archives)
    labels = load_labels(args.labels)
    by_digest = {archive.archive_sha256: archive for archive in archives}
    missing_labels = sorted(set(by_digest) - set(labels))
    missing_archives = sorted(set(labels) - set(by_digest))
    if missing_labels or missing_archives:
        if missing_labels:
            sys.stderr.write(f"Missing labels for archive hashes: {', '.join(missing_labels)}\n")
        if missing_archives:
            sys.stderr.write(f"Labels reference archives not supplied: {', '.join(missing_archives)}\n")
        return 2
    if any(not archive.valid for archive in archives):
        for archive in archives:
            for error in archive.errors:
                sys.stderr.write(f"{archive.archive_sha256}: {error.code}: {error.message}\n")
        return 1

    samples = sorted(
        (catalog_sample(archive, labels[digest]) for digest, archive in by_digest.items()),
        key=lambda sample: sample["sampleId"],
    )
    sample_id_by_digest = {sample["archiveSha256"]: sample["sampleId"] for sample in samples}
    fingerprint_groups: dict[str, list[EvidenceArchive]] = {}
    for archive in archives:
        fingerprint = archive.manifest.get("device", {}).get("fingerprint")
        if fingerprint:
            fingerprint_groups.setdefault(fingerprint, []).append(archive)
    comparisons = []
    for group in fingerprint_groups.values():
        if len(group) != 2:
            continue
        comparison = compare_archives(group[0], group[1])
        comparison["leftSampleId"] = sample_id_by_digest[group[0].archive_sha256]
        comparison["rightSampleId"] = sample_id_by_digest[group[1].archive_sha256]
        comparisons.append(comparison)
    comparisons.sort(key=lambda item: (item["leftSampleId"], item["rightSampleId"]))
    apis = [sample.get("device", {}).get("sdkInt") for sample in samples]
    integer_apis = [api for api in apis if isinstance(api, int)]
    collector_identities = sorted(
        {
            canonical_json({
                "versionName": sample.get("collector", {}).get("versionName"),
                "versionCode": sample.get("collector", {}).get("versionCode"),
                "gitRevision": sample.get("collector", {}).get("gitRevision"),
                "gitDirty": sample.get("collector", {}).get("gitDirty"),
            })
            for sample in samples
        }
    )
    catalog = {
        "schema": CATALOG_SCHEMA,
        "schemaVersion": CATALOG_SCHEMA_VERSION,
        "sourceEvidenceSchema": {"name": SCHEMA, "version": SCHEMA_VERSION},
        "cohort": {
            "archiveCount": len(samples),
            "validArchiveCount": sum(1 for sample in samples if sample["validation"]["valid"]),
            "minimumApi": min(integer_apis) if integer_apis else None,
            "maximumApi": max(integer_apis) if integer_apis else None,
            "perGlyphArchiveCount": sum(
                1 for sample in samples if sample["evidenceTier"] == "per-glyph-font-readback"
            ),
            "collectorIdentities": [json.loads(identity) for identity in collector_identities],
        },
        "samples": samples,
        "sameFingerprintComparisons": comparisons,
    }
    write_json(args.json_output, catalog)
    write_text(args.markdown_output, catalog_markdown(catalog, comparisons))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate = subparsers.add_parser("validate", help="Validate one or more evidence ZIPs")
    validate.add_argument("archives", nargs="+")
    validate.add_argument("--output", type=Path)
    validate.set_defaults(function=command_validate)

    compare = subparsers.add_parser("compare", help="Compare two ZIPs by stable observation id")
    compare.add_argument("left")
    compare.add_argument("right")
    compare.add_argument("--json-output", type=Path)
    compare.add_argument("--markdown-output", type=Path)
    compare.set_defaults(function=command_compare)

    catalog = subparsers.add_parser("catalog", help="Generate a reviewed sample catalog")
    catalog.add_argument("archives", nargs="+")
    catalog.add_argument("--labels", type=Path, required=True)
    catalog.add_argument("--json-output", type=Path, required=True)
    catalog.add_argument("--markdown-output", type=Path, required=True)
    catalog.set_defaults(function=command_catalog)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.function(args)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        sys.stderr.write(f"error: {error}\n")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
