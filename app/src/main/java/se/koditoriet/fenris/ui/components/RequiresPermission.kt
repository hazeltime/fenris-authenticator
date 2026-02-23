package se.koditoriet.fenris.ui.components

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import se.koditoriet.fenris.ui.primaryHint
import se.koditoriet.fenris.ui.theme.PADDING_L

private const val TAG = "RequiresPermission"

@Composable
fun RequiresPermission(
    permission: String,
    permissionsRequiredMessage: String = "",
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        onResult = {
            when (it)  {
                true -> Log.i(TAG, "Permission granted: $permission")
                false -> Log.i(TAG, "Permission denied: $permission")
            }
            granted = it
        }
    )

    LaunchedEffect(Unit) {
        if (!granted) {
            Log.i(TAG, "Requesting permission: $permission")
            permissionLauncher.launch(permission)
        }
    }

    if (granted) {
        content()
    } else {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(PADDING_L)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = permissionsRequiredMessage,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primaryHint,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(PADDING_L))
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", ctx.packageName, null)
                        }
                        ctx.startActivity(intent)
                    }) {
                        Text("Open Settings")
                    }
                }
            }
        }
    }
}
