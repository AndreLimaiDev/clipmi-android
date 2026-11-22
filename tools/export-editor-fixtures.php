<?php

/**
 * Mengekspor urutan operasi editor klip desktop (`App\Analysis\ClipEditor` + `ClipSilence::parse`) sebagai fixture
 * padanan Kotlin (core, `id.clipmi.core.render.ClipEditor`). Kode desktop dijalankan apa adanya dengan SQLite di
 * memori dan folder kerja sementara; repo desktop tidak tersentuh.
 *
 *   "C:/Users/<user>/AppData/Local/Programs/clipmi/resources/build/php/php.exe" tools/export-editor-fixtures.php
 */

$desktop = $argv[1] ?? 'D:/laragon/www/clip-mi';
$out = __DIR__.'/../core/fixtures/render';
$workspace = sys_get_temp_dir().DIRECTORY_SEPARATOR.'clipmi-editor-fixtures';

require $desktop.'/vendor/autoload.php';
$app = require $desktop.'/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();

config([
    'database.default' => 'sqlite', 'database.connections.sqlite.database' => ':memory:', 'queue.default' => 'sync',
    'filesystems.disks.workspace.root' => $workspace,
]);
Illuminate\Support\Facades\DB::purge('sqlite');
Illuminate\Support\Facades\Artisan::call('migrate', ['--force' => true]);
Illuminate\Support\Facades\Storage::forgetDisk('workspace');

use App\Analysis\ClipEditor;
use App\Analysis\ClipSilence;
use App\Models\CaptionStyle;
use App\Models\Clip;
use App\Models\Project;
use App\Transcript\WordTimer;
use Illuminate\Support\Facades\Storage;

mt_srand(20261118);
$pick = fn (array $items) => $items[mt_rand(0, count($items) - 1)];

// Transkrip sintetis dengan jeda di antara sebagian kata.
$speech = ['Bismillah,', 'jamaah', 'sekalian.', 'Hari', 'ini', 'kita', 'bahas', 'sabar', 'dan', 'syukur.', 'Rasulullah', 'bersabda:',
    '"Orang', 'yang', 'sabar', 'itu', 'dicintai', 'Allah."', 'Benar?', 'Ya…', 'insyaAllah', 'eee', 'mampu!', 'Qur’an'];
$segments = [];
$time = 0.0;
for ($i = 1; $i <= 60; $i++) {
    $words = [];
    for ($k = 0, $n = mt_rand(3, 12); $k < $n; $k++) {
        $words[] = $pick($speech);
    }
    $text = implode(' ', $words);
    $start = round($time, 3);
    $end = round($time + mt_rand(1500, 6000) / 1000, 3);
    $segments[] = ['sequence' => $i, 'start_time' => $start, 'end_time' => $end, 'text' => $text,
        'words' => $i % 13 === 0 ? null : WordTimer::distribute($text, $start, $end), 'words_estimated' => false];
    $time = $end + (mt_rand(0, 3) === 0 ? 1.1 : 0.15);
}
$duration = round($time, 3);
$project = Project::create(['title' => 'Uji editor', 'source_type' => 'local', 'video_path' => 'v.mp4', 'duration_seconds' => mt_rand(0, 1) ? $duration : null,
    'video_status' => 'ready', 'transcript_status' => 'ready']);
$project->segments()->createMany($segments);
$style = CaptionStyle::query()->where('is_default', true)->first();

$editor = app(ClipEditor::class);
$state = fn (Clip $clip) => [
    'start_time' => $clip->start_time,
    'end_time' => $clip->end_time,
    'cuts' => $clip->cuts,
    'subtitle_lines' => $clip->subtitle_lines,
    'subtitle_edited' => (bool) $clip->subtitle_edited,
];

$sequences = [];
for ($s = 0; $s < 40; $s++) {
    $first = mt_rand(0, 45);
    $start = round($segments[$first]['start_time'] + $pick([0.0, 0.2, -0.35]), 3);
    $clip = Clip::create(['project_id' => $project->id, 'title' => "Klip $s", 'start_time' => max(0, $start),
        'end_time' => round(min($duration, $start + mt_rand(6000, 40000) / 1000), 3), 'caption_style_id' => $style?->id]);
    $clip->refresh();

    // Jeda "terdeteksi" (seperti silencedetect) disimpan di cache ClipSilence agar pauses() bisa dibaca.
    $silences = [];
    $cursor = $clip->start_time - 0.2;
    while ($cursor < $clip->end_time) {
        $cursor += mt_rand(800, 5000) / 1000;
        $length = mt_rand(250, 2200) / 1000;
        $silences[] = [round(max($clip->start_time, $cursor), 3), round(min($clip->end_time, $cursor + $length), 3)];
        $cursor += $length;
    }
    $writeSilence = function () use ($clip, &$silences) {
        $key = md5(json_encode([$clip->start_time, $clip->end_time, $clip->project->playablePath()]));
        Storage::disk('workspace')->put(ClipSilence::path($clip), json_encode(['key' => $key, 'pauses' => $silences]));
    };
    $writeSilence();

    $initial = $state($clip);
    $steps = [];
    for ($k = 0; $k < 12; $k++) {
        $clip->refresh();
        $words = $editor->words($clip);
        $pauses = $editor->pauses($clip) ?? [];
        $range = function () use ($words, $pick) {
            if ($words === []) {
                return [0.0, 0.0];
            }
            $a = mt_rand(0, count($words) - 1);
            $b = min(count($words) - 1, $a + mt_rand(0, 2));

            return [$words[$a]['start'], $words[$b]['end']];
        };
        $op = $pick(['nudge', 'snap', 'extend', 'cut', 'cut', 'restore', 'hide', 'show', 'edit', 'edit', 'pause', 'clean', 'range']);
        $args = [];
        $result = null;
        switch ($op) {
            case 'nudge':
                $args = [$pick(['start', 'end']), $pick(ClipEditor::STEPS)];
                $editor->nudge($clip, ...$args);
                break;
            case 'snap':
                $editor->snapToWords($clip);
                break;
            case 'extend':
                $args = [$pick(['before', 'after'])];
                $editor->extend($clip, ...$args);
                break;
            case 'cut':
            case 'restore':
            case 'hide':
            case 'show':
                $args = $range();
                $result = match ($op) {
                    'cut' => $editor->cut($clip, ...$args),
                    'restore' => $editor->restore($clip, ...$args),
                    'hide' => $editor->hide($clip, ...$args),
                    'show' => $editor->show($clip, ...$args),
                };
                break;
            case 'edit':
                $args = [...$range(), $pick(['Sabar,', 'sabar itu indah', 'ALLAH', '  dua   kata ', '', 'satu dua tiga empat lima'])];
                $result = $editor->editWords($clip, ...$args);
                break;
            case 'pause':
                if ($pauses === []) {
                    $op = 'snap';
                    $editor->snapToWords($clip);
                    break;
                }
                $args = [mt_rand(0, count($pauses) - 1)];
                $result = $editor->togglePause($clip, $pauses[$args[0]]);
                break;
            case 'clean':
                $args = [$pick([0.3, 0.6, 1.0])];
                $result = $editor->cleanPauses($clip, ...$args);
                break;
            case 'range':
                $args = [round($clip->start_time + $pick([-2.5, 0.0, 1.25]), 3), round($clip->end_time + $pick([-3.0, 0.0, 2.0, 500.0]), 3)];
                $editor->setRange($clip, ...$args);
                break;
        }
        $clip->refresh();
        // Rentang berubah: jeda dibaca ulang untuk rentang baru (cache lama tidak berlaku lagi).
        $writeSilence();
        $steps[] = [
            'op' => $op,
            'args' => $args,
            'result' => $result,
            'state' => $state($clip),
            'words' => $editor->words($clip),
            'pauses' => $editor->pauses($clip),
        ];
    }
    $sequences[] = ['silences' => $silences, 'initial' => $initial, 'steps' => $steps];
}

$logs = [];
foreach ([
    ["[silencedetect @ 0x1] silence_start: 1.25\n[silencedetect @ 0x1] silence_end: 2.5 | silence_duration: 1.25\nsilence_start: 7.9", 10.0, 18.0],
    ["silence_start: -0.02\nsilence_end: 0.2\nsilence_start: 3\nsilence_end: 3.35\nsilence_start: 5.1\nsilence_end: 9.99", 100.0, 108.5],
    ['', 0.0, 5.0],
] as [$log, $start, $end]) {
    $logs[] = ['log' => $log, 'start' => $start, 'end' => $end, 'result' => ClipSilence::parse($log, $start, $end)];
}

file_put_contents("$out/editor.json", json_encode([
    'php' => PHP_VERSION,
    'segments' => $segments,
    'projectDuration' => $project->duration_seconds,
    'style' => $style?->only(['font_family', 'font_size', 'words_per_line']),
    'sequences' => $sequences,
    'silenceLogs' => $logs,
], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION));
echo "editor.json\n";
