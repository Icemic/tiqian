#ifndef TIQIAN_PLAN_ABI_H
#define TIQIAN_PLAN_ABI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Tiqian plan packed ABI (corrective-2).
 *
 * The engine is the single writer; Rust is the reader. The bytes are the
 * single cross-boundary payload. This header is the single source of truth
 * for the packed layout; the Kotlin writer in engine/nativeMain and the Rust
 * decoder in ffi/rust mirror it. Little endian throughout, f64 for geometry
 * (the widening argument of snapshot_table_binary), per-column partitions,
 * string pool with u32 deltas.
 */

#define TIQIAN_PLAN_ABI_PROTOCOL_REVISION 1u

/* Packed plan buffers start with "TQPP". */
#define TIQIAN_PLAN_MAGIC 0x54515050u

/*
 * Packed plan layout. Fixed header, then sequential regions read by offset.
 * u32 = 4 bytes LE, i32 = 4 bytes LE, f64 = IEEE 754 LE, strings are pooled:
 * the pool holds count u32 deltas summed from an implicit zero start plus the
 * concatenated UTF-8 bytes; every string reference is a u32 index into the pool
 * (TIQIAN_PLAN_STRING_ABSENT = 0xFFFFFFFFu means absent). f64 columns use NaN
 * as absence for optional f64; italic/bool absences use 2. Unknown enum codes
 * and out-of-range references are named errors.
 *
 *   0   u32 magic (TIQIAN_PLAN_MAGIC)
 *   4   u32 version (TIQIAN_PLAN_ABI_PROTOCOL_REVISION)
 *   8   f64 width
 *   16  f64 height
 *   24  f64 fontSize (NaN = absent)
 *   32  f64 overlayWidth (NaN = absent)
 *   40  u32 lineCount
 *   44  u32 cellCount
 *   48  u32 emphasisRangeCount
 *   52  u32 inlineEdgeCount
 *   56  u32 rubyCount
 *   60  u32 bopomofoCount
 *   64  u32 bopomofoPlacementTotal
 *   68  u32 decorationSegmentCount
 *   72  u32 emphasisDotCount
 *   76  u32 stringCount
 *   80  u32 openTypeFeatureTotal
 *   84  u32 rubyFamilyTotal
 *   88  u32 bopomofoFamilyTotal
 *
 * Regions, in order:
 *
 *   stringDeltas          stringCount x u32         offsets into stringBytes
 *   stringBytes           UTF-8, concatenated
 *   lineRangeStart        lineCount x i32
 *   lineRangeEnd          lineCount x i32
 *   lineTop               lineCount x f64
 *   lineBottom            lineCount x f64
 *   lineBaseline          lineCount x f64
 *   lineIndent            lineCount x f64
 *   lineVisualWidth       lineCount x f64
 *   lineHyphenAdvance     lineCount x f64
 *   lineEndReason         lineCount x u8            0 AutoWrap,1 MandatoryBreak,2 ParagraphEnd
 *   lineCellCount         lineCount x u32
 *   cellRangeStart        cellCount x i32
 *   cellRangeEnd          cellCount x i32
 *   cellSourceRef         cellCount x u32            string index
 *   cellDisplayRef        cellCount x u32
 *   cellDrawX             cellCount x f64
 *   cellNaturalWidth      cellCount x f64
 *   cellLeadingAdvance    cellCount x f64
 *   cellShapingBoundary   cellCount x u8              0 or 1
 *   cellLatin             cellCount x u8              0 or 1
 *   cellRenderFamilyRef   cellCount x u32            absent = 0xFFFFFFFFu
 *   cellDashStrategyRef   cellCount x u32
 *   cellShapingLanguageRef cellCount x u32
 *   cellResolvedFaceRef   cellCount x u32
 *   cellGlyphIdsRef       cellCount x u32
 *   cellShapingEvidenceRef cellCount x u32
 *   cellPunctuationInkFloor cellCount x f64           NaN = absent (both floor/body together)
 *   cellPunctuationBodyWidth cellCount x f64
 *   cellAdvance           cellCount x f64            NaN = absent
 *   cellInlineObject      cellCount x f64
 *   cellStyleFontSize     cellCount x f64            NaN = absent
 *   cellStyleFontWeight   cellCount x f64            NaN = absent
 *   cellStyleItalic       cellCount x u8              0 false,1 true,2 absent
 *   cellFeatureOffset     cellCount x u32            index into feature pool
 *   cellFeatureCount      cellCount x u32
 *   featurePool           openTypeFeatureTotal x u32  string index per feature
 *   emphasisStart         emphasisRangeCount x f64
 *   emphasisEnd           emphasisRangeCount x f64
 *   inlineEdgeOffset      inlineEdgeCount x f64
 *   inlineEdgeStart       inlineEdgeCount x f64      NaN = absent
 *   inlineEdgeEnd         inlineEdgeCount x f64
 *   rubyBaseStart         rubyCount x i32
 *   rubyBaseEnd           rubyCount x i32
 *   rubyTextRef           rubyCount x u32
 *   rubyCenterX           rubyCount x f64
 *   rubyBaselineY         rubyCount x f64
 *   rubyFontSize          rubyCount x f64
 *   rubyFontWeight        rubyCount x f64
 *   rubyFamilyOffset      rubyCount x u32
 *   rubyFamilyCount       rubyCount x u32
 *   rubyAscent            rubyCount x f64            NaN = absent
 *   rubyFamilyPool        rubyFamilyTotal x u32
 *   bopomofoBaseStart     bopomofoCount x i32
 *   bopomofoBaseEnd       bopomofoCount x i32
 *   bopomofoTextRef       bopomofoCount x u32
 *   bopomofoFontWeight    bopomofoCount x f64
 *   bopomofoFamilyOffset  bopomofoCount x u32
 *   bopomofoFamilyCount   bopomofoCount x u32
 *   bopomofoPlacementOffset bopomofoCount x u32
 *   bopomofoPlacementCount bopomofoCount x u32
 *   bopomofoFamilyPool    bopomofoFamilyTotal x u32
 *   bopomofoPlaceTextRef  bopomofoPlacementTotal x u32
 *   bopomofoPlaceRoleRef  bopomofoPlacementTotal x u32
 *   bopomofoPlaceLeft     bopomofoPlacementTotal x f64
 *   bopomofoPlaceTop      bopomofoPlacementTotal x f64
 *   bopomofoPlaceWidth    bopomofoPlacementTotal x f64
 *   bopomofoPlaceHeight   bopomofoPlacementTotal x f64
 *   decorationKindRef     decorationSegmentCount x u32
 *   decorationLeft        decorationSegmentCount x f64
 *   decorationTop         decorationSegmentCount x f64
 *   decorationRight       decorationSegmentCount x f64
 *   dotClusterStart       emphasisDotCount x f64     NaN = absent
 *   dotAnchorX            emphasisDotCount x f64
 *   dotAnchorY            emphasisDotCount x f64
 *   dotDiameter           emphasisDotCount x f64
 *
 * No padding between regions; readers walk the regions in order. The plan
 * reader validates every reference, every enum, every NaN sentinel, and the
 * trailing length.
 */

#define TIQIAN_PLAN_STRING_ABSENT 0xFFFFFFFFu

#ifdef __cplusplus
}
#endif

#endif /* TIQIAN_PLAN_ABI_H */
