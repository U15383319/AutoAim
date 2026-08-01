# Auto Aim

Minecraft 1.20.1 client mod built with Architectury API for Fabric and Forge.

## Controls

- **G** toggles the projectile impact marker. While a bow, crossbow, trident,
  or compatible `ProjectileWeaponItem` is being charged, the first block on the
  trajectory receives a pulsing overlay. A fully loaded crossbow also keeps the
  marker visible while merely held. If an entity would be hit first, the block
  marker is hidden and that entity receives a vanilla glowing outline.
- Hold **V** to acquire a hittable entity near the complete three-dimensional
  projectile trajectory. Targets closer to the path are preferred, with targets
  beside earlier portions of the path winning within the same 0.5-block distance
  band. While the marker is enabled, the locked target receives the configured
  glowing outline. Releasing **V** releases the target immediately.

Both keys can be changed in Minecraft's Controls screen. The acquisition tube is
limited to 12 blocks around the trajectory, and trajectories are limited to 128
blocks. All projectile-collidable entity types are eligible, including airborne
entities, players, vehicles, and end crystals.

## Configuration

On first launch the mod creates `config/autoaim.json`:

```json
{
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

`glowColor` accepts `#RRGGBB`, `RRGGBB`, or `0xRRGGBB`. Entries in
`entityBlacklist` are entity type registry IDs and affect auto-aim acquisition
only; an actually obstructing entity is still shown by the impact marker. Restart
the client after editing the file.

## Building

Run `./gradlew build` (or `gradlew.bat build` on Windows). Fabric and Forge jars
are produced under their respective `build/libs` directories.
