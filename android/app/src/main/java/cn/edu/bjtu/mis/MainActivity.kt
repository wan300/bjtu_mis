package cn.edu.bjtu.mis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import cn.edu.bjtu.mis.ui.BjtuMisApp
import cn.edu.bjtu.mis.ui.theme.BjtuMisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BjtuMisApplication
        setContent {
            BjtuMisTheme {
                BjtuMisApp(app.container, onExit = { finish() })
            }
        }
    }
}
