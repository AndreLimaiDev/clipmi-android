<?php

/**
 * Template bingkai acak (PNG dengan area transparan) dan hasil `TemplateFrame::analyze` desktop (FFmpeg desktop
 * memperkecil kanal alpha), sebagai fixture padanan Kotlin `id.clipmi.core.render.TemplateFrame`.
 *
 *   "C:/Users/<user>/AppData/Local/Programs/clipmi/resources/build/php/php.exe" tools/export-template-fixtures.php
 */

$desktop = $argv[1] ?? 'D:/laragon/www/clip-mi';
$out = __DIR__.'/../core/fixtures/template';

require $desktop.'/vendor/autoload.php';
$app = require $desktop.'/bootstrap/app.php';
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();

use App\Render\TemplateFrame;
use App\Render\TemplateFrameException;

@mkdir($out, 0777, true);
foreach (glob("$out/*.png") as $old) {
    unlink($old);
}
mt_srand(20261117);

$sizes = [[1080, 1920], [1080, 1350], [1080, 1080], [720, 1280], [1920, 1080], [1000, 1777], [607, 1080]];
$cases = [];

for ($i = 0; $i < 40; $i++) {
    [$w, $h] = $sizes[$i % count($sizes)];
    $image = imagecreatetruecolor($w, $h);
    imagesavealpha($image, true);
    imagealphablending($image, false);
    imagefilledrectangle($image, 0, 0, $w - 1, $h - 1, imagecolorallocatealpha($image, mt_rand(0, 255), mt_rand(0, 255), mt_rand(0, 255), 0));

    // Satu atau dua lubang transparan (kadang terlalu kecil, kadang tidak ada), kadang tepi setengah transparan.
    $holes = $i % 13 === 5 ? 0 : (mt_rand(1, 4) === 1 ? 2 : 1);
    for ($k = 0; $k < $holes; $k++) {
        $hw = (int) ($w * mt_rand(10, 95) / 100);
        $hh = (int) ($h * mt_rand(8, 80) / 100);
        $x = mt_rand(0, $w - $hw);
        $y = mt_rand(0, $h - $hh);
        if (mt_rand(1, 3) === 1) {
            imagefilledrectangle($image, $x - 6, $y - 6, $x + $hw + 5, $y + $hh + 5, imagecolorallocatealpha($image, 255, 255, 255, 64));
        }
        imagefilledrectangle($image, $x, $y, $x + $hw - 1, $y + $hh - 1, imagecolorallocatealpha($image, 0, 0, 0, 127));
    }

    $name = sprintf('template-%02d.png', $i);
    imagepng($image, "$out/$name");
    imagedestroy($image);

    try {
        $result = app(TemplateFrame::class)->analyze("$out/$name");
        $cases[] = ['file' => $name, 'aspect' => $result['aspect'], 'area' => $result['area'], 'error' => null];
    } catch (TemplateFrameException $e) {
        $cases[] = ['file' => $name, 'aspect' => null, 'area' => null, 'error' => $e->getMessage()];
    }
}

file_put_contents("$out/template.json", json_encode(['php' => PHP_VERSION, 'cases' => $cases], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION | JSON_PRETTY_PRINT));
echo count($cases)." template\n";
