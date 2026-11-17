<?php

/**
 * Mengekspor contoh file ASS dari ClipMI desktop (AssWriter + CaptionPreview yang sama dengan render desktop) untuk
 * uji libass di Android. Repo desktop hanya dibaca: gaya subtitle dibuat di memori, tidak ada yang disimpan ke DB.
 *
 *   php tools/export-ass-fixtures.php D:/laragon/www/clip-mi app/src/main/assets/lab
 */

[$script, $desktop, $outDir] = $argv + [null, 'D:/laragon/www/clip-mi', __DIR__.'/../app/src/main/assets/lab'];

require $desktop.'/vendor/autoload.php';
$app = require $desktop.'/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();

use App\Models\CaptionStyle;
use App\Render\AssWriter;
use App\Render\CaptionPreview;

$base = [
    'font_family' => 'Poppins', 'font_size' => 76, 'uppercase' => false,
    'primary_color' => '#FFFFFF', 'outline_color' => '#000000',
    'highlight_active_word' => false, 'highlight_style' => 'color', 'highlight_color' => '#FFD400',
    'upcoming_color' => null, 'keyword_highlight' => true, 'keyword_color' => '#22C55E',
    'position' => 'bottom', 'words_per_line' => 4, 'animation' => 'none', 'effect' => 'outline',
    'line_box' => false, 'line_box_color' => '#000000', 'line_box_opacity' => 65,
];

// Kombinasi yang mewakili fitur gaya desktop: font, efek, sorotan karaoke, kotak baris, animasi, posisi tengah.
$styles = [
    'poppins-outline' => [],
    'anton-glow-karaoke' => ['font_family' => 'Anton', 'effect' => 'glow', 'highlight_active_word' => true, 'uppercase' => true],
    'montserrat-box-pop' => ['font_family' => 'Montserrat ExtraBold', 'highlight_active_word' => true, 'highlight_style' => 'box', 'animation' => 'pop', 'words_per_line' => 3],
    'bebas-shadow-linebox' => ['font_family' => 'Bebas Neue', 'effect' => 'shadow', 'line_box' => true, 'position' => 'middle', 'font_size' => 90],
    'lilita-reveal-upcoming' => ['font_family' => 'Lilita One', 'animation' => 'reveal', 'upcoming_color' => '#9CA3AF', 'highlight_active_word' => true],
];

@mkdir($outDir, 0777, true);
$preview = $app->make(CaptionPreview::class);
$writer = $app->make(AssWriter::class);
$manifest = [];

foreach ($styles as $name => $overrides) {
    $style = new CaptionStyle(array_merge($base, ['name' => $name], $overrides));
    $built = $preview->build($style);
    file_put_contents("$outDir/$name.ass", $built['ass']);
    $manifest[] = ['name' => $name, 'file' => "$name.ass", 'duration' => $built['duration']];
}

// Hook bawaan (kotak transparan di atas) + subtitle, seperti klip sungguhan.
$style = new CaptionStyle(array_merge($base, ['name' => 'hook']));
$lines = [];
$time = 0.3;
foreach (array_chunk(CaptionPreview::WORDS, 4) as $chunk) {
    $words = [];
    $start = $time;
    foreach ($chunk as $word) {
        $words[] = ['text' => $word, 'start' => round($time, 3), 'end' => round($time + 0.45, 3)];
        $time += 0.45;
    }
    $lines[] = ['start' => round($start, 3), 'end' => round($time, 3), 'text' => implode(' ', $chunk), 'words' => $words];
}
$duration = round($time + 0.8, 3);
file_put_contents("$outDir/hook-poppins.ass", $writer->build($lines, $duration, 'Kenapa orang sabar dicintai Allah?', $style, keywords: ['sabar']));
$manifest[] = ['name' => 'hook-poppins', 'file' => 'hook-poppins.ass', 'duration' => $duration];

file_put_contents("$outDir/manifest.json", json_encode($manifest, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES)."\n");
echo 'Ditulis '.count($manifest)." file ASS ke $outDir\n";
