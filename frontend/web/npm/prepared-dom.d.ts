// Type surface of the prepared-DOM module. This package exports the implementation
// directly so browser runtimes, server embedders, and downstream packages share one renderer.

export declare function renderPreparedParagraph(
  planOrJson: unknown,
  typographyOrLocale?: unknown,
): string;
