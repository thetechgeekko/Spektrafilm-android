/*
 * Spektrafilm for Android — feature:film-emulation build.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * NOTE: This module activates at M1, once the ImageToolbox host (with its
 * build-logic convention plugins + gradle/libs.versions.toml catalog) is seeded
 * into the repo (see tools/bootstrap.md). Until then it documents the intended
 * build: a standard ImageToolbox feature module driving engine:spektra-core.
 */
plugins {
    alias(libs.plugins.image.toolbox.library)
    alias(libs.plugins.image.toolbox.feature)
    alias(libs.plugins.image.toolbox.hilt)
    alias(libs.plugins.image.toolbox.compose)
}

android.namespace = "com.spectrafilm.feature.film_emulation"

dependencies {
    // The Spektrafilm additions this screen drives.
    implementation(projects.engine.spektraCore)
    implementation(projects.lib.libraw)

    // The usual core:* modules. The image.toolbox.feature convention plugin
    // already brings most of these transitively; listed explicitly here to
    // mirror feature modules that depend on core APIs directly.
    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(projects.core.domain)
    implementation(projects.core.resources)
}
