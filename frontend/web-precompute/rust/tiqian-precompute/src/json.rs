//! JSON emission matching `JSON.stringify`: the replay keys of
//! `snapshot-schema.js` are `JSON.stringify([...])` calls, so the Rust port
//! needs byte-compatible output — same key order (insertion), same number
//! formatting (`String(number)`), `NaN`/`Infinity` as `null`, `-0` as `0`.

use crate::js_compat::js_number_string;

/// A JSON value built in insertion order, the way JS object literals order
/// keys for `JSON.stringify`.
#[derive(Debug, Clone, PartialEq)]
pub enum Json {
    Null,
    Bool(bool),
    Num(f64),
    Str(String),
    Arr(Vec<Json>),
    Obj(Vec<(String, Json)>),
}

impl Json {
    pub fn str(value: impl Into<String>) -> Json {
        Json::Str(value.into())
    }

    /// Renders the value the way `JSON.stringify` does.
    pub fn render(&self) -> String {
        let mut out = String::new();
        self.write(&mut out);
        out
    }

    fn write(&self, out: &mut String) {
        match self {
            Json::Null => out.push_str("null"),
            Json::Bool(value) => out.push_str(if *value { "true" } else { "false" }),
            Json::Num(value) => {
                // JSON.stringify maps NaN and ±Infinity to null.
                if value.is_finite() {
                    out.push_str(&js_number_string(*value));
                } else {
                    out.push_str("null");
                }
            }
            Json::Str(value) => out.push_str(&json_string(value)),
            Json::Arr(items) => {
                out.push('[');
                for (index, item) in items.iter().enumerate() {
                    if index > 0 {
                        out.push(',');
                    }
                    item.write(out);
                }
                out.push(']');
            }
            Json::Obj(fields) => {
                out.push('{');
                for (index, (key, value)) in fields.iter().enumerate() {
                    if index > 0 {
                        out.push(',');
                    }
                    out.push_str(&json_string(key));
                    out.push(':');
                    value.write(out);
                }
                out.push('}');
            }
        }
    }

}

/// `JSON.stringify(string)`: escapes `"`, `\`, the C0 shorthands and other
/// control characters; leaves everything above U+001F (including U+007F and
/// non-ASCII) as raw text.
pub fn json_string(value: &str) -> String {
    let mut out = String::with_capacity(value.len() + 2);
    out.push('"');
    for c in value.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\u{0008}' => out.push_str("\\b"),
            '\u{0009}' => out.push_str("\\t"),
            '\u{000a}' => out.push_str("\\n"),
            '\u{000c}' => out.push_str("\\f"),
            '\u{000d}' => out.push_str("\\r"),
            c if (c as u32) < 0x20 => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn string_escapes_match_json_stringify() {
        assert_eq!(json_string("plain"), "\"plain\"");
        assert_eq!(json_string("a\"b\\c"), "\"a\\\"b\\\\c\"");
        assert_eq!(json_string("line\nbreak\ttab"), "\"line\\nbreak\\ttab\"");
        assert_eq!(json_string("\u{0001}\u{001f}"), "\"\\u0001\\u001f\"");
        // U+007F and astral characters stay raw, the way stringify writes them.
        assert_eq!(json_string("\u{007f}你😀"), "\"\u{007f}你😀\"");
    }

    #[test]
    fn numbers_render_like_stringify() {
        assert_eq!(Json::Num(400.0).render(), "400");
        assert_eq!(Json::Num(400.5).render(), "400.5");
        assert_eq!(Json::Num(-0.0).render(), "0");
        assert_eq!(Json::Num(f64::NAN).render(), "null");
        assert_eq!(Json::Num(f64::INFINITY).render(), "null");
        assert_eq!(Json::Num(1e21).render(), "1e+21");
        assert_eq!(Json::Num(0.000001).render(), "0.000001");
        assert_eq!(Json::Num(1e-7).render(), "1e-7");
    }

    #[test]
    fn arrays_and_objects_keep_insertion_order() {
        let value = Json::Arr(vec![
            Json::str("a\u{001f}b"),
            Json::Num(700.0),
            Json::Bool(false),
            Json::Null,
            Json::Obj(vec![
                ("wght".to_string(), Json::Num(350.0)),
                ("rest".to_string(), Json::Null),
            ]),
        ]);
        assert_eq!(
            value.render(),
            "[\"a\\u001fb\",700,false,null,{\"wght\":350,\"rest\":null}]"
        );
    }
}
