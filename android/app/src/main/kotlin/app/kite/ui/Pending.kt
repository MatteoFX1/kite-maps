@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import androidx.compose.runtime.Composable
import app.kite.*
import app.kite.core.*

// Screens still being ported from the iPhone app. Each moves to its own file as it's done.
@Composable fun SavedPage(model: AppModel) {}
@Composable fun SettingsPage(model: AppModel) {}
@Composable fun LayersSheet(close: () -> Unit) {}
@Composable fun PlacePicker(model: AppModel, kind: PickKind) {}
@Composable fun StreetViewScreen(req: StreetViewRequest, close: () -> Unit) {}
@Composable fun WelcomeScreen() {}
@Composable fun WazeIntroScreen() {}
