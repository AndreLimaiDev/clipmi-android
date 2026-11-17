<?php

/**
 * Mengekspor hasil parser + perapian transkrip + kamus koreksi ClipMI desktop sebagai fixture tes padanan Kotlin
 * (modul core). Jalankan dengan PHP yang sama dengan desktop terpasang (NativePHP, PHP 8.3), karena round() dan
 * mbstring harus sama persis:
 *
 *   "C:/Users/<user>/AppData/Local/Programs/clipmi/resources/build/php/php.exe" tools/export-transcript-fixtures.php
 *
 * Input: core/src/test/resources/fixtures/transcript/input (dan input/real, tidak di-commit).
 * Output: .../expected/<nama>.json berisi cues, segments, dan hasil kamus (corrections.json).
 * Juga round.json: 200.000 angka acak dan round(x, 3) dari PHP desktop.
 * Repo desktop hanya dibaca.
 */

$desktop = $argv[1] ?? 'D:/laragon/www/clip-mi';
$root = __DIR__.'/../core/src/test/resources/fixtures/transcript';

require $desktop.'/vendor/autoload.php';

use App\Transcript\CorrectionApplier;
use App\Transcript\Cue;
use App\Transcript\TranscriptParseException;
use App\Transcript\TranscriptParser;
use App\Transcript\TranscriptTidier;

$corrections = new CorrectionApplier(array_column(json_decode(file_get_contents("$root/corrections.json"), true), 1, 0));
$parser = new TranscriptParser;
$tidier = new TranscriptTidier;

@mkdir("$root/expected/real", 0777, true);
$files = array_merge(glob("$root/input/*.*"), glob("$root/input/real/*.*"));

foreach ($files as $file) {
    $relative = substr($file, strlen("$root/input/"));
    $out = ['php' => PHP_VERSION];

    try {
        $cues = $parser->parse(file_get_contents($file));
        $out['cues'] = array_map(fn (Cue $c) => [
            'start' => $c->start, 'end' => $c->end, 'text' => $c->text, 'words' => $c->words, 'wordsEstimated' => $c->wordsEstimated,
        ], $cues);
        $segments = $tidier->tidy($cues);
        $out['segments'] = $segments;

        $corrected = [];
        $count = 0;
        foreach ($segments as $segment) {
            [$segment, $n] = $corrections->applyToSegment($segment);
            $corrected[] = $segment;
            $count += $n;
        }
        $out['corrected'] = ['count' => $count, 'segments' => $corrected];
    } catch (TranscriptParseException $e) {
        $out['error'] = $e->getMessage();
    }

    $target = "$root/expected/".preg_replace('/\.[^.]+$/', '.json', $relative);
    file_put_contents($target, json_encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION)."\n");
    echo $relative.': '.(isset($out['error']) ? 'error' : count($out['segments']).' segmen, '.$out['corrected']['count'].' koreksi')."\n";
}

// round(x, 3) PHP 8.3 memakai "pre-rounding"; Kotlin harus menirunya. Angka dibuat dari pola waktu nyata
// (ms/1000, hasil bagi, penjumlahan) plus acak lebar.
mt_srand(20261117);
$values = [];
for ($i = 0; $i < 200000; $i++) {
    $values[] = match ($i % 5) {
        0 => mt_rand(0, 36000000) / 1000 + mt_rand(0, 999) / 1e7,
        1 => mt_rand(0, 7200000) / 1000 * mt_rand(1, 50) / mt_rand(1, 50),
        2 => mt_rand(0, 100000) / 1000 + 0.0005,
        3 => mt_rand() / mt_getrandmax() * 10 ** mt_rand(-6, 9),
        default => mt_rand(0, 3600) + mt_rand(0, 9999) / 10000 + 0.00049999999,
    };
}
file_put_contents("$root/round.json", json_encode(['in' => $values, 'out' => array_map(fn ($v) => round($v, 3), $values)], JSON_PRESERVE_ZERO_FRACTION));
echo "round.json: ".count($values)." angka\n";

// Transcriber::toSrt (hasil transkripsi API → SRT): cue dengan detik pecahan, akhir < awal + 0,1, dan lewat 1 jam.
$cues = [[0.0, 2.54, 'akan aku perbuat, kalau itu berkaitan dengan Islam.'], [2.8, 2.81, 'Akhir terlalu dekat.'],
    [1199.9996, 1205.0004, 'Pembulatan milidetik.'], [3725.5, 3724.0, 'Akhir sebelum awal, lewat satu jam.'], [12.3456789, 15.0, 'Angka panjang.']];
file_put_contents("$root/srt-writer.json", json_encode(['cues' => $cues, 'srt' => App\Ai\Transcriber::toSrt($cues)], JSON_UNESCAPED_UNICODE | JSON_PRESERVE_ZERO_FRACTION)."\n");
echo "srt-writer.json\n";
