package com.example.calculatexiaomi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

import com.example.calculatexiaomi.ui.theme.CalculateXiaomiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CalculateXiaomiTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Home(innerPadding)
                }
            }
        }
    }
}

@Composable
fun Home(innerPadding: PaddingValues) {

}


@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    CalculateXiaomiTheme {
        Home(PaddingValues())
    }
}
