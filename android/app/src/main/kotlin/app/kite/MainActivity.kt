package app.kite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.maps.MapView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                MapView(ctx).apply {
                    onCreate(null)
                    getMapAsync { it.setStyle("https://tiles.openfreemap.org/styles/liberty") }
                }
            })
        }
    }
}
