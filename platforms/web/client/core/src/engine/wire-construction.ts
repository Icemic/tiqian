// ffi wire constructors.
//
// The generated @tiqian/ffi interfaces carry a required
// __doNotUseOrImplementIt brand (a unique symbol per wire type), so an
// object literal cannot satisfy them and a cast cannot be avoided at the
// boundary. Host data enters the ffi request channel through the named
// constructors below; each constructor holds the single assertion for its
// wire type. The ts-discipline gate bans object-literal and
// derived-collection assertions everywhere else, so a wire value can only
// be constructed here.

import type {
  DecorationWire,
  InlineBoxWire,
  InlineObjectWire,
  LineBreakSpanWire,
  PrepareParagraphRequest,
  RenderInlineBoxWire,
  SemanticSpanWire,
  TextSpanWire,
  WorkerLayoutRequest,
} from "@tiqian/ffi";

type WireFields<T> = Omit<T, "__doNotUseOrImplementIt">;

export function textSpanWires(fields: WireFields<TextSpanWire>[]): TextSpanWire[] {
  return fields as TextSpanWire[];
}

export function inlineBoxWires(fields: WireFields<InlineBoxWire>[]): InlineBoxWire[] {
  return fields as InlineBoxWire[];
}

export function lineBreakSpanWires(fields: WireFields<LineBreakSpanWire>[]): LineBreakSpanWire[] {
  return fields as LineBreakSpanWire[];
}

export function inlineObjectWires(fields: WireFields<InlineObjectWire>[]): InlineObjectWire[] {
  return fields as InlineObjectWire[];
}

export function decorationWires(fields: WireFields<DecorationWire>[]): DecorationWire[] {
  return fields as DecorationWire[];
}

export function semanticSpanWires(fields: WireFields<SemanticSpanWire>[]): SemanticSpanWire[] {
  return fields as SemanticSpanWire[];
}

export function renderInlineBoxWires(fields: WireFields<RenderInlineBoxWire>[]): RenderInlineBoxWire[] {
  return fields as RenderInlineBoxWire[];
}

export function prepareParagraphRequestWire(fields: WireFields<PrepareParagraphRequest>): PrepareParagraphRequest {
  return fields as PrepareParagraphRequest;
}

export function workerLayoutRequestWire(fields: WireFields<WorkerLayoutRequest>): WorkerLayoutRequest {
  return fields as WorkerLayoutRequest;
}
