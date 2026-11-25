# Procedural terrain helper

Include `rocket_terrain.glsl` and call:

```glsl
float surfaceHeight;
vec3 surface = rocketProceduralTerrain(worldXZ, sun, distanceToEye, surfaceHeight);
```

`worldXZ` is the continuous world position in blocks. `sun` is a world-space
direction toward the sun, and `distanceToEye` is the surface distance in blocks.
The Java bridge must supply `rocketWorldSeed` as a float integer in 0..65535.
The returned color is scene linear; it contains surface lighting but no aerial
scattering or tonemapping. Heights are Overworld block Y, with sea level at 62.

There are at most 12 value-noise evaluations per call, each using four integer
hashes. Analytic noise derivatives supply slopes without extra samples. Bands
fade to their mean between 6 and 22 times their wavelength and skip evaluation
after fading out. Positions use a fixed four-block grid to remain stable near
the vanilla world border. This is Minecraft-like visual terrain, not vanilla
seed world generation; river geometry, mountain shadows, and biomes are visual
approximations. Reprojecting with the returned height costs another call. Root
renderer owns real-map blending, cube mapping, atmosphere, and GPU validation.
