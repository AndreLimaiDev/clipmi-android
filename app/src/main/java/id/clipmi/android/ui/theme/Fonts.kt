package id.clipmi.android.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import id.clipmi.android.R

/** Plus Jakarta Sans dari res/font (satu berkas variabel), dipasang ke tema bersama saat aplikasi mulai. */
fun installClipMiFonts() {
    ClipMiFonts.install(
        FontFamily(
            listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold).map { weight ->
                Font(R.font.plus_jakarta_sans, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
            },
        ),
    )
}
