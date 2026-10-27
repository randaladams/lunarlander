# Lunar Lander

An old-school lunar lander for Android phones and tablets: white vector lines
on a black sky, in the style of the 1979–80 arcade vector games.

Land the craft gently on a flat pad on a jagged lunar surface before you run
out of fuel — or air.

## Playing

| | |
|---|---|
| **Steer** | Tilt the device left or right; the ship points the way you tilt. |
| **Thrust** | Hold anywhere on the **right half** of the screen. |
| **Anti-gravity** | Tap the **left half**: sideways movement stops dead, gravity switches off, and a free shield bubble protects you, all for 1 second. 3 at the start; you earn one back for finishing every 5th level (never more than 3 held). The dots bottom-left show what's left. |
| **Land** | Touch down on a flat pad, upright, slowly. |
| **Pause** | The **II** button at the top center (or the Back button). RESUME, or QUIT to end the game and save the score. |

New? Tap **TUTORIAL** on the title screen: five short steps (tilt, thrust,
anti-gravity, grab a power-up, land), with no gravity until the last one and
no lives to lose. **SCORES** shows your high score (with the level it reached)
and your last 20 games.

* **Pads** are marked with their payout. You start next to the wide, cheap
  pad; each pad further away is narrower and pays more, and **X5** is always
  the farthest. One fuel tank is always placed on the way to X5, so going for
  it is a gamble you can win.
* **Safe touchdown**: under 12 down, under 7 sideways (shown top-right as
  `V` and `H`; they blink when you're too fast), and nearly upright.
* **Shield** (`SHLD`): absorbs the excess force of a hard landing, a bump into
  a rock face, or a meteor strike. Once it's used up, the next hit destroys
  the ship. It carries over between levels with a small top-up each level, and
  is restored when you lose a ship. While anti-gravity is on, its bubble takes
  every hit instead and your shield isn't touched.
* **Power-ups**: fuel (`F` diamonds, +350), oxygen (`O2` circles, +22 s) and
  shield (`S` hexagons, +35, one per level). They float 70–200 units above
  the ground, high enough to grab on the way down, and only between where you
  start and the far pad, never out past the last pad where you couldn't get
  back. (`Level.POWERUP_BONUS` sets how many fuel/O2: currently 5% above the
  base count.)
* Every level has at least 2 pads (X1 X2 X3 X5, then X1 X3 X5, then X2 X5),
  spread across the surface.
* **Sound**: engine rumble while thrusting, an explosion when you crash, and a
  zap when a shield absorbs a hit or anti-gravity switches on, and an alert for
  incoming meteors. Use the volume
  keys.
* **Oxygen** runs down all the time. When it hits zero you have 6 seconds to
  grab an `O2` or land.
* The view **zooms in** when you're close to the ground.
* **Meteor warning**: a small blinking arrow on the screen edge points at any
  meteor that's still off screen but on a collision course (up to 4 s out),
  with a two-tone alert when it first appears (once per meteor, at most every
  1.5 s). It blinks faster as impact gets closer and disappears once the
  meteor is in view.
* 3 ships per game. After a crash you get a fresh, random playfield.
* **Extra ships** at 1,500 points, then every 3,000 (4,500, 7,500, ...), up
  to 5 ships. NEXT SHIP next to the high score shows the next target.
* **Meteors can destroy power-ups**: a meteor that clips one has a 40% chance
  to smash it. A replacement of the same kind then materializes somewhere
  else useful (the X5 route tank reappears on the route) and can be grabbed
  once it has fully appeared.
* After every landing or crash, the screen explains **why**: your touchdown
  speeds and tilt against the safe limits, and how much shield the impact
  needed versus what you had (or what hit you, or that the air ran out).
* **Rescue missions** (level 10, then every second level: 12, 14, ...): an
  astronaut waits on an extra **SOS** pad. Land there first: it refuels you,
  repairs the shield and tops up oxygen. Lift off and land again on any
  scoring pad for a +250 rescue bonus. Land without the astronaut and you get
  a **strike**; 3 strikes cost a ship (crashing is not a strike).

### Levels

| Level | What changes |
|---|---|
| 1–3 | Four pads. Gravity rises a little each level |
| 2 | Narrower pads |
| 3 | Solar wind pushes you sideways (arrow at top) |
| 4–8 | Three pads. Meteor showers from level 4 |
| 9+ | Two pads. Rougher ground, more meteors. Gravity, wind and the fuel floor stop getting worse here, so every level stays landable |

All the numbers live in
[`Level.java`](app/src/main/java/com/randaladams/lunarlander/engine/Level.java)
for tuning.

## Installing on your phone

The game opens on an animated start screen (about 4 seconds, tap to skip)
while the sounds load, then the title menu: PLAY, TUTORIAL, SCORES and EXIT.
To drop the start screen, set `Game.SPLASH_SECONDS` to 0.

Every push to `main` is built by GitHub Actions and published to the
[**latest** release](../../releases/tag/latest). On your Android phone or
tablet, open that page, download `lunarlander.apk`, and open it. Android will
ask you to allow installs from your browser the first time.

All builds are signed with the same key (in `keystore/`), so a new build
installs over the old one and keeps your high score.

Requires Android 8.0 or newer and a device with a tilt sensor.

## Building yourself

Open the folder in Android Studio, or from a terminal with JDK 17+:

```sh
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Code layout

```
app/src/main/java/com/randaladams/lunarlander/
  MainActivity.java     full-screen, landscape activity
  Sounds.java           engine loop, crash and shield sounds (SoundPool)
  GameView.java         game loop thread, touch → thrust/anti-gravity/taps, vibration, high score
  TiltSensor.java       accelerometer → left/right roll, for any screen rotation
  AndroidGfx.java       draws vector lines on an Android Canvas, with glow
  engine/               pure Java, no Android code:
    Game.java           rules, physics, collisions, power-ups, meteors, scoring
    Level.java          difficulty curve
    Terrain.java        jagged terrain and landing pads
    Renderer.java       draws everything (HUD, ship, terrain) through Gfx
    VectorFont.java     arcade-style stroke font
    Gfx.java            minimal drawing interface
tools/sounds/           make_sounds.py synthesizes res/raw/*.wav (needs numpy)
tools/desktop/          runs the engine on a desktop JVM: an autopilot that
                        checks every level can be landed, and PNG screenshots
```

Run the desktop checks without Android tooling:

```sh
javac -d /tmp/ll $(find app/src/main/java -path '*engine*' -name '*.java') tools/desktop/src/*.java
java -cp /tmp/ll EngineCheck /tmp/ll/screens
```

The `keystore/debug.keystore` is a throwaway debug key so sideloaded builds can
update each other. Use a private key for any Play Store release.
