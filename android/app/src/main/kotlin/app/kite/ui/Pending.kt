package app.kite.ui

import androidx.compose.runtime.Composable
import app.kite.*
import app.kite.core.*

// Screens still being ported from the iPhone app. Each moves to its own file as it's done.
@Composable fun NavigationOverlay(model: AppModel, nav: Navigator) {}
@Composable fun DirectionsOverlay(model: AppModel) {}
@Composable fun SavedPage(model: AppModel) {}
@Composable fun SettingsPage(model: AppModel) {}
@Composable fun WazeInfoToast(model: AppModel, info: WazeAlert) {}
@Composable fun LayersSheet(close: () -> Unit) {}
@Composable fun StopPicker(model: AppModel) {}
@Composable fun PlacePicker(model: AppModel, kind: PickKind) {}
@Composable fun StreetViewScreen(req: StreetViewRequest, close: () -> Unit) {}
@Composable fun WelcomeScreen() {}
@Composable fun WazeIntroScreen() {}
