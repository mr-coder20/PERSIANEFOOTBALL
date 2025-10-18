package psp.msmi.efootball.ui.theme

// import coil.size.Size // اگر مستقیما استفاده نمی شود، می توان حذف کرد
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import psp.msmi.efootball.R

@Composable
fun MainScreen(
    // downloadProgress: Int, // <<-- حذف شد
    // isDownloading: Boolean, // <<-- حذف شد
    onDownloadRequest: () -> Unit,
    onStartGame: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRubika: () -> Unit,
    onInstallSimulator: () -> Unit,
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    backgroundChoice: String
) {
    val systemUiController = rememberSystemUiController()

    SideEffect {
        systemUiController.setSystemBarsColor(
            color = Color.Transparent,
            darkIcons = false // آیکون های استاتوس بار باید در حالت دارک، روشن باشند و بالعکس
            // این منطق شماست و تغییری داده نشده.
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {

        Crossfade(targetState = backgroundChoice, label = "BackgroundCrossfade") { bg ->
            val imageId = when (bg) {
                "bg1" -> R.drawable.bg1
                "bg2" -> R.drawable.bg2
                else -> R.drawable.bg1
            }

            val context = LocalContext.current
            val painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(imageId)
                    .scale(Scale.FILL)
                    .allowHardware(false)
                    .crossfade(false) // شما از Crossfade خود Compose برای کل پس زمینه استفاده می کنید
                    .precision(Precision.EXACT)
                    .build(),
                imageLoader = context.imageLoader
            )


            Image(
                painter = painter,
                contentDescription = "Background",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 20.dp, end = 16.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            ToggleThemeButton(isDarkMode = isDarkMode, onToggle = onToggleDarkMode)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val buttonGradient = if (isDarkMode) {
                listOf(Color(0xC6FF0055), Color(0xC4FF80AB)) // نارنجی - زرد برای حالت دارک
            } else {
                listOf(Color(0xFF12212C), Color(0xCB0599D3)) // آبی تیره - آبی روشن برای حالت لایت
            }

            GradientButton("شروع بازی", buttonGradient, onStartGame)
            Spacer(Modifier.height(12.dp))
            GradientButton(
                "تنظیمات",
                buttonGradient,
                onClick = onOpenSettings
            )
            Spacer(Modifier.height(12.dp))
            GradientButton("روبیکا", buttonGradient, onOpenRubika)
            Spacer(Modifier.height(12.dp))
            GradientButton("نصب شبیه‌ساز", buttonGradient, onInstallSimulator)
            Spacer(Modifier.height(12.dp))
            GradientButton(
                text = "دانلود دیتا", // <<-- متن ثابت
                gradient = buttonGradient,
                onClick = onDownloadRequest
            )
        }
    }
}

@Composable
fun ToggleThemeButton(isDarkMode: Boolean, onToggle: () -> Unit) {
    var rotationState by remember { mutableFloatStateOf(0f) }
    val rotation by animateFloatAsState(
        targetValue = rotationState,
        animationSpec = tween(durationMillis = 700),
        label = "ThemeToggleRotation"
    )

    IconButton(
        onClick = {
            onToggle()
            rotationState += 360f
        },
        modifier = Modifier
            .size(48.dp)
            .background(
                color = if (isDarkMode) Color(0xC6FF0055) else Color(0xFF274065),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(8.dp)
    ) {
        Icon(
            painter = painterResource(id = if (isDarkMode) R.drawable.ic_sun else R.drawable.ic_moon),
            contentDescription = "Toggle theme",
            tint = Color.White,
            modifier = Modifier
                .rotate(rotation)
                .fillMaxSize()
        )
    }
}

@Composable
fun GradientButton(text: String, gradient: List<Color>, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.horizontalGradient(gradient),
                    shape = RoundedCornerShape(18.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

