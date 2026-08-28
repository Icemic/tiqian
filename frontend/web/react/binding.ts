// @tiqian/react — the reference React binding over the neutral core
// (2026-08-27 core-neutral wave). The binding holds the
// EnhancedElementContext built by createEnhanceContext directly; there is no
// adapter layer between React and the context verb surface.
//
// Four covered paths: mount (createEnhanceContext + mount()), options update
// (updateOptions against the applied ledger), unmount (unmount + destroy in
// the effect cleanup), and relayout-ready notification (onRelayoutReady
// subscription surfaced through the onRelayoutReady prop).

import { createElement, useEffect, useRef, useState } from "react";
import type { ReactElement } from "react";
import { createEnhanceContext } from "@tiqian/core/core/engine/context/enhance-context.js";
import type { EnhancedElementContext } from "@tiqian/core/core/engine/context/enhance-context.js";
import type { EnhancementOptions } from "@tiqian/core/core/engine/enhance/options-ledger.js";

export interface TiqianProseEventDetail {
  readonly [key: string]: unknown;
}

export interface UseTiqianProseResult {
  /** Live ref to the mounted EnhancedElementContext; null while unmounted. */
  readonly contextRef: { current: EnhancedElementContext | null };
}

// Mounts the enhancement context for a host element and keeps it alive for
// the element's React lifetime. The context is created in the effect, so the
// binding never holds a per-root object longer than the element is mounted;
// options changes flow through updateOptions, and the cleanup runs
// unmount() + destroy() in that order.
function useTiqianProse(
  element: Element | null,
  options?: EnhancementOptions,
  handlers: { onRelayoutReady?: (detail: TiqianProseEventDetail) => void } = {},
): UseTiqianProseResult {
  const contextRef = useRef<EnhancedElementContext | null>(null);
  const optionsRef = useRef(options);
  const relayoutReadyRef = useRef(handlers.onRelayoutReady);
  relayoutReadyRef.current = handlers.onRelayoutReady;

  useEffect(() => {
    if (!element) return undefined;
    const context = createEnhanceContext(element, optionsRef.current);
    contextRef.current = context;
    const unsubscribe = context.onRelayoutReady((detail) => {
      relayoutReadyRef.current?.(detail as TiqianProseEventDetail);
    });
    void context.mount();
    return () => {
      unsubscribe();
      context.unmount();
      context.destroy();
      contextRef.current = null;
    };
  }, [element]);

  useEffect(() => {
    optionsRef.current = options;
    contextRef.current?.updateOptions(options ?? {});
  }, [options]);

  return { contextRef };
}

export interface TiqianProseProps {
  /** Optional host attributes forwarded to the rendered <tiqian-prose>. */
  readonly id?: string;
  readonly className?: string;
  /** The prose source paragraphs (plain HTML children). */
  readonly children?: ReactElement | ReactElement[] | string | null;
  readonly options?: EnhancementOptions;
  readonly onRelayoutReady?: (detail: TiqianProseEventDetail) => void;
}

// Small component face: renders the <tiqian-prose> host element and drives
// the same hook from its attached instance, so React-owned markup takes the
// identical context path.
function TiqianProse(props: TiqianProseProps): ReactElement {
  const [element, setElement] = useState<Element | null>(null);
  useTiqianProse(element, props.options, { onRelayoutReady: props.onRelayoutReady });
  return createElement(
    "tiqian-prose",
    {
      id: props.id,
      className: props.className,
      ref: (value: HTMLElement | null) => setElement(value),
    },
    props.children,
  );
}

export { useTiqianProse, TiqianProse };
export type { EnhancementOptions, EnhancedElementContext };
