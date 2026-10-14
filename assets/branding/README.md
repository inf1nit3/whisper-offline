# Logo

- `logo.svg` – Hauptlogo (512 × 512): Mikrofon auf einem Chip, Wellenform, Neon-Ring
- `logo-small.svg` – vereinfachte Fassung für 16–40 px (nur Ring, Chip, Mikrofon)
- `logo-512.png` – gerendertes Hauptlogo, z. B. für die Downloads auf scheisssewasser.xyz

Daraus erzeugt:

- Android: `app/android/app/src/main/res/drawable-*/ic_launcher_foreground.png`
  (Logo auf 68 dp in der 108-dp-Fläche des adaptiven Symbols) und
  `drawable/ic_launcher_monochrome.xml` (Themen-Icon)
- Windows: `app/windows/WhisperOffline/Assets/app.ico` (16–40 px aus `logo-small.svg`,
  48–256 px aus `logo.svg`)

Farben: Türkis `#4FE8D4`, Lila `#B06CF2`, Scheibe `#2E1658` → `#120926`.
