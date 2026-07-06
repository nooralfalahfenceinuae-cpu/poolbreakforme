# Playable Pool Prototype (Android, Kotlin)

A self-contained, single-screen pool game: touch-drag from the cue ball to
aim (live trajectory preview with cushion banks and pot detection), release
to shoot, and a real physics simulation takes over (friction, cushion
rebounds, ball-to-ball collisions, pocketing).

No screen capture, no overlay permissions, no background service — it only
ever draws and simulates its own game state on its own screen.

This repo is a full Gradle project, ready to build via GitHub Actions or
locally in Android Studio.

## Get an APK without installing anything (GitHub Actions)

1. Create a new **public or private GitHub repo** and push everything in
   this folder to it (root of the repo = this folder's contents, so
   `settings.gradle.kts` sits at the repo root).
   ```
   git init
   git add .
   git commit -m "Pool prototype"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
2. Pushing to `main` automatically triggers `.github/workflows/build-apk.yml`,
   which installs the Android SDK + Gradle on GitHub's runner (no local
   install needed) and builds a debug APK.
3. Go to your repo's **Actions** tab → the latest "Build Debug APK" run →
   scroll to **Artifacts** → download `pool-prototype-debug-apk`. Unzip it
   to get `app-debug.apk`.
4. Copy that APK to your phone and install it (you'll need to allow
   "install unknown apps" for whatever app you use to open it, since it's
   unsigned/debug-signed rather than from the Play Store).

You can also trigger a build manually anytime from the Actions tab via
"Run workflow" (enabled by the `workflow_dispatch` trigger).

## Building locally instead (Android Studio)

1. Open this folder directly in Android Studio (File → Open). It's a
   normal Gradle project, so Studio will sync it automatically and
   generate the Gradle wrapper jar it needs.
2. Build → Build Bundle(s)/APK(s) → Build APK(s).
3. Find the APK in `app/build/outputs/apk/debug/`.

## Project layout

```
settings.gradle.kts / build.gradle.kts   Root Gradle config
app/build.gradle.kts                     App module config (min/target SDK, deps)
app/src/main/AndroidManifest.xml         No special permissions requested
app/src/main/java/com/example/poolgame/
  MainActivity.kt                        Launches the game full-screen
  model/
    Vec2.kt                              2D vector math (no Android deps)
    Ball.kt                              Ball state: position, velocity, pocketed flag
    Table.kt                             Table/cushion/pocket geometry
  engine/
    TrajectoryEngine.kt                  Predictive aim line: cushion reflection,
                                          ball-impact + pocket detection (while aiming)
    PhysicsSimulator.kt                  Real simulation: friction, collisions,
                                          cushion bounces, pocket capture (after shot)
  view/
    GameView.kt                          SurfaceView game loop + touch input —
                                          the main playable view
    PoolTableView.kt                     Simpler Canvas-only reference view
                                          (aim preview only, no physics loop)
.github/workflows/build-apk.yml          CI: builds the debug APK on every push
```

## How to extend it

This is a working slice, not a finished game. Natural next steps:
- **Turns & fouls**: track whose shot it is, detect scratches (cue ball
  pocketed), detect no-rail/no-contact fouls.
- **Solids vs. stripes**: assign ball groups on the first legal pot.
- **Win condition**: check when a player's group is cleared, then the 8-ball.
- **Cue stick visual**: draw a stick sprite along the aim line instead of
  just a line, animated pulling back with drag distance.
- **Sound/haptics**: on collisions and pockets.

## Tuning physics feel

All the tunable numbers live in `model/Ball.kt` under `PhysicsConstants`:
`FRICTION_DECEL`, `CUSHION_RESTITUTION`, `MAX_SHOT_SPEED`, `STOP_THRESHOLD`.
