<?php

/**
 * Mengekspor fixture Paket Standar Tim dari kode desktop (`TeamPack::parse` dan `preview`) untuk padanan Kotlin di
 * core (`id.clipmi.core.settings.TeamPack`). Paket acak berisi entri sah dan rusak (tipe salah, di luar rentang, kolom
 * lama, gambar rusak). Kode desktop dijalankan apa adanya dengan SQLite di memori; repo desktop tidak tersentuh.
 *
 *   "C:/Users/<user>/AppData/Local/Programs/clipmi/resources/build/php/php.exe" tools/export-teampack-fixtures.php
 */

$desktop = $argv[1] ?? 'D:/laragon/www/clip-mi';
$out = __DIR__.'/../core/src/test/resources/fixtures/settings';
$workspace = sys_get_temp_dir().DIRECTORY_SEPARATOR.'clipmi-teampack-fixtures';

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

use App\Models\CaptionStyle;
use App\Models\CorrectionEntry;
use App\Models\FrameStyle;
use App\Settings\TeamPack;
use App\Settings\TeamPackException;

@mkdir($out, 0777, true);
mt_srand(20261118);
$pick = fn (array $items) => $items[mt_rand(0, count($items) - 1)];

$image = function (string $type): string {
    $img = imagecreatetruecolor(4, 3);
    imagefill($img, 0, 0, imagecolorallocate($img, 200, 30, 90));
    ob_start();
    match ($type) {
        'png' => imagepng($img),
        'jpg' => imagejpeg($img),
        'webp' => function_exists('imagewebp') ? imagewebp($img) : imagepng($img),
    };

    return base64_encode((string) ob_get_clean());
};
$images = ['png' => $image('png'), 'jpg' => $image('jpg'), 'webp' => $image('webp')];
$fakeVideo = base64_encode("\x00\x00\x00\x18ftypmp42 isi video uji");

// Nilai acak per kolom: kebanyakan sah, sebagian salah tipe atau di luar rentang.
$colors = ['#FFFFFF', '#00ff00', '#12AB9f', 'merah', '#FFF', '#1234567', 123456, null, ''];
$bools = [true, false, 1, 0, '1', '0', 'true', 2, 1.0, null];
$ints = fn (int $min, int $max) => [mt_rand($min, $max), mt_rand($min, $max), (string) mt_rand($min, $max), ' '.mt_rand($min, $max).' ', $min - 1, $max + 1, (float) mt_rand($min, $max), mt_rand($min, $max) + 0.5, 'abc', null, true];
$names = ['Bawaan', 'Karaoke Kuning', 'bawaan', 'Tim Dakwah', 'Kotak Biru', 'Crop penuh', '  ', '', str_repeat('Nama panjang ', 6), 42, null];

$maybe = function (array &$item, string $key, array $values, int $chance = 4) use ($pick) {
    if (mt_rand(0, $chance) > 0) {
        $item[$key] = $pick($values);
    }
};

$caption = function () use ($pick, $maybe, $colors, $bools, $ints, $names) {
    $s = [];
    $maybe($s, 'name', $names, 12);
    $maybe($s, 'font_family', ['Poppins', 'Montserrat', 'Comic Sans', 'Bebas Neue', null], 10);
    $maybe($s, 'font_size', $ints(40, 140), 10);
    $maybe($s, 'uppercase', $bools);
    $maybe($s, 'primary_color', $colors, 10);
    $maybe($s, 'outline_color', $colors, 10);
    $maybe($s, 'highlight_active_word', $bools);
    $maybe($s, 'highlight_style', ['color', 'box', 'garis']);
    $maybe($s, 'highlight_color', $colors, 10);
    $maybe($s, 'upcoming_color', [...$colors, null, null]);
    $maybe($s, 'keyword_highlight', $bools);
    $maybe($s, 'keyword_color', $colors);
    $maybe($s, 'position', ['bottom', 'middle', 'top'], 10);
    $maybe($s, 'words_per_line', $ints(1, 8), 10);
    $maybe($s, 'animation', ['none', 'pop', 'fade', 'reveal', 'goyang']);
    $maybe($s, 'effect', ['outline', 'shadow', 'glow', 'neon']);
    $maybe($s, 'line_box', $bools);
    $maybe($s, 'line_box_color', $colors);
    $maybe($s, 'line_box_opacity', $ints(10, 100));
    $maybe($s, 'is_default', $bools);
    $maybe($s, 'kolom_asing', ['x', 1]);

    return $s;
};

$frame = function () use ($pick, $maybe, $colors, $bools, $ints, $names, $images) {
    $s = [];
    $maybe($s, 'name', $names, 12);
    $maybe($s, 'aspect', ['9:16', '1:1', '4:5', '16:9', '3:4']);
    $maybe($s, 'mode', ['crop', 'fit', 'top', 'template', 'zoom'], 10);
    $maybe($s, 'background', ['blur', 'color', 'gradient', 'image', 'video'], 10);
    $maybe($s, 'blur_strength', $ints(1, 30), 10);
    $maybe($s, 'background_color', $colors, 10);
    $maybe($s, 'background_color_2', $colors);
    $maybe($s, 'video_height', $ints(30, 85));
    $maybe($s, 'video_position', ['top', 'center', 'bottom', 'kiri']);
    $maybe($s, 'zoom', $ints(100, 200));
    $maybe($s, 'corner_radius', $ints(0, 80));
    $maybe($s, 'border_width', $ints(0, 24));
    $maybe($s, 'border_color', $colors);
    $maybe($s, 'logo_position', ['top-left', 'top-right', 'bottom-left', 'bottom-right', 'tengah'], 2);
    $maybe($s, 'logo_x', $ints(0, 100), 2);
    $maybe($s, 'logo_y', $ints(0, 100), 2);
    $maybe($s, 'logo_size', $ints(5, 40));
    $maybe($s, 'logo_opacity', $ints(10, 100));
    $maybe($s, 'watermark_text', ['@kajian', null, '', str_repeat('w', 81), 77]);
    $maybe($s, 'watermark_font', ['Poppins', 'Montserrat', 'Arial']);
    $maybe($s, 'watermark_size', $ints(16, 120));
    $maybe($s, 'watermark_color', $colors);
    $maybe($s, 'watermark_opacity', $ints(10, 100));
    $maybe($s, 'watermark_x', $ints(0, 100));
    $maybe($s, 'watermark_y', $ints(0, 100));
    $maybe($s, 'title_bar', $bools);
    $maybe($s, 'title_bar_color', $colors);
    $maybe($s, 'title_text_color', $colors);
    $maybe($s, 'subtitle_y', [...$ints(5, 95), null]);
    $maybe($s, 'template_area', [null, ['x' => 0.1, 'y' => 0.2, 'width' => 0.5, 'height' => 0.4], ['x' => 1.5], ['x' => 0.1, 'z' => 0.2], [], [0.1, 0.2], ['x' => 'a'], ['x' => '0.3', 'y' => 0]]);
    $maybe($s, 'is_default', $bools);
    $file = fn () => $pick([
        ['extension' => 'png', 'data' => $images['png']], ['extension' => 'jpg', 'data' => $images['jpg']], ['extension' => 'webp', 'data' => $images['webp']],
        ['extension' => 'gif', 'data' => $images['png']], ['extension' => 'png', 'data' => 'bukan base64!!'], ['extension' => 'png', 'data' => base64_encode('bukan gambar')],
        null, 'teks',
    ]);
    $maybe($s, 'logo', [$file(), $file()], 2);
    $maybe($s, 'background_image', [$file()], 3);
    $maybe($s, 'template_image', [$file()], 3);

    return $s;
};

$correction = function () use ($pick, $maybe, $bools) {
    $s = [];
    $maybe($s, 'wrong_text', ['solat', 'Alloh', 'insya allah', '', str_repeat('x', 256), 5, null], 10);
    $maybe($s, 'correct_text', ['shalat', 'Allah', 'insyaallah', '  ', 7], 10);
    $maybe($s, 'is_active', $bools);

    return $s;
};

$bumper = function () use ($pick, $fakeVideo) {
    return $pick([
        ['kind' => 'intro', 'name' => 'Pembuka', 'duration' => 3.5, 'video' => ['extension' => 'mp4', 'data' => $fakeVideo]],
        ['kind' => 'outro', 'name' => '  Penutup  ', 'duration' => '4', 'video' => ['extension' => 'mov', 'data' => $fakeVideo]],
        ['kind' => 'intro', 'name' => 'Webm', 'duration' => 2, 'video' => ['extension' => 'webm', 'data' => $fakeVideo]],
        ['kind' => 'tengah', 'name' => 'Salah', 'duration' => 2, 'video' => ['extension' => 'mp4', 'data' => $fakeVideo]],
        ['kind' => 'intro', 'name' => 'Terlalu lama', 'duration' => 31, 'video' => ['extension' => 'mp4', 'data' => $fakeVideo]],
        ['kind' => 'outro', 'name' => '', 'duration' => 2, 'video' => ['extension' => 'mp4', 'data' => $fakeVideo]],
        ['kind' => 'intro', 'name' => 'Kosong', 'duration' => 2, 'video' => ['extension' => 'mp4', 'data' => '']],
        ['kind' => 'intro', 'name' => 'AVI', 'duration' => 2, 'video' => ['extension' => 'avi', 'data' => $fakeVideo]],
        ['kind' => 'intro', 'name' => 'Nol', 'duration' => 0, 'video' => ['extension' => 'mp4', 'data' => $fakeVideo]],
        'bukan objek',
    ]);
};

$existing = [
    'styles' => CaptionStyle::query()->pluck('name')->all(),
    'frames' => FrameStyle::query()->pluck('name')->all(),
    'corrections' => ['solat', 'Alloh'],
];
foreach ($existing['corrections'] as $word) {
    CorrectionEntry::create(['wrong_text' => $word, 'correct_text' => $word, 'is_active' => true]);
}

$packs = [];
for ($i = 0; $i < 300; $i++) {
    $pack = ['format' => 'clipmi-team-pack', 'version' => 1];
    if (mt_rand(0, 4)) $pack['prompt_template'] = $pick(['Template {transcript}', '   ', null, 12]);
    if (mt_rand(0, 4)) $pack['prompt_max_tokens'] = $pick([20000, '20000', 1.5, null, 0]);
    if (mt_rand(0, 6)) $pack['caption_styles'] = array_map(fn () => $caption(), range(1, mt_rand(0, 6)));
    if (mt_rand(0, 6)) $pack['frame_styles'] = array_map(fn () => $frame(), range(1, mt_rand(0, 6)));
    if (mt_rand(0, 6)) $pack['corrections'] = array_map(fn () => $correction(), range(1, mt_rand(0, 6)));
    if (mt_rand(0, 3) === 0) $pack['bumpers'] = array_map(fn () => $bumper(), range(1, mt_rand(1, 4)));
    if (mt_rand(0, 15) === 0) $pack['caption_styles'] = 'bukan larik';
    $packs[] = json_encode($pack, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION);
}
$packs[] = '{"format": "lain"}';
$packs[] = 'bukan json';
$packs[] = '{"format": "clipmi-team-pack", "version": 2}';
$packs[] = '[1, 2]';

$teamPack = new TeamPack;
$cases = [];
foreach ($packs as $json) {
    try {
        $parsed = $teamPack->parse($json);
        $strip = fn ($file) => $file === null ? null : ['extension' => $file['extension'], 'size' => strlen(base64_decode($file['data']))];
        $cases[] = [
            'json' => $json,
            'result' => [
                'prompt_template' => $parsed['prompt_template'],
                'prompt_max_tokens' => $parsed['prompt_max_tokens'],
                'caption_styles' => $parsed['caption_styles'],
                'frame_styles' => array_map(function (array $style) use ($strip) {
                    foreach (['logo', 'background_image', 'template_image'] as $key) {
                        if (array_key_exists($key, $style)) {
                            $style[$key] = $strip($style[$key]);
                        }
                    }

                    return $style;
                }, $parsed['frame_styles']),
                'corrections' => $parsed['corrections'],
                'bumpers' => array_map(fn ($b) => ['kind' => $b['kind'], 'name' => $b['name'], 'duration' => $b['duration'], 'video' => $strip($b['video'])], $parsed['bumpers']),
                'skipped' => $parsed['skipped'],
            ],
            'preview' => $teamPack->preview($parsed),
        ];
    } catch (TeamPackException $e) {
        $cases[] = ['json' => $json, 'error' => $e->getMessage()];
    }
}

file_put_contents("$out/teampack.json", json_encode(['existing' => $existing, 'cases' => $cases], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION)."\n");
echo 'teampack '.count($cases).', webp '.(function_exists('imagewebp') ? 'ya' : 'tidak')."\n";
