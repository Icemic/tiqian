// Browser metrics bridge adapter (TsHost runtime port, Slice 4a part 4).
// Adapts the canvas font metrics resolver and canvas text shaper to the
// JSON-string ABI expected by precomputeParagraphWithBrowserMetrics.
//
// Plain script, no exports: running it installs
// globalThis.__TiqianBrowserMetricsBridge. Two consumers share this file as
// the single source of truth: the npm host (importing it for the side effect)
// and the Kotlin runtime bundle, into which a future gradle bridge task will
// embed this source verbatim. Double installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals. Use var
// declarations.

(function () {
  if (globalThis.__TiqianBrowserMetricsBridge) return;

  /**
   * Create a browser metrics bridge instance.
   *
   * @param {{ fonts: Object, cjkDashCapability: Object|null, env: { createCanvasContext: Function, createProbeElement: Function, attachProbe: Function } }} options
   * @returns {{ shapeJson: (requestJson: string) => string, metricsJson: (requestJson: string) => string }}
   */
  function createBrowserMetricsBridge(options) {
    var opts = options || {};
    var fonts = opts.fonts;
    var cjkDashCapability = opts.cjkDashCapability != null ? opts.cjkDashCapability : null;
    var env = opts.env || {};

    var shaper = globalThis.__TiqianCanvasShaping.createTextShaper(fonts, cjkDashCapability, env);
    var resolver = globalThis.__TiqianCanvasMetrics.createMetricsResolver(fonts, env.createCanvasContext);

    function shapeJson(requestJson) {
      var wireInput = JSON.parse(requestJson);
      var fontDecision = wireInput.fontDecision;
      var shaperInput = {
        text: wireInput.text,
        range: wireInput.range,
        style: wireInput.style,
        fontDecision: {
          role: fontDecision ? fontDecision.role : "",
          candidate: {
            key: fontDecision ? fontDecision.candidateKey : "",
          },
        },
        displayText: wireInput.displayText,
      };
      var result = shaper.shape(shaperInput);
      return JSON.stringify(result);
    }

    function metricsJson(requestJson) {
      var request = JSON.parse(requestJson);
      var result = resolver.resolve(request);
      return JSON.stringify(result);
    }

    return {
      shapeJson: shapeJson,
      metricsJson: metricsJson,
    };
  }

  globalThis.__TiqianBrowserMetricsBridge = {
    createBrowserMetricsBridge: createBrowserMetricsBridge,
  };
})();
