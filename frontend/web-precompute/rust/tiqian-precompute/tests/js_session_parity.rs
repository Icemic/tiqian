//! Cross-checks the Rust font session against the production JS precompute
//! oracle (ADR 0050). The test builds a case matrix, runs it through the
//! Rust session in-process and through `frontend/web/npm/precompute-fonts.js`
//! under Node, then byte-compares the two JSON dumps.
//!
//! The single normalized field is `harfbuzzVersion`: the documented
//! engine-identity exemption (wasm HarfBuzz on the JS side, harfrust here).
//! The test skips with a reason when node, the npm node_modules, or the
//! required system fonts are unavailable; the variable-font and woff2 cases
//! drop out separately when their inputs are absent.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::process::Command;

use tiqian_precompute::emit;
use tiqian_precompute::font_record::{FontFaceSpec, FontWeightSpec};
use tiqian_precompute::json::Json;
use tiqian_precompute::session::{
    create_font_session, FontEvidence, FontSession, MetricsInput, SessionFaceSpec, SessionOptions,
    ShapeInput,
};
use tiqian_precompute::shaping::ShapeRecordResult;
use tiqian_precompute::source_boundaries::{
    merge_serialized_source_boundaries, worker_exact_subset_source_boundaries, BoundaryStyle,
    BoundaryTextSpan, MetadataFaceSpec, WorkerBoundaryRequest,
};

const HAN_REGULAR: &str = "hanRegular";
const HAN_BOLD: &str = "hanBold";
const GARAMOND_REGULAR: &str = "garamondRegular";
const GARAMOND_BOLD: &str = "garamondBold";
const DELA_WOFF2: &str = "delaWoff2";
const NOTO_VF: &str = "notoVf";

struct FaceDef {
    family: &'static str,
    font: &'static str,
    public_url: &'static str,
    weight: WeightDef,
    style: &'static str,
    unicode_range: Option<&'static str>,
    source_order: Option<f64>,
}

enum WeightDef {
    Single(f64),
    Range(f64, f64),
}

impl WeightDef {
    fn to_json(&self) -> Json {
        match self {
            WeightDef::Single(value) => Json::Num(*value),
            WeightDef::Range(low, high) => Json::Arr(vec![Json::Num(*low), Json::Num(*high)]),
        }
    }
}

struct SessionDef {
    id: &'static str,
    prefix: &'static str,
    base_features: Option<&'static [&'static str]>,
    faces: &'static [FaceDef],
}

#[derive(Clone, Copy)]
struct ShapeDef {
    session: &'static str,
    tag: &'static str,
    display_text: &'static str,
    families: &'static [&'static str],
    font_size: f64,
    font_weight: f64,
    italic: bool,
    locale: &'static str,
    role: Option<&'static str>,
    source_text: Option<&'static str>,
    deps: &'static [&'static str],
}

#[derive(Clone, Copy)]
struct MetricsDef {
    session: &'static str,
    tag: &'static str,
    families: &'static [&'static str],
    font_size: f64,
    font_weight: f64,
    italic: bool,
    role: Option<&'static str>,
    face_selection_text: Option<&'static str>,
    deps: &'static [&'static str],
}

#[derive(Clone, Copy)]
struct RenderFamiliesDef {
    session: &'static str,
    tag: &'static str,
    requested: &'static [&'static str],
}

#[derive(Clone, Copy)]
struct SpanDef {
    start: f64,
    end: f64,
    families: &'static [&'static str],
    font_size: f64,
    font_weight: f64,
    italic: bool,
    baseline_shift: Option<f64>,
}

#[derive(Clone, Copy)]
struct BoundariesDef {
    session: &'static str,
    tag: &'static str,
    text: &'static str,
    families: &'static [&'static str],
    font_size: f64,
    font_weight: f64,
    italic: bool,
    baseline_shift: Option<f64>,
    spans: &'static [SpanDef],
    deps: &'static [&'static str],
}

#[derive(Clone, Copy)]
struct WorkerFaceDef {
    family: &'static str,
    local_names: &'static [&'static str],
    style: &'static str,
    weight_low: f64,
    weight_high: f64,
    unicode_range: Option<&'static str>,
    public_url: &'static str,
    face_index: f64,
    source_order: f64,
}

#[derive(Clone, Copy)]
struct WorkerBoundariesDef {
    tag: &'static str,
    text: &'static str,
    families: &'static str,
    font_size: f64,
    font_weight: f64,
    italic: bool,
    spans: &'static str,
    faces: &'static [WorkerFaceDef],
}

#[derive(Clone, Copy)]
struct MergeBoundariesDef {
    tag: &'static str,
    serialized: &'static str,
    additional: &'static [f64],
}

#[derive(Clone, Copy)]
enum CallDef {
    Shape(ShapeDef),
    Metrics(MetricsDef),
    RenderFamilies(RenderFamiliesDef),
    BeginCapture(&'static str),
    Evidence(&'static str),
    Boundaries(BoundariesDef),
    WorkerBoundaries(WorkerBoundariesDef),
    MergeBoundaries(MergeBoundariesDef),
}

/// Session ordering pins the counter semantics: `badBase` is rejected after
/// consuming a session id, so `lnum` (created after it) numbers one higher
/// than a naive count would suggest. `badOrder`, `styleErr` and `empty`
/// fail before the id is read.
const SESSIONS: &[SessionDef] = &[
    SessionDef {
        id: "main",
        prefix: "tq-font",
        base_features: None,
        faces: &[
            FaceDef {
                family: "Source Han Sans SC",
                font: HAN_REGULAR,
                public_url: "/fonts/han-regular.otf",
                weight: WeightDef::Single(400.0),
                style: "normal",
                unicode_range: None,
                source_order: Some(0.0),
            },
            FaceDef {
                family: "Source Han Sans SC",
                font: HAN_BOLD,
                public_url: "/fonts/han-bold.otf",
                weight: WeightDef::Single(700.0),
                style: "normal",
                unicode_range: None,
                source_order: Some(1.0),
            },
            FaceDef {
                family: "EB Garamond",
                font: GARAMOND_BOLD,
                public_url: "/fonts/garamond-bold.ttf",
                weight: WeightDef::Single(700.0),
                style: "normal",
                unicode_range: Some("U+0000-00FF, U+2000-206F"),
                source_order: Some(3.0),
            },
            FaceDef {
                family: "Dela Gothic One",
                font: DELA_WOFF2,
                public_url: "/fonts/dela.woff2",
                weight: WeightDef::Single(400.0),
                style: "normal",
                unicode_range: Some("U+4E00-9FFF"),
                source_order: Some(5.0),
            },
            FaceDef {
                family: "Noto Sans SC",
                font: NOTO_VF,
                public_url: "/fonts/noto-vf.ttf",
                weight: WeightDef::Range(100.0, 900.0),
                style: "normal",
                unicode_range: None,
                source_order: Some(6.0),
            },
            FaceDef {
                family: "Mixed Proof",
                font: GARAMOND_REGULAR,
                public_url: "/fonts/garamond-regular.ttf",
                weight: WeightDef::Single(400.0),
                style: "normal",
                unicode_range: Some("U+0000-00FF"),
                source_order: Some(7.0),
            },
            FaceDef {
                family: "Mixed Proof",
                font: DELA_WOFF2,
                public_url: "/fonts/dela-mixed.woff2",
                weight: WeightDef::Single(400.0),
                style: "normal",
                unicode_range: Some("U+4E00-9FFF"),
                source_order: Some(8.0),
            },
        ],
    },
    SessionDef {
        id: "badBase",
        prefix: "tq-font",
        base_features: Some(&["kern"]),
        faces: &[FaceDef {
            family: "EB Garamond",
            font: GARAMOND_REGULAR,
            public_url: "/fonts/garamond-regular.ttf",
            weight: WeightDef::Single(400.0),
            style: "normal",
            unicode_range: None,
            source_order: None,
        }],
    },
    SessionDef {
        id: "lnum",
        prefix: "  tq-lnum  ",
        base_features: Some(&["lnum", "lnum"]),
        faces: &[FaceDef {
            family: "EB Garamond",
            font: GARAMOND_REGULAR,
            public_url: "/fonts/garamond-regular.ttf",
            weight: WeightDef::Single(400.0),
            style: "normal",
            unicode_range: None,
            source_order: None,
        }],
    },
    SessionDef {
        id: "badOrder",
        prefix: "tq-font",
        base_features: None,
        faces: &[
            FaceDef {
                family: "Source Han Sans SC",
                font: HAN_REGULAR,
                public_url: "/fonts/han-regular.otf",
                weight: WeightDef::Single(400.0),
                style: "normal",
                unicode_range: None,
                source_order: None,
            },
            FaceDef {
                family: "Source Han Sans SC",
                font: HAN_BOLD,
                public_url: "/fonts/han-bold.otf",
                weight: WeightDef::Single(700.0),
                style: "normal",
                unicode_range: None,
                source_order: Some(-1.0),
            },
        ],
    },
    SessionDef {
        id: "styleErr",
        prefix: "tq-font",
        base_features: None,
        faces: &[FaceDef {
            family: "EB Garamond",
            font: GARAMOND_REGULAR,
            public_url: "/fonts/garamond-regular.ttf",
            weight: WeightDef::Single(400.0),
            style: "oblique",
            unicode_range: None,
            source_order: None,
        }],
    },
    SessionDef {
        id: "empty",
        prefix: "tq-font",
        base_features: None,
        faces: &[],
    },
];

const HAN: &[&str] = &["Source Han Sans SC"];
const VF: &[&str] = &["Noto Sans SC"];
const MIXED: &[&str] = &["Mixed Proof"];

const CALLS: &[CallDef] = &[
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "han-cjktext",
        display_text: "提椠正文：直排测试，行内混排 Latin 与标点。",
        families: HAN,
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        locale: "zh-cn",
        role: Some("CjkText"),
        source_text: None,
        deps: &[],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "han-bold",
        display_text: "提椠正文：直排测试，行内混排 Latin 与标点。",
        families: HAN,
        font_size: 16.0,
        font_weight: 700.0,
        italic: false,
        locale: "zh-cn",
        role: Some("CjkText"),
        source_text: None,
        deps: &[],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "han-punct-curly",
        display_text: "「引文」与‘单引号’——破折号……",
        families: HAN,
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        locale: "zh-cn",
        role: Some("CjkPunctuation"),
        source_text: None,
        deps: &[],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "garamond-curly",
        display_text: "“Curly” text ‘quotes’ inside.",
        families: &["EB Garamond"],
        font_size: 15.5,
        font_weight: 700.0,
        italic: false,
        locale: "en",
        role: Some("LatinText"),
        source_text: None,
        deps: &[],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "garamond-range-miss",
        display_text: "ĀĂ Latin",
        families: &["EB Garamond"],
        font_size: 16.0,
        font_weight: 700.0,
        italic: false,
        locale: "en",
        role: None,
        source_text: None,
        deps: &[],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "mixed-split",
        display_text: "Hello 世界",
        families: MIXED,
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        locale: "zh-cn",
        role: None,
        source_text: None,
        deps: &[DELA_WOFF2],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "vf-350",
        display_text: "变体字重测试",
        families: VF,
        font_size: 15.5,
        font_weight: 350.0,
        italic: false,
        locale: "zh-cn",
        role: None,
        source_text: None,
        deps: &[NOTO_VF],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "vf-950",
        display_text: "变体字重测试",
        families: VF,
        font_size: 15.5,
        font_weight: 950.0,
        italic: false,
        locale: "zh-cn",
        role: None,
        source_text: None,
        deps: &[NOTO_VF],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "vf-4005",
        display_text: "变体字重测试",
        families: VF,
        font_size: 15.5,
        font_weight: 400.5,
        italic: false,
        locale: "zh-cn",
        role: None,
        source_text: None,
        deps: &[NOTO_VF],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "vf-role-cjktext",
        display_text: "正文角色",
        families: VF,
        font_size: 16.0,
        font_weight: 350.0,
        italic: false,
        locale: "zh-cn",
        role: Some("CjkText"),
        source_text: None,
        deps: &[NOTO_VF],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "local-name",
        display_text: "本地名匹配",
        families: &["SourceHanSansSC-Regular"],
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        locale: "zh-cn",
        role: None,
        source_text: None,
        deps: &[],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "astral-han",
        display_text: "𠀀字表意",
        families: HAN,
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        locale: "zh-cn",
        role: None,
        source_text: None,
        deps: &[],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "astral-emoji",
        display_text: "😀表情",
        families: HAN,
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        locale: "zh-cn",
        role: None,
        source_text: None,
        deps: &[],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "display-source-split",
        display_text: "😊",
        families: HAN,
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        locale: "zh-cn",
        role: None,
        source_text: Some("A"),
        deps: &[],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "italic-flag",
        display_text: "hi",
        families: &["EB Garamond"],
        font_size: 16.0,
        font_weight: 700.0,
        italic: true,
        locale: "en",
        role: None,
        source_text: None,
        deps: &[],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "unknown-family",
        display_text: "字",
        families: &["No Such Family"],
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        locale: "zh-cn",
        role: None,
        source_text: None,
        deps: &[],
    }),
    CallDef::Shape(ShapeDef {
        session: "lnum",
        tag: "lnum-latin",
        display_text: "Figures 123",
        families: &["EB Garamond"],
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        locale: "en",
        role: Some("LatinText"),
        source_text: None,
        deps: &[],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "fallback-chain",
        display_text: "汉字abc",
        families: &["No Such Family", "EB Garamond", "Source Han Sans SC"],
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        locale: "zh-cn",
        role: None,
        source_text: None,
        deps: &[],
    }),
    // The two corpus lines come from the golden fixtures
    // adjacent-punctuation-spacing and ascii-brackets-in-cjk; corpus-level
    // shaping parity is the 1078/1078 differential's job, these two cover
    // the session surface on realistic prose.
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "corpus-adjacent",
        display_text: "他说：“你好，世界。”！！",
        families: HAN,
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        locale: "zh-cn",
        role: Some("CjkPunctuation"),
        source_text: None,
        deps: &[],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "corpus-mixed",
        display_text: "中文段落(English)和[mixed]说明。",
        families: HAN,
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        locale: "zh-cn",
        role: None,
        source_text: None,
        deps: &[],
    }),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "dela-han",
        display_text: "常用漢字",
        families: &["Dela Gothic One"],
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        locale: "ja",
        role: None,
        source_text: None,
        deps: &[DELA_WOFF2],
    }),
    CallDef::Metrics(MetricsDef {
        session: "main",
        tag: "han-metrics",
        families: HAN,
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        role: None,
        face_selection_text: None,
        deps: &[],
    }),
    CallDef::Metrics(MetricsDef {
        session: "main",
        tag: "mixed-nonuniform",
        families: MIXED,
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        role: None,
        face_selection_text: None,
        deps: &[DELA_WOFF2],
    }),
    CallDef::Metrics(MetricsDef {
        session: "main",
        tag: "mixed-select-han",
        families: MIXED,
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        role: None,
        face_selection_text: Some("世界"),
        deps: &[DELA_WOFF2],
    }),
    CallDef::Metrics(MetricsDef {
        session: "main",
        tag: "mixed-select-latin",
        families: MIXED,
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        role: None,
        face_selection_text: Some("Hello"),
        deps: &[DELA_WOFF2],
    }),
    CallDef::Metrics(MetricsDef {
        session: "main",
        tag: "vf-metrics",
        families: VF,
        font_size: 16.0,
        font_weight: 350.0,
        italic: false,
        role: None,
        face_selection_text: None,
        deps: &[NOTO_VF],
    }),
    CallDef::Metrics(MetricsDef {
        session: "main",
        tag: "italic-metrics-err",
        families: HAN,
        font_size: 16.0,
        font_weight: 400.0,
        italic: true,
        role: None,
        face_selection_text: None,
        deps: &[],
    }),
    CallDef::Metrics(MetricsDef {
        session: "main",
        tag: "unknown-metrics-err",
        families: &["No Such Family"],
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        role: None,
        face_selection_text: None,
        deps: &[],
    }),
    CallDef::Metrics(MetricsDef {
        session: "lnum",
        tag: "lnum-metrics",
        families: &["EB Garamond"],
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        role: None,
        face_selection_text: None,
        deps: &[],
    }),
    CallDef::Metrics(MetricsDef {
        session: "main",
        tag: "size-zero",
        families: HAN,
        font_size: 0.0,
        font_weight: 400.0,
        italic: false,
        role: None,
        face_selection_text: None,
        deps: &[],
    }),
    CallDef::RenderFamilies(RenderFamiliesDef {
        session: "main",
        tag: "render-main",
        requested: &["Source Han Sans SC", "Dela Gothic One", "Nope"],
    }),
    CallDef::RenderFamilies(RenderFamiliesDef {
        session: "main",
        tag: "render-empty",
        requested: &[],
    }),
    CallDef::RenderFamilies(RenderFamiliesDef {
        session: "lnum",
        tag: "render-lnum",
        requested: &["EB Garamond", "Other"],
    }),
    CallDef::BeginCapture("main"),
    CallDef::Shape(ShapeDef {
        session: "main",
        tag: "post-capture",
        display_text: "捕获之后",
        families: HAN,
        font_size: 16.0,
        font_weight: 400.0,
        italic: false,
        locale: "zh-cn",
        role: Some("CjkText"),
        source_text: None,
        deps: &[],
    }),
    CallDef::Metrics(MetricsDef {
        session: "main",
        tag: "post-capture-metrics",
        families: HAN,
        font_size: 17.0,
        font_weight: 400.0,
        italic: false,
        role: None,
        face_selection_text: None,
        deps: &[],
    }),
    CallDef::Evidence("main"),
    CallDef::Evidence("lnum"),
    CallDef::Boundaries(BoundariesDef {
        session: "main",
        tag: "mixed-runs",
        text: "汉B汉",
        families: MIXED,
        font_size: 18.0,
        font_weight: 400.0,
        italic: false,
        baseline_shift: None,
        spans: &[],
        deps: &[DELA_WOFF2, GARAMOND_REGULAR],
    }),
    CallDef::Boundaries(BoundariesDef {
        session: "main",
        tag: "crlf-keeps-run-offsets",
        text: "汉\r\nB",
        families: MIXED,
        font_size: 18.0,
        font_weight: 400.0,
        italic: false,
        baseline_shift: None,
        spans: &[],
        deps: &[DELA_WOFF2, GARAMOND_REGULAR],
    }),
    CallDef::Boundaries(BoundariesDef {
        session: "main",
        tag: "span-weight-split",
        text: "BB",
        families: MIXED,
        font_size: 18.0,
        font_weight: 400.0,
        italic: false,
        baseline_shift: None,
        spans: &[SpanDef {
            start: 1.0,
            end: 2.0,
            families: MIXED,
            font_size: 18.0,
            font_weight: 700.0,
            italic: false,
            baseline_shift: None,
        }],
        deps: &[DELA_WOFF2, GARAMOND_REGULAR],
    }),
    CallDef::Boundaries(BoundariesDef {
        session: "main",
        tag: "span-baseline-shift-split",
        text: "汉汉",
        families: HAN,
        font_size: 18.0,
        font_weight: 400.0,
        italic: false,
        baseline_shift: None,
        spans: &[
            SpanDef {
                start: 0.0,
                end: 1.0,
                families: HAN,
                font_size: 18.0,
                font_weight: 400.0,
                italic: false,
                baseline_shift: Some(0.0),
            },
            SpanDef {
                start: 1.0,
                end: 2.0,
                families: HAN,
                font_size: 18.0,
                font_weight: 400.0,
                italic: false,
                baseline_shift: Some(2.0),
            },
        ],
        deps: &[HAN_REGULAR],
    }),
    CallDef::Boundaries(BoundariesDef {
        session: "main",
        tag: "astral-span-offsets",
        text: "\u{20000}B",
        families: HAN,
        font_size: 18.0,
        font_weight: 400.0,
        italic: false,
        baseline_shift: None,
        spans: &[SpanDef {
            start: 2.0,
            end: 3.0,
            families: MIXED,
            font_size: 18.0,
            font_weight: 400.0,
            italic: false,
            baseline_shift: None,
        }],
        deps: &[HAN_REGULAR, GARAMOND_REGULAR, DELA_WOFF2],
    }),
    CallDef::Boundaries(BoundariesDef {
        session: "main",
        tag: "uncovered-point-throws",
        text: "汉”",
        families: MIXED,
        font_size: 18.0,
        font_weight: 400.0,
        italic: false,
        baseline_shift: None,
        spans: &[],
        deps: &[DELA_WOFF2, GARAMOND_REGULAR],
    }),
    CallDef::WorkerBoundaries(WorkerBoundariesDef {
        tag: "latin-punctuation-shard",
        text: "B\u{201d}",
        families: "MiSans VF\u{1f}ui-sans-serif",
        font_size: 18.0,
        font_weight: 460.0,
        italic: false,
        spans: "",
        faces: &[
            WorkerFaceDef {
                family: "MiSans VF",
                local_names: &["MiSans VF"],
                style: "normal",
                weight_low: 100.0,
                weight_high: 900.0,
                unicode_range: Some("U+0041-005A"),
                public_url: "/fonts/latin.woff2",
                face_index: 0.0,
                source_order: 0.0,
            },
            WorkerFaceDef {
                family: "MiSans VF",
                local_names: &["MiSans VF"],
                style: "normal",
                weight_low: 100.0,
                weight_high: 900.0,
                unicode_range: Some("U+201D"),
                public_url: "/fonts/punctuation.woff2",
                face_index: 0.0,
                source_order: 1.0,
            },
        ],
    }),
    CallDef::WorkerBoundaries(WorkerBoundariesDef {
        tag: "overlapping-declarations-follow-source-order",
        text: "AB",
        families: "MiSans VF",
        font_size: 18.0,
        font_weight: 460.0,
        italic: false,
        spans: "",
        faces: &[
            WorkerFaceDef {
                family: "MiSans VF",
                local_names: &["MiSans VF"],
                style: "normal",
                weight_low: 100.0,
                weight_high: 900.0,
                unicode_range: Some("U+0041-005A"),
                public_url: "/fonts/latin-a.woff2",
                face_index: 0.0,
                source_order: 0.0,
            },
            WorkerFaceDef {
                family: "MiSans VF",
                local_names: &["MiSans VF"],
                style: "normal",
                weight_low: 100.0,
                weight_high: 900.0,
                unicode_range: Some("U+0042"),
                public_url: "/fonts/latin-b.woff2",
                face_index: 0.0,
                source_order: 1.0,
            },
        ],
    }),
    CallDef::WorkerBoundaries(WorkerBoundariesDef {
        tag: "soft-break-needs-no-face",
        text: "B\u{200b}\u{201d}",
        families: "MiSans VF",
        font_size: 18.0,
        font_weight: 460.0,
        italic: false,
        spans: "",
        faces: &[
            WorkerFaceDef {
                family: "MiSans VF",
                local_names: &["MiSans VF"],
                style: "normal",
                weight_low: 100.0,
                weight_high: 900.0,
                unicode_range: Some("U+0041-005A"),
                public_url: "/fonts/latin.woff2",
                face_index: 0.0,
                source_order: 0.0,
            },
            WorkerFaceDef {
                family: "MiSans VF",
                local_names: &["MiSans VF"],
                style: "normal",
                weight_low: 100.0,
                weight_high: 900.0,
                unicode_range: Some("U+201D"),
                public_url: "/fonts/punctuation.woff2",
                face_index: 0.0,
                source_order: 1.0,
            },
        ],
    }),
    CallDef::WorkerBoundaries(WorkerBoundariesDef {
        tag: "crlf-utf16-offsets",
        text: "\u{7531}\r\nB",
        families: "MiSans VF",
        font_size: 18.0,
        font_weight: 460.0,
        italic: false,
        spans: "",
        faces: &[
            WorkerFaceDef {
                family: "MiSans VF",
                local_names: &["MiSans VF"],
                style: "normal",
                weight_low: 100.0,
                weight_high: 900.0,
                unicode_range: Some("U+4E00-9FFF"),
                public_url: "/fonts/cjk.woff2",
                face_index: 0.0,
                source_order: 0.0,
            },
            WorkerFaceDef {
                family: "MiSans VF",
                local_names: &["MiSans VF"],
                style: "normal",
                weight_low: 100.0,
                weight_high: 900.0,
                unicode_range: Some("U+0041-005A"),
                public_url: "/fonts/latin.woff2",
                face_index: 0.0,
                source_order: 1.0,
            },
        ],
    }),
    CallDef::WorkerBoundaries(WorkerBoundariesDef {
        tag: "span-keeps-dom-boundary",
        text: "B\u{201d}B",
        families: "MiSans VF",
        font_size: 18.0,
        font_weight: 460.0,
        italic: false,
        spans: "2\u{1d}3\u{1d}MiSans VF\u{1d}18\u{1d}700\u{1d}false\u{1d}0",
        faces: &[
            WorkerFaceDef {
                family: "MiSans VF",
                local_names: &["MiSans VF"],
                style: "normal",
                weight_low: 100.0,
                weight_high: 900.0,
                unicode_range: Some("U+0041-005A"),
                public_url: "/fonts/latin.woff2",
                face_index: 0.0,
                source_order: 0.0,
            },
            WorkerFaceDef {
                family: "MiSans VF",
                local_names: &["MiSans VF"],
                style: "normal",
                weight_low: 100.0,
                weight_high: 900.0,
                unicode_range: Some("U+201D"),
                public_url: "/fonts/punctuation.woff2",
                face_index: 0.0,
                source_order: 1.0,
            },
        ],
    }),
    CallDef::WorkerBoundaries(WorkerBoundariesDef {
        tag: "uncovered-point-throws",
        text: "\u{4e00}",
        families: "MiSans VF",
        font_size: 18.0,
        font_weight: 460.0,
        italic: false,
        spans: "",
        faces: &[WorkerFaceDef {
            family: "MiSans VF",
            local_names: &["MiSans VF"],
            style: "normal",
            weight_low: 100.0,
            weight_high: 900.0,
            unicode_range: Some("U+0041-005A"),
            public_url: "/fonts/latin.woff2",
            face_index: 0.0,
            source_order: 0.0,
        }],
    }),
    CallDef::WorkerBoundaries(WorkerBoundariesDef {
        tag: "italic-without-italic-face-throws",
        text: "B",
        families: "MiSans VF",
        font_size: 18.0,
        font_weight: 460.0,
        italic: true,
        spans: "",
        faces: &[WorkerFaceDef {
            family: "MiSans VF",
            local_names: &["MiSans VF"],
            style: "normal",
            weight_low: 100.0,
            weight_high: 900.0,
            unicode_range: Some("U+0041-005A"),
            public_url: "/fonts/latin.woff2",
            face_index: 0.0,
            source_order: 0.0,
        }],
    }),
    CallDef::WorkerBoundaries(WorkerBoundariesDef {
        tag: "empty-contract-throws",
        text: "B",
        families: "MiSans VF",
        font_size: 18.0,
        font_weight: 460.0,
        italic: false,
        spans: "",
        faces: &[],
    }),
    CallDef::WorkerBoundaries(WorkerBoundariesDef {
        tag: "bad-span-field-count-throws",
        text: "B",
        families: "MiSans VF",
        font_size: 18.0,
        font_weight: 460.0,
        italic: false,
        spans: "0\u{1d}1\u{1d}MiSans VF",
        faces: &[WorkerFaceDef {
            family: "MiSans VF",
            local_names: &["MiSans VF"],
            style: "normal",
            weight_low: 100.0,
            weight_high: 900.0,
            unicode_range: Some("U+0041-005A"),
            public_url: "/fonts/latin.woff2",
            face_index: 0.0,
            source_order: 0.0,
        }],
    }),
    CallDef::MergeBoundaries(MergeBoundariesDef {
        tag: "empty-serialized",
        serialized: "",
        additional: &[1.0],
    }),
    CallDef::MergeBoundaries(MergeBoundariesDef {
        tag: "dedupes-and-sorts",
        serialized: "2,0",
        additional: &[1.0, 2.0],
    }),
    CallDef::MergeBoundaries(MergeBoundariesDef {
        tag: "number-coercion",
        serialized: " 3 ,0x2,1e1",
        additional: &[],
    }),
    CallDef::MergeBoundaries(MergeBoundariesDef {
        tag: "negative-throws",
        serialized: "-1",
        additional: &[],
    }),
    CallDef::MergeBoundaries(MergeBoundariesDef {
        tag: "non-numeric-throws",
        serialized: "x",
        additional: &[],
    }),
];

fn skip(reason: &str) {
    eprintln!("skipping js_session_parity: {reason}");
}

#[test]
fn session_outputs_match_the_js_precompute_oracle() {
    let repo_root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .ancestors()
        .nth(4)
        .expect("manifest sits four levels below the repo root")
        .to_path_buf();
    let npm_dir = repo_root.join("frontend/web/npm");

    if !Command::new("node")
        .arg("--version")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
    {
        return skip("node is not available");
    }
    for package in ["harfbuzzjs", "woff2-encoder"] {
        if !npm_dir.join("node_modules").join(package).is_dir() {
            return skip(&format!(
                "frontend/web/npm/node_modules/{package} is missing"
            ));
        }
    }

    let home = std::env::var("HOME").unwrap_or_default();
    let fonts_dir = Path::new(&home).join(".local/share/fonts");
    let mut fonts: HashMap<&str, PathBuf> = HashMap::new();
    for (key, file) in [
        (HAN_REGULAR, "SourceHanSansSC-Regular.otf"),
        (HAN_BOLD, "SourceHanSansSC-Bold.otf"),
        (GARAMOND_REGULAR, "EBGaramond-Regular.ttf"),
        (GARAMOND_BOLD, "EBGaramond-Bold.ttf"),
    ] {
        let path = fonts_dir.join(file);
        if !path.is_file() {
            return skip(&format!("{} is missing", path.display()));
        }
        fonts.insert(key, path);
    }
    let dela_ttf = fonts_dir.join("DelaGothicOne-Regular.ttf");
    if !dela_ttf.is_file() {
        return skip(&format!("{} is missing", dela_ttf.display()));
    }

    let workdir = std::env::temp_dir().join("tiqian-session-parity");
    if let Err(error) = std::fs::create_dir_all(&workdir) {
        return skip(&format!("workdir unavailable: {error}"));
    }
    let woff2_path = workdir.join("DelaGothicOne-Regular.woff2");
    let encode_script = r#"(async () => {
  const { readFile, writeFile } = require("node:fs/promises");
  const { compress } = await import("woff2-encoder");
  const ttf = new Uint8Array(await readFile(process.argv[1]));
  await writeFile(process.argv[2], await compress(ttf));
})().catch((error) => { console.error(error.message); process.exit(1); });
"#;
    match Command::new("node")
        .current_dir(&npm_dir)
        .arg("-e")
        .arg(encode_script)
        .arg(&dela_ttf)
        .arg(&woff2_path)
        .output()
    {
        Ok(output) if output.status.success() => {
            fonts.insert(DELA_WOFF2, woff2_path);
        }
        Ok(output) => eprintln!(
            "note: woff2 encode failed ({}), woff2 cases drop out",
            String::from_utf8_lossy(&output.stderr)
        ),
        Err(_) => return skip("node could not spawn for the woff2 encode"),
    }
    let vf_path = PathBuf::from("/tmp/hb-diff/NotoSansSC-VF.ttf");
    if vf_path.is_file() {
        fonts.insert(NOTO_VF, vf_path);
    } else {
        eprintln!("note: NotoSansSC-VF.ttf absent, variable-font cases drop out");
    }

    let sources: HashMap<&str, Vec<u8>> = fonts
        .iter()
        .map(|(key, path)| (*key, std::fs::read(path).expect("font reads")))
        .collect();

    let entries = run_rust_side(&sources);
    let cases_path = workdir.join("cases.json");
    std::fs::write(&cases_path, build_cases_json(&fonts).render()).expect("cases.json writes");

    let oracle = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/oracle/session_oracle.mjs");
    let oracle_run = Command::new("node")
        .arg(&oracle)
        .arg(&cases_path)
        .arg(&repo_root)
        .output()
        .expect("node spawns");
    if !oracle_run.status.success() {
        panic!(
            "session oracle failed:\n{}",
            String::from_utf8_lossy(&oracle_run.stderr)
        );
    }
    let js_dump = normalize_engine_versions(String::from_utf8_lossy(&oracle_run.stdout).trim());
    let rust_dump = normalize_engine_versions(&Json::Arr(entries).render());

    if rust_dump != js_dump {
        report_first_diff(&rust_dump, &js_dump, &workdir);
        panic!("Rust session dump differs from the JS precompute oracle");
    }
}

/// Runs the matrix through the Rust session and emits the entries the
/// oracle emits, in the same key order.
fn run_rust_side(sources: &HashMap<&str, Vec<u8>>) -> Vec<Json> {
    let available: HashSet<&&str> = sources.keys().collect();
    let mut entries: Vec<Json> = Vec::new();
    let mut sessions: HashMap<&str, FontSession> = HashMap::new();

    for def in SESSIONS {
        let specs: Vec<SessionFaceSpec> = def
            .faces
            .iter()
            .filter(|face| available.contains(&face.font))
            .map(|face| SessionFaceSpec {
                spec: FontFaceSpec {
                    family: face.family,
                    public_url: face.public_url,
                    source: &sources[face.font],
                    face_index: None,
                    weight: match face.weight {
                        WeightDef::Single(value) => FontWeightSpec::Single(Some(value)),
                        WeightDef::Range(low, high) => FontWeightSpec::Range(low, high),
                    },
                    style: face.style,
                    unicode_range: face.unicode_range,
                    source_order: 0,
                },
                source_order: face.source_order,
            })
            .collect();
        let options = SessionOptions {
            session_prefix: def.prefix.to_string(),
            base_features: def
                .base_features
                .map(|features| features.iter().map(|tag| tag.to_string()).collect()),
        };
        match create_font_session(specs, options) {
            Ok(session) => {
                entries.push(Json::Obj(vec![
                    ("kind".into(), Json::str("session")),
                    ("id".into(), Json::str(def.id)),
                    ("ok".into(), Json::Bool(true)),
                    ("sessionId".into(), Json::str(session.session_id.clone())),
                    (
                        "backendRevision".into(),
                        Json::str(session.backend_revision),
                    ),
                    (
                        "harfbuzzVersion".into(),
                        Json::str(session.harfbuzz_version),
                    ),
                    (
                        "faces".into(),
                        Json::Arr(session.faces().iter().map(face_info_json).collect()),
                    ),
                ]));
                sessions.insert(def.id, session);
            }
            Err(error) => entries.push(Json::Obj(vec![
                ("kind".into(), Json::str("session")),
                ("id".into(), Json::str(def.id)),
                ("ok".into(), Json::Bool(false)),
                ("error".into(), Json::str(error.to_string())),
            ])),
        }
    }

    for call in CALLS {
        match *call {
            CallDef::Shape(def) => {
                if !def.deps.iter().all(|dep| available.contains(dep)) {
                    continue;
                }
                let session = sessions
                    .get_mut(def.session)
                    .expect(&format!("matrix session {} exists", def.session));
                let serialized = def.families.join("\u{1f}");
                let input = ShapeInput {
                    display_text: def.display_text,
                    serialized_families: &serialized,
                    font_size: def.font_size,
                    font_weight: def.font_weight,
                    italic: def.italic,
                    locale: def.locale,
                    role: def.role,
                    source_text: def.source_text,
                };
                match session.shape(&input) {
                    Ok(result) => entries.push(shape_ok_json(def.session, def.tag, &result)),
                    Err(error) => {
                        entries.push(call_error_json("shape", def.session, def.tag, &error))
                    }
                }
            }
            CallDef::Metrics(def) => {
                if !def.deps.iter().all(|dep| available.contains(dep)) {
                    continue;
                }
                let session = sessions
                    .get_mut(def.session)
                    .expect(&format!("matrix session {} exists", def.session));
                let serialized = def.families.join("\u{1f}");
                let input = MetricsInput {
                    serialized_families: &serialized,
                    font_size: def.font_size,
                    font_weight: def.font_weight,
                    italic: def.italic,
                    role: def.role,
                    face_selection_text: def.face_selection_text,
                };
                match session.metrics(&input) {
                    Ok(values) => entries.push(Json::Obj(vec![
                        ("kind".into(), Json::str("metrics")),
                        ("session".into(), Json::str(def.session)),
                        ("tag".into(), Json::str(def.tag)),
                        ("ok".into(), Json::Bool(true)),
                        (
                            "values".into(),
                            Json::Arr(values.iter().map(|value| Json::Num(*value)).collect()),
                        ),
                    ])),
                    Err(error) => {
                        entries.push(call_error_json("metrics", def.session, def.tag, &error))
                    }
                }
            }
            CallDef::RenderFamilies(def) => {
                let session = sessions
                    .get_mut(def.session)
                    .expect(&format!("matrix session {} exists", def.session));
                let requested: Vec<String> = def
                    .requested
                    .iter()
                    .map(|family| family.to_string())
                    .collect();
                match session.render_families(&requested) {
                    Ok(families) => entries.push(Json::Obj(vec![
                        ("kind".into(), Json::str("renderFamilies")),
                        ("session".into(), Json::str(def.session)),
                        ("tag".into(), Json::str(def.tag)),
                        ("ok".into(), Json::Bool(true)),
                        (
                            "families".into(),
                            Json::Arr(families.iter().map(|f| Json::str(f.clone())).collect()),
                        ),
                    ])),
                    Err(error) => entries.push(call_error_json(
                        "renderFamilies",
                        def.session,
                        def.tag,
                        &error,
                    )),
                }
            }
            CallDef::BeginCapture(id) => {
                sessions
                    .get_mut(id)
                    .expect(&format!("matrix session {id} exists"))
                    .begin_capture();
                entries.push(Json::Obj(vec![
                    ("kind".into(), Json::str("beginCapture")),
                    ("session".into(), Json::str(id)),
                ]));
            }
            CallDef::Evidence(id) => {
                let session = sessions
                    .get(id)
                    .expect(&format!("matrix session {id} exists"));
                entries.push(evidence_json(id, &session.capture_evidence()));
            }
            CallDef::Boundaries(def) => {
                if !def.deps.iter().all(|dep| available.contains(dep)) {
                    continue;
                }
                let session = sessions
                    .get(def.session)
                    .expect(&format!("matrix session {} exists", def.session));
                let base_style = BoundaryStyle {
                    font_families: def.families.iter().map(|f| f.to_string()).collect(),
                    font_size_px: def.font_size,
                    font_weight: def.font_weight,
                    italic: def.italic,
                    baseline_shift_px: def.baseline_shift,
                };
                let spans: Vec<BoundaryTextSpan> = def
                    .spans
                    .iter()
                    .map(|span| BoundaryTextSpan {
                        start: span.start,
                        end: span.end,
                        style: BoundaryStyle {
                            font_families: span.families.iter().map(|f| f.to_string()).collect(),
                            font_size_px: span.font_size,
                            font_weight: span.font_weight,
                            italic: span.italic,
                            baseline_shift_px: span.baseline_shift,
                        },
                    })
                    .collect();
                match session.source_boundaries(def.text, &base_style, &spans) {
                    Ok(boundaries) => entries.push(Json::Obj(vec![
                        ("kind".into(), Json::str("sourceBoundaries")),
                        ("session".into(), Json::str(def.session)),
                        ("tag".into(), Json::str(def.tag)),
                        ("ok".into(), Json::Bool(true)),
                        (
                            "boundaries".into(),
                            Json::Arr(boundaries.iter().map(|value| Json::Num(*value)).collect()),
                        ),
                    ])),
                    Err(error) => entries.push(call_error_json(
                        "sourceBoundaries",
                        def.session,
                        def.tag,
                        &error,
                    )),
                }
            }
            CallDef::WorkerBoundaries(def) => {
                let faces: Vec<MetadataFaceSpec> = def
                    .faces
                    .iter()
                    .map(|face| MetadataFaceSpec {
                        family: face.family.to_string(),
                        local_names: face.local_names.iter().map(|n| n.to_string()).collect(),
                        style: face.style.to_string(),
                        weight: (face.weight_low, face.weight_high),
                        unicode_range: face.unicode_range.map(str::to_string),
                        public_url: face.public_url.to_string(),
                        face_index: face.face_index,
                        source_order: face.source_order,
                    })
                    .collect();
                let request = WorkerBoundaryRequest {
                    text: def.text,
                    font_families: def.families,
                    font_size_px: def.font_size,
                    font_weight: def.font_weight,
                    italic: def.italic,
                    text_spans: def.spans,
                };
                let result = worker_exact_subset_source_boundaries(&faces, &request);
                let boundaries = match result {
                    Ok(boundaries) => boundaries,
                    Err(error) => {
                        entries.push(call_error_no_session_json(
                            "workerBoundaries",
                            def.tag,
                            &error,
                        ));
                        continue;
                    }
                };
                entries.push(Json::Obj(vec![
                    ("kind".into(), Json::str("workerBoundaries")),
                    ("tag".into(), Json::str(def.tag)),
                    ("ok".into(), Json::Bool(true)),
                    (
                        "boundaries".into(),
                        Json::Arr(boundaries.iter().map(|value| Json::Num(*value)).collect()),
                    ),
                ]));
            }
            CallDef::MergeBoundaries(def) => {
                let kind = "mergeBoundaries";
                match merge_serialized_source_boundaries(def.serialized, def.additional) {
                    Ok(merged) => entries.push(Json::Obj(vec![
                        ("kind".into(), Json::str(kind)),
                        ("tag".into(), Json::str(def.tag)),
                        ("ok".into(), Json::Bool(true)),
                        ("merged".into(), Json::str(merged)),
                    ])),
                    Err(error) => entries.push(call_error_no_session_json(kind, def.tag, &error)),
                }
            }
        }
    }
    entries
}

/// Serializes the matrix for the oracle: the sessions and calls the Rust
/// side executed, with fonts by absolute path.
fn build_cases_json(fonts: &HashMap<&str, PathBuf>) -> Json {
    let mut font_fields: Vec<(String, Json)> = fonts
        .iter()
        .map(|(key, path)| ((*key).to_string(), Json::str(path.display().to_string())))
        .collect();
    font_fields.sort_by(|left, right| left.0.cmp(&right.0));
    let session_fields: Vec<Json> = SESSIONS
        .iter()
        .map(|def| {
            let faces: Vec<Json> = def
                .faces
                .iter()
                .filter(|face| fonts.contains_key(face.font))
                .map(|face| {
                    let mut fields = vec![
                        ("family".to_string(), Json::str(face.family)),
                        ("font".to_string(), Json::str(face.font)),
                        ("publicUrl".to_string(), Json::str(face.public_url)),
                        ("weight".to_string(), face.weight.to_json()),
                        ("style".to_string(), Json::str(face.style)),
                    ];
                    if let Some(range) = face.unicode_range {
                        fields.push(("unicodeRange".to_string(), Json::str(range)));
                    }
                    if let Some(order) = face.source_order {
                        fields.push(("sourceOrder".to_string(), Json::Num(order)));
                    }
                    Json::Obj(fields)
                })
                .collect();
            Json::Obj(vec![
                ("id".to_string(), Json::str(def.id)),
                ("prefix".to_string(), Json::str(def.prefix)),
                (
                    "baseFeatures".to_string(),
                    match def.base_features {
                        None => Json::Null,
                        Some(features) => {
                            Json::Arr(features.iter().map(|tag| Json::str(*tag)).collect())
                        }
                    },
                ),
                ("faces".to_string(), Json::Arr(faces)),
            ])
        })
        .collect();
    let call_fields: Vec<Json> = CALLS
        .iter()
        .filter_map(|call| call_to_json(call, fonts))
        .collect();
    Json::Obj(vec![
        ("fonts".to_string(), Json::Obj(font_fields)),
        ("sessions".to_string(), Json::Arr(session_fields)),
        ("calls".to_string(), Json::Arr(call_fields)),
    ])
}

fn call_to_json(call: &CallDef, fonts: &HashMap<&str, PathBuf>) -> Option<Json> {
    match call {
        CallDef::Shape(def) => {
            if !def.deps.iter().all(|dep| fonts.contains_key(dep)) {
                return None;
            }
            Some(Json::Obj(vec![
                ("kind".to_string(), Json::str("shape")),
                ("session".to_string(), Json::str(def.session)),
                ("tag".to_string(), Json::str(def.tag)),
                ("displayText".to_string(), Json::str(def.display_text)),
                (
                    "families".to_string(),
                    Json::Arr(def.families.iter().map(|f| Json::str(*f)).collect()),
                ),
                ("fontSize".to_string(), Json::Num(def.font_size)),
                ("fontWeight".to_string(), Json::Num(def.font_weight)),
                ("italic".to_string(), Json::Bool(def.italic)),
                ("locale".to_string(), Json::str(def.locale)),
                (
                    "role".to_string(),
                    match def.role {
                        None => Json::Null,
                        Some(role) => Json::str(role),
                    },
                ),
                (
                    "sourceText".to_string(),
                    match def.source_text {
                        None => Json::Null,
                        Some(text) => Json::str(text),
                    },
                ),
            ]))
        }
        CallDef::Metrics(def) => {
            if !def.deps.iter().all(|dep| fonts.contains_key(dep)) {
                return None;
            }
            Some(Json::Obj(vec![
                ("kind".to_string(), Json::str("metrics")),
                ("session".to_string(), Json::str(def.session)),
                ("tag".to_string(), Json::str(def.tag)),
                (
                    "families".to_string(),
                    Json::Arr(def.families.iter().map(|f| Json::str(*f)).collect()),
                ),
                ("fontSize".to_string(), Json::Num(def.font_size)),
                ("fontWeight".to_string(), Json::Num(def.font_weight)),
                ("italic".to_string(), Json::Bool(def.italic)),
                (
                    "role".to_string(),
                    match def.role {
                        None => Json::Null,
                        Some(role) => Json::str(role),
                    },
                ),
                (
                    "faceSelectionText".to_string(),
                    match def.face_selection_text {
                        None => Json::Null,
                        Some(text) => Json::str(text),
                    },
                ),
            ]))
        }
        CallDef::RenderFamilies(def) => Some(Json::Obj(vec![
            ("kind".to_string(), Json::str("renderFamilies")),
            ("session".to_string(), Json::str(def.session)),
            ("tag".to_string(), Json::str(def.tag)),
            (
                "requested".to_string(),
                Json::Arr(def.requested.iter().map(|f| Json::str(*f)).collect()),
            ),
        ])),
        CallDef::BeginCapture(id) => Some(Json::Obj(vec![
            ("kind".to_string(), Json::str("beginCapture")),
            ("session".to_string(), Json::str(*id)),
        ])),
        CallDef::Evidence(id) => Some(Json::Obj(vec![
            ("kind".to_string(), Json::str("evidence")),
            ("session".to_string(), Json::str(*id)),
        ])),
        CallDef::Boundaries(def) => {
            if !def.deps.iter().all(|dep| fonts.contains_key(dep)) {
                return None;
            }
            let fields = vec![
                ("kind".to_string(), Json::str("sourceBoundaries")),
                ("session".to_string(), Json::str(def.session)),
                ("tag".to_string(), Json::str(def.tag)),
                ("text".to_string(), Json::str(def.text)),
                (
                    "families".to_string(),
                    Json::Arr(def.families.iter().map(|f| Json::str(*f)).collect()),
                ),
                ("fontSizePx".to_string(), Json::Num(def.font_size)),
                ("fontWeight".to_string(), Json::Num(def.font_weight)),
                ("italic".to_string(), Json::Bool(def.italic)),
                (
                    "baselineShiftPx".to_string(),
                    match def.baseline_shift {
                        None => Json::Null,
                        Some(value) => Json::Num(value),
                    },
                ),
                (
                    "spans".to_string(),
                    Json::Arr(
                        def.spans
                            .iter()
                            .map(|span| {
                                let mut fields = vec![
                                    ("start".to_string(), Json::Num(span.start)),
                                    ("end".to_string(), Json::Num(span.end)),
                                    (
                                        "fontFamilies".to_string(),
                                        Json::Arr(
                                            span.families.iter().map(|f| Json::str(*f)).collect(),
                                        ),
                                    ),
                                    ("fontSizePx".to_string(), Json::Num(span.font_size)),
                                    ("fontWeight".to_string(), Json::Num(span.font_weight)),
                                    ("italic".to_string(), Json::Bool(span.italic)),
                                ];
                                if let Some(value) = span.baseline_shift {
                                    fields.push(("baselineShiftPx".to_string(), Json::Num(value)));
                                }
                                Json::Obj(fields)
                            })
                            .collect(),
                    ),
                ),
            ];
            Some(Json::Obj(fields))
        }
        CallDef::WorkerBoundaries(def) => Some(Json::Obj(vec![
            ("kind".to_string(), Json::str("workerBoundaries")),
            ("tag".to_string(), Json::str(def.tag)),
            ("text".to_string(), Json::str(def.text)),
            ("fontFamilies".to_string(), Json::str(def.families)),
            ("fontSizePx".to_string(), Json::Num(def.font_size)),
            ("fontWeight".to_string(), Json::Num(def.font_weight)),
            ("italic".to_string(), Json::Bool(def.italic)),
            ("textSpans".to_string(), Json::str(def.spans)),
            (
                "faces".to_string(),
                Json::Arr(
                    def.faces
                        .iter()
                        .map(|face| {
                            Json::Obj(vec![
                                ("family".to_string(), Json::str(face.family)),
                                (
                                    "localNames".to_string(),
                                    Json::Arr(
                                        face.local_names.iter().map(|n| Json::str(*n)).collect(),
                                    ),
                                ),
                                ("style".to_string(), Json::str(face.style)),
                                (
                                    "weight".to_string(),
                                    Json::Arr(vec![
                                        Json::Num(face.weight_low),
                                        Json::Num(face.weight_high),
                                    ]),
                                ),
                                (
                                    "unicodeRange".to_string(),
                                    match face.unicode_range {
                                        None => Json::Null,
                                        Some(value) => Json::str(value),
                                    },
                                ),
                                ("publicUrl".to_string(), Json::str(face.public_url)),
                                ("faceIndex".to_string(), Json::Num(face.face_index)),
                                ("sourceOrder".to_string(), Json::Num(face.source_order)),
                            ])
                        })
                        .collect(),
                ),
            ),
        ])),
        CallDef::MergeBoundaries(def) => Some(Json::Obj(vec![
            ("kind".to_string(), Json::str("mergeBoundaries")),
            ("tag".to_string(), Json::str(def.tag)),
            ("serialized".to_string(), Json::str(def.serialized)),
            (
                "additional".to_string(),
                Json::Arr(
                    def.additional
                        .iter()
                        .map(|value| Json::Num(*value))
                        .collect(),
                ),
            ),
        ])),
    }
}

fn face_info_json(face: &tiqian_precompute::session::FaceInfo) -> Json {
    emit::face_info_json(face)
}

fn shape_ok_json(session: &str, tag: &str, result: &ShapeRecordResult) -> Json {
    let mut entry = vec![
        ("kind".into(), Json::str("shape")),
        ("session".into(), Json::str(session)),
        ("tag".into(), Json::str(tag)),
        ("ok".into(), Json::Bool(true)),
    ];
    let Json::Obj(fields) = emit::shape_result_json(result) else {
        unreachable!("shape_result_json always builds an object");
    };
    entry.extend(fields);
    Json::Obj(entry)
}

fn call_error_json(kind: &str, session: &str, tag: &str, error: &str) -> Json {
    Json::Obj(vec![
        ("kind".into(), Json::str(kind)),
        ("session".into(), Json::str(session)),
        ("tag".into(), Json::str(tag)),
        ("ok".into(), Json::Bool(false)),
        ("error".into(), Json::str(error)),
    ])
}

/// Error entry for calls that run outside any session.
fn call_error_no_session_json(kind: &str, tag: &str, error: &str) -> Json {
    Json::Obj(vec![
        ("kind".into(), Json::str(kind)),
        ("tag".into(), Json::str(tag)),
        ("ok".into(), Json::Bool(false)),
        ("error".into(), Json::str(error)),
    ])
}

fn evidence_json(session: &str, evidence: &FontEvidence) -> Json {
    let mut entry = vec![
        ("kind".into(), Json::str("evidence")),
        ("session".into(), Json::str(session)),
        ("ok".into(), Json::Bool(true)),
    ];
    let Json::Obj(fields) = emit::evidence_json(evidence) else {
        unreachable!("evidence_json always builds an object");
    };
    entry.extend(fields);
    Json::Obj(entry)
}

/// Replaces every `"harfbuzzVersion":"…"` value with a token before the
/// byte comparison; the value is the documented engine-identity exemption
/// and never contains escapes.
fn normalize_engine_versions(dump: &str) -> String {
    let marker = "\"harfbuzzVersion\":\"";
    let mut out = String::with_capacity(dump.len());
    let mut rest = dump;
    while let Some(position) = rest.find(marker) {
        out.push_str(&rest[..position + marker.len()]);
        let after = &rest[position + marker.len()..];
        match after.find('"') {
            Some(end) => {
                out.push_str("<engine>");
                rest = &after[end..];
            }
            None => {
                out.push_str(after);
                rest = "";
            }
        }
    }
    out.push_str(rest);
    out
}

/// Prints the first differing byte with context and locates the entry it
/// belongs to, then leaves both dumps in the workdir for inspection.
fn report_first_diff(rust_dump: &str, js_dump: &str, workdir: &Path) {
    let rust_bytes = rust_dump.as_bytes();
    let js_bytes = js_dump.as_bytes();
    let common = rust_bytes.len().min(js_bytes.len());
    let mut index = 0;
    while index < common && rust_bytes[index] == js_bytes[index] {
        index += 1;
    }
    let entry = rust_dump[..index].matches("\"kind\"").count();
    let low = index.saturating_sub(120);
    let rust_high = (index + 120).min(rust_bytes.len());
    let js_high = (index + 120).min(js_bytes.len());
    eprintln!("divergence at byte {index} (entry #{entry}, 1-based over emitted kinds):");
    eprintln!(
        "  rust: …{}…",
        String::from_utf8_lossy(&rust_bytes[low..rust_high])
    );
    eprintln!(
        "  js:   …{}…",
        String::from_utf8_lossy(&js_bytes[low..js_high])
    );

    let rust_path = workdir.join("rust-dump.json");
    let js_path = workdir.join("js-dump.json");
    let _ = std::fs::write(&rust_path, rust_dump);
    let _ = std::fs::write(&js_path, js_dump);
    eprintln!("full dumps: {} {}", rust_path.display(), js_path.display());
}
