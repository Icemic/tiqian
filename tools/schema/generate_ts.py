#!/usr/bin/env python3
"""Generate the TypeScript field-array module from the assembly-record schema."""

import difflib
import json
from pathlib import Path
import sys

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
SCHEMA_PATH = REPO_ROOT / "ffi/schema/assembly-record.schema.json"
TARGET_PATH = (
    REPO_ROOT
    / "platforms/web/client/core/src/engine/web-worker/assembly-record-fields.ts"
)


def map_ts_type(type_str: str) -> str:
    if type_str in ("f64", "f32", "i32", "u32", "i64", "u64"):
        return "number"
    elif type_str == "bool":
        return "boolean"
    elif type_str == "string":
        return "string"
    elif type_str == "string[]":
        return "string[]"
    elif type_str == "enum":
        return "string"
    return "any"


def generate_ts(schema: dict) -> str:
    revision = schema["revision"]
    worker_fields = schema["surfaces"]["workerJson"]["fields"]
    scalars_by_name = {s["name"]: s for s in schema.get("scalars", [])}
    tables_by_name = {t["name"]: t for t in schema.get("tables", [])}

    lines = []
    lines.append(
        f"// Generated from ffi/schema/assembly-record.schema.json revision {revision}. "
        f"Edit the schema and run python3 tools/schema/generate_ts.py."
    )
    lines.append("")
    lines.append("export const LAYOUT_REQUEST_FIELDS = Object.freeze([")
    for field_name in worker_fields:
        lines.append(f'  "{field_name}",')
    lines.append("] as const);")
    lines.append("")
    lines.append(f"export const ASSEMBLY_RECORD_REVISION = {revision};")
    lines.append("")
    lines.append("/**")
    lines.append(" * @typedef {Object} AssemblyRecordRequest")

    for field_name in worker_fields:
        if (
            field_name in tables_by_name
            and tables_by_name[field_name].get("kind") != "scalar-string"
        ):
            table = tables_by_name[field_name]
            kind = table.get("kind")
            if kind == "offset-list":
                elem_type = map_ts_type(table.get("elementType", "i32"))
                prop_type = f"{elem_type}[]"
            elif kind == "record-list":
                inner_fields = [
                    f"{f['name']}: {map_ts_type(f['type'])}"
                    for f in table.get("fields", [])
                ]
                prop_type = f"Array.<{{{', '.join(inner_fields)}}}>"
            else:
                prop_type = "any"
        elif field_name in scalars_by_name:
            scalar = scalars_by_name[field_name]
            prop_type = map_ts_type(scalar.get("type", "any"))
        elif (
            field_name in tables_by_name
            and tables_by_name[field_name].get("kind") == "scalar-string"
        ):
            prop_type = "string"
        else:
            prop_type = "any"

        lines.append(f" * @property {{{prop_type}}} {field_name}")

    lines.append(" */")
    lines.append("")
    return "\n".join(lines)


def main():
    if not SCHEMA_PATH.exists():
        print(f"Error: schema file not found at {SCHEMA_PATH}", file=sys.stderr)
        sys.exit(1)

    with open(SCHEMA_PATH, "r", encoding="utf-8") as f:
        schema = json.load(f)

    generated = generate_ts(schema)

    check_mode = "--check" in sys.argv
    if check_mode:
        if not TARGET_PATH.exists():
            print(
                f"Error: target file {TARGET_PATH} does not exist",
                file=sys.stderr,
            )
            sys.exit(1)

        with open(TARGET_PATH, "r", encoding="utf-8") as f:
            existing = f.read()

        if existing != generated:
            diff = list(
                difflib.unified_diff(
                    existing.splitlines(keepends=True),
                    generated.splitlines(keepends=True),
                    fromfile=str(TARGET_PATH),
                    tofile="generated",
                )
            )
            first_diff = "".join(diff[:10]) if diff else "Content differs"
            print(
                f"Error: {TARGET_PATH} is stale. First diff:\n{first_diff}",
                file=sys.stderr,
            )
            sys.exit(1)

        sys.exit(0)
    else:
        TARGET_PATH.parent.mkdir(parents=True, exist_ok=True)
        with open(TARGET_PATH, "w", encoding="utf-8") as f:
            f.write(generated)


if __name__ == "__main__":
    main()
