#!/usr/bin/env python3
"""Generate Rust types and specs from the assembly-record schema."""

import difflib
import json
from pathlib import Path
import re
import sys

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
SCHEMA_PATH = REPO_ROOT / "ffi/schema/assembly-record.schema.json"
TARGET_PATH = REPO_ROOT / "ffi/rust/tiqian/src/assembly_record_spec.rs"


def camel_to_snake(name: str) -> str:
    s = re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()
    return s


def enum_type_name(enum_name: str) -> str:
    return enum_name[0].upper() + enum_name[1:] + "Code"


def table_spec_struct_name(table_name: str) -> str:
    base = table_name
    if base.endswith("es"):
        base = base[:-2]
    elif base.endswith("s"):
        base = base[:-1]
    return base[0].upper() + base[1:] + "Spec"


def map_rust_field_type(field: dict) -> str:
    if "rustType" in field:
        return field["rustType"]
    t = field.get("type")
    if t == "f64":
        return "f64"
    elif t in ("f32", "i32", "bool"):
        return t
    elif t == "string":
        return "String"
    elif t == "string[]":
        return "Vec<String>"
    elif t == "enum":
        return enum_type_name(field["enum"])
    return "String"


def map_rust_field_name(field_name: str) -> str:
    if field_name == "fontFamilies":
        return "families"
    return camel_to_snake(field_name)


def generate_rust(schema: dict) -> str:
    revision = schema["revision"]
    enums = schema.get("enums", [])
    tables = [
        t
        for t in schema.get("tables", [])
        if t.get("surfaces", {}).get("rustAbi", True) is not False
    ]
    scalars_by_name = {s["name"]: s for s in schema.get("scalars", [])}
    tables_by_name = {t["name"]: t for t in schema.get("tables", [])}
    pack_order = schema["surfaces"]["rustAbi"]["packOrder"]

    lines = []
    lines.append(
        f"//! Generated from ffi/schema/assembly-record.schema.json revision {revision}."
    )
    lines.append(
        "//! Edit the schema and run python3 tools/schema/generate_rust.py."
    )
    lines.append("//!")
    lines.append(
        "//! Note: inlineObjects table has rustAbi set to false, so it does not appear in the Rust ABI."
    )
    lines.append("")

    # Enums
    for enum_def in enums:
        type_name = enum_type_name(enum_def["name"])
        doc = ""
        if enum_def["name"] == "lineBreakPolicy":
            doc = "/// Line-break policy codes of the request protocol."
        elif enum_def["name"] == "inlineBoxOuterSpacing":
            doc = "/// Inline-box outer spacing codes of the request protocol."
        else:
            doc = f"/// {type_name} codes of the request protocol."

        lines.append(doc)
        lines.append("#[derive(Debug, Clone, Copy, PartialEq, Eq)]")
        lines.append(f"pub enum {type_name} {{")
        for val in enum_def.get("values", []):
            lines.append(f"    {val['name']} = {val['rustAbiCode']},")
        lines.append("}")
        lines.append("")
        lines.append(f"impl {type_name} {{")
        lines.append("    /// Wire code of the variant.")
        lines.append("    pub fn code(self) -> i32 {")
        lines.append("        match self {")
        for val in enum_def.get("values", []):
            lines.append(
                f"            {type_name}::{val['name']} => {val['rustAbiCode']},"
            )
        lines.append("        }")
        lines.append("    }")
        lines.append("}")
        lines.append("")

    # Table record-list structs
    for table in tables:
        if table.get("kind") != "record-list":
            continue
        struct_name = table_spec_struct_name(table["name"])
        doc = ""
        if table["name"] == "textSpans":
            doc = "/// One styled text span. Ranges count UTF-16 code units."
        elif table["name"] == "lineBreakSpans":
            doc = "/// One line-break policy span. Ranges count UTF-16 code units."
        elif table["name"] == "inlineBoxes":
            doc = "/// One inline box. Ranges count UTF-16 code units."
        else:
            doc = f"/// {struct_name} record. Ranges count UTF-16 code units."

        has_heap = False
        has_float = False
        fields = table.get("fields", [])
        for f in fields:
            ftype = map_rust_field_type(f)
            if ftype in ("f32", "f64"):
                has_float = True
            elif ftype.startswith("Vec<") or ftype == "String":
                has_heap = True

        if has_heap:
            derive = "#[derive(Debug, Clone, PartialEq)]"
        elif has_float:
            derive = "#[derive(Debug, Clone, Copy, PartialEq)]"
        else:
            derive = "#[derive(Debug, Clone, Copy, PartialEq, Eq)]"

        lines.append(doc)
        lines.append(derive)
        lines.append(f"pub struct {struct_name} {{")
        for f in fields:
            fname = map_rust_field_name(f["name"])
            ftype = map_rust_field_type(f)
            lines.append(f"    pub {fname}: {ftype},")
        lines.append("}")
        lines.append("")

    # LayoutRequest struct
    lines.append(
        "/// Engine-level layout request. Domain validation (empty paragraph, font"
    )
    lines.append(
        "/// ranges, span geometry) belongs to the caller; the engine re-checks the"
    )
    lines.append("/// packed structure and reports named protocol errors.")
    lines.append("#[derive(Debug, Clone, PartialEq)]")
    lines.append("pub struct LayoutRequest {")
    for field_name in pack_order:
        if field_name in scalars_by_name:
            scalar = scalars_by_name[field_name]
            fname = map_rust_field_name(field_name)
            ftype = map_rust_field_type(scalar)
            lines.append(f"    pub {fname}: {ftype},")
        elif field_name in tables_by_name:
            table = tables_by_name[field_name]
            fname = map_rust_field_name(field_name)
            kind = table.get("kind")
            if kind == "offset-list":
                elem_type = map_rust_field_type(
                    {"type": table.get("elementType", "i32")}
                )
                ftype = f"Vec<{elem_type}>"
            elif kind == "record-list":
                struct_name = table_spec_struct_name(field_name)
                ftype = f"Vec<{struct_name}>"
            elif kind == "scalar-string":
                ftype = "String"
            else:
                ftype = "String"
            lines.append(f"    pub {fname}: {ftype},")
    lines.append("}")
    lines.append("")

    return "\n".join(lines)


def main():
    if not SCHEMA_PATH.exists():
        print(f"Error: schema file not found at {SCHEMA_PATH}", file=sys.stderr)
        sys.exit(1)

    with open(SCHEMA_PATH, "r", encoding="utf-8") as f:
        schema = json.load(f)

    generated = generate_rust(schema)

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
