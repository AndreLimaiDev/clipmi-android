package id.clipmi.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.clipmi.android.ui.theme.ClipMi

/**
 * Panduan singkat membuat gaya analisis sendiri (dibuka dari ikon di lembar Gaya analisis): apa itu gaya, cara menulis
 * kriteria, dan contoh yang bisa ditiru.
 */
@Composable
fun PromptStyleGuideSheet(onDismiss: () -> Unit) {
    val c = ClipMi.colors
    ClipMiSheet(onDismiss, title = "Panduan gaya sendiri", subtitle = "Gaya = cara AI memilih momen dari video jenis tertentu.") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GuideStep(1, "Beri nama sesuai jenis video", "Misalnya \"Khutbah Jumat\", \"Kajian Fiqih\", atau \"Podcast Pemuda\". Keterangan boleh dikosongkan.")
            GuideStep(2, "Tulis kriteria, satu per baris", "Setiap baris = satu aturan yang AI ikuti saat memilih momen. Tulis singkat dan jelas, seperti memberi arahan ke editor.")
            GuideStep(3, "Aturan dasar ditambahkan otomatis", "Durasi klip, momen yang berdiri sendiri, tidak memotong kalimat, jumlah klip, dan seri sudah ada. Tidak perlu ditulis lagi.")
            GuideStep(4, "Pilih gaya, lalu analisis", "Pilih gaya di lembar ini, atur durasi/jumlah klip bila perlu, simpan, lalu jalankan analisis (otomatis atau lewat chat AI).")
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.bg).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Contoh kriteria", style = MaterialTheme.typography.titleSmall, color = c.text)
            listOf(
                "Utamakan nasihat praktis yang bisa langsung diamalkan.",
                "Lewati bagian pembukaan, salam, dan doa panjang.",
                "Lewati bagian berbahasa Arab tanpa terjemahan.",
                "Pilih momen dengan cerita atau perumpamaan yang menyentuh.",
                "Hindari bagian tanya jawab yang pertanyaannya tidak terdengar.",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = c.textBody) }
        }
        Text(
            "Tips: kalau hasilnya kurang pas, ubah satu kriteria saja lalu analisis lagi, supaya terlihat aturan mana yang berpengaruh.",
            style = MaterialTheme.typography.bodySmall, color = c.textMuted,
        )
        PrimaryButton("Mengerti", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun GuideStep(number: Int, title: String, body: String) {
    val c = ClipMi.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = c.accent)
        }
        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(body, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
    }
}
