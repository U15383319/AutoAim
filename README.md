# Auto Aim

Minecraft 1.20.1 client mod built with Architectury API for Fabric and Forge.

## Controls

- **G** toggles the impact/attack marker. While a bow, crossbow, trident, or
  compatible `ProjectileWeaponItem` is being charged, the first block on the
  trajectory receives a pulsing overlay. A fully loaded crossbow also keeps the
  marker visible while merely held. Ender pearls, snowballs, eggs, splash potions,
  and lingering potions always show their trajectory while held, without requiring
  item use. A Riptide trident shows its level-dependent dash path once fully charged
  in water or rain. If an entity would be hit first, the block marker is hidden and
  that entity receives a vanilla glowing outline. When a sword, axe, or idle
  trident is selected as the held combat item, the entity under the crosshair
  glows only when Minecraft's own reach and obstruction checks say a normal
  attack can hit it.
- Hold **V** with a supported ranged weapon or throwable item to acquire a hittable
  entity near the complete three-dimensional projectile trajectory. Targets closer
  to the path are preferred, with targets beside earlier portions of the path
  winning within the same 0.5-block distance band. While the marker is enabled,
  the locked target receives the configured glowing outline. Releasing **V**
  releases the target immediately. With a held melee weapon, **V** instead
  locks the nearest eligible entity within 12 blocks and turns the crosshair toward
  its bounding-box center. A melee-locked entity glows only after it is actually
  within vanilla attack reach and is not blocked by a block or another entity.

While a valid lock is active, the view follows the target smoothly each rendered
frame, with a capped turn speed and frame-rate-independent damping. Mouse look is
temporarily suppressed during that lock to prevent it from fighting the aim.
Releasing **V**, losing a valid aim solution, or opening a screen restores normal
mouse look.

Both keys can be changed in Minecraft's Controls screen. The acquisition tube is
limited to 12 blocks around the trajectory, and trajectories are limited to 128
blocks. All projectile-collidable entity types are eligible, including airborne
entities, players, vehicles, and end crystals.

## Optional TaCZ compatibility

The Forge build supports **TaCZ 1.2.0 for Minecraft 1.20.1**. Install TaCZ alongside
Auto Aim to enable gun trajectory markers with **G** and smooth target locking
with **V** while holding a gun in the main hand. Guns do not need to be charging
or aiming down sights. The existing 128-block trajectory limit, 12-block target
search radius, and entity blacklist also apply to guns.

Gun locks aim at a living target's actual eye position, the origin of its debug
view ray. The eye height follows the entity's current pose. If no trajectory can
reach the eye region, the gun does not fall back to a torso aim point. Vehicles,
end crystals, and other non-living targets use their bounding-box center.

The crosshair shows the firing direction, so it can sit above the eyes to
compensate for bullet drop. This becomes more visible
at long range, especially with slower handgun rounds. Grounded targets do not
receive a false downward lead from their residual gravity velocity. A reserved
target is highlighted as locked only while a valid aim solution is available.
Target movement is sampled every client tick, even before the lock key is held.
Gun targets use a conservative constant-velocity predictor, with no mob-specific
movement rules. A component receives a lead only when three consecutive network
movement intervals admit a common velocity, allowing one tick of timestamp
quantization because packets are observed on client ticks. This uncertainty is
used to assess observations, never added to the aim as an assumed delay. A stop,
turn, changing vertical speed, insufficient history, or stale position packets
disables the affected component instead of extrapolating old velocities.
The predictor never extrapolates an estimated acceleration or damping curve.
Confident shot anticipation uses the actual flight time within the existing
30-tick/eight-block global forecast limits. A stable fast target is not clipped
to a one-block lead merely to keep the view close: continuous camera tracking
does not use this anticipation at all. These are upper bounds, not assumed delays.

Between shots, camera tracking uses the currently displayed eyes and bullet-drop
compensation only. Movement anticipation is evaluated separately when a TaCZ shot
is queued, so a changing movement estimate cannot continuously drag the view away
from the head. Horizontal interception starts at those same displayed eyes,
matching TaCZ's horizontal hitbox rewind. Its headshot height uses the live entity
Y instead, so stable vertical interception uses the latest received Y and the
monotonic time when its position update was processed. The available RTT estimate
accounts for the incoming position packet and outgoing shot together. It does
not measure the actual delay of each individual packet.
The engine's tracking-before-movement order and whole-tick target collision poses
are accounted for separately from network age. No fixed two-tick packet age is
assumed. This vertical alignment is bounded by one additional block and applies
only to the shot, not to continuous tracking. Uncertain vertical motion uses the
latest observed height without extrapolating it through any further time. A valid solver result is not a promise
that a changing target will receive a headshot.
Vanilla ranged weapons independently retain their simple velocity-based lead,
body aim points, and existing ballistic profiles.

Between shots, the view follows smoothly. At the trigger, the client aligns its
view with the latest bounded interception solution and sends that rotation immediately before
TaCZ's shoot packet. This prevents its asynchronous shooting code from using an
older server-side angle. The trigger correction can be visible if the view has
not finished turning toward a newly acquired target.

Gun profiles use TaCZ's synchronized client gun data, attachment-adjusted bullet
speed when its cache is available, and the global bullet speed multiplier. The
prediction includes gravity, drag, bullet lifetime, inherited player movement,
and TaCZ's launch position and water behavior. As with TaCZ's own rangefinder,
server-only scripts that change ballistics cannot be predicted exactly. Random
spread, penetration through entities/blocks, and custom projectile-spawn scripts
are not simulated; the marker follows the central trajectory to its first obstruction.

TaCZ is optional: Auto Aim has no compile-time or required runtime dependency on
it. Its public APIs are resolved only after the mod loader reports that TaCZ is
installed. Missing or incompatible APIs disable the integration while keeping
vanilla weapon support available. Fabric and Forge builds continue to work on
their own with the existing required Architectury dependencies.

## Configuration

On first launch the mod creates `config/autoaim.json`:

```json
{
  "markerEnabled": false,
  "glowColor": "#FF7A0D",
  "entityBlacklist": [
    "minecraft:item",
    "minecraft:experience_orb",
    "minecraft:area_effect_cloud",
    "minecraft:arrow",
    "minecraft:spectral_arrow",
    "minecraft:trident",
    "minecraft:firework_rocket",
    "minecraft:fishing_bobber"
  ]
}
```

`markerEnabled` is updated whenever the marker key is toggled, so the mode is
restored after restarting the client. `glowColor` accepts `#RRGGBB`, `RRGGBB`,
or `0xRRGGBB`. Entries in
`entityBlacklist` are entity type registry IDs and affect projectile and melee
auto-aim acquisition only; an actually obstructing entity is still shown by the
impact marker. Restart the client after editing the file.

## Building

Run `./gradlew build` (or `gradlew.bat build` on Windows). Fabric and Forge jars
are produced under their respective `build/libs` directories.

## Testing in the development client

The Forge development runtime includes TaCZ 1.2.0 by default via
`modLocalRuntime`; Loom remaps the release JAR for the development environment.
It is not bundled in Auto Aim or added to its published dependencies. TaCZ's
bundled libraries and default gun pack are included in the official release.

Run `./gradlew :forge:runClient` (Windows: `gradlew.bat :forge:runClient`), or
reload the Gradle project in IntelliJ IDEA and use **Minecraft Client (:forge)**.
The working directory is `forge/run`, and the client uses up to 4 GB of heap.
Use a creative world, take a gun and ammunition from the TaCZ creative tabs,
then test **G** for impact markers and hold **V** for smooth target locking.

TaCZ also binds **G** to fire-mode selection and **V** to melee by default.
Rebind either mod's conflicting actions in Minecraft's Controls screen before
testing (for example, move Auto Aim's actions to **F6** and **F7**).

To test without TaCZ, run `./gradlew :forge:runClient -Ptacz_dev=false`.
For IDE launches without TaCZ, set `tacz_dev=false` in `gradle.properties` and
reload the Gradle project first. Fabric development runs do not include TaCZ.

## Automated tests

Run `./gradlew :common:test` for the ballistic and optional compatibility unit tests.

Run `./gradlew -Pautoaim_gametest=true :forge:runGameTestServer` for Minecraft
GameTests. The suite creates real Withers and zombies, fires actual TaCZ golden
Desert Eagle bullets without random spread, and checks damage and TaCZ's
headshot event at 30, 60, 100, and 120 blocks. Moving Withers are tested while
rising, falling, strafing, and pursuing a target at 70 and 120 blocks. Moving
zombies, cows, allays, and iron golems use the same observed-motion predictor.
Motion history is collected from real world ticks before firing. Shared
world tests also check eye-region intersections and rejection of a stone wall.
These tests execute the production solver and trajectory code.
The moving-headshot cases remain strict accuracy audits: a conservative model
declining an unstable lead can still fail their headshot requirement. Keep those
failures visible; passing camera-following tests does not replace this audit.

The test server uses `forge/build/gametest`, recreating only its test `world`
directory on each run. It does not use development-client saves in `forge/run`.
Reports are written to `forge/build/reports/gametest-tacz.xml`. To run the shared
world tests without loading TaCZ, add `-Ptacz_dev=false`; that report is
`forge/build/reports/gametest-standalone.xml`. Test classes and structures are
excluded from published jars. These server tests validate trajectories and
real bullet impacts; they do not exercise client camera rendering or gun sights.

After generating that test world, run
`./gradlew -Pautoaim_clienttest=true :forge:runClient` for the real client integration
suite. It copies the GameTest world to `forge/build/clienttest/saves/autoaim-test`,
loads that isolated copy, holds the lock key, and fires through TaCZ's client
operator and normal network packets. The original 24 scenarios cover the golden Desert
Eagle, M4A1, and AWP against stationary Withers and zombies at 60/120 blocks, plus
handgun shots against lateral, rising, and falling targets at 70/120 blocks.
Those assertions still require TaCZ's actual server-side headshot event; vertical
motion cases also verify that the target really rose or fell before impact.
Eight additional scenarios use normal AI walking, a native jump, a removed floor
for natural falling, and a navigation turn at 60/120 blocks. Their explicit
requirement is bounded camera tracking during real movement; hit/headshot outcomes
are recorded separately and are not counted as headshot assertions.

Results, firing angles, native collision-box heights, and impact coordinates are
saved to `forge/build/reports/client-aim.json`; a failed assertion fails the Gradle
task. Add `-Pautoaim_clienttest_case=23` to select one scenario by its zero-based
index, `-Pautoaim_clienttest_case=natural` for just the eight AI scenarios, or
`-Pautoaim_clienttest_case=accuracy` for the original 24 headshot assertions.
Every invocation recreates only the isolated copied world so a previous
scenario's saved blocks cannot invalidate an airborne test.

Both integration suites remove random spread only in their test fixtures to
check the central trajectory. Production gun spread is unchanged. These tests
do not guarantee hits after an unpredictable turn, a teleport, or arbitrary
network delay, and cannot predict server-only custom ballistic scripts.
TaCZ 1.2.0 also offsets a moving entity's collision box vertically while testing
default headshot height against its unshifted eye height. During sufficiently
fast vertical movement those regions can stop overlapping; a client aiming mod
cannot make a headshot possible in a server tick where that intersection is empty.
