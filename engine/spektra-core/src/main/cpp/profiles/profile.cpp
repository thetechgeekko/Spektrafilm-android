/*
 * SpectraFilm for Android — native engine: film/print Profile struct + loader.
 * Copyright (C) 2026 SpectraFilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm.
 */
#include "profile.h"

#include <fstream>
#include <sstream>
#include <stdexcept>

#include "json_min.h"

namespace spk {

namespace {

// Read a JSON array of numbers into a flat float vector. JSON null -> NaN.
std::vector<float> read_vec(const json::Value& v) {
    if (!v.is_array()) throw std::runtime_error("Profile: expected numeric array");
    std::vector<float> out;
    out.reserve(v.size());
    for (size_t i = 0; i < v.size(); ++i) {
        out.push_back(static_cast<float>(v[i].as_number()));
    }
    return out;
}

// Read a JSON (N x 3) array of numbers into a flat row-major float vector.
// Returns the row count via *rows. JSON null -> NaN.
std::vector<float> read_matrix3(const json::Value& v, int* rows) {
    if (!v.is_array()) throw std::runtime_error("Profile: expected matrix array");
    std::vector<float> out;
    out.reserve(v.size() * 3);
    for (size_t i = 0; i < v.size(); ++i) {
        const json::Value& row = v[i];
        if (!row.is_array() || row.size() != 3)
            throw std::runtime_error("Profile: expected (N,3) matrix row");
        out.push_back(static_cast<float>(row[0].as_number()));
        out.push_back(static_cast<float>(row[1].as_number()));
        out.push_back(static_cast<float>(row[2].as_number()));
    }
    *rows = static_cast<int>(v.size());
    return out;
}

}  // namespace

Profile load_profile_string(const std::string& json_text) {
    json::ValuePtr root = json::parse(json_text);
    const json::Value& r = *root;

    const json::Value& info = r.at("info");
    const json::Value& data = r.at("data");

    Profile p;
    p.type = info.at("type").as_string();
    p.viewing_illuminant = info.at("viewing_illuminant").as_string();
    if (info.has("reference_illuminant"))
        p.reference_illuminant = info.at("reference_illuminant").as_string();

    p.wavelengths = read_vec(data.at("wavelengths"));
    p.n_samples = static_cast<int>(p.wavelengths.size());

    int cd_rows = 0;
    p.channel_density = read_matrix3(data.at("channel_density"), &cd_rows);
    p.base_density = read_vec(data.at("base_density"));

    int dc_rows = 0;
    p.density_curves = read_matrix3(data.at("density_curves"), &dc_rows);
    p.n_density_pts = dc_rows;

    // Validation mirroring profiles/io.py::_validate_profile (subset).
    if (cd_rows != p.n_samples)
        throw std::runtime_error("Profile: channel_density rows != wavelengths");
    if (static_cast<int>(p.base_density.size()) != p.n_samples)
        throw std::runtime_error("Profile: base_density size != wavelengths");

    return p;
}

Profile load_profile_file(const std::string& json_path) {
    std::ifstream in(json_path, std::ios::binary);
    if (!in) throw std::runtime_error("Profile: cannot open " + json_path);
    std::ostringstream ss;
    ss << in.rdbuf();
    return load_profile_string(ss.str());
}

}  // namespace spk
