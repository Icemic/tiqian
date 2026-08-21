//! Plain-text issue parity: the Rust checks against the js oracle over every
//! Unicode code point (ADR 0050 amendment `PrecomputeInRust`).
//!
//! `scripts/plain-text-issue-oracle.mjs` runs the js `snapshotPlainTextIssue`
//! on each code point as a single-character text and stores a run-length dump;
//! this test rebuilds the same dump from `snapshot_plain_text_issue` and
//! compares the ranges. A mismatch means the generated Unicode tables drifted
//! from the js engine data; regenerate the tables and re-run the oracle.
//!
//! The comparison skips with a reason when the oracle dump is absent.

use std::path::PathBuf;

use tiqian_precompute::json::{parse_json, Json};
use tiqian_precompute::normalize::snapshot_plain_text_issue;

const MAX_CODE_POINT: u32 = 0x10ffff;

struct IssueRange {
    start: u32,
    end: u32,
    issue: Option<String>,
}

fn oracle_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../build/plain-text-issue/oracle.json")
}

/// The Rust dump over the same single-character corpus.
fn native_ranges() -> Vec<IssueRange> {
    let mut ranges: Vec<IssueRange> = Vec::new();
    let mut current = issue_at(0);
    let mut start = 0u32;
    for point in 1..=MAX_CODE_POINT {
        let issue = issue_at(point);
        if issue != current {
            ranges.push(IssueRange {
                start,
                end: point - 1,
                issue: current,
            });
            start = point;
            current = issue;
        }
    }
    ranges.push(IssueRange {
        start,
        end: MAX_CODE_POINT,
        issue: current,
    });
    ranges
}

fn issue_at(point: u32) -> Option<String> {
    if let Some(ch) = char::from_u32(point) {
        snapshot_plain_text_issue(&ch.to_string()).map(str::to_string)
    } else {
        // Surrogate halves are not Rust chars; the js lane sees lone
        // surrogates and classifies them as unassigned, which the script gate
        // reports. Keep the native dump aligned by classifying them the same
        // way the tables do: surrogates are not Common, not Han.
        Some("UnsupportedSnapshotScript".to_string())
    }
}

fn oracle_ranges(json: &Json) -> Vec<IssueRange> {
    let Json::Arr(entries) = json else {
        panic!("oracle dump is not an array");
    };
    entries
        .iter()
        .map(|entry| {
            let Json::Obj(fields) = entry else {
                panic!("oracle entry is not an object");
            };
            let mut range = IssueRange {
                start: 0,
                end: 0,
                issue: None,
            };
            for (key, value) in fields {
                match (key.as_str(), value) {
                    ("start", Json::Num(value)) => range.start = *value as u32,
                    ("end", Json::Num(value)) => range.end = *value as u32,
                    ("issue", Json::Str(value)) => range.issue = Some(value.clone()),
                    ("issue", Json::Null) => range.issue = None,
                    _ => panic!("unexpected oracle field {key}"),
                }
            }
            range
        })
        .collect()
}

#[test]
fn plain_text_issue_matches_the_js_oracle_over_all_code_points() {
    let Ok(oracle) = std::fs::read_to_string(oracle_path()) else {
        if std::env::var("TIQIAN_REQUIRE_PARITY_ORACLE").is_ok_and(|value| value == "1") {
            panic!(
                "TIQIAN_REQUIRE_PARITY_ORACLE=1 but no oracle dump at {}; \
                 run node scripts/plain-text-issue-oracle.mjs in frontend/web-precompute",
                oracle_path().display()
            );
        }
        eprintln!(
            "skipped: no oracle dump at {}; run node scripts/plain-text-issue-oracle.mjs in frontend/web-precompute to produce it",
            oracle_path().display()
        );
        return;
    };
    let expected = oracle_ranges(&parse_json(&oracle).expect("oracle json parses"));
    let native = native_ranges();
    assert_eq!(native.len(), expected.len(), "range count differs");
    for (index, (left, right)) in native.iter().zip(&expected).enumerate() {
        assert_eq!(left.start, right.start, "range {index} start differs");
        assert_eq!(left.end, right.end, "range {index} end differs");
        assert_eq!(left.issue, right.issue, "range {index} issue differs");
    }
}
