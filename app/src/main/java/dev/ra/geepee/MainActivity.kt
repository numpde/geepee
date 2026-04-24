package dev.ra.geepee

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val viewModel: GeePeeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        syncPermissions()

        setContent {
            GeePeeApp(viewModel = viewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
    }

    override fun onResume() {
        super.onResume()
        syncPermissions()
    }

    override fun onStop() {
        viewModel.setForeground(false)
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val uiState = viewModel.uiState
        if (event.repeatCount == 0 && uiState.sessionRunning && uiState.routeModel != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    viewModel.zoomInRouteScale()
                    return true
                }

                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    viewModel.zoomOutRouteScale()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun syncPermissions() {
        viewModel.updateLocationPermissions(
            coarseGranted = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
            fineGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
        )
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}
