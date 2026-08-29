import { fileURLToPath } from "node:url";
import type { AstroIntegration } from "astro";

import { hoistTiqianAstroDirectory } from "./transport.js";
import {
  normalizeTiqianAstroTablesOptions,
  shipTiqianAstroTables,
  tiqianAstroTableMiddleware,
  tiqianAstroTables,
} from "./tables.js";

const VIRTUAL_MODULE_ID = "virtual:@tiqian/astro/preparer";
const RESOLVED_VIRTUAL_MODULE_ID = `\0${VIRTUAL_MODULE_ID}`;

export interface TiqianAstroFontFaceOption {
  readonly source: string | URL;
  readonly [key: string]: unknown;
}

export interface TiqianAstroFontStylesheetOption {
  readonly source: string | URL;
  readonly [key: string]: unknown;
}

export interface TiqianAstroSnapshotOption {
  readonly maxWidthPx: number;
}

export interface TiqianAstroOptions {
  readonly typography?: unknown;
  readonly fontStylesheets?: readonly TiqianAstroFontStylesheetOption[];
  readonly faces?: readonly TiqianAstroFontFaceOption[];
  readonly paragraphSelector?: string;
  readonly skippedAncestorSelector?: string;
  readonly snapshot?: TiqianAstroSnapshotOption;
  readonly tables?: unknown;
  readonly projectSnapshotParagraph?: unknown;
  readonly precomputer?: unknown;
}

export interface TiqianAstroSerializableOptions {
  readonly typography: unknown;
  readonly paragraphSelector?: string;
  readonly skippedAncestorSelector?: string;
  readonly fontStylesheets?: readonly unknown[];
  readonly faces?: readonly unknown[];
}

export interface TiqianAstroViteEnvironmentConfig {
  readonly resolve: {
    readonly external: string[];
  };
}

type ConfigSetupHook = NonNullable<AstroIntegration["hooks"]["astro:config:setup"]>;
type ConfigDoneHook = NonNullable<AstroIntegration["hooks"]["astro:config:done"]>;
type ServerSetupHook = NonNullable<AstroIntegration["hooks"]["astro:server:setup"]>;
type BuildDoneHook = NonNullable<AstroIntegration["hooks"]["astro:build:done"]>;

type ConfigSetupParams = Parameters<ConfigSetupHook>[0];
type ConfigDoneParams = Parameters<ConfigDoneHook>[0];
type ServerSetupParams = Parameters<ServerSetupHook>[0];
type BuildDoneParams = Parameters<BuildDoneHook>[0];

function serializableSource(source: unknown): string {
  if (source instanceof URL) return source.href;
  if (typeof source === "string") return source;
  throw new Error("TiqianAstroFontSourceMustBePathOrUrl");
}

function serializableOptions(options: TiqianAstroOptions): TiqianAstroSerializableOptions {
  if (options.projectSnapshotParagraph != null || options.precomputer != null) {
    throw new Error("TiqianAstroNonSerializableHtmlPreparerOption");
  }
  return {
    typography: options.typography,
    ...(options.paragraphSelector == null ? {} : { paragraphSelector: options.paragraphSelector }),
    ...(options.skippedAncestorSelector == null
      ? {}
      : { skippedAncestorSelector: options.skippedAncestorSelector }),
    ...(options.fontStylesheets == null ? {} : {
      fontStylesheets: options.fontStylesheets.map((stylesheet: TiqianAstroFontStylesheetOption) => ({
        ...stylesheet,
        source: serializableSource(stylesheet.source),
      })),
    }),
    ...(options.faces == null ? {} : {
      faces: options.faces.map((face: TiqianAstroFontFaceOption) => ({
        ...face,
        source: serializableSource(face.source),
      })),
    }),
  };
}

export function tiqian(options: TiqianAstroOptions = {}): AstroIntegration {
  const precomputeEnabled = options.typography != null;
  if (!precomputeEnabled && (options.fontStylesheets != null || options.faces != null || options.snapshot != null)) {
    throw new Error("TiqianAstroPrecomputeTypographyRequired");
  }
  // The tables step is independent of the preparer step: a host with its own
  // shaping pipeline configures only table delivery.
  const tablesOptions = normalizeTiqianAstroTablesOptions(options.tables);
  const tables = tiqianAstroTables(tablesOptions);
  const htmlOptions = precomputeEnabled ? serializableOptions(options) : null;
  const defaultSnapshot = options.snapshot == null ? null : {
    maxWidthPx: Number(options.snapshot.maxWidthPx),
  };
  if (defaultSnapshot && (!Number.isFinite(defaultSnapshot.maxWidthPx) || defaultSnapshot.maxWidthPx <= 0)) {
    throw new Error("InvalidMaximumMeasure");
  }
  const virtualSource = precomputeEnabled ? `
      import { createHtmlPreparer } from "@tiqian/precompute/precompute-html";
      ${
      tablesOptions == null ? "" : `import { createSnapshotTableFileTransport } from "@tiqian/precompute/transport";
      const tableTransport = createSnapshotTableFileTransport(${JSON.stringify(tablesOptions)});`
    }
      const preparer = await createHtmlPreparer(${JSON.stringify(htmlOptions)});
      const defaultSnapshot = ${JSON.stringify(defaultSnapshot)};
      export async function prepareTiqianHtml(html, options = {}) {
        const snapshot = options.snapshot === undefined ? defaultSnapshot : options.snapshot;
        const result = await preparer.prepare(html, { ...options, ...(snapshot == null ? {} : { snapshot }) });
        ${
      tablesOptions == null
        ? "return result;"
        : `// The native call freezes per-item table bytes; the transport serves
        // them and the root attribute points the runtime at the URL.
        if (result.tables == null) return result;
        return {
          ...result,
          rootAttributes: { ...result.rootAttributes, "tq-tables": tableTransport.write(result.tables) },
        };`
    }
      }
    ` : `
      export async function prepareTiqianHtml(html) {
        return {
          html: String(html),
          rootAttributes: {},
          bundle: null,
          clientBundle: null,
          serverAssets: null,
          issues: [],
        };
      }
    `;
  let buildOutput = "static";

  return {
    name: "@tiqian/astro",
    hooks: {
      "astro:config:setup": ({ updateConfig }: ConfigSetupParams): void => {
        updateConfig({
          vite: {
            plugins: [{
              name: "@tiqian/astro-preparer",
              enforce: "pre",
              // `@tiqian/precompute` reads font and style files relative to
              // its own modules and loads a native addon at first use.
              // Inlining it into a server chunk moves those lookups onto the
              // chunk path. The top-level `ssr.external` key reaches only the
              // `ssr` environment; the prerendered chunks come from the
              // `prerender` environment, so every server environment names
              // the package here. Snapshot publications from a fork install
              // the same package under the registry's scope, so both names
              // stay external.
              configEnvironment(name: string): TiqianAstroViteEnvironmentConfig | undefined {
                if (name !== "ssr" && name !== "prerender" && name !== "astro") return undefined;
                return { resolve: { external: ["@tiqian/precompute", "@losses/precompute"] } };
              },
              resolveId(id: string): string | null {
                return id === VIRTUAL_MODULE_ID ? RESOLVED_VIRTUAL_MODULE_ID : null;
              },
              load(id: string): string | null {
                return id === RESOLVED_VIRTUAL_MODULE_ID ? virtualSource : null;
              },
            }],
          },
        });
      },
      "astro:config:done": ({ buildOutput: output, injectTypes }: ConfigDoneParams): void => {
        // `astro check` currently invokes this hook without a build output.
        // Enforce the transport boundary only when Astro is actually
        // configuring a static or server build.
        if (output != null) buildOutput = output;
        if (output != null && output !== "static") {
          throw new Error("TiqianAstroStaticOutputRequired");
        }
        injectTypes({
          filename: "tiqian-astro.d.ts",
          content: `
            declare module "virtual:@tiqian/astro/preparer" {
              import type { HtmlPrepareOptions, PreparedHtml } from "@tiqian/precompute/precompute-html";
              export function prepareTiqianHtml(
                html: string,
                options?: HtmlPrepareOptions,
              ): Promise<PreparedHtml>;
            }
          `,
        });
      },
      "astro:server:setup": ({ server }: ServerSetupParams): void => {
        // Dev delivery of snapshot tables: the preparer's per-item freezes
        // write the transport directory during page renders, and the dev
        // server serves those bytes under the built output's URL.
        if (tables != null) {
          server.middlewares.use(tiqianAstroTableMiddleware(tables));
        }
      },
      "astro:build:done": async ({ dir, logger }: BuildDoneParams): Promise<void> => {
        if (buildOutput !== "static") return;
        if (tables != null) {
          await shipTiqianAstroTables(tables, fileURLToPath(dir), logger);
        }
        const result = await hoistTiqianAstroDirectory(fileURLToPath(dir));
        if (result.snapshotCount > 0) {
          logger.info(`hoisted ${result.snapshotCount} Tiqian snapshots across ${result.pageCount} pages`);
        }
      },
    },
  };
}

export default tiqian;
