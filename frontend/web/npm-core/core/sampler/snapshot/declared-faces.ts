// DeclaredFaceEvidence: host-declared @font-face text as a validation
// candidate source. Hosts that manage webfonts through the FontFace API
// never produce CSSFontFaceRule entries, so CSSOM collection alone cannot
// verify their pages. declareTiqianFontFaces hands the collector the same
// CSS text the host registered, parsed through a constructed sheet that
// never enters the document.
//
// The registry is module-level state; no globalThis name is added (ADR 0053
// DeclaredFaceEvidence). baseUrl must be passed explicitly: fetched CSS
// text carries src URLs relative to the CSS file address, and the collector
// resolves them against this value (a constructed sheet has no href, so the
// document.baseURI fallback would resolve them against the page address).
// Declarations only supplement the validation candidate set; the
// document.fonts.load and advance-geometry probes still gate adoption, so a
// forged declaration cannot bypass build-time evidence (fail-closed).

export interface DeclaredFaceDiagnostic {
  kind: "DeclaredTextInvalid" | "DeclaredRulesUnavailable";
  error: string;
}

export interface DeclaredFaceSheet {
  rules: CSSRuleList;
  baseUrl: string;
}

interface DeclaredFaceParseOutcome {
  rules: CSSRuleList | null;
  baseUrl: string;
  diagnostics: DeclaredFaceDiagnostic[];
}

interface DeclaredFaceEntry {
  cssText: string;
  baseUrl: string;
  refCount: number;
  outcome: DeclaredFaceParseOutcome;
}

interface DeclaredFaceOptions {
  baseUrl?: unknown;
}

type DeclaredFaceVoidCallbackFn = () => void;

type DeclaredFaceUnsubscribeBoolFn = () => boolean;

const entries = new Map<string, DeclaredFaceEntry>();
const changeListeners = new Set<DeclaredFaceVoidCallbackFn>();

function entryKey(cssText: string, baseUrl: string): string {
  return cssText + "\n" + baseUrl;
}

// Parse ladder: constructed CSSStyleSheet with the declared baseUrl, then a
// detached <style> element, then absence. replaceSync throwing (syntax
// error, @import NotAllowedError) records DeclaredTextInvalid with the
// exception name; environments where no rules are readable record
// DeclaredRulesUnavailable. Both outcomes keep the declaration registered
// while contributing no faces.
function parseDeclaredText(cssText: string, baseUrl: string): DeclaredFaceParseOutcome {
  const outcome: DeclaredFaceParseOutcome = { rules: null, baseUrl, diagnostics: [] };
  const sheetConstructor = globalThis.CSSStyleSheet;
  if (typeof sheetConstructor === "function") {
    const sheet = new sheetConstructor(baseUrl ? { baseURL: baseUrl } : {});
    try {
      sheet.replaceSync(cssText);
    } catch (error) {
      outcome.diagnostics.push({
        kind: "DeclaredTextInvalid",
        error: String((error && (error as { name?: unknown }).name) || error),
      });
      return outcome;
    }
    try {
      outcome.rules = sheet.cssRules;
    } catch (error) {
      outcome.diagnostics.push({
        kind: "DeclaredRulesUnavailable",
        error: String((error && (error as { name?: unknown }).name) || error),
      });
      return outcome;
    }
    if (!outcome.rules) {
      outcome.diagnostics.push({ kind: "DeclaredRulesUnavailable", error: "" });
    }
    return outcome;
  }
  const documentObject = globalThis.document;
  if (documentObject && typeof documentObject.createElement === "function") {
    const style = documentObject.createElement("style");
    style.textContent = cssText;
    let sheet: CSSStyleSheet | null = null;
    try {
      sheet = style.sheet;
    } catch {
      sheet = null;
    }
    if (sheet && sheet.cssRules) {
      outcome.rules = sheet.cssRules;
      return outcome;
    }
  }
  outcome.diagnostics.push({ kind: "DeclaredRulesUnavailable", error: "" });
  return outcome;
}

function notifyChanged(): void {
  for (const listener of changeListeners) listener();
}

/**
 * Register host-declared @font-face CSS text as a validation candidate
 * source. `options.baseUrl` is the address the declared text's relative
 * URLs resolve against. Re-registering the same `(cssText, baseUrl)` pair
 * increments a reference count; the returned function decrements it and
 * removes the declaration when it reaches zero. Blank text is a no-op.
 * Registry changes notify listeners synchronously (listeners must not
 * execute validation inline).
 */
export function declareTiqianFontFaces(cssText: unknown, options: DeclaredFaceOptions = {}): DeclaredFaceVoidCallbackFn {
  if (typeof cssText !== "string" || cssText.trim() === "") {
    return () => {};
  }
  const baseUrl = typeof options.baseUrl === "string" ? options.baseUrl : "";
  const key = entryKey(cssText, baseUrl);
  const existing = entries.get(key);
  if (existing) {
    existing.refCount += 1;
  } else {
    entries.set(key, {
      cssText,
      baseUrl,
      refCount: 1,
      outcome: parseDeclaredText(cssText, baseUrl),
    });
    notifyChanged();
  }
  let active = true;
  return () => {
    if (!active) return;
    active = false;
    const entry = entries.get(key);
    if (!entry) return;
    entry.refCount -= 1;
    if (entry.refCount <= 0) {
      entries.delete(key);
      notifyChanged();
    }
  };
}

/** Parseable declared sheets, declaration order preserved. */
export function declaredFaceSheets(): DeclaredFaceSheet[] {
  const sheets: DeclaredFaceSheet[] = [];
  for (const entry of entries.values()) {
    if (entry.outcome.rules) {
      sheets.push({ rules: entry.outcome.rules, baseUrl: entry.baseUrl });
    }
  }
  return sheets;
}

/** Parse outcomes for declarations that contributed no faces. */
export function declaredFacesDiagnostics(): DeclaredFaceDiagnostic[] {
  const diagnostics: DeclaredFaceDiagnostic[] = [];
  for (const entry of entries.values()) {
    if (!entry.outcome.rules) diagnostics.push(...entry.outcome.diagnostics);
  }
  return diagnostics;
}

/** Subscribe to registry changes; returns an unsubscribe function. */
export function onDeclaredFacesChanged(listener: DeclaredFaceVoidCallbackFn): DeclaredFaceUnsubscribeBoolFn {
  changeListeners.add(listener);
  return () => changeListeners.delete(listener);
}
