# Ikon aplikasi iOS

`AppIcon.svg` = ikon adaptif Android (`app/src/main/res/drawable/ic_launcher_*.xml`) di kanvas 1024×1024 penuh
(iOS memotong sudutnya sendiri). PNG di `Assets.xcassets/AppIcon.appiconset/icon-1024.png` dirender dari SVG ini
dengan Edge headless (tanpa alpha, sesuai syarat ikon iOS):

```
msedge --headless=new --user-data-dir=D:\dev\tmp\edgeprof --window-size=1024,1024 --screenshot=icon-1024.png halaman.html
```

`halaman.html` hanya berisi `<img src="AppIcon.svg">` selebar 1024 px dengan margin 0.
