<?php

/**
 * Mengekspor hasil PromptBuilder dan ClipImporter ClipMI desktop sebagai fixture padanan Kotlin (modul core).
 * Kode desktop dijalankan apa adanya dengan database SQLite di memori (migrasi desktop), jadi repo desktop dan
 * database pengguna tidak tersentuh. Jalankan dengan PHP desktop terpasang (NativePHP, PHP 8.3):
 *
 *   "C:/Users/<user>/AppData/Local/Programs/clipmi/resources/build/php/php.exe" tools/export-analysis-fixtures.php
 */

$desktop = $argv[1] ?? 'D:/laragon/www/clip-mi';
$out = __DIR__.'/../core/fixtures/analysis';

require $desktop.'/vendor/autoload.php';
$app = require $desktop.'/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();

config(['database.default' => 'sqlite', 'database.connections.sqlite.database' => ':memory:', 'queue.default' => 'sync']);
Illuminate\Support\Facades\DB::purge('sqlite');
Illuminate\Support\Facades\Artisan::call('migrate', ['--force' => true]);

use App\Analysis\AnalysisResultException;
use App\Analysis\ClipImporter;
use App\Analysis\PromptBuilder;
use App\Models\AppSetting;
use App\Models\Project;
use App\Transcript\WordTimer;

@mkdir($out, 0777, true);

// Transkrip sintetis yang deterministik: 420 segmen ±6 detik, sebagian dengan tanda baca dan huruf non-ASCII.
mt_srand(17112026);
$vocabulary = ['Rasulullah', 'bersabda,', 'orang', 'yang', 'sabar', 'itu', 'dicintai', 'Allah.', 'jamaah', 'sekalian', 'kita', 'bahas',
    'tentang', 'ikhlas', 'dan', 'syukur;', 'karena', 'hidup', 'ini', 'ujian', '"amanah"', 'Qur’an', 'é', 'insyaAllah', '—', 'kajian'];
$segments = [];
$time = 0.0;
for ($i = 1; $i <= 420; $i++) {
    $count = mt_rand(4, 18);
    $words = [];
    for ($k = 0; $k < $count; $k++) {
        $words[] = $vocabulary[mt_rand(0, count($vocabulary) - 1)];
    }
    $text = implode(' ', $words);
    $start = round($time, 3);
    $end = round($time + mt_rand(2000, 9000) / 1000, 3);
    $segments[] = [
        'sequence' => $i, 'start_time' => $start, 'end_time' => $end, 'text' => $text,
        // Sebagian segmen tanpa waktu per kata, untuk rangeTimes yang jatuh ke start_time/end_time segmen.
        'words' => $i % 7 === 0 ? null : WordTimer::distribute($text, $start + 0.1, $end - 0.05),
        'words_estimated' => $i % 3 === 0,
    ];
    $time = $end + (mt_rand(0, 4) === 0 ? 1.2 : 0.05);
}

$project = Project::create([
    'title' => 'Kajian {{durasi}} & "Sabar" — Uji', 'source_type' => 'youtube', 'duration_seconds' => $time,
    'video_status' => 'ready', 'transcript_status' => 'ready',
]);
$project->segments()->createMany($segments);
$project->refresh();

$builder = new PromptBuilder;
$prompts = ['default' => $builder->build($project)];
AppSetting::write('prompt_max_tokens', 4000);
$prompts['max4000'] = $builder->build($project->refresh());
AppSetting::write('prompt_template', "Video {{judul}} ({{durasi}}) {{judul}}\nTanpa durasi: {{tidak ada}}");
$prompts['custom_template'] = $builder->build($project->refresh());
AppSetting::write('prompt_max_tokens', 1);
$prompts['minimum_budget'] = $builder->build($project->refresh());
$untimed = Project::create(['title' => 'Tanpa durasi', 'source_type' => 'local', 'video_status' => 'ready', 'transcript_status' => 'ready']);
$untimed->segments()->createMany(array_slice($segments, 0, 5));
AppSetting::write('prompt_max_tokens', PromptBuilder::DEFAULT_MAX_TOKENS);
AppSetting::write('prompt_template', PromptBuilder::DEFAULT_TEMPLATE);
$prompts['untimed'] = $builder->build($untimed->refresh());

file_put_contents("$out/prompts.json", json_encode([
    'php' => PHP_VERSION,
    'template' => PromptBuilder::DEFAULT_TEMPLATE,
    'project' => ['title' => $project->title, 'duration_seconds' => $project->duration_seconds],
    'segments' => $segments,
    'settings' => ['default' => [null, null], 'max4000' => [null, 4000], 'custom_template' => ["Video {{judul}} ({{durasi}}) {{judul}}\nTanpa durasi: {{tidak ada}}", 4000], 'minimum_budget' => ["Video {{judul}} ({{durasi}}) {{judul}}\nTanpa durasi: {{tidak ada}}", 1]],
    'prompts' => $prompts,
], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION)."\n");
echo 'prompts.json: '.implode(', ', array_map(fn ($k, $v) => "$k=".count($v), array_keys($prompts), $prompts))."\n";

// Balasan AI: dibaca berurutan ke project yang sama (klip yang sudah ada dilewati), seperti menempel beberapa bagian.
$long = str_repeat('Judul sangat panjang ', 20);
$replies = [
    'normal' => "Berikut hasilnya:\n```json\n".json_encode(['clips' => [
        ['title' => '  Sabar itu dicintai  ', 'start_segment' => 'S0012', 'end_segment' => 'S0019', 'score' => 85.6, 'hook' => ' Hook! ', 'caption' => 'Caption', 'hashtags' => ['#kajian', 'nasihat', '#Kajian', 'dua kata', '#', 7], 'keywords' => ['sabar', ' "Allah." ', 'tiga kata saja', 'empat kata terlalu panjang', 'Sabar', '', 'a,b'], 'reason' => 'Alasan', 'series' => null, 'part' => null],
        ['title' => 'Seri satu', 'start_segment' => 's21', 'end_segment' => '0028', 'score' => '70', 'series' => ' Kisah Nabi ', 'part' => '1'],
        ['title' => 'Seri dua', 'start_segment' => 29, 'end_segment' => 'S0035', 'score' => 0, 'series' => 'Kisah Nabi', 'part' => 2.9, 'hashtags' => '#satu #dua, tiga'],
        ['title' => 'Bukan seri tapi part', 'start_segment' => 'S0040', 'end_segment' => 'S0047', 'score' => 150, 'series' => '   ', 'part' => 3],
    ]], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE)."\n```\nSemoga membantu.",
    'array_root_trailing_commas' => "[ {\"title\": \"Array\", \"start_segment\": \"S0050\", \"end_segment\": \"S0058\", \"score\": -5, \"keywords\": \"sabar, ikhlas, , syukur\",}, ]",
    'errors' => json_encode(['clips' => [
        'bukan objek',
        ['title' => '', 'start_segment' => 'S0001', 'end_segment' => 'S0002'],
        ['title' => 'ID salah', 'start_segment' => 'X12', 'end_segment' => 'S0013'],
        ['title' => 'ID tidak ada', 'start_segment' => 'S0001', 'end_segment' => 'S9999'],
        ['title' => 'Terbalik', 'start_segment' => 'S0030', 'end_segment' => 'S0020'],
        ['title' => 'Terlalu pendek', 'start_segment' => 'S0100', 'end_segment' => 'S0100'],
        ['title' => 'Terlalu panjang', 'start_segment' => 'S0100', 'end_segment' => 'S0200'],
        ['title' => $long, 'start_segment' => 'S0060', 'end_segment' => 'S0066', 'score' => '12abc'],
        ['title' => 'Duplikat', 'start_segment' => 'S0012', 'end_segment' => 'S0019'],
        ['title' => 'Segmen tanpa kata', 'start_segment' => 'S0070', 'end_segment' => 'S0077'],
        ['start_segment' => 'S0001', 'end_segment' => 'S0005'],
        ['title' => 'Tipe ID salah', 'start_segment' => 12.5, 'end_segment' => true],
    ]], JSON_UNESCAPED_UNICODE),
    'no_json' => 'Maaf, saya tidak bisa membantu.',
    'invalid_json' => '{"clips": [ {"title": "x", } ',
    'empty_clips' => '{"clips": []}',
    'clips_not_list' => '{"klip": [1]}',
    'object_before_array' => 'Catatan {"clips": [{"title": "Obj", "start_segment": "S0080", "end_segment": "S0086"}]} lalu [1,2]',
];

$results = [];
foreach ($replies as $name => $reply) {
    try {
        $r = (new ClipImporter)->import($project, $reply);
        $results[$name] = ['created' => $r->created, 'skipped' => $r->skipped, 'errors' => $r->errors];
    } catch (AnalysisResultException $e) {
        $results[$name] = ['exception' => $e->getMessage()];
    }
}

$clips = $project->clips()->with('series')->orderBy('id')->get()->map(fn ($c) => [
    'title' => $c->title, 'start_time' => $c->start_time, 'end_time' => $c->end_time, 'score' => $c->score,
    'hook' => $c->hook, 'caption' => $c->caption, 'hashtags' => $c->hashtags, 'keywords' => $c->keywords, 'reason' => $c->reason,
    'series' => $c->series?->title, 'part_number' => $c->part_number, 'display_title' => $c->displayTitle(), 'share_text' => $c->shareText(),
])->all();

file_put_contents("$out/import.json", json_encode([
    'php' => PHP_VERSION, 'replies' => $replies, 'results' => $results, 'clips' => $clips,
], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION)."\n");
echo 'import.json: '.count($clips)." klip\n";
