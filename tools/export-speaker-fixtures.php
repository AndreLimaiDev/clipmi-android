<?php

/**
 * Mengekspor fixture jalur "ikuti penceramah" dari kode desktop (`SpeakerTrack::build/positionAt/expression` dan
 * `ClipRenderer::filter` dengan keyframe) untuk padanan Kotlin di core (`id.clipmi.core.render.SpeakerTrack`).
 * Repo desktop tidak tersentuh.
 *
 *   "C:/Users/<user>/AppData/Local/Programs/clipmi/resources/build/php/php.exe" tools/export-speaker-fixtures.php
 */

$desktop = $argv[1] ?? 'D:/laragon/www/clip-mi';
$out = __DIR__.'/../core/fixtures/render';

require $desktop.'/vendor/autoload.php';
$app = require $desktop.'/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();

use App\Models\FrameStyle;
use App\Render\ClipRenderer;
use App\Render\Composition;
use App\Render\SpeakerTrack;

mt_srand(20261118);
$pick = fn (array $items) => $items[mt_rand(0, count($items) - 1)];

$builds = [];
for ($i = 0; $i < 600; $i++) {
    $duration = round(mt_rand(5000, 180000) / 1000, 3);
    $interval = $pick([SpeakerTrack::interval($duration), SpeakerTrack::interval($duration), 0.333, 0.5, 1.0]);
    $count = max(1, (int) ceil($duration / $interval - 0.001));
    // Penceramah diam di satu tempat, berpindah sesekali, atau dua orang bergantian; sebagian tidak terdeteksi.
    $base = mt_rand(20, 80) / 100;
    $other = mt_rand(10, 90) / 100;
    $noise = $pick([0.0, 0.01, 0.03, 0.08]);
    $missing = $pick([0, 0, 5, 30, 100]);
    $switch = $pick([0, 0, 8, 20]);
    $samples = [];
    $current = $base;
    for ($k = 0; $k < $count; $k++) {
        if ($switch > 0 && mt_rand(0, 100) < $switch) {
            $current = $current === $base ? $other : $base;
        }
        $samples[] = mt_rand(0, 99) < $missing ? null : round(max(-0.1, min(1.1, $current + (mt_rand(-1000, 1000) / 1000) * $noise)), 3);
    }
    $cuts = [];
    for ($c = 0, $n = $pick([0, 0, 1, 3, 6]); $c < $n; $c++) {
        $cuts[] = round(mt_rand(0, (int) ($duration * 1000)) / 1000, 3);
    }
    $keyframes = SpeakerTrack::build($samples, $interval, $duration, $cuts);
    $times = [];
    if ($keyframes !== []) {
        foreach ([-1.0, 0.0, $duration / 3, $duration / 2, $duration, $duration + 2, ...array_column($keyframes, 0)] as $t) {
            $times[] = [$t, SpeakerTrack::positionAt($keyframes, $t)];
        }
    }
    $builds[] = [
        'samples' => $samples, 'interval' => $interval, 'duration' => $duration, 'cuts' => $cuts,
        'keyframes' => $keyframes, 'positions' => $times,
        'expression' => $keyframes !== [] ? SpeakerTrack::expression($keyframes) : null,
    ];
}

$filters = [];
foreach ($builds as $i => $build) {
    if ($build['keyframes'] === [] || $i % 5 !== 0) {
        continue;
    }
    $attributes = $pick([
        ['name' => 'a', 'mode' => 'crop'],
        ['name' => 'b', 'mode' => 'crop', 'aspect' => '4:5', 'zoom' => 150],
        ['name' => 'c', 'mode' => 'top', 'video_height' => 60, 'video_position' => 'top', 'background' => 'blur'],
        ['name' => 'd', 'mode' => 'fit', 'background' => 'color', 'background_color' => '#000000'],
        ['name' => 'e', 'mode' => 'template', 'template_area' => ['x' => 0.05, 'y' => 0.15, 'width' => 0.9, 'height' => 0.6]],
    ]);
    $frame = new FrameStyle($attributes);
    $source = $pick([null, [1920, 1080], [1280, 720]]);
    $composition = Composition::make($frame, $source, 50, null, $pick([50, 30]), $build['keyframes']);
    $inputs = $frame->usesTemplate() ? ['template' => 1] : [];
    $filters[] = [
        'frame' => $attributes, 'source' => $source, 'cropY' => $composition->video['cropY'], 'keyframes' => $build['keyframes'],
        'inputs' => (object) $inputs, 'filter' => ClipRenderer::filter($composition, $inputs, null),
    ];
}

file_put_contents("$out/speaker.json", json_encode(['builds' => $builds, 'filters' => $filters], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION)."\n");
echo 'speaker builds '.count($builds).', with keyframes '.count(array_filter($builds, fn ($b) => $b['keyframes'] !== [])).', filters '.count($filters)."\n";
