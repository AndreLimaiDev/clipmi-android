<?php

/**
 * Mengekspor fixture Jadwal & Statistik dari kode desktop (`SocialAccount` slot, `QueueFiller`, `ScheduleReminder`,
 * jadwal ulang, `StatsRefresher`, `ScheduleAccount::statAt`, ringkasan Statistik, `YtDlp::parseStats`,
 * `PostStatsFetcher`) untuk padanan Kotlin di core (`id.clipmi.core.schedule`). Kode desktop dijalankan apa adanya
 * dengan SQLite di memori; repo desktop tidak tersentuh.
 *
 *   "C:/Users/<user>/AppData/Local/Programs/clipmi/resources/build/php/php.exe" tools/export-schedule-fixtures.php
 */

$desktop = $argv[1] ?? 'D:/laragon/www/clip-mi';
$out = __DIR__.'/../core/fixtures/schedule';
$workspace = sys_get_temp_dir().DIRECTORY_SEPARATOR.'clipmi-schedule-fixtures';

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
Carbon\Carbon::setLocale('id');
Carbon\CarbonImmutable::setLocale('id');

use App\Enums\RenderStatus;
use App\Enums\ReviewStatus;
use App\Models\AppSetting;
use App\Models\Clip;
use App\Models\ClipSeries;
use App\Models\PostStat;
use App\Models\Project;
use App\Models\Schedule;
use App\Models\ScheduleAccount;
use App\Models\SocialAccount;
use App\Scheduling\Notifier;
use App\Scheduling\QueueFiller;
use App\Scheduling\ScheduleReminder;
use App\Stats\StatsRefresher;
use App\YouTube\YtDlp;
use Carbon\CarbonImmutable;
use Illuminate\Support\Facades\DB;

@mkdir($out, 0777, true);
@mkdir($workspace, 0777, true);
$existing = $workspace.DIRECTORY_SEPARATOR.'ada.mp4';
file_put_contents($existing, 'mp4');

mt_srand(20261118);
$pick = fn (array $items) => $items[mt_rand(0, count($items) - 1)];
$fmt = fn (?CarbonImmutable $at) => $at?->format('Y-m-d H:i:s');
// Jadwal disimpan sebagai jam dinding; dengan zona UTC, wallNow sama dengan waktu tiruan.
AppSetting::write(Schedule::TIMEZONE_SETTING, 'UTC');

$randomSlots = function () use ($pick): array {
    $slots = [];
    for ($i = 0, $n = mt_rand(0, 4); $i < $n; $i++) {
        $days = [];
        foreach (range(1, 7) as $day) {
            if (mt_rand(0, 2) > 0) {
                $days[] = $day;
            }
        }
        if ($days === []) {
            $days = [mt_rand(1, 7)];
        }
        shuffle($days);
        $slots[] = ['time' => sprintf('%02d:%02d', mt_rand(0, 23), $pick([0, 0, 0, 15, 30, 45])), 'days' => $days];
    }
    // Jam kembar supaya penggabungan ikut teruji.
    if ($slots !== [] && mt_rand(0, 3) === 0) {
        $slots[] = ['time' => $slots[0]['time'], 'days' => [mt_rand(1, 7)]];
    }

    return $slots;
};
$randomNow = fn () => CarbonImmutable::parse('2026-01-01 00:00:00')->addMinutes(mt_rand(0, 365 * 24 * 60));

// normalizeSlots milik komponen halaman Jadwal (private static); disalin persis dari resources/views/pages/schedule/index.blade.php.
$normalize = function (array $slots): ?array {
    $merged = [];
    foreach ($slots as $slot) {
        $days = array_values(array_unique([...($merged[$slot['time']] ?? []), ...array_map('intval', $slot['days'])]));
        sort($days);
        $merged[$slot['time']] = $days;
    }
    ksort($merged);

    return $merged === [] ? null : array_map(fn (string $time, array $days) => ['time' => $time, 'days' => $days], array_keys($merged), $merged);
};

// 1. Slot rutin: penggabungan, ringkasan, dan waktu slot.
$slotCases = [];
for ($i = 0; $i < 400; $i++) {
    $raw = $randomSlots();
    $slots = $normalize($raw);
    $account = new SocialAccount(['name' => 'x', 'platform' => 'instagram', 'color' => '#000000', 'slots' => $slots]);
    $after = $randomNow()->addSeconds(mt_rand(0, 59));
    $until = mt_rand(0, 1) ? $after->addDays(mt_rand(0, 20))->endOfDay() : $after->addMinutes(mt_rand(0, 60 * 24 * 5));
    $slotCases[] = [
        'raw' => $raw,
        'slots' => $slots,
        'summary' => $account->slotSummary(),
        'after' => $fmt($after),
        'until' => $until->format('Y-m-d H:i:s.u'),
        'times' => array_map(fn ($at) => $at->format('Y-m-d H:i'), $account->slotTimes($after, $until)),
    ];
}

// 2. Isi otomatis.
$reset = function () {
    DB::table('post_stats')->delete();
    DB::table('schedule_accounts')->delete();
    DB::table('schedules')->delete();
    DB::table('clips')->delete();
    DB::table('clip_series')->delete();
    DB::table('projects')->delete();
    DB::table('social_accounts')->delete();
};

$fillCases = [];
for ($i = 0; $i < 200; $i++) {
    $reset();
    $now = $randomNow();
    CarbonImmutable::setTestNow($now);
    Carbon\Carbon::setTestNow($now);
    $accounts = [];
    for ($a = 0, $n = mt_rand(1, 4); $a < $n; $a++) {
        $slots = mt_rand(0, 4) === 0 ? null : $normalize($randomSlots());
        $accounts[] = SocialAccount::factory()->create(['name' => 'Akun '.$a, 'slots' => $slots]);
    }
    $project = Project::factory()->create();
    $series = [];
    for ($s = 0, $n = mt_rand(0, 3); $s < $n; $s++) {
        $series[] = ClipSeries::create(['project_id' => $project->id, 'title' => 'Seri '.$s]);
    }
    $clips = [];
    for ($c = 0, $n = mt_rand(1, 14); $c < $n; $c++) {
        $inSeries = $series !== [] && mt_rand(0, 1);
        $done = mt_rand(0, 4) > 0;
        $clips[] = Clip::factory()->for($project)->create([
            'title' => 'Klip '.$c,
            'review_status' => mt_rand(0, 6) > 0 ? ReviewStatus::Selected : ReviewStatus::Candidate,
            'render_status' => $done ? RenderStatus::Done : $pick([RenderStatus::NotRendered, RenderStatus::Failed, RenderStatus::Queued]),
            'output_path' => $done ? (mt_rand(0, 8) > 0 ? $existing : $workspace.'/hilang.mp4') : null,
            'rendered_at' => $done ? $now->subMinutes(mt_rand(1, 60 * 24 * 10)) : null,
            'clip_series_id' => $inSeries ? $pick($series)->id : null,
            'part_number' => $inSeries ? mt_rand(1, 4) : null,
        ]);
    }
    // Jadwal yang sudah ada: mengisi slot (sebagian tepat di jam slot) atau menjadwalkan klip.
    $existingSchedules = [];
    for ($e = 0, $n = mt_rand(0, 5); $e < $n; $e++) {
        $account = $pick($accounts);
        $times = $account->slotTimes($now, $now->addDays(14)->endOfDay());
        $at = $times !== [] && mt_rand(0, 2) > 0 ? $pick($times) : $now->addMinutes(mt_rand(-60 * 24 * 3, 60 * 24 * 16))->startOfMinute();
        $schedule = $pick($clips)->schedules()->create(['scheduled_at' => $at]);
        $ids = array_values(array_unique([$account->id, ...(mt_rand(0, 2) === 0 ? [$pick($accounts)->id] : [])]));
        $schedule->accounts()->sync($ids);
    }
    $before = [
        'now' => $fmt($now),
        'accounts' => array_map(fn (SocialAccount $a) => ['id' => $a->id, 'slots' => $a->slots], $accounts),
        'clips' => Clip::query()->orderBy('id')->get()->map(fn (Clip $clip) => [
            'id' => $clip->id, 'series' => $clip->clip_series_id, 'part' => $clip->part_number,
            'selected' => $clip->review_status === ReviewStatus::Selected, 'done' => $clip->render_status === RenderStatus::Done,
            'file' => $clip->output_path !== null && is_file($clip->output_path), 'rendered_at' => $clip->rendered_at?->format('Y-m-d H:i:s'),
        ])->all(),
        'schedules' => Schedule::with('accounts')->orderBy('id')->get()->map(fn (Schedule $s) => [
            'clip' => $s->clip_id, 'at' => $s->scheduled_at->format('Y-m-d H:i:s'), 'accounts' => $s->accounts->modelKeys(),
        ])->all(),
    ];
    $known = Schedule::query()->pluck('id')->all();
    $result = QueueFiller::fill($now);
    $planned = Schedule::with('accounts')->whereNotIn('id', $known)->orderBy('id')->get()->map(fn (Schedule $s) => [
        'clip' => $s->clip_id, 'at' => $s->scheduled_at->format('Y-m-d H:i'), 'accounts' => $s->accounts->modelKeys(),
    ])->all();
    // Pesan setelah Isi otomatis, disalin persis dari autoFill() halaman Jadwal.
    if ($result['scheduled'] === []) {
        $banner = $result['blocked'] > 0 && $result['left'] === 0
            ? 'Belum ada yang dijadwalkan: klip seri menunggu bagian sebelumnya dirender atau dijadwalkan.'
            : 'Belum ada yang dijadwalkan: slot rutin '.QueueFiller::DAYS.' hari ke depan sudah terisi semua.';
    } else {
        $first = $result['scheduled'][0]->scheduled_at;
        $last = end($result['scheduled'])->scheduled_at;
        $banner = count($result['scheduled']).' klip dijadwalkan, '.($first->isSameDay($last) ? $first->translatedFormat('j M') : $first->translatedFormat('j M').' – '.$last->translatedFormat('j M')).'.'
            .($result['left'] > 0 ? " {$result['left']} klip belum kebagian slot." : '')
            .($result['blocked'] > 0 ? " {$result['blocked']} bagian seri menunggu bagian sebelumnya." : '');
    }
    $fillCases[] = [...$before, 'planned' => $planned, 'left' => $result['left'], 'blocked' => $result['blocked'], 'banner' => $banner];
}

// 3. Jadwal ulang jadwal terlewat (disalin dari reschedule() halaman Jadwal) dan pengingat.
$rescheduleCases = [];
for ($i = 0; $i < 120; $i++) {
    $reset();
    $now = $randomNow();
    CarbonImmutable::setTestNow($now);
    Carbon\Carbon::setTestNow($now);
    $accounts = [];
    for ($a = 0, $n = mt_rand(1, 4); $a < $n; $a++) {
        $accounts[] = SocialAccount::factory()->create(['name' => 'Akun '.$a, 'slots' => mt_rand(0, 3) === 0 ? null : $normalize($randomSlots())]);
    }
    $project = Project::factory()->create();
    $clip = Clip::factory()->for($project)->create(['title' => 'Klip']);
    $others = [];
    for ($e = 0, $n = mt_rand(0, 4); $e < $n; $e++) {
        $account = $pick($accounts);
        $times = $account->slotTimes($now, $now->addDays(14)->endOfDay());
        if ($times !== []) {
            $s = $clip->schedules()->create(['scheduled_at' => $pick($times)]);
            $s->accounts()->sync([$account->id]);
            $others[] = ['at' => $s->scheduled_at->format('Y-m-d H:i:s'), 'accounts' => [$account->id]];
        }
    }
    $schedule = $clip->schedules()->create(['scheduled_at' => $now->subMinutes(mt_rand(1, 60 * 24 * 5))->startOfMinute()]);
    $targets = array_slice($accounts, 0, mt_rand(1, count($accounts)));
    $schedule->accounts()->sync(array_map(fn ($a) => $a->id, $targets));
    $statuses = [];
    foreach ($targets as $index => $account) {
        $status = $index === 0 ? ScheduleAccount::PENDING : $pick([ScheduleAccount::PENDING, ScheduleAccount::PUBLISHED, ScheduleAccount::SKIPPED]);
        $schedule->accounts()->updateExistingPivot($account->id, ['status' => $status]);
        $statuses[$account->id] = $status;
    }
    $schedule->load('accounts');
    $pending = $schedule->accounts->filter(fn (SocialAccount $account) => $account->pivot->status === ScheduleAccount::PENDING)->values();
    $slots = QueueFiller::openSlots($pending, $now);
    $slot = collect($slots)->search(fn (array $ids) => count($ids) === $pending->count()) ?: array_key_first($slots);
    $at = $slot !== null ? CarbonImmutable::parse($slot) : $now->addDay()->setTimeFrom($schedule->scheduled_at);
    $rescheduleCases[] = [
        'now' => $fmt($now),
        'accounts' => array_map(fn (SocialAccount $a) => ['id' => $a->id, 'slots' => $a->slots], $accounts),
        'others' => $others,
        'scheduled_at' => $schedule->scheduled_at->format('Y-m-d H:i:s'),
        'statuses' => $statuses,
        'at' => $at->format('Y-m-d H:i:s'),
        'banner' => 'Klip dijadwalkan ulang ke '.$at->translatedFormat('l, j M, H.i').'.',
    ];
}

$reminderCases = [];
for ($i = 0; $i < 120; $i++) {
    $reset();
    $now = $randomNow()->addSeconds(mt_rand(0, 59));
    CarbonImmutable::setTestNow($now);
    Carbon\Carbon::setTestNow($now);
    $accounts = [];
    for ($a = 0, $n = mt_rand(1, 3); $a < $n; $a++) {
        $accounts[] = SocialAccount::factory()->create(['name' => $pick(['Kajian IG', 'Kajian TikTok', 'YT Sunnah', 'FB Masjid']).' '.$a]);
    }
    $project = Project::factory()->create();
    $series = ClipSeries::create(['project_id' => $project->id, 'title' => 'Seri']);
    $schedules = [];
    for ($s = 0, $n = mt_rand(0, 5); $s < $n; $s++) {
        $part = mt_rand(0, 2) === 0 ? mt_rand(1, 3) : null;
        $clip = Clip::factory()->for($project)->create(['title' => $pick(['Sabar', 'Ikhlas', 'Syukur itu indah']), 'part_number' => $part, 'clip_series_id' => $part ? $series->id : null]);
        $at = $now->subMinutes(mt_rand(0, 1) ? mt_rand(-20, 15) : mt_rand(-30, 60 * 30))->startOfMinute();
        $schedule = $clip->schedules()->create(['scheduled_at' => $at, 'notified_at' => mt_rand(0, 4) === 0 ? $now->subHour() : null]);
        $targets = array_slice($accounts, 0, mt_rand(1, count($accounts)));
        $schedule->accounts()->sync(array_map(fn ($a) => $a->id, $targets));
        $rows = [];
        foreach ($targets as $account) {
            $status = $pick([ScheduleAccount::PENDING, ScheduleAccount::PENDING, ScheduleAccount::PUBLISHED, ScheduleAccount::SKIPPED]);
            $schedule->accounts()->updateExistingPivot($account->id, ['status' => $status]);
            $rows[] = ['name' => $account->name, 'status' => $status];
        }
        $schedules[] = ['id' => $schedule->id, 'title' => $clip->displayTitle(), 'at' => $at->format('Y-m-d H:i:s'),
            'notified' => $schedule->notified_at !== null, 'targets' => $rows];
    }
    $sent = new ArrayObject;
    $reminder = new ScheduleReminder(new class($sent) implements Notifier
    {
        public function __construct(private ArrayObject $sent) {}

        public function notify(string $title, string $message, string $reference): void
        {
            $this->sent[] = [$title, $message, $reference];
        }
    });
    $count = $reminder->send($now);
    $reminderCases[] = ['now' => $fmt($now), 'schedules' => $schedules, 'notes' => $sent->getArrayCopy(), 'count' => $count];
}

// 4. Statistik: umur hari ke-n, jadwal pengambilan, ringkasan, format angka, dan pembacaan hasil.
$reset();
$project = Project::factory()->create();
$clip = Clip::factory()->for($project)->create();
$account = SocialAccount::factory()->create();
$statCases = [];
for ($i = 0; $i < 300; $i++) {
    $published = $randomNow();
    $schedule = $clip->schedules()->create(['scheduled_at' => $published]);
    $schedule->accounts()->sync([$account->id]);
    $row = ScheduleAccount::query()->where('schedule_id', $schedule->id)->sole();
    $row->update(['status' => ScheduleAccount::PUBLISHED, 'published_at' => mt_rand(0, 9) ? $published : null]);
    $points = [];
    $time = $published;
    for ($p = 0, $n = mt_rand(0, 8); $p < $n; $p++) {
        $time = $time->addMinutes(mt_rand(10, 60 * 24 * 6));
        PostStat::create(['schedule_account_id' => $row->id, 'views' => mt_rand(0, 100000), 'source' => 'ytdlp', 'recorded_at' => $time]);
        $points[] = $time->format('Y-m-d H:i:s');
    }
    $row = $row->fresh();
    $statCases[] = [
        'published_at' => $row->published_at?->format('Y-m-d H:i:s'),
        'points' => $points,
        'at' => array_combine(ScheduleAccount::AGES, array_map(fn ($days) => $row->statAt($days)?->recorded_at->format('Y-m-d H:i:s'), ScheduleAccount::AGES)),
    ];
}

$reset();
$project = Project::factory()->create();
$clip = Clip::factory()->for($project)->create();
$account = SocialAccount::factory()->create();
$dueNow = CarbonImmutable::parse('2026-11-04 12:00:00');
CarbonImmutable::setTestNow($dueNow);
Carbon\Carbon::setTestNow($dueNow);
$dueRows = [];
for ($i = 0; $i < 400; $i++) {
    $published = $dueNow->subMinutes(mt_rand(0, 60 * 24 * 40));
    $checked = mt_rand(0, 5) === 0 ? null : $dueNow->subMinutes(mt_rand(0, 60 * 72));
    $schedule = $clip->schedules()->create(['scheduled_at' => $published]);
    $schedule->accounts()->sync([$account->id]);
    $row = ScheduleAccount::query()->where('schedule_id', $schedule->id)->sole();
    $row->update(['status' => mt_rand(0, 6) ? ScheduleAccount::PUBLISHED : ScheduleAccount::PENDING, 'published_at' => $published,
        'stats_checked_at' => $checked, 'url' => mt_rand(0, 6) ? 'https://www.tiktok.com/@k/video/'.$i : null]);
    $dueRows[$row->id] = ['status' => $row->status, 'url' => $row->url !== null, 'published_at' => $published->format('Y-m-d H:i:s'), 'checked_at' => $checked?->format('Y-m-d H:i:s')];
}
$due = StatsRefresher::due($dueNow)->modelKeys();
$dueCases = ['now' => $fmt($dueNow), 'rows' => array_map(fn ($id, $row) => [...$row, 'due' => in_array($id, $due, true)], array_keys($dueRows), $dueRows)];

$num = fn (?int $value) => $value === null ? '–' : number_format($value, 0, ',', '.');
$rate = fn (?float $value) => $value === null ? '–' : number_format($value * 100, 1, ',', '.').'%';
$summaryCases = [];
for ($i = 0; $i < 300; $i++) {
    $views = [];
    for ($k = 0, $n = mt_rand(1, 9); $k < $n; $k++) {
        $views[] = mt_rand(0, 4) === 0 ? null : $pick([mt_rand(0, 50), mt_rand(0, 5000), mt_rand(0, 9000000)]);
    }
    $measured = collect($views)->filter(fn ($value) => $value !== null)->sort()->values();
    $summary = [
        'count' => count($views),
        'total' => $measured->isEmpty() ? null : (int) $measured->sum(),
        'median' => $measured->isEmpty() ? null : (int) $measured->median(),
        'best' => $measured->isEmpty() ? null : (int) $measured->max(),
        'measured' => $measured->count(),
    ];
    $median = $summary['measured'] >= 3 ? $summary['median'] : null;
    $ratios = array_map(fn ($v) => $v !== null && $median ? number_format($v / $median, 1, ',', '.') : null, $views);
    $likes = mt_rand(0, 3) ? mt_rand(0, 9000) : null;
    $comments = mt_rand(0, 3) ? mt_rand(0, 900) : null;
    $shares = mt_rand(0, 3) ? mt_rand(0, 900) : null;
    $stat = new PostStat(['views' => $views[0], 'likes' => $likes, 'comments' => $comments, 'shares' => $shares]);
    $summaryCases[] = [
        'views' => $views, 'summary' => $summary, 'ratios' => $ratios,
        'formatted' => array_map($num, [$summary['total'], $summary['median'], $summary['best'], ...$views]),
        'likes' => $likes, 'comments' => $comments, 'shares' => $shares, 'rate' => $rate($stat->engagementRate()),
    ];
}

$parseCases = array_map(fn (string $output) => ['output' => $output, 'stats' => YtDlp::parseStats($output)], [
    "[info] x\nclipmi-stats 1500|20|NA|3\n", 'ERROR: Unsupported URL', "clipmi-stats NA|NA|NA|NA\n", "clipmi-stats 159900|35200|5484|1519\r\n",
    "clipmi-stats  12 | 3|x|\n", "clipmi-stats 0|0|0|0", "a\nclipmi-stats 9999999999|1|2|3\nclipmi-stats 5|5|5|5\n", "clipmi-stats 1.5|2|3|4\n",
]);

$explain = (new ReflectionClass(App\Stats\PostStatsFetcher::class))->getMethod('explain');
$explainCases = array_map(fn (array $c) => [...$c, 'message' => $explain->invoke(null, $c['url'], $c['error'])], [
    ['url' => 'https://www.instagram.com/reel/abc/', 'error' => 'ERROR: login required'],
    ['url' => 'https://web.facebook.com/watch?v=1', 'error' => 'x'],
    ['url' => 'https://fb.watch/abc/', 'error' => 'x'],
    ['url' => 'https://www.tiktok.com/@k', 'error' => 'ERROR: Unsupported URL: https://www.tiktok.com/@k'],
    ['url' => 'https://www.tiktok.com/@k/video/1', 'error' => 'HTTP Error 403: Forbidden'],
    ['url' => 'bukan link', 'error' => 'ERROR: x'],
]);

CarbonImmutable::setTestNow();
Carbon\Carbon::setTestNow();

$write = fn (string $name, $data) => file_put_contents("$out/$name.json", json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION)."\n");
$write('slots', $slotCases);
$write('fill', $fillCases);
$write('reschedule', $rescheduleCases);
$write('reminders', $reminderCases);
$write('stats', ['stat_at' => $statCases, 'due' => $dueCases, 'summary' => $summaryCases, 'parse' => $parseCases, 'explain' => $explainCases]);

echo 'slots '.count($slotCases).', fill '.count($fillCases).', reschedule '.count($rescheduleCases).', reminders '.count($reminderCases).
    ', stat_at '.count($statCases).', due '.count($dueCases['rows']).', summary '.count($summaryCases)."\n";
