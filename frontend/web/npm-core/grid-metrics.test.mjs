import assert from "node:assert/strict";
import test from "node:test";

import {
  lineLengthGridCellCount,
  lineLengthGridMeasure,
} from "./core/sampler/grid-metrics.js";

test("responsive invalidation follows the engine line-length grid", () => {
  assert.equal(lineLengthGridCellCount(912, 15), 60);
  assert.equal(lineLengthGridCellCount(911, 15), 60);
  assert.equal(lineLengthGridCellCount(900, 15), 60);
  assert.equal(lineLengthGridCellCount(899, 15), 59);
  assert.equal(lineLengthGridCellCount(0, 15), 1);
  assert.equal(lineLengthGridCellCount(320, 0), null);
  assert.equal(lineLengthGridMeasure(912, 15), 900);
  assert.equal(lineLengthGridMeasure(911, 15), 900);
  assert.notEqual(lineLengthGridMeasure(10, 15), lineLengthGridMeasure(12, 15));
  assert.equal(lineLengthGridCellCount(305.98, 15.3), 19);
  assert.equal(lineLengthGridCellCount(306, 15.3), 20);
  assert.equal(lineLengthGridCellCount(306.02, 15.3), 20);
  assert.equal(lineLengthGridMeasure(305.98, 15.3), Math.fround(19 * Math.fround(15.3)));
  assert.equal(lineLengthGridMeasure(306.02, 15.3), 306);
});
