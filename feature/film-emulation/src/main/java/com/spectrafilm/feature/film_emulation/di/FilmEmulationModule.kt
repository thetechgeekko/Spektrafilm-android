/*
 * SpectraFilm for Android — film-emulation DI module.
 * Copyright (C) 2026 SpectraFilm Android contributors.
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
 * Provides the native SpektraEngine as an application-scoped singleton. The
 * engine loads libspektra.so and reads bundled profile/LUT/ICC assets, so it is
 * expensive to create and held for the app's lifetime.
 */
package com.spectrafilm.feature.film_emulation.di

import com.spectrafilm.engine.SpektraEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object FilmEmulationModule {

    @Provides
    @Singleton
    fun provideSpektraEngine(): SpektraEngine =
        // assetDir = null lets the engine resolve its bundled spektra/ assets
        // (profiles, luts, icc) from the linked native library's asset path.
        // TODO(host): if the host extracts assets to filesDir, pass that path here.
        SpektraEngine(assetDir = null)
}
