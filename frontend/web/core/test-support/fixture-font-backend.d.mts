// Type declarations for fixture-font-backend.mjs

export interface FixtureFontBackend {
  shapeJson: (requestJson: string) => string;
  metricsJson: (requestJson: string) => string;
  uninstall: () => void;
}

export function installFixtureFontBackend(): FixtureFontBackend;
export function installThrowingFontBackend(error: Error): FixtureFontBackend;
