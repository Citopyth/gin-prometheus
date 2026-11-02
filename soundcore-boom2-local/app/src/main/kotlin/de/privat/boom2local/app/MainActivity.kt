package de.privat.boom2local.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import de.privat.boom2local.app.ui.Boom2App
import de.privat.boom2local.app.ui.Boom2Theme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Boom2Theme {
                val model: Boom2ViewModel = viewModel()
                Boom2App(model)
            }
        }
    }
}
