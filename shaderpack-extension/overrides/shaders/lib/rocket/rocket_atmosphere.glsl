// ROCKET extension, composited in scene-linear space before the pack's tonemapper.
uniform float rocketTransitionAltitude;
uniform vec3 rocketMapInfo;
uniform vec3 rocketMapOrigin;
uniform vec3 rocketWorldMapInfo;
uniform vec3 rocketWorldMapOrigin;
uniform vec3 rocketCameraForward;
uniform vec3 rocketCameraUp;
uniform vec3 rocketCameraRight;
uniform sampler2D rocketTerrain;
uniform sampler2D rocketWorldTerrain;

vec4 rocketWorldSample(vec2 uv) {
    vec2 pixel = clamp(uv * 128.0 - 0.5, vec2(0.0), vec2(127.0));
    ivec2 p = ivec2(floor(pixel));
    ivec2 q = min(p + 1, ivec2(127));
    vec2 f = fract(pixel);
    return mix(mix(texelFetch(rocketWorldTerrain, p, 0), texelFetch(rocketWorldTerrain, ivec2(q.x, p.y), 0), f.x),
               mix(texelFetch(rocketWorldTerrain, ivec2(p.x, q.y), 0), texelFetch(rocketWorldTerrain, q, 0), f.x), f.y);
}

vec3 rocketViewRay(vec2 uv) {
    vec2 plane = (uv * 2.0 - 1.0) / vec2(gbufferProjection[0][0], gbufferProjection[1][1]);
    return normalize(rocketCameraForward + plane.x * rocketCameraRight + plane.y * rocketCameraUp);
}

vec3 rocketClockSunVector() {
    float angle = fract(float(rocketWorldTime) / 24000.0 - 0.25);
    angle = (angle + (-0.5 * cos(angle * 3.14159265) + 0.5 - angle) / 3.0) * 6.28318531;
    float tilt = radians(sunPathRotation);
    return vec3(-sin(angle), cos(angle) * cos(tilt), -cos(angle) * sin(tilt));
}

vec3 rocketSunVector() {
    vec3 orbitalDaylight = normalize(vec3(-0.22, 0.88, 0.42));
    float orbitalBlend = smoothstep(1240.0, 1400.0, rocketAltitude);
    return normalize(mix(rocketClockSunVector(), orbitalDaylight, orbitalBlend));
}

float rocketSceneHeight() {
    float progress = clamp((rocketAltitude - 64.0) / max(rocketTransitionAltitude - 64.0, 1.0), 0.0, 1.0);
    return 1536.0 * pow(progress, 2.05);
}

uint rocketStarHash(ivec3 p) {
    uint h = uint(p.x) * 73856093u ^ uint(p.y) * 19349663u ^ uint(p.z) * 83492791u;
    h ^= h >> 16u;
    h *= 2246822519u;
    return h ^ (h >> 13u);
}

vec2 rocketShell(vec3 origin, vec3 direction, float radius) {
    float radialDistance = length(origin);
    float b = dot(origin, direction);
    // Factoring the radius difference keeps precision with a large virtual planet.
    float c = (radialDistance - radius) * (radialDistance + radius);
    float discriminant = b * b - c;
    if (discriminant < 0.0) return vec2(-1.0);
    float root = sqrt(discriminant);
    return vec2(-b - root, -b + root);
}

vec2 rocketAirDensity(vec3 position) {
    float altitude = max(length(position) - 25000.0, 0.0);
    return exp(-altitude / vec2(34.0, 6.0));
}

vec3 rocketScattering(vec3 ray, vec3 sun, out vec3 transmission) {
    const float radius = 25000.0;
    const vec3 betaRayleigh = vec3(0.0018, 0.0042, 0.0102);
    const vec3 betaMie = vec3(0.0012);
    float sceneHeight = rocketSceneHeight();
    vec3 eye = vec3(0.0, radius + sceneHeight, 0.0);
    vec2 shell = rocketShell(eye, ray, radius + 400.0);
    transmission = vec3(1.0);
    if (shell.y <= 0.0) return vec3(0.0);
    float start = max(shell.x, 0.0), finish = shell.y;
    vec2 ground = rocketShell(eye, ray, radius);
    bool groundHit = ground.x > 0.0 && ground.x < finish;
    if (groundHit) finish = ground.x;
    vec2 viewDepth = vec2(0.0);
    vec3 sumRayleigh = vec3(0.0), sumMie = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        float u0 = float(i) / 12.0, u1 = float(i + 1) / 12.0;
        float t0 = mix(start, finish, u0 * u0);
        float t1 = mix(start, finish, u1 * u1);
        float stepLength = t1 - t0;
        vec3 position = eye + ray * (0.5 * (t0 + t1));
        vec2 segmentDepth = rocketAirDensity(position) * stepLength;
        viewDepth += segmentDepth * 0.5;
        vec2 shadow = rocketShell(position, sun, radius);
        if (shadow.x <= 0.0) {
            float lightDistance = max(rocketShell(position, sun, radius + 400.0).y, 0.0);
            vec2 lightDepth = vec2(0.0);
            // Quadratic spacing resolves the dense air close to each sample.
            for (int j = 0; j < 4; j++) {
                float v0 = float(j) / 4.0, v1 = float(j + 1) / 4.0;
                float s0 = lightDistance * v0 * v0;
                float s1 = lightDistance * v1 * v1;
                lightDepth += rocketAirDensity(position + sun * (0.5 * (s0 + s1))) * (s1 - s0);
            }
            vec2 opticalDepth = viewDepth + lightDepth;
            vec3 attenuation = exp(-betaRayleigh * opticalDepth.x - betaMie * opticalDepth.y * 1.1);
            sumRayleigh += attenuation * segmentDepth.x;
            sumMie += attenuation * segmentDepth.y;
        }
        viewDepth += segmentDepth * 0.5;
    }
    transmission = exp(-betaRayleigh * viewDepth.x - betaMie * viewDepth.y * 1.1);
    float mu = clamp(dot(ray, sun), -1.0, 1.0);
    float phaseRayleigh = 3.0 * (1.0 + mu * mu) / (16.0 * 3.14159265);
    const float g = 0.76;
    float phaseMie = (1.0 - g * g) / (4.0 * 3.14159265 * pow(1.0 + g * g - 2.0 * g * mu, 1.5));
    return 12.0 * (betaRayleigh * phaseRayleigh * sumRayleigh + betaMie * phaseMie * sumMie);
}

vec3 rocketAtmosphere(vec3 original, vec3 ray, vec3 sun) {
    #ifndef OVERWORLD
        return original;
    #endif
    if (rocketSpacePass == 0) return original;
    float height = rocketAltitude;
    float ascent = smoothstep(180.0, 1250.0, height);
    if (ascent <= 0.0 || isEyeInWater != 0) return original;
    vec3 transmission;
    vec3 sky = rocketScattering(ray, sun, transmission);
    float sceneHeight = rocketSceneHeight();
    vec3 groundEye = vec3(0.0, 25000.0 + sceneHeight, 0.0);
    vec2 groundShell = rocketShell(groundEye, ray, 25000.0);
    bool hitsGround = ray.y < -0.0001 && groundShell.x > 0.0;
    // Keep native celestial detail below orbit, then use the independent orbital sun.
    float celestial = smoothstep(0.997, 0.9995, abs(dot(ray, sun)));
    float orbitalLight = smoothstep(1240.0, 1400.0, height);
    sky = mix(sky, max(sky, original), celestial * (1.0 - orbitalLight));
    sky += vec3(4.0, 3.8, 3.3) * smoothstep(cos(0.006), cos(0.0038), dot(ray, sun)) * orbitalLight;
    uint starHash = rocketStarHash(ivec3(floor(ray * 650.0)));
    float star = float(starHash % 1100u == 0u && !hitsGround);
    float starVisibility = exp(-dot(sky, vec3(0.2126, 0.7152, 0.0722)) * 18.0);
    vec3 starColor = mix(vec3(0.78, 0.86, 1.0), vec3(1.0, 0.90, 0.76), float(starHash % 37u) / 36.0);
    sky += star * starColor * 0.6 * ascent * starVisibility * transmission;

    if (hitsGround) {
        float distanceToGround = groundShell.x;
        vec2 local = rocketMapInfo.xy + ray.xz * distanceToGround;
        vec2 wideLocal = rocketWorldMapInfo.xy + ray.xz * distanceToGround;
        vec2 wideUV = wideLocal / max(rocketWorldMapInfo.z, 1.0) + 0.5 + 0.5 / 128.0;
        float wideEdge = max(abs(wideUV.x - 0.5), abs(wideUV.y - 0.5));
        vec4 wideTerrain = rocketWorldSample(wideUV);
        float wideKnown = step(0.001, wideTerrain.a) * (1.0 - smoothstep(0.46, 0.5, wideEdge));
        vec3 surfaceColor = mix(vec3(0.14, 0.17, 0.19), wideTerrain.rgb, wideKnown);
        float surfaceHeight = mix(64.0, (wideTerrain.a * 255.0 - 1.0) * 384.0 / 254.0 - 64.0, wideKnown);
        vec2 uv = local / max(rocketMapInfo.z, 1.0) + 0.5 + 0.5 / 256.0;
        float edge = max(abs(uv.x - 0.5), abs(uv.y - 0.5));
        vec4 terrain = texture2D(rocketTerrain, clamp(uv, vec2(0.002), vec2(0.998)));
        float known = step(0.001, terrain.a) * (1.0 - smoothstep(0.28, 0.5, edge));
        // Reproject once using the sampled column height instead of a flat sea plane.
        surfaceHeight = mix(surfaceHeight, (terrain.a * 255.0 - 1.0) * 384.0 / 254.0 - 64.0, known);
        vec2 heightUV = (rocketMapInfo.xy + ray.xz * ((surfaceHeight - height) / ray.y))
                      / max(rocketMapInfo.z, 1.0) + 0.5 + 0.5 / 256.0;
        vec4 elevated = texture2D(rocketTerrain, clamp(heightUV, vec2(0.002), vec2(0.998)));
        terrain.rgb = mix(terrain.rgb, elevated.rgb, step(0.001, elevated.a));
        surfaceColor = mix(surfaceColor, terrain.rgb, known);
        vec2 texel = vec2(1.0 / 128.0, 0.0);
        float leftHeight = rocketWorldSample(wideUV - texel.xy).a;
        float rightHeight = rocketWorldSample(wideUV + texel.xy).a;
        float nearHeight = rocketWorldSample(wideUV - texel.yx).a;
        float farHeight = rocketWorldSample(wideUV + texel.yx).a;
        vec2 slope = vec2(rightHeight - leftHeight, farHeight - nearHeight) * 384.0 / 512.0;
        vec3 normal = normalize(vec3(-slope.x, 1.0, -slope.y));
        vec3 ground = pow(surfaceColor, vec3(2.2)) * (0.025 + 1.25 * max(dot(normal, sun), 0.0));
        ground = ground * transmission + sky;
        float mapBlend = smoothstep(240.0, 640.0, height);
        sky = mix(sky, ground, mapBlend);
    }
    return mix(original, sky, ascent);
}
