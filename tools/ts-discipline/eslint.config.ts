// Strict TypeScript Discipline ESLint Configuration
//
// Origin: ADR 0053 (StrictTsDiscipline)
//
// This configuration enforces a strict type discipline across the three npm packages:
//   - platforms/web/client/web-component
//   - platforms/web/client/core
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
//   7. Zero `var`: executable `var` is forbidden (const, or let when reassigned).
//      One exemption: the TypeScript printer's synthesized `var _a` hoist in emit products
//      (G2 module boundary).
//   8. prefer-const on TypeScript sources: a let that is never reassigned is a
//      const (G2 module boundary).
//   9. Zero inline `typeof import(...)` / `import(...)` in type annotations (`TSImportType`):
//      types must use top-level `import type` declarations.
//  10. Zero object-literal type assertions (`{} as T`, `<T>{...}`) and zero
//      type assertions on derived collections (`f(...) as T[]`): declare the
//      shape at the source or narrow with a type predicate (wc-s1, P/S/C 10).

import { existsSync } from "node:fs";
import path from "node:path";
import process from "node:process";
import tseslint from "typescript-eslint";

function findRepoRoot(): string {
  let current: string = process.cwd();
  while (current !== path.dirname(current)) {
    if (existsSync(path.join(current, ".git"))) {
      return current;
    }
    current = path.dirname(current);
  }
  return process.cwd();
}

const repoRoot: string = findRepoRoot();

const targetPackages: readonly string[] = [
  "platforms/web/client/web-component",
  "platforms/web/client/core",
  "platforms/web/client/react",
  "ffi/js/npm",
  "demo/web/tests",
];

const patterns: readonly string[] = [
  ...targetPackages.flatMap((pkg: string): readonly string[] => [
    path.join(repoRoot, pkg, "**/*.js"),
    path.join(repoRoot, pkg, "**/*.mjs"),
    path.join(repoRoot, pkg, "**/*.ts"),
    path.join(repoRoot, pkg, "**/*.d.ts"),
    path.join(repoRoot, pkg, "*.d.ts"),
  ]),
  ...targetPackages.flatMap((pkg: string): readonly string[] => [
    `${pkg}/**/*.js`,
    `${pkg}/**/*.mjs`,
    `${pkg}/**/*.ts`,
    `${pkg}/**/*.d.ts`,
    `${pkg}/*.d.ts`,
  ]),
];

// TypeScript sources only: prefer-const is a source discipline; handwritten
// .mjs test files and printer emit products stay out of its scope.
const tsPatterns: readonly string[] = [
  ...targetPackages.flatMap((pkg: string): readonly string[] => [
    path.join(repoRoot, pkg, "**/*.ts"),
    path.join(repoRoot, pkg, "**/*.d.ts"),
    path.join(repoRoot, pkg, "*.d.ts"),
  ]),
  ...targetPackages.flatMap((pkg: string): readonly string[] => [
    `${pkg}/**/*.ts`,
    `${pkg}/**/*.d.ts`,
    `${pkg}/*.d.ts`,
  ]),
];

const ignores: readonly string[] = [
  "**/node_modules/**",
  "ffi/js/npm/runtime/**",
  "**/runtime/**",
  "**/build/**",
  "**/.gradle/**",
  "**/.b2-tmp/**",
  "**/target/**",
  "demo/font-diagnostics/**",
  "demo/android/**",
  "demo/apple/**",
  "demo/src/**",
  "demo/web-history/**",
  "demo/web/dist/**",
  "demo/web/main.js",
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
  "platforms/web/client/astro/**",
  "platforms/web/client/sveltekit/**",
];

export default tseslint.config(
  {
    ignores: ignores as string[],
  },
  {
    files: patterns as string[],
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
      "@typescript-eslint/consistent-type-assertions": [
        "error",
        {
          assertionStyle: "as",
          objectLiteralTypeAssertions: "never",
          arrayLiteralTypeAssertions: "never",
        },
      ],
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
        {
          selector:
            "VariableDeclaration[kind=\"var\"]:not(VariableDeclaration:has(VariableDeclarator[id.name=\"_a\"]))",
          message:
            "var is forbidden; use const, or let when reassigned. Exemption: the TypeScript printer's synthesized `var _a` hoist in emit products (G2 module boundary).",
        },
        {
          selector: "TSImportType",
          message:
            "Inline `typeof import(...)` or `import(...)` type annotations are forbidden; use named top-level `import type` declarations instead.",
        },
        {
          selector: 'TSAsExpression[expression.type="CallExpression"][typeAnnotation.type="TSArrayType"]',
          message:
            "Type assertion on a derived collection: declare the element type at the source or narrow with a type predicate instead of casting the call result (wc-s1, P/S/C 10).",
        },
      ],
    },
  },
  {
    files: tsPatterns as string[],
    rules: {
      "prefer-const": "error",
    },
  },
);
