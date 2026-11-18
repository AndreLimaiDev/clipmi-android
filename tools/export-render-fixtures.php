<?php

/**
 * Mengekspor hasil kode render ClipMI desktop sebagai fixture padanan Kotlin (core, paket id.clipmi.core.render):
 * Composition + filtergraph FFmpeg (ClipRenderer::filter), AssWriter, SubtitleBuilder, HookLayout, Keywords,
 * ClipCuts, dan bagian ClipRenderer yang murni (relativeLines, previewTracks, joinBumpers, opener, audioFilter).
 * Kode desktop dijalankan apa adanya dengan SQLite di memori; repo desktop dan database pengguna tidak tersentuh.
 *
 *   "C:/Users/<user>/AppData/Local/Programs/clipmi/resources/build/php/php.exe" tools/export-render-fixtures.php
 */

$desktop = $argv[1] ?? 'D:/laragon/www/clip-mi';
$out = __DIR__.'/../core/src/test/resources/fixtures/render';

require $desktop.'/vendor/autoload.php';
$app = require $desktop.'/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();

config(['database.default' => 'sqlite', 'database.connections.sqlite.database' => ':memory:', 'queue.default' => 'sync']);
Illuminate\Support\Facades\DB::purge('sqlite');
Illuminate\Support\Facades\Artisan::call('migrate', ['--force' => true]);

use App\Analysis\ClipCuts;
use App\Models\CaptionStyle;
use App\Models\Clip;
use App\Models\FrameStyle;
use App\Models\Project;
use App\Render\AssWriter;
use App\Render\ClipRenderer;
use App\Render\Composition;
use App\Render\HookLayout;
use App\Render\Keywords;
use App\Render\SubtitleBuilder;
use App\Render\TextMeasure;
use App\Transcript\WordTimer;

@mkdir($out, 0777, true);
mt_srand(20261117);

$pick = fn (array $items) => $items[mt_rand(0, count($items) - 1)];
$maybe = fn ($value, int $percentNull = 30) => mt_rand(1, 100) <= $percentNull ? null : $value;
$write = function (string $name, array $data) use ($out) {
    file_put_contents("$out/$name.json", json_encode(['php' => PHP_VERSION, ...$data], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION));
    echo "$name.json\n";
};

// ---------------------------------------------------------------------------------------------------------------
// Composition + filtergraph
// ---------------------------------------------------------------------------------------------------------------

$colors = ['#000000', '#FFFFFF', '#1E3A8A', '#0f766e', '#FFD400', '#123456'];
$randomFrame = function () use ($pick, $maybe, $colors): array {
    $mode = $pick(['crop', 'fit', 'fit', 'top', 'top', 'template']);
    $frame = [
        'name' => 'acak',
        'aspect' => $maybe($pick(array_keys(FrameStyle::ASPECTS)), 10),
        'mode' => $mode,
        'background' => $pick(['blur', 'color', 'gradient', 'image']),
        'blur_strength' => $pick([2, 8, 20, 40]),
        'background_color' => $pick($colors),
        'background_color_2' => $maybe($pick($colors), 20),
        'video_height' => $maybe($pick([20, 30, 50, 65, 85, 95]), 15),
        'video_position' => $maybe($pick(['top', 'center', 'bottom']), 15),
        'zoom' => $maybe($pick([80, 100, 125, 150, 200, 260]), 20),
        'corner_radius' => $maybe($pick([0, 0, 16, 32, 60]), 15),
        'border_width' => $maybe($pick([0, 0, 4, 10]), 15),
        'border_color' => $maybe($pick($colors), 20),
        'logo_x' => $maybe($pick([0, 25, 50, 100, 130]), 15),
        'logo_y' => $maybe($pick([0, 50, 100]), 15),
        'logo_size' => $maybe($pick([3, 10, 18, 40, 55]), 15),
        'logo_opacity' => $maybe($pick([5, 50, 85, 100]), 15),
        'watermark_text' => $maybe($pick(['@kajian.id', '  Ustadz: "Sabar" 100%  ', 'ClipMI']), 50),
        'watermark_font' => $maybe($pick(['Poppins', 'Anton', 'Bebas Neue', 'Tidak Ada']), 20),
        'watermark_size' => $maybe($pick([10, 40, 72, 150]), 15),
        'watermark_color' => $maybe($pick($colors), 15),
        'watermark_opacity' => $maybe($pick([0, 45, 80, 100]), 15),
        'watermark_x' => $maybe($pick([0, 50, 100]), 15),
        'watermark_y' => $maybe($pick([0, 50, 100]), 15),
        'title_bar' => $pick([false, false, true]),
        'title_bar_color' => $pick($colors),
        'title_text_color' => $pick($colors),
        'subtitle_y' => $maybe($pick([2, 20, 50, 80, 99]), 70),
    ];

    if ($mode === 'template') {
        $frame['template_area'] = $pick([
            ['x' => 0.0, 'y' => 0.2, 'width' => 1.0, 'height' => 0.5],
            ['x' => 0.05, 'y' => 0.15, 'width' => 0.9, 'height' => 0.6],
            ['x' => 0.1, 'y' => 0.3333, 'width' => 0.8, 'height' => 0.45],
            ['x' => 0.0, 'y' => 0.0, 'width' => 1.0, 'height' => 1.0],
        ]);
        // Sebagian template tanpa area: usesTemplate() salah, jadi diperlakukan sebagai video utuh.
        if (mt_rand(1, 10) === 1) {
            $frame['template_area'] = null;
        }
    }

    return $frame;
};

$compositions = [];
for ($i = 0; $i < 800; $i++) {
    $attributes = $randomFrame();
    $frame = new FrameStyle($attributes);
    $source = $pick([null, null, [1920, 1080], [1280, 720], [1080, 1920], [720, 1280], [1440, 1080], [0, 0]]);
    $cropX = $pick([50, 50, 0, 33, 100, -5, 120]);
    $zoom = $maybe($pick([1.0, 1.25, 1.5, 2.333, 2.5]), 60);
    $cropY = $pick([50, 50, 0, 25, 100]);
    $composition = Composition::make($frame, $source, $cropX, $zoom, $cropY);
    $inputs = array_filter([
        'background' => $attributes['background'] === 'image' && $frame->mode !== 'template' && mt_rand(0, 1) ? 1 : null,
        'template' => $frame->usesTemplate() && mt_rand(0, 4) > 0 ? 1 : null,
    ]);
    if (mt_rand(0, 2) === 0) {
        $inputs['logo'] = count($inputs) + 1;
    }
    $keep = $pick([null, null, null, 'between(t,0.000,4.250)+between(t,5.100,30.000)']);

    $compositions[] = [
        'frame' => $attributes,
        'source' => $source,
        'cropX' => $cropX,
        'zoom' => $zoom,
        'cropY' => $cropY,
        'inputs' => (object) $inputs,
        'keep' => $keep,
        'composition' => $composition->toArray(),
        'framesVideo' => $composition->framesVideo(),
        'filter' => ClipRenderer::filter($composition, $inputs, $keep),
        'summary' => $frame->summary(),
    ];
}

// anchorExpression dengan pecahan yang perlu dirapikan (0.5, 0.33, 1).
$anchors = [];
foreach ([[0.0, 0.0], [0.5, 1.0], [0.333, 0.666], [1.0, 0.05], [0.125, 0.995]] as [$x, $y]) {
    $composition = Composition::make(new FrameStyle(['aspect' => '4:5', 'mode' => 'crop', 'title_bar' => true]));
    $anchors[] = ['x' => $x, 'y' => $y, 'result' => $composition->anchorExpression($x, $y, 'W', 'H', 'w', 'h')];
}

$write('composition', ['cases' => $compositions, 'anchors' => $anchors]);

// ---------------------------------------------------------------------------------------------------------------
// AssWriter
// ---------------------------------------------------------------------------------------------------------------

$fonts = ['Poppins', 'Anton', 'Archivo Black', 'Montserrat ExtraBold', 'Bebas Neue', 'Lilita One'];
$randomStyle = function () use ($pick, $maybe, $colors, $fonts): array {
    return [
        'name' => 'acak',
        'font_family' => $pick($fonts),
        'font_size' => $pick([40, 64, 76, 90, 120]),
        'uppercase' => $pick([false, true]),
        'primary_color' => $pick($colors),
        'outline_color' => $pick($colors),
        'highlight_active_word' => $pick([false, true]),
        'highlight_style' => $maybe($pick(['color', 'box']), 10),
        'highlight_color' => $pick($colors),
        'upcoming_color' => $maybe($pick($colors), 50),
        'keyword_highlight' => $pick([false, true]),
        'keyword_color' => $maybe($pick($colors), 20),
        'position' => $maybe($pick(['bottom', 'middle']), 10),
        'words_per_line' => $pick([1, 2, 3, 4, 6]),
        'animation' => $maybe($pick(['none', 'pop', 'reveal', 'fade']), 15),
        'effect' => $maybe($pick(['outline', 'shadow', 'glow']), 15),
        'line_box' => $pick([false, false, true]),
        'line_box_color' => $maybe($pick($colors), 20),
        'line_box_opacity' => $maybe($pick([0, 30, 65, 100, 120]), 20),
    ];
};

$vocabulary = ['Rasulullah', 'bersabda,', 'orang', 'yang', 'sabar', 'itu', 'dicintai', 'Allah.', 'jamaah', 'Sabar,', 'ikhlas', '{tag}', 'C:\\path',
    'Qur’an', 'é', 'ẞig', 'insyaAllah', 'ﬁ', 'shalat', 'dan', 'syukur!', '"amanah"', 'yang sabar'];
$randomLines = function (int $count) use ($pick, $vocabulary): array {
    $lines = [];
    $time = mt_rand(0, 3) === 0 ? -0.4 : 0.2;
    for ($i = 0; $i < $count; $i++) {
        $words = [];
        $start = $time;
        for ($k = 0, $n = mt_rand(1, 6); $k < $n; $k++) {
            $length = mt_rand(150, 700) / 1000;
            $words[] = ['text' => $pick($vocabulary), 'start' => round($time, 3), 'end' => round($time + $length, 3)];
            $time += $length + (mt_rand(0, 5) === 0 ? 0.4 : 0.0);
        }
        $text = implode(' ', array_column($words, 'text'));
        if (mt_rand(0, 9) === 0) {
            $text = '  '.$text."\t baru ";
        }
        $line = ['start' => round($start, 3), 'end' => round($time, 3), 'text' => $text];
        if (mt_rand(0, 6) > 0) {
            $line['words'] = $words;
        }
        $lines[] = $line;
        $time += mt_rand(0, 3) === 0 ? 0.5 : 0.1;
    }

    return $lines;
};

$hooks = [null, '', 'Benarkah arwah orang meninggal bisa gentayangan? Ini jawaban Islam!', 'Sabar {itu} indah \\ ya',
    'Jin Itu Selalu Bohong?', 'Kriteria pemimpin minimal: sayang sama rakyatnya, itu kriteria paling dasar dalam Islam menurut ulama'];
$randomHookLayout = function () use ($pick, $maybe, $colors): ?array {
    if (mt_rand(0, 3) === 0) {
        return null;
    }
    $stored = array_filter([
        'start' => $maybe($pick([0.0, 1.5, -1.0, 30.0]), 50),
        'end' => $maybe($pick([2.0, 3.5, 6.0, 100.0]), 60),
        'x' => $maybe($pick([0, 50, 87.6, 120]), 60),
        'y' => $maybe($pick([10, 20, 50]), 60),
        'model' => $maybe($pick(['dark', 'light', 'accent', 'outline', 'unknown']), 30),
        'color' => $maybe($pick([...$colors, 'merah', '#abc']), 70),
        'background' => $maybe($pick($colors), 70),
        'size' => $maybe($pick([30, 62, 80, 120]), 50),
        'radius' => $maybe($pick([0, 12, 24, 48, 60]), 40),
        'animation' => $maybe($pick(['fade', 'pop', 'none', 'spin']), 40),
        'opener' => $maybe($pick([true, false]), 70),
    ], fn ($value) => $value !== null);

    return $stored;
};

/** Lebar semua potongan kata berurutan dari $text (untuk TextMeasure palsu di tes Kotlin). */
$measures = function (string $family, int $size, string $text): array {
    $font = TextMeasure::fontFile($family);
    $words = preg_split('/\s+/u', trim($text)) ?: [];
    $widths = [];
    for ($i = 0; $i < count($words); $i++) {
        for ($j = $i; $j < count($words); $j++) {
            $piece = implode(' ', array_slice($words, $i, $j - $i + 1));
            $widths[$piece] = TextMeasure::width($font, $size, $piece);
        }
    }

    return $widths;
};

$writer = app(AssWriter::class);
$assCases = [];
for ($i = 0; $i < 500; $i++) {
    $styleAttributes = $randomStyle();
    $style = new CaptionStyle($styleAttributes);
    $lines = $randomLines(mt_rand(0, 6));
    $duration = $pick([2.0, 5.5, 12.345, 60.0]);
    $hook = $pick($hooks);
    $centerY = $maybe($pick([960, 1500, 333]), 70);
    $titleBar = $maybe($pick([
        ['text' => 'Kajian Sabar (Bagian 2)', 'background' => '#000000', 'color' => '#FFFFFF', 'height' => 300],
        ['text' => 'Judul {kurung}', 'background' => null, 'color' => '#FFD400', 'height' => 210],
        ['text' => '   ', 'background' => '#123456', 'color' => '#FFFFFF', 'height' => 168],
    ]), 65);
    $aspect = $pick(array_values(FrameStyle::ASPECTS));
    $canvas = [$aspect['width'], $aspect['height'], $aspect['scale']];
    $hookTop = $maybe($pick([200, 380, 1113]), 60);
    $hookSize = $pick([62, 62, 92]);
    $keywords = $pick([[], ['sabar'], ['yang sabar', 'Allah', 'shalat dan'], ['é', 'qur’an']]);
    $hookLayout = $randomHookLayout();
    $resolvedLayout = $hookLayout === null ? null : HookLayout::resolve($hookLayout, $duration, $pick([0.0, 0.0, 2.0]));

    $case = [
        'style' => $styleAttributes,
        'lines' => $lines,
        'duration' => $duration,
        'hook' => $hook,
        'centerY' => $centerY,
        'titleBar' => $titleBar,
        'canvas' => $canvas,
        'hookTop' => $hookTop,
        'hookSize' => $hookSize,
        'keywords' => $keywords,
        'hookLayout' => $resolvedLayout,
        'ass' => $writer->build($lines, $duration, $hook, $style, $centerY, $titleBar, $canvas, $hookTop, $hookSize, $keywords, $resolvedLayout),
    ];

    // Kotak hook bersudut membulat memakai TextMeasure: lebar tiap potongan teks hook dicatat untuk tes.
    if ($hook !== null && trim($hook) !== '' && $resolvedLayout !== null && $resolvedLayout['alpha'] !== null && $resolvedLayout['radius'] > 0) {
        $size = (int) round($resolvedLayout['size'] * $canvas[2]);
        $case['measure'] = ['family' => $style->font_family, 'size' => $size, 'widths' => (object) $measures($style->font_family, $size, $hook)];
    }

    $assCases[] = $case;
}

$times = [];
foreach ([0.0, 0.004, 0.005, 0.015, 1.995, 59.999, 61.235, 3599.995, 3600.0, 7322.456, 1.005, 2.675] as $seconds) {
    $times[] = ['seconds' => $seconds, 'ass' => AssWriter::time($seconds)];
}

$write('ass', ['cases' => $assCases, 'times' => $times]);

// ---------------------------------------------------------------------------------------------------------------
// HookLayout, Keywords, ClipCuts
// ---------------------------------------------------------------------------------------------------------------

$layouts = [];
for ($i = 0; $i < 400; $i++) {
    $stored = $randomHookLayout();
    $length = $pick([0.2, 3.0, 12.5, 61.0]);
    $lead = $pick([0.0, 0.0, 1.0, 2.0, 4.5, -1.0]);
    $layouts[] = ['stored' => $stored, 'length' => $length, 'lead' => $lead, 'result' => HookLayout::resolve($stored, $length, $lead)];
}
$merges = [];
foreach ([
    [null, ['model' => 'light']],
    [['model' => 'dark', 'color' => '#FF0000', 'background' => '#00FF00', 'size' => 70], ['model' => 'accent']],
    [['model' => 'dark', 'color' => '#FF0000'], ['model' => 'dark', 'x' => 40, 'y' => 30, 'foo' => 1]],
    [['start' => 1.0, 'end' => 3.0], ['start' => null, 'radius' => 12]],
    [['opener' => true], ['opener' => false, 'animation' => 'pop']],
] as [$stored, $changes]) {
    $merges[] = ['stored' => $stored, 'changes' => $changes, 'result' => HookLayout::merge($stored, $changes)];
}
$write('hooklayout', ['resolve' => $layouts, 'merge' => $merges]);

$marks = [];
foreach ([
    [['Orang', 'yang', 'sabar,', 'itu', 'dicintai', 'Allah.'], ['sabar', 'yang sabar', 'Allah']],
    [['Yang', 'Sabar', 'yang', 'sabar'], ['yang sabar']],
    [['Qur’an', 'é', 'ẞig'], ['quran', 'é', 'ßig']],
    [['shalat', 'dan', 'syukur!'], ['shalat dan syukur', 'dan']],
    [[], ['sabar']],
    [['sabar'], []],
] as [$texts, $keywords]) {
    $marks[] = ['texts' => $texts, 'keywords' => $keywords, 'result' => (object) Keywords::mark($texts, $keywords)];
}
$toggles = [];
foreach ([
    [['sabar'], 'Sabar,'],
    [['yang sabar'], 'sabar'],
    [['sabar'], 'ikhlas'],
    [['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'], 'baru'],
    [[], 'satu dua tiga empat'],
    [['satu'], '...'],
] as [$keywords, $phrase]) {
    $toggles[] = ['keywords' => $keywords, 'phrase' => $phrase, 'result' => Keywords::toggle($keywords, $phrase)];
}
$write('keywords', ['mark' => $marks, 'toggle' => $toggles]);

$cutCases = [];
for ($i = 0; $i < 300; $i++) {
    $start = $pick([0.0, 10.0, 125.5]);
    $end = $start + $pick([5.0, 30.0, 61.25]);
    $raw = [];
    for ($k = 0, $n = mt_rand(0, 6); $k < $n; $k++) {
        $a = $start - 2 + mt_rand(0, (int) (($end - $start + 4) * 1000)) / 1000;
        $raw[] = mt_rand(0, 4) === 0 ? [$a, $a + 0.01] : ($pick([true, false]) ? [$a, $a + mt_rand(10, 3000) / 1000] : [$a + mt_rand(10, 3000) / 1000, $a]);
    }
    if (mt_rand(0, 9) === 0) {
        $raw[] = ['x', 2];
        $raw[] = [3];
    }
    $cuts = ClipCuts::normalize($raw, $start, $end);
    $kept = ClipCuts::kept($cuts, $start, $end);
    $from = $start + mt_rand(0, (int) (($end - $start) * 1000)) / 1000;
    $to = $from + mt_rand(0, 4000) / 1000;
    $probe = $start + mt_rand(0, (int) (($end - $start) * 1000)) / 1000;
    $cutCases[] = [
        'raw' => $raw, 'start' => $start, 'end' => $end,
        'normalized' => $cuts,
        'kept' => $kept,
        'removed' => ClipCuts::removed($cuts),
        'from' => $from, 'to' => $to,
        'added' => ClipCuts::add($cuts, $from, $to, $start, $end),
        'subtracted' => ClipCuts::subtract($cuts, $from, $to, $start, $end),
        'probe' => $probe,
        'mapped' => ClipCuts::map($cuts, $start, $probe),
        'covers' => ClipCuts::covers($cuts, $probe),
        'select' => $kept === [] ? '' : ClipCuts::selectExpression($kept, $start),
    ];
}
$write('cuts', ['cases' => $cutCases]);

// ---------------------------------------------------------------------------------------------------------------
// SubtitleBuilder + ClipRenderer (relativeLines, previewTracks) dengan project dan klip sungguhan di SQLite memori
// ---------------------------------------------------------------------------------------------------------------

$speech = ['Bismillah,', 'jamaah', 'sekalian.', 'Hari', 'ini', 'kita', 'bahas', 'sabar', 'dan', 'syukur.', 'Rasulullah', 'bersabda:', '"Orang',
    'yang', 'sabar', 'itu', 'dicintai', 'Allah."', 'Benar?', 'Ya…', 'insyaAllah', 'kita', 'mampu!', 'Qur’an', 'surat', 'Al-Baqarah', 'ayat', '153.'];
$segments = [];
$time = 0.0;
for ($i = 1; $i <= 80; $i++) {
    $count = mt_rand(3, 14);
    $words = [];
    for ($k = 0; $k < $count; $k++) {
        $words[] = $pick($speech);
    }
    $text = implode(' ', $words);
    $start = round($time, 3);
    $end = round($time + mt_rand(1500, 7000) / 1000, 3);
    // Kata dengan jeda acak di antaranya (memicu baris baru setelah jeda >= 0,6 detik).
    $timed = WordTimer::distribute($text, $start, $end);
    foreach ($timed as $w => $word) {
        if ($w > 0 && mt_rand(0, 9) === 0) {
            $shift = 0.7;
            for ($x = $w; $x < count($timed); $x++) {
                $timed[$x]['start'] = round($timed[$x]['start'] + $shift, 3);
                $timed[$x]['end'] = round($timed[$x]['end'] + $shift, 3);
            }
            $end = round($end + $shift, 3);
        }
    }
    $segments[] = ['sequence' => $i, 'start_time' => $start, 'end_time' => $end, 'text' => $text, 'words' => $i % 11 === 0 ? null : $timed, 'words_estimated' => false];
    $time = $end + (mt_rand(0, 3) === 0 ? 0.9 : 0.1);
}

$project = Project::create(['title' => 'Uji render', 'source_type' => 'youtube', 'duration_seconds' => $time, 'video_status' => 'ready', 'transcript_status' => 'ready']);
$project->segments()->createMany($segments);

$builder = app(SubtitleBuilder::class);
$renderer = app(ClipRenderer::class);
$relative = new ReflectionMethod(ClipRenderer::class, 'relativeLines');
$styleRows = [];
foreach (range(1, 6) as $n) {
    $attributes = [...$randomStyle(), 'name' => "Gaya $n"];
    $styleRows[] = CaptionStyle::create(array_filter($attributes, fn ($v) => $v !== null))->refresh();
}
$frameRows = [];
foreach (range(1, 6) as $n) {
    $attributes = [...$randomFrame(), 'name' => "Bingkai $n"];
    unset($attributes['template_area']);
    $attributes['mode'] = $pick(['crop', 'fit', 'top']);
    $frameRows[] = FrameStyle::create(array_filter($attributes, fn ($v) => $v !== null))->refresh();
}

$clipCases = [];
for ($i = 0; $i < 70; $i++) {
    $first = mt_rand(1, 70);
    $startTime = round($segments[$first - 1]['start_time'] + $pick([0.0, 0.35, -0.2]), 3);
    $endTime = round(min($time, $startTime + mt_rand(8000, 70000) / 1000), 3);
    $cuts = null;
    if (mt_rand(0, 2) === 0) {
        $range = fn () => [round($startTime + mt_rand(0, (int) (($endTime - $startTime) * 1000)) / 1000, 3), 0];
        $cuts = [];
        foreach (['words', 'pauses', 'hidden'] as $kind) {
            if (mt_rand(0, 1)) {
                $cuts[$kind] = array_map(function () use ($range) {
                    [$a] = $range();

                    return [$a, round($a + mt_rand(100, 1500) / 1000, 3)];
                }, range(1, mt_rand(1, 3)));
            }
        }
    }
    $clip = Clip::create([
        'project_id' => $project->id,
        'title' => 'Klip '.$i,
        'part_number' => $maybe(2, 80),
        'start_time' => $startTime,
        'end_time' => $endTime,
        'hook' => $maybe($pick($hooks), 30),
        'keywords' => $maybe($pick([['sabar'], ['jamaah sekalian', 'Allah'], ['qur’an']]), 40),
        'caption_style_id' => $maybe($pick($styleRows)->id, 30),
        'frame_style_id' => $maybe($pick($frameRows)->id, 30),
        'crop_zoom' => $maybe($pick([100, 140, 300]), 70),
        'crop_y' => $maybe($pick([0, 40, 100]), 70),
        'crop_position' => $maybe($pick([0, 50, 80]), 60),
        'cuts' => $cuts,
        'show_hook_overlay' => mt_rand(0, 5) > 0,
        'hook_layout' => $maybe($randomHookLayout(), 50),
    ]);
    $clip->refresh();
    $original = $builder->linesFor($clip);

    // Sebagian klip disunting subtitlenya, lalu rentangnya digeser (baris di luar rentang suntingan diisi transkrip).
    $edits = null;
    if (mt_rand(0, 2) === 0 && $original !== []) {
        $edits = [];
        foreach ((array) array_rand($original, min(count($original), mt_rand(1, 3))) as $index) {
            $words = explode(' ', $original[$index]['text']);
            $edits[$index] = $pick([
                '',
                implode(' ', array_map('mb_strtoupper', $words)),
                $original[$index]['text'].' tambahan kata',
                "  ganti\tsemua   ",
            ]);
        }
        $builder->applyEdits($clip, $edits);
        $clip->refresh();
        if (mt_rand(0, 1)) {
            $clip->update(['start_time' => max(0, round($clip->start_time - 3.25, 3)), 'end_time' => round(min($time, $clip->end_time + 4.5), 3)]);
            $clip->refresh();
        }
    }

    $composition = Composition::forClip($clip);
    $clipCases[] = [
        'clip' => [
            'start_time' => $clip->start_time,
            'end_time' => $clip->end_time,
            'title' => $clip->title,
            'part_number' => $clip->part_number,
            'hook' => $clip->hook,
            'keywords' => $clip->keywords,
            'cuts' => $clip->cuts,
            'show_hook_overlay' => $clip->show_hook_overlay,
            'hook_layout' => $clip->hook_layout,
            'subtitle_lines' => $clip->subtitle_lines,
            'subtitle_edited' => $clip->subtitle_edited,
            'crop_zoom' => $clip->crop_zoom,
            'crop_y' => $clip->crop_y,
            'crop_position' => $clip->crop_position,
            'project_crop_position' => $project->crop_position,
            'style' => $clip->effectiveStyle()->only(array_keys($randomStyle())),
            'frame' => $clip->effectiveFrameStyle()->only([...array_keys($randomFrame()), 'template_area']),
        ],
        'edits' => $edits === null ? null : (object) $edits,
        'original' => $original,
        'lines' => $builder->linesFor($clip),
        'relative' => $relative->invoke($renderer, $clip),
        'cutRanges' => $clip->cutRanges(),
        'wordsOffSubtitle' => $clip->wordsOffSubtitle(),
        'outputDuration' => $clip->outputDuration(),
        'hookLayout' => $clip->hookLayout(),
        'composition' => $composition->toArray(),
        'preview' => $renderer->previewTracks($clip, $composition),
    ];

    // Hook pratinjau dengan kotak bersudut membulat: lebar teks dicatat untuk TextMeasure palsu di tes.
    $layout = $clip->hookLayout();
    if ($clip->shownHook() !== null && $layout['alpha'] !== null && $layout['radius'] > 0) {
        $size = (int) round($layout['size'] * $composition->canvas['scale']);
        $family = $clip->effectiveStyle()->font_family;
        $clipCases[array_key_last($clipCases)]['measure'] = ['family' => $family, 'size' => $size, 'widths' => (object) $measures($family, $size, $clip->shownHook())];
    }
}

$editLines = [];
foreach ([
    ['Orang yang sabar', 'orang yang Sabar,'],
    ['Orang yang sabar', 'orang sabar'],
    ['Orang yang sabar', 'orang yang sabar itu dicintai'],
] as [$text, $replacement]) {
    $line = ['start' => 1.0, 'end' => 2.5, 'text' => $text, 'words' => WordTimer::distribute($text, 1.0, 2.5)];
    $editLines[] = ['line' => $line, 'text' => $replacement, 'result' => $builder->editLine($line, $replacement)];
}

$write('subtitles', [
    'segments' => $segments,
    'clips' => $clipCases,
    'editLine' => $editLines,
]);

// ---------------------------------------------------------------------------------------------------------------
// Sambungan intro/outro dan kartu pembuka
// ---------------------------------------------------------------------------------------------------------------

$join = new ReflectionMethod(ClipRenderer::class, 'joinBumpers');
$opener = new ReflectionMethod(ClipRenderer::class, 'opener');
$audio = new ReflectionMethod(ClipRenderer::class, 'audioFilter');
$joins = [];
foreach ([
    [['intro' => [1, true, 4.5]], null, true, 30.0],
    [['outro' => [2, false, 3.0]], 'between(t,0.000,4.250)', true, 25.75],
    [['intro' => [1, false, 2.0, '[1:v]null', 0.3], 'outro' => [2, true, 5.0]], null, false, 12.0],
    [['intro' => [3, true, 6.25], 'outro' => [4, true, 2.5]], 'between(t,0.000,1.000)+between(t,2.000,9.000)', true, 8.0],
] as [$parts, $keep, $mainAudio, $length]) {
    $frameAttributes = $randomFrame();
    $frame = new FrameStyle($frameAttributes);
    $joins[] = [
        'frame' => $frameAttributes,
        'graph' => '[0:v]null[joined]',
        'parts' => $parts,
        'fps' => '29.970',
        'keep' => $keep,
        'mainAudio' => $mainAudio,
        'length' => $length,
        'result' => $join->invoke(null, '[0:v]null[joined]', $frame, $parts, '29.970', $keep, $mainAudio, $length),
    ];
}
$openers = [];
foreach ([[null, 50], [0.37, 50], [null, 0], [0.9, 100]] as [$center, $cropPosition]) {
    $frameAttributes = $randomFrame();
    $frame = new FrameStyle($frameAttributes);
    $openers[] = [
        'frame' => $frameAttributes,
        'input' => 3,
        'center' => $center,
        'cropPosition' => $cropPosition,
        'fps' => '25.000',
        'seconds' => 2.3,
        'result' => $opener->invoke(null, 3, $frame, $center, $cropPosition, '25.000', 2.3),
    ];
}
$audios = [];
foreach ([null, 'between(t,0.000,4.250)'] as $keep) {
    $audios[] = ['keep' => $keep, 'result' => $audio->invoke(null, $keep)];
}
$write('renderer', ['join' => $joins, 'opener' => $openers, 'audio' => $audios, 'audioFilter' => ClipRenderer::AUDIO_FILTER]);
