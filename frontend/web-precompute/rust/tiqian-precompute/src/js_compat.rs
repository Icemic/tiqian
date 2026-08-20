//! JS value semantics shared by the `precompute-fonts.js` port: Number →
//! String formatting, `String.prototype.trim`, and the UTF-16 code-unit
//! ordering of `Array.prototype.sort` (ADR 0050 parity oracle).

/// Formats an f64 the way JavaScript `String(number)` does: shortest
/// round-trip digits, integer form below 1e21, exponential form with a
/// signed exponent at 1e21 and below 1e-6.
pub fn js_number_string(value: f64) -> String {
    if value.is_nan() {
        return "NaN".to_string();
    }
    if value.is_infinite() {
        return if value > 0.0 { "Infinity" } else { "-Infinity" }.to_string();
    }
    if value == 0.0 {
        return "0".to_string(); // covers -0: String(-0) === "0"
    }
    let magnitude = value.abs();
    if value.fract() == 0.0 && magnitude < 1e21 {
        return format!("{}", value as i128);
    }
    if (1e-6..1e21).contains(&magnitude) {
        return format!("{value}");
    }
    // Exponential range. Rust's {:e} is shortest round-trip but omits the
    // '+' on positive exponents; JavaScript always writes the sign.
    let rust_form = format!("{value:e}");
    match rust_form.split_once('e') {
        Some((mantissa, exponent)) if !exponent.starts_with('-') => {
            format!("{mantissa}e+{exponent}")
        }
        _ => rust_form,
    }
}

/// `String.prototype.trim`: strips Unicode White_Space plus U+FEFF from both
/// ends. Rust's `str::trim` covers White_Space but not U+FEFF.
pub fn js_trim(value: &str) -> &str {
    value.trim_matches(|c: char| c.is_whitespace() || c == '\u{feff}')
}

/// `Math.min`: NaN propagates (Rust's `f64::min` drops it); `-0` wins over
/// `+0` (Rust's may return either).
pub fn js_min(left: f64, right: f64) -> f64 {
    if left.is_nan() || right.is_nan() {
        return f64::NAN;
    }
    if left == right {
        return if left.is_sign_negative() || right.is_sign_negative() { -0.0 } else { left };
    }
    if left < right { left } else { right }
}

/// `Math.max`: NaN propagates; `+0` wins over `-0`.
pub fn js_max(left: f64, right: f64) -> f64 {
    if left.is_nan() || right.is_nan() {
        return f64::NAN;
    }
    if left == right {
        return if left.is_sign_negative() && right.is_sign_negative() { left } else { left.abs() };
    }
    if left > right { left } else { right }
}

/// Orders strings by UTF-16 code units, the comparison `Array.prototype.sort`
/// uses on strings. It differs from Unicode scalar ordering when a
/// supplementary character (surrogate pair, D800–DFFF) meets a BMP character
/// at or above U+E000.
pub fn cmp_utf16(left: &str, right: &str) -> std::cmp::Ordering {
    left.encode_utf16().cmp(right.encode_utf16())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cmp::Ordering;

    #[test]
    fn number_string_matches_javascript_forms() {
        assert_eq!(js_number_string(0.0), "0");
        assert_eq!(js_number_string(-0.0), "0");
        assert_eq!(js_number_string(400.0), "400");
        assert_eq!(js_number_string(400.5), "400.5");
        assert_eq!(js_number_string(0.5), "0.5");
        assert_eq!(js_number_string(-42.75), "-42.75");
        assert_eq!(js_number_string(1e20), "100000000000000000000");
        assert_eq!(js_number_string(1e21), "1e+21");
        assert_eq!(js_number_string(1.5e25), "1.5e+25");
        assert_eq!(js_number_string(-1e21), "-1e+21");
        assert_eq!(js_number_string(1e-6), "0.000001");
        assert_eq!(js_number_string(1e-7), "1e-7");
        assert_eq!(js_number_string(1.5e-7), "1.5e-7");
        assert_eq!(js_number_string(f64::NAN), "NaN");
        assert_eq!(js_number_string(f64::INFINITY), "Infinity");
        assert_eq!(js_number_string(f64::NEG_INFINITY), "-Infinity");
    }

    #[test]
    fn math_min_max_propagate_nan_like_javascript() {
        assert_eq!(js_min(1.0, 2.0), 1.0);
        assert_eq!(js_max(1.0, 2.0), 2.0);
        assert!(js_min(f64::NAN, 2.0).is_nan());
        assert!(js_max(1.0, f64::NAN).is_nan());
        assert!(js_min(f64::NAN, f64::NAN).is_nan());
        assert_eq!(js_min(-0.0, 0.0).is_sign_negative(), true);
        assert_eq!(js_max(-0.0, 0.0).is_sign_negative(), false);
    }

    #[test]
    fn trim_strips_whitespace_and_bom() {
        assert_eq!(js_trim("  Source Han\t"), "Source Han");
        assert_eq!(js_trim("\u{feff}思源黑体\u{feff}"), "思源黑体");
        assert_eq!(js_trim("\u{00a0}x\u{2028}"), "x");
        assert_eq!(js_trim(""), "");
    }

    #[test]
    fn utf16_order_puts_surrogate_pairs_before_high_bmp() {
        let astral = "x\u{10000}";
        let high_bmp = "x\u{fffd}";
        assert_eq!(cmp_utf16(astral, high_bmp), Ordering::Less);
        // scalar order is the opposite; the comparator must not use it
        assert!(astral.chars().collect::<Vec<_>>() > high_bmp.chars().collect::<Vec<_>>());
    }
}
