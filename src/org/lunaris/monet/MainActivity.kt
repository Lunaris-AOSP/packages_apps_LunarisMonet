/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.monet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import com.android.axion.compose.preferences.ExpressiveShapes
import com.android.axion.compose.theme.AxionTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MonetViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AxionTheme { MaterialTheme(shapes = ExpressiveShapes) { MonetScreen(viewModel) } }
        }
    }
}
