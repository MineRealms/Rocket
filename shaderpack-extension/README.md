# ROCKET local shader extension

This directory contains only the ROCKET-authored extension. Supply the existing
RocketFlow_Local pack separately; its source is not redistributed here.

Copy the unmodified pack to `.local-cache/shaderpacks/RocketFlow_ROCKET`, then run:

```powershell
python -B tools/install_rocket_shader.py
```

The installer patches that local copy and deploys it to
`.local-cache/production/client/shaderpacks/RocketFlow_ROCKET`. Both paths can be
overridden with `--source` and `--destination`, within `.local-cache`. It validates
all patch anchors before writing and can be run repeatedly. When changing the
installer's patch snippets, refresh the local source from the original pack first.
GLSL helper changes are copied on every invocation.

The patch uses the optional Java Oculus bridge's `rocketAltitude`,
`rocketTransitionAltitude`, `rocketWorldTime`, `rocketMoonPhase`,
`rocketSpacePass`, camera basis and the `rocketTerrain`/`rocketWorldTerrain` samplers.
The world-pass gate limits the effect to the Overworld and `rocketmod:space`,
including shaderpack `world0` variants. It leaves underwater views alone. The
pack keeps responsibility for surface materials, water, shadows, low-altitude
clouds and postprocessing.

Ascent fades overhead clouds between H=180 and H=800. Atmospheric scattering
blends through the ascent, the local terrain cache appears between H=240 and
H=640. A server-generated 32 km atlas of actual FULL-chunk surface samples fills
the distant view, refining progressively and persisting in world SavedData.
H=1400 through any Space altitude keeps the same near-orbit view. This is sparse
surface LOD, not full block geometry, and does not draw detached cloud fields.
Unknown atlas cells stay neutral until actual samples arrive. The Overworld clock
supplies the ground-stage sky. H=1240 through H=1400 blends into a fixed orbital
sun direction; changing Space time cannot darken the orbital terrain or atmosphere.

Run the production client for actual GPU compilation and visual validation.
Successful installation alone does not verify GLSL runtime compatibility.
