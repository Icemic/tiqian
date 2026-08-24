// Canvas font stack resolution and fallback generation (TsHost runtime port,
// Slice 4a part 1). Ports WebFontFamilies and cssFamilyToken from
// WebCanvasTextShaper.kt.
//
// Role values on this layer are the Kotlin FontRole enum NAMES:
// "CjkText", "CjkPunctuation", "LatinText", "Symbol", "Emoji", "Unknown".
// They are NOT the markdown-lowering role strings ("cjk-text" etc.) — that is
// a different layer mapped through classifyFontRole.
//
// The adapter-layer default stacks here (latinMonospace, cjkSerif, latinSerif,
// bopomofo) are intentionally DIFFERENT from the root-default stacks in
// lifecycle.js (where DEFAULT_MONOSPACE_FONT_FAMILY etc. include variable-font
// entries). The two default sets live at different layers and differ by design.
//
// Plain script, no exports: running it installs globalThis.__TiqianCanvasFonts.
// The single consumer is the npm host (importing it for the side effect);
// duplicate installation is guarded.

export type FontRoleName =
  | "CjkText"
  | "CjkPunctuation"
  | "LatinText"
  | "Symbol"
  | "Emoji"
  | "Unknown";

type FontsForRoleFn = (role: FontRoleName | string, preferredFamilies?: string[]) => string;

type FontsFallbackStacksFn = (role: FontRoleName | string, preferredFamilies?: string[]) => string[];

type FontsPreferredFamiliesToFamilyFn = (preferredFamilies?: string[]) => string;

type FontsForRoleNameFn = (name?: string | null, preferredFamilies?: string[]) => string;

type FontsCreateFamiliesFn = (config?: WebFontFamiliesConfig) => WebFontFamiliesInstance;

type FontsCssFamilyTokenFn = (value: string) => string;

interface CanvasFontsGlobal {
  createFontFamilies: FontsCreateFamiliesFn;
  cssFamilyToken: FontsCssFamilyTokenFn;
  BOPOMOFO_FALLBACK_FAMILIES: string[];
  DEFAULT_LATIN_MONOSPACE_FONT_FAMILY: string;
  DEFAULT_CJK_SERIF_FONT_FAMILY: string;
  DEFAULT_LATIN_SERIF_FONT_FAMILY: string;
  DEFAULT_BOPOMOFO_FONT_FAMILY: string;
}

export interface WebFontFamiliesInstance {
  cjk: string;
  latin: string;
  latinMonospace: string;
  cjkSerif: string;
  latinSerif: string;
  bopomofo: string;
  forRole: FontsForRoleFn;
  fallbackStacks: FontsFallbackStacksFn;
  forRuby: FontsPreferredFamiliesToFamilyFn;
  forBopomofo: FontsPreferredFamiliesToFamilyFn;
  forRoleName: FontsForRoleNameFn;
}

interface WebFontFamiliesConfig {
  cjk?: string;
  latin?: string;
  latinMonospace?: string;
  cjkSerif?: string;
  latinSerif?: string;
  bopomofo?: string;
}

declare global {
  var __TiqianCanvasFonts: CanvasFontsGlobal;
}

(function () {
  if (globalThis.__TiqianCanvasFonts) return;

  var GENERIC_FAMILY_KEYWORDS: Record<string, boolean> = {
    "serif": true,
    "sans-serif": true,
    "sansserif": true,
    "monospace": true,
    "cursive": true,
    "fantasy": true,
    "system-ui": true,
  };

  /**
   * Quote a CSS font family token unless it is a generic keyword or already quoted.
   *
   * @param {string} value
   * @returns {string}
   */
  function cssFamilyToken(value: string): string {
    var str = String(value);
    var lower = str.toLowerCase();
    if (GENERIC_FAMILY_KEYWORDS[lower]) {
      return str;
    }
    if (str.charAt(0) === '"' || str.charAt(0) === "'") {
      return str;
    }
    return '"' + str + '"';
  }

  // Traditional Chinese system sans first: they carry the vertical tone glyphs.
  // Dedicated Bopomofo fonts stay after TC sans faces: many machines do not
  // ship them, and their metrics are not the fallback profile the web renderer
  // mirrors (ADR 0033). Ming/Song fallbacks still usually have correct marks.
  var BOPOMOFO_FALLBACK_FAMILIES: string[] = [
    "PingFang TC",
    "Hiragino Sans CNS",
    "Heiti TC",
    "Microsoft JhengHei UI",
    "Microsoft JhengHei",
    "Noto Sans CJK TC",
    "Source Han Sans TC",
    "Noto Sans Bopomofo",
    "Noto Serif Bopomofo",
    "BpmfGenYoGothic",
    "BpmfGenSenRounded",
    "Apple LiGothic",
    "Apple LiSung",
    "PMingLiU",
    "MingLiU",
    "Noto Serif CJK TC",
    "Source Han Serif TC",
    "sans-serif",
  ];

  var DEFAULT_LATIN_MONOSPACE_FONT_FAMILY: string =
    '"SFMono-Regular", Menlo, Consolas, "Liberation Mono", monospace';
  var DEFAULT_CJK_SERIF_FONT_FAMILY: string =
    '"Songti SC", "Noto Serif CJK SC", serif';
  var DEFAULT_LATIN_SERIF_FONT_FAMILY: string =
    'Georgia, "Times New Roman", serif';
  var DEFAULT_BOPOMOFO_FONT_FAMILY: string = BOPOMOFO_FALLBACK_FAMILIES.map(cssFamilyToken).join(", ");

  /**
   * Create a WebFontFamilies instance matching the Kotlin shaping adapter.
   *
   * @param {{ cjk: string, latin: string, latinMonospace?: string, cjkSerif?: string, latinSerif?: string, bopomofo?: string }} config
   * @returns {WebFontFamiliesInstance}
   */
  function createFontFamilies(config?: WebFontFamiliesConfig): WebFontFamiliesInstance {
    var cfg: Partial<WebFontFamiliesConfig> = config || {};
    var cjk = cfg.cjk || "";
    var latin = cfg.latin || "";
    var latinMonospace = cfg.latinMonospace != null ? cfg.latinMonospace : DEFAULT_LATIN_MONOSPACE_FONT_FAMILY;
    var cjkSerif = cfg.cjkSerif != null ? cfg.cjkSerif : DEFAULT_CJK_SERIF_FONT_FAMILY;
    var latinSerif = cfg.latinSerif != null ? cfg.latinSerif : DEFAULT_LATIN_SERIF_FONT_FAMILY;
    var bopomofo = cfg.bopomofo != null ? cfg.bopomofo : DEFAULT_BOPOMOFO_FONT_FAMILY;

    var roleFamilyCache: Record<string, string> = {};

    function forRole(role: FontRoleName | string, preferredFamilies?: string[]): string {
      var families = preferredFamilies || [];
      var key = role + "\u001f" + families.join("\u001f");
      if (Object.prototype.hasOwnProperty.call(roleFamilyCache, key)) {
        return roleFamilyCache[key];
      }
      var defaultFamily = (role === "LatinText") ? latin : cjk;
      var resolved: string;
      if (families.length === 0) {
        resolved = defaultFamily;
      } else if (families.length === 1) {
        var single = families[0].toLowerCase();
        if (single === "monospace") {
          resolved = (role === "LatinText") ? latinMonospace : cjk;
        } else if (single === "serif") {
          resolved = (role === "LatinText") ? latinSerif : cjkSerif;
        } else if (single === "sans-serif" || single === "sansserif") {
          resolved = defaultFamily;
        } else {
          resolved = cssFamilyToken(families[0]);
        }
      } else {
        resolved = families.map(cssFamilyToken).join(", ");
      }
      roleFamilyCache[key] = resolved;
      return resolved;
    }

    /**
     * Canvas occasionally accepts a webfont as the selected face even when that
     * face intentionally maps an unsupported character to zero advance. DOM text
     * continues through the CSS stack in that case, so measurement must probe the
     * same suffixes instead of hard-coding a family name to exclude.
     */
    function fallbackStacks(role: FontRoleName | string, preferredFamilies?: string[]): string[] {
      var families = preferredFamilies || [];
      if (families.length <= 1) {
        return [forRole(role, families)];
      }
      var result: string[] = [];
      var seen: Record<string, boolean> = {};
      for (var i = 0; i < families.length; i += 1) {
        var sub = families.slice(i);
        var formatted = sub.map(cssFamilyToken).join(", ");
        if (!seen[formatted]) {
          seen[formatted] = true;
          result.push(formatted);
        }
      }
      return result;
    }

    function forRuby(preferredFamilies?: string[]): string {
      return forRole("LatinText", preferredFamilies);
    }

    function forBopomofo(preferredFamilies?: string[]): string {
      var families = preferredFamilies || [];
      if (families.length === 0) {
        return bopomofo;
      }
      return families.map(cssFamilyToken).join(", ") + ", " + bopomofo;
    }

    function forRoleName(name?: string | null, preferredFamilies?: string[]): string {
      var role = (name === "LatinText") ? "LatinText" : "CjkText";
      return forRole(role, preferredFamilies);
    }

    return {
      cjk: cjk,
      latin: latin,
      latinMonospace: latinMonospace,
      cjkSerif: cjkSerif,
      latinSerif: latinSerif,
      bopomofo: bopomofo,
      forRole: forRole,
      fallbackStacks: fallbackStacks,
      forRuby: forRuby,
      forBopomofo: forBopomofo,
      forRoleName: forRoleName,
    };
  }

  globalThis.__TiqianCanvasFonts = {
    createFontFamilies: createFontFamilies,
    cssFamilyToken: cssFamilyToken,
    BOPOMOFO_FALLBACK_FAMILIES: BOPOMOFO_FALLBACK_FAMILIES,
    DEFAULT_LATIN_MONOSPACE_FONT_FAMILY: DEFAULT_LATIN_MONOSPACE_FONT_FAMILY,
    DEFAULT_CJK_SERIF_FONT_FAMILY: DEFAULT_CJK_SERIF_FONT_FAMILY,
    DEFAULT_LATIN_SERIF_FONT_FAMILY: DEFAULT_LATIN_SERIF_FONT_FAMILY,
    DEFAULT_BOPOMOFO_FONT_FAMILY: DEFAULT_BOPOMOFO_FONT_FAMILY,
  };
})();

