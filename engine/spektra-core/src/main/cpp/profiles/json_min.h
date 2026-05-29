/*
 * SpectraFilm for Android — native engine: minimal dependency-free JSON parser.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 *
 * --------------------------------------------------------------------------------
 * A tiny, header-only, NDK-friendly recursive-descent JSON parser. It supports
 * the subset spektrafilm profile JSON uses: objects, arrays, strings, numbers,
 * booleans, and `null` (decoded as NaN for numeric leaves). No external
 * dependencies; standard library only.
 * --------------------------------------------------------------------------------
 */
#ifndef SPK_PROFILES_JSON_MIN_H
#define SPK_PROFILES_JSON_MIN_H

#include <cmath>
#include <cstdlib>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace spk {
namespace json {

class Value;
using ValuePtr = std::shared_ptr<Value>;

enum class Type { Null, Bool, Number, String, Array, Object };

class Value {
public:
    Type type = Type::Null;
    bool boolean = false;
    double number = 0.0;       // NaN for JSON null in numeric context
    bool is_null_number = false;  // true when this leaf was a JSON `null`
    std::string str;
    std::vector<ValuePtr> array;
    std::map<std::string, ValuePtr> object;

    bool is_object() const { return type == Type::Object; }
    bool is_array() const { return type == Type::Array; }
    bool is_string() const { return type == Type::String; }
    bool is_number() const { return type == Type::Number; }

    // Object member access; throws if missing or wrong type.
    const Value& at(const std::string& key) const {
        if (type != Type::Object) throw std::runtime_error("JSON: not an object for key '" + key + "'");
        auto it = object.find(key);
        if (it == object.end()) throw std::runtime_error("JSON: missing key '" + key + "'");
        return *it->second;
    }
    bool has(const std::string& key) const {
        return type == Type::Object && object.find(key) != object.end();
    }

    const Value& operator[](size_t i) const {
        if (type != Type::Array) throw std::runtime_error("JSON: not an array");
        return *array.at(i);
    }
    size_t size() const { return array.size(); }

    // Numeric leaf -> double; JSON null becomes NaN (matching the Python loader's
    // allow_nan round-trip where missing measurements are stored as `null`).
    double as_number() const {
        if (type == Type::Number) return is_null_number ? std::nan("") : number;
        if (type == Type::Null) return std::nan("");
        throw std::runtime_error("JSON: value is not a number");
    }
    const std::string& as_string() const {
        if (type != Type::String) throw std::runtime_error("JSON: value is not a string");
        return str;
    }
};

class Parser {
public:
    explicit Parser(const std::string& text) : s_(text), n_(text.size()) {}

    ValuePtr parse() {
        skip_ws();
        ValuePtr v = parse_value();
        skip_ws();
        if (pos_ != n_) throw std::runtime_error("JSON: trailing characters");
        return v;
    }

private:
    const std::string& s_;
    size_t pos_ = 0;
    size_t n_;

    [[noreturn]] void fail(const std::string& msg) {
        throw std::runtime_error("JSON parse error at " + std::to_string(pos_) + ": " + msg);
    }
    char peek() { return pos_ < n_ ? s_[pos_] : '\0'; }
    char get() { return pos_ < n_ ? s_[pos_++] : '\0'; }

    void skip_ws() {
        while (pos_ < n_) {
            char c = s_[pos_];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') ++pos_;
            else break;
        }
    }

    ValuePtr parse_value() {
        skip_ws();
        char c = peek();
        switch (c) {
            case '{': return parse_object();
            case '[': return parse_array();
            case '"': return parse_string_value();
            case 't': case 'f': return parse_bool();
            case 'n': return parse_null();
            default: return parse_number();
        }
    }

    ValuePtr parse_object() {
        auto v = std::make_shared<Value>();
        v->type = Type::Object;
        get();  // '{'
        skip_ws();
        if (peek() == '}') { get(); return v; }
        while (true) {
            skip_ws();
            if (peek() != '"') fail("expected string key");
            std::string key = parse_raw_string();
            skip_ws();
            if (get() != ':') fail("expected ':'");
            ValuePtr val = parse_value();
            v->object[key] = val;
            skip_ws();
            char d = get();
            if (d == ',') continue;
            if (d == '}') break;
            fail("expected ',' or '}'");
        }
        return v;
    }

    ValuePtr parse_array() {
        auto v = std::make_shared<Value>();
        v->type = Type::Array;
        get();  // '['
        skip_ws();
        if (peek() == ']') { get(); return v; }
        while (true) {
            ValuePtr val = parse_value();
            v->array.push_back(val);
            skip_ws();
            char d = get();
            if (d == ',') continue;
            if (d == ']') break;
            fail("expected ',' or ']'");
        }
        return v;
    }

    std::string parse_raw_string() {
        get();  // opening quote
        std::string out;
        while (pos_ < n_) {
            char c = s_[pos_++];
            if (c == '"') return out;
            if (c == '\\') {
                char e = pos_ < n_ ? s_[pos_++] : '\0';
                switch (e) {
                    case '"': out += '"'; break;
                    case '\\': out += '\\'; break;
                    case '/': out += '/'; break;
                    case 'b': out += '\b'; break;
                    case 'f': out += '\f'; break;
                    case 'n': out += '\n'; break;
                    case 'r': out += '\r'; break;
                    case 't': out += '\t'; break;
                    case 'u': {
                        // Minimal \uXXXX handling: decode to UTF-8 (BMP only).
                        if (pos_ + 4 > n_) fail("bad unicode escape");
                        unsigned code = (unsigned)std::strtoul(s_.substr(pos_, 4).c_str(), nullptr, 16);
                        pos_ += 4;
                        if (code < 0x80) out += (char)code;
                        else if (code < 0x800) {
                            out += (char)(0xC0 | (code >> 6));
                            out += (char)(0x80 | (code & 0x3F));
                        } else {
                            out += (char)(0xE0 | (code >> 12));
                            out += (char)(0x80 | ((code >> 6) & 0x3F));
                            out += (char)(0x80 | (code & 0x3F));
                        }
                        break;
                    }
                    default: fail("bad escape");
                }
            } else {
                out += c;
            }
        }
        fail("unterminated string");
    }

    ValuePtr parse_string_value() {
        auto v = std::make_shared<Value>();
        v->type = Type::String;
        v->str = parse_raw_string();
        return v;
    }

    ValuePtr parse_bool() {
        auto v = std::make_shared<Value>();
        v->type = Type::Bool;
        if (s_.compare(pos_, 4, "true") == 0) { v->boolean = true; pos_ += 4; }
        else if (s_.compare(pos_, 5, "false") == 0) { v->boolean = false; pos_ += 5; }
        else fail("invalid literal");
        return v;
    }

    ValuePtr parse_null() {
        auto v = std::make_shared<Value>();
        if (s_.compare(pos_, 4, "null") == 0) { pos_ += 4; }
        else fail("invalid literal");
        // Represent null as a Number leaf carrying NaN so numeric arrays parse
        // uniformly; as_number() yields NaN either way.
        v->type = Type::Number;
        v->is_null_number = true;
        v->number = std::nan("");
        return v;
    }

    ValuePtr parse_number() {
        size_t start = pos_;
        if (peek() == '-' || peek() == '+') get();
        while (pos_ < n_) {
            char c = s_[pos_];
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' ||
                c == '+' || c == '-') {
                ++pos_;
            } else break;
        }
        if (pos_ == start) fail("invalid number");
        auto v = std::make_shared<Value>();
        v->type = Type::Number;
        v->number = std::strtod(s_.substr(start, pos_ - start).c_str(), nullptr);
        return v;
    }
};

inline ValuePtr parse(const std::string& text) {
    return Parser(text).parse();
}

}  // namespace json
}  // namespace spk

#endif  // SPK_PROFILES_JSON_MIN_H
