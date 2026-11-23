# Earth Cube Textures

`assets/rocketmod/textures/environment/earth_*.png` are 128 by 128 pixel cube faces
derived from the Earth map distributed in the
[three.js examples](https://threejs.org/examples/textures/planets/earth_atmos_2048.jpg).
Image credit: NASA / Visible Earth, Reto Stockli and Robert Simmon.
Background and image credits: [NASA Blue Marble](https://science.nasa.gov/earth/earth-observatory/the-blue-marble-true-color-global-imagery-at-1km-resolution/).

Regenerate from the downloaded source image with
`python tools/generate_earth_cubemap.py path/to/earth_atmos_2048.jpg`.
The textures are a fixed visual proxy. They do not claim to represent the current
Minecraft world's continents or generated terrain.
