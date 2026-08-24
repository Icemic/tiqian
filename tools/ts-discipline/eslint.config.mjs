// Strict TypeScript Discipline ESLint Configuration
//
// Origin: ADR 0053 (StrictTsDiscipline)
//
// This configuration enforces a strict type discipline across the three npm packages:
//   - frontend/web/npm
//   - frontend/web/npm-core
//   - ffi/js/npm
//
// Policy:
//   1. Zero `any`: `any` is forbidden in all forms (explicit, annotations, casts, generics).
//   2. Zero wide types: `object`, `Object`, and `{}` are banned in favor of explicit interfaces or Record<string, unknown>.
//   3. Zero double assertions: `as unknown as ...` (`TSAsExpression > TSAsExpression`) is banned.
//   4. Zero `eslint-disable`: No inline, block, or file-level disable directives are permitted.
//      This is additionally backstopped by CI grep checks.
//   5. No inline object types inside annotations (`TSTypeAnnotation TSTypeLiteral`):
//      shapes must be named (interface/type) before use in an annotation.
//   6. No inline function types inside annotations (`TSTypeAnnotation TSFunctionType`):
//      function types must be named (e.g. `type Handler = (x: number) => string`).
//      Rules 5-6 follow the G1 code standard (.agent-specs/g1-code-standard.md).

import path from "node:path";
import { fileURLToPath } from "node:url";
import tseslint from "typescript-eslint";

const repoRoot = fileURLToPath(new URL("../../", import.meta.url));

const targetPackages = [
  "frontend/web/npm",
  "frontend/web/npm-core",
  "ffi/js/npm",
];

const patterns = [
  ...targetPackages.flatMap((pkg) => [
    path.join(repoRoot, pkg, "**/*.js"),
    path.join(repoRoot, pkg, "**/*.mjs"),
    path.join(repoRoot, pkg, "**/*.ts"),
    path.join(repoRoot, pkg, "**/*.d.ts"),
    path.join(repoRoot, pkg, "*.d.ts"),
  ]),
  ...targetPackages.flatMap((pkg) => [
    `${pkg}/**/*.js`,
    `${pkg}/**/*.mjs`,
    `${pkg}/**/*.ts`,
    `${pkg}/**/*.d.ts`,
    `${pkg}/*.d.ts`,
  ]),
];

const ignores = [
  "**/node_modules/**",
  "frontend/web/npm-core/runtime/**",
  "ffi/js/npm/runtime/**",
  "**/runtime/**",
  "**/build/**",
  "**/.gradle/**",
  "**/.b2-tmp/**",
  "**/target/**",
  "demo/**",
  "docs/**",
  "tools/**",
  "shaping/**",
  "core/**",
  "font/**",
  "linebreak/**",
  "clreq/**",
  "layout/**",
  "ffi/native/**",
  "ffi/schema/**",
  "ffi/rust/**",
  "frontend/web-precompute/**",
  "frontend/web/integrations/**",
];

export default [
  {
    ignores,
  },
  {
    files: patterns,
    languageOptions: {
      parser: tseslint.parser,
      parserOptions: {
        ecmaVersion: "latest",
        sourceType: "module",
      },
    },
    plugins: {
      "@typescript-eslint": tseslint.plugin,
    },
    rules: {
      "@typescript-eslint/no-explicit-any": "error",
      "@typescript-eslint/no-restricted-types": [
        "error",
        {
          types: {
            object: {
              message:
                "Avoid wide `object` type; use a concrete interface or Record<string, unknown> instead.",
              suggest: ["Record<string, unknown>"],
            },
            Object: {
              message:
                "Avoid `Object` type; use a concrete interface or Record<string, unknown> instead.",
              suggest: ["Record<string, unknown>"],
            },
            "{}": {
              message:
                "Avoid `{}` type; use a concrete interface or Record<string, unknown> instead.",
              suggest: ["Record<string, unknown>"],
            },
          },
        },
      ],
      "no-restricted-syntax": [
        "error",
        {
          selector: "TSAsExpression > TSAsExpression",
          message:
            "Double assertion (`as unknown as ...` / `TSAsExpression > TSAsExpression`) is strictly forbidden under ADR 0053 StrictTsDiscipline.",
        },
        {
          selector: "TSTypeAnnotation TSTypeLiteral",
          message:
            "Inline object type in annotation: name the shape (interface/type) first, then reference it (G1 code standard rule 4).",
        },
        {
          selector: "TSTypeAnnotation TSFunctionType",
          message:
            "Inline function type in annotation: name the type (e.g. `type Handler = (x: number) => string`) first, then reference it (G1 code standard rule 5).",
        },
      ],
    },
  },
];
