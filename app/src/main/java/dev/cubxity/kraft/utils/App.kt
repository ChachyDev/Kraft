/*
 *     Kraft: Lightweight Minecraft client for Android featuring modules support and other task automation
 *     Copyright (C) 2020  Cubxity
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.cubxity.kraft.utils

import android.content.Context
import androidx.core.content.edit
import java.util.*

val Context.clientToken: UUID
    get() {
        val prefs = getSharedPreferences("minecraft", Context.MODE_PRIVATE)
        return if (prefs.contains("client_token")) {
            UUID.fromString(prefs.getString("client_token", null))
        } else {
            val token = UUID.randomUUID()
            prefs.edit(commit = true) {
                putString("client_token", "$token")
            }
            token
        }
    }