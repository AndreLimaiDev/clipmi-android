<?php

/**
 * Mengekspor fixture Analisis Performa dari kode desktop (`PerformanceDigest::build`, `PerformancePrompt::build/parse`)
 * untuk padanan Kotlin di core (`id.clipmi.core.schedule.PerformanceDigest/PerformancePrompt`). Kode desktop
 * dijalankan apa adanya dengan SQLite di memori; repo desktop tidak tersentuh.
 *
 *   "C:/Users/<user>/AppData/Local/Programs/clipmi/resources/build/php/php.exe" tools/export-performance-fixtures.php
 */

$desktop = $argv[1] ?? 'D:/laragon/www/clip-mi';
$out = __DIR__.'/../core/src/test/resources/fixtures/schedule';
$workspace = sys_get_temp_dir().DIRECTORY_SEPARATOR.'clipmi-performance-fixtures';

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
app()->setLocale('id');

use App\Analysis\AnalysisResultException;
use App\Models\AppSetting;
use App\Models\Clip;
use App\Models\ClipSeries;
use App\Models\PostStat;
use App\Models\Project;
use App\Models\Schedule;
use App\Models\ScheduleAccount;
use App\Models\SocialAccount;
use App\Stats\PerformanceDigest;
use App\Stats\PerformancePrompt;
use Carbon\CarbonImmutable;
use Illuminate\Support\Facades\DB;

@mkdir($out, 0777, true);
mt_srand(20261118);
$pick = fn (array $items) => $items[mt_rand(0, count($items) - 1)];
AppSetting::write(Schedule::TIMEZONE_SETTING, 'UTC');

$titles = ['Sabar itu indah', 'Ikhlas | tanpa pamrih', "Syukur  dan\tnikmat", 'Jin bisa menyamar?', 'Doa sebelum tidur', 'Adab kepada orang tua'];
$hooks = [null, 'Kenapa kita mudah marah?', '  Rahasia sabar  ', 'Jangan | putus asa', ''];
$keywords = [null, ['sabar'], ['sabar', 'ikhlas', 'syukur', 'doa', 'adab', 'jin'], ['ilmu', 'amal']];
$captions = [null, 'Pendek.', str_repeat('Kajian tentang sabar dan syukur. ', 5), str_repeat('Penjelasan panjang ustadz tentang adab ', 9), 'Emoji 🤲 doa'];
$hashtagSets = [[], ['#kajian'], ['#kajian', '#sabar', '#ikhlas', '#dakwah', '#islam', '#sunnah'], array_map(fn ($i) => '#tag'.$i, range(1, 18))];

$cases = [];
for ($i = 0; $i < 60; $i++) {
    DB::table('post_stats')->delete();
    DB::table('schedule_accounts')->delete();
    DB::table('schedules')->delete();
    DB::table('clips')->delete();
    DB::table('clip_series')->delete();
    DB::table('projects')->delete();
    DB::table('social_accounts')->delete();

    $now = CarbonImmutable::parse('2026-11-01 00:00:00')->addMinutes(mt_rand(0, 60 * 24 * 30));
    CarbonImmutable::setTestNow($now);
    Carbon\Carbon::setTestNow($now);

    $accounts = [];
    for ($a = 0, $n = mt_rand(1, 3); $a < $n; $a++) {
        $accounts[] = SocialAccount::factory()->create(['name' => $pick(['Kajian IG', 'Kajian TikTok', 'YT Sunnah']).' '.$a, 'platform' => $pick(['instagram', 'tiktok', 'youtube', 'facebook', 'other'])]);
    }
    $projects = [];
    for ($p = 0, $n = mt_rand(1, 3); $p < $n; $p++) {
        $projects[] = Project::factory()->create(['title' => $pick(['Kajian Rutin | Masjid', 'Tabligh Akbar', 'Ngaji Kitab'])." $p"]);
    }
    $rows = [];
    $postCount = $i % 40 === 0 ? mt_rand(150, 170) : mt_rand(0, 20);
    $minute = 0;
    for ($k = 0; $k < $postCount; $k++) {
        $project = $pick($projects);
        $series = mt_rand(0, 3) === 0 ? ClipSeries::firstOrCreate(['project_id' => $project->id, 'title' => 'Seri '.$project->id]) : null;
        $start = mt_rand(0, 3000);
        $hook = $pick($hooks);
        $clip = Clip::factory()->for($project)->create([
            'title' => $pick($titles),
            'start_time' => $start,
            'end_time' => $start + mt_rand(15, 120) + mt_rand(0, 999) / 1000,
            'part_number' => $series ? mt_rand(1, 4) : null,
            'clip_series_id' => $series?->id,
            'hook' => $hook,
            'show_hook_overlay' => mt_rand(0, 4) > 0,
            'hook_layout' => mt_rand(0, 2) === 0 ? null : ['model' => $pick(['dark', 'light', 'accent', 'outline', 'hilang']), 'opener' => (bool) mt_rand(0, 1)],
            'keywords' => $pick($keywords),
            'caption' => $pick($captions),
            'hashtags' => $pick($hashtagSets),
        ]);
        $minute += mt_rand(30, 60 * 30);
        $published = $now->subMinutes(60 * 24 * 60)->addMinutes($minute)->addSeconds(mt_rand(0, 59));
        if ($published->greaterThan($now)) {
            $published = $now->subMinutes(mt_rand(1, 600));
        }
        $schedule = $clip->schedules()->create([
            'scheduled_at' => $published,
            'caption' => mt_rand(0, 3) === 0 ? $pick($captions) : null,
            'hashtags' => mt_rand(0, 3) === 0 ? $pick($hashtagSets) : null,
        ]);
        $account = $pick($accounts);
        $schedule->accounts()->sync([$account->id]);
        $row = ScheduleAccount::query()->where('schedule_id', $schedule->id)->sole();
        $status = mt_rand(0, 9) === 0 ? ScheduleAccount::PENDING : ScheduleAccount::PUBLISHED;
        $row->update(['status' => $status, 'published_at' => $status === ScheduleAccount::PUBLISHED ? $published : null]);
        $points = [];
        $time = $published;
        for ($s = 0, $m = mt_rand(0, 6); $s < $m; $s++) {
            $time = $time->addMinutes(mt_rand(60, 60 * 24 * 8));
            if ($time->greaterThan($now)) {
                break;
            }
            $views = mt_rand(0, 9) === 0 ? null : $pick([mt_rand(0, 900), mt_rand(0, 90000), mt_rand(0, 3000000)]);
            $stat = PostStat::create([
                'schedule_account_id' => $row->id, 'views' => $views, 'likes' => mt_rand(0, 3) ? mt_rand(0, 5000) : null,
                'comments' => mt_rand(0, 3) ? mt_rand(0, 500) : null, 'shares' => mt_rand(0, 3) ? mt_rand(0, 500) : null,
                'source' => 'ytdlp', 'recorded_at' => $time,
            ]);
            $points[] = ['at' => $time->format('Y-m-d H:i:s'), 'views' => $stat->views, 'likes' => $stat->likes, 'comments' => $stat->comments, 'shares' => $stat->shares];
        }
        $clip->refresh();
        if ($status === ScheduleAccount::PUBLISHED) {
            $rows[] = [
                'row_id' => $row->id, 'schedule_id' => $schedule->id, 'account_id' => $account->id, 'account' => $account->name,
                'platform' => $account->platformLabel(), 'published_at' => $published->format('Y-m-d H:i:s'), 'stats' => $points,
                'title' => $clip->displayTitle(), 'hook' => $clip->shownHook(), 'hook_model' => $clip->hook_layout['model'] ?? null,
                'final_duration' => $clip->finalDuration(), 'lead' => $clip->leadIn()['kind'] ?? null, 'series' => $clip->clip_series_id !== null,
                'keywords' => $clip->keywords ?? [], 'project' => (string) $clip->project?->title,
                'caption' => $schedule->effectiveCaption(), 'hashtags' => count($schedule->effectiveHashtags()),
            ];
        }
    }

    $digest = PerformanceDigest::build($now);
    $replies = [];
    $ids = array_column($digest['posts'], 'id');
    $someId = fn () => $ids !== [] && mt_rand(0, 3) ? $pick($ids) : 'P999999';
    $replies[] = 'Tidak ada JSON di sini.';
    $replies[] = '{"summary": "rusak", "strengths": [}';
    $replies[] = "```json\n".json_encode(['summary' => '', 'strengths' => []])."\n```";
    $replies[] = json_encode(['summary' => 'Ringkasan.']);
    $replies[] = "Berikut hasilnya:\n```json\n".json_encode([
        'summary' => '  Akun naik saat hook pertanyaan.  ',
        'strengths' => array_map(fn ($j) => ['point' => "Kekuatan $j", 'evidence' => [strtolower($someId()), $someId(), $someId(), 12, null]], range(1, 10)),
        'weaknesses' => [['point' => '  '], ['point' => 'Kelemahan', 'evidence' => 'P1'], 'teks', ['point' => 'Lain', 'how' => '  ', 'evidence' => [$someId()]]],
        'recommendations' => [['point' => 'Rekomendasi', 'how' => 'tidak dipakai', 'evidence' => [' '.$someId().' ']]],
        'experiments' => [['point' => 'Eksperimen', 'how' => '  Ukur 2 minggu ', 'evidence' => []]],
        'data_notes' => mt_rand(0, 1) ? 'Data sedikit.' : '   ',
    ], JSON_UNESCAPED_UNICODE)."\n```\nSemoga membantu.";
    $replies[] = '{"summary": "Dengan koma lebih", "strengths": [{"point": "A", "evidence": ["'.$someId().'",],},],}';
    $parsed = array_map(function (string $reply) use ($digest) {
        try {
            return ['reply' => $reply, 'result' => PerformancePrompt::parse($reply, $digest)];
        } catch (AnalysisResultException $e) {
            return ['reply' => $reply, 'error' => $e->getMessage()];
        }
    }, $replies);

    $cases[] = [
        'now' => $now->format('Y-m-d H:i:s'),
        'rows' => $rows,
        'digest' => $digest,
        'prompt' => count($digest['posts']) > 0 ? PerformancePrompt::build($digest) : null,
        'thin' => count(PerformanceDigest::thinAccounts($digest)),
        'parse' => $parsed,
    ];
}

CarbonImmutable::setTestNow();
Carbon\Carbon::setTestNow();

file_put_contents("$out/performance.json", json_encode($cases, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION)."\n");
echo 'performance '.count($cases).', posts '.array_sum(array_map(fn ($c) => count($c['digest']['posts']), $cases))."\n";
