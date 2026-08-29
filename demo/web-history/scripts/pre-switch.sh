#!/usr/bin/env bash
# Pre-checkout hygiene required by the spec: drop the kit caches and kill any
# stray kit processes so a commit switch never serves stale artifacts. The kit
# runs a plain node http server (no parcel); the bracketed regexes never match
# this script's own command line, so it cannot kill its caller.
set -u
pkill -f 'oneshot-history-harnes[s]' 2>/dev/null || true
pkill -f 'remote-debugging-port=990[2]' 2>/dev/null || true
rm -rf demo/web-history/.parcel-cache demo/web-history/.cache
exit 0
