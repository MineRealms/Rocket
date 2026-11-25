#version 150
uniform float AirHeight;
uniform vec3 SunDirection;
uniform float OrbitFade;
uniform float RainWeight;
uniform sampler2D Sampler0;
uniform float MapWidth;
uniform float MapTexel;
uniform vec2 MapOffset;
uniform float NearAltitude;
uniform float TerrainBlend;
uniform vec2 CloudOffset;
in vec3 worldDirection;
out vec4 fragColor;

const float PI = 3.14159265359;
const float EARTH = 6360.0;
const float TOP = 6480.0;
const vec3 BETA_R = vec3(0.0058, 0.0135, 0.0331);
const vec3 BETA_M = vec3(0.0030);

vec2 sphereHit(vec3 origin, vec3 direction, float radius) {
    float b = dot(origin, direction);
    float d = b * b - dot(origin, origin) + radius * radius;
    if (d < 0.0) return vec2(1.0, -1.0);
    float root = sqrt(d);
    return vec2(-b - root, -b + root);
}

vec2 density(vec3 position) {
    float altitude = max(length(position) - EARTH, 0.0);
    return exp(-altitude / vec2(8.0, 1.2));
}

vec3 scatter(vec3 origin, vec3 view, vec3 sun) {
    vec2 hit = sphereHit(origin, view, TOP);
    float start = max(0.0, hit.x), end = hit.y;
    vec2 ground = sphereHit(origin, view, EARTH);
    if (ground.x > 0.0) end = min(end, ground.x);
    if (end <= start) return vec3(0.0);
    float stepLength = (end - start) / 12.0;
    vec2 viewDepth = vec2(0.0);
    vec3 sumR = vec3(0.0), sumM = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        vec3 position = origin + view * (start + (float(i) + 0.5) * stepLength);
        vec2 local = density(position) * stepLength;
        viewDepth += local * 0.5;
        float sunLength = max(sphereHit(position, sun, TOP).y, 0.0);
        vec2 sunDepth = vec2(0.0);
        float sunlight = 1.0;
        vec2 shadow = sphereHit(position, sun, EARTH);
        if (shadow.x > 0.0) sunlight = 0.0;
        for (int j = 0; j < 4; j++) {
            vec3 lightPosition = position + sun * (float(j) + 0.5) * (sunLength / 4.0);
            sunDepth += density(lightPosition) * (sunLength / 4.0);
        }
        vec3 extinction = exp(-(BETA_R * (viewDepth.x + sunDepth.x) + BETA_M * (viewDepth.y + sunDepth.y)));
        sumR += local.x * extinction * sunlight;
        sumM += local.y * extinction * sunlight;
        viewDepth += local * 0.5;
    }
    float mu = dot(view, sun), g = 0.76;
    float phaseR = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float phaseM = 3.0 / (8.0 * PI) * (1.0 - g * g) * (1.0 + mu * mu)
        / ((2.0 + g * g) * pow(max(1.0 + g * g - 2.0 * g * mu, 0.001), 1.5));
    vec3 color = 18.0 * (sumR * BETA_R * phaseR + sumM * BETA_M * phaseM);
    float groundAir = exp(-AirHeight / 13.0);
    float day = smoothstep(-0.2, 0.12, sun.y);
    color += vec3(0.012, 0.025, 0.052) * groundAir * day;
    return color;
}

float starField(vec3 direction) {
    vec3 grid = direction * 300.0;
    vec3 cell = floor(grid);
    float random = fract(sin(dot(cell, vec3(127.1, 311.7, 74.7))) * 43758.5453);
    float point = 1.0 - smoothstep(0.04, 0.18, length(fract(grid) - 0.5));
    return step(0.997, random) * point;
}

float knownAt(vec2 uv) {
    return smoothstep(0.0, 0.02, texture(Sampler0, clamp(uv, 0.0, 1.0)).a);
}

float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(vec2 p) {
    vec2 cell = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(cell), hash21(cell + vec2(1, 0)), f.x),
               mix(hash21(cell + vec2(0, 1)), hash21(cell + vec2(1, 1)), f.x), f.y);
}

float cloudDensity(vec2 p) {
    p = floor(p / 8.0) * 8.0 / 900.0;
    float value = 0.0, weight = 0.55;
    for (int i = 0; i < 4; i++) {
        value += noise(p) * weight;
        p = p * 2.03 + vec2(17.1, 31.7);
        weight *= 0.5;
    }
    return smoothstep(0.46, 0.70, value);
}

vec3 groundScene(vec3 view, vec3 sun) {
    float distanceToGround = NearAltitude / max(-view.y, 0.00001);
    vec2 point = view.xz * distanceToGround;
    vec2 uv = (point + MapOffset) / MapWidth + 0.5;
    vec4 center = texture(Sampler0, clamp(uv, 0.0, 1.0));
    float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
    float known = knownAt(uv) * smoothstep(0.0, 0.12, edge);
    float coverage = 0.0;
    for (int i = 1; i <= 6; i++) {
        vec2 offset = vec2(MapTexel * float(i) * 2.0, 0.0);
        coverage += knownAt(uv + offset) + knownAt(uv - offset);
        coverage += knownAt(uv + offset.yx) + knownAt(uv - offset.yx);
    }
    known *= smoothstep(0.55, 1.0, coverage / 24.0);
    vec2 dx = vec2(MapTexel, 0.0);
    float west = texture(Sampler0, clamp(uv - dx, 0.0, 1.0)).a;
    float east = texture(Sampler0, clamp(uv + dx, 0.0, 1.0)).a;
    float north = texture(Sampler0, clamp(uv - dx.yx, 0.0, 1.0)).a;
    float south = texture(Sampler0, clamp(uv + dx.yx, 0.0, 1.0)).a;
    vec3 normal = normalize(vec3((west - east) * 384.0, 2.0 * MapWidth * MapTexel, (north - south) * 384.0));
    float illumination = 0.34 + 0.66 * max(dot(normal, sun), 0.0);
    float day = smoothstep(-0.18, 0.12, sun.y);
    vec3 hazeColor = mix(vec3(0.006, 0.009, 0.017), vec3(0.19, 0.30, 0.40), day);
    vec3 land = mix(vec3(dot(center.rgb, vec3(0.21, 0.72, 0.07))), center.rgb, 0.72) * illumination;
    land *= mix(0.07, 1.0, day);
    float haze = 1.0 - exp(-distanceToGround / 2600.0);
    vec3 ground = mix(mix(hazeColor * 0.80, land, known), hazeColor, haze * 0.80);
    if (NearAltitude > 96.0) {
        vec2 cloudPoint = view.xz * ((NearAltitude - 64.0) / max(-view.y, 0.00001)) + CloudOffset;
        float clouds = cloudDensity(cloudPoint);
        float sunward = cloudDensity(cloudPoint + sun.xz * 100.0);
        float shadow = clamp(0.5 + (clouds - sunward) * 0.5, 0.25, 0.85);
        vec3 cloudLight = mix(vec3(0.018, 0.025, 0.04), vec3(0.74, 0.80, 0.85), day) * (0.68 + shadow * 0.32);
        float cloudHaze = 1.0 - exp(-distanceToGround / 12000.0);
        cloudLight = mix(cloudLight, hazeColor, cloudHaze * 0.7);
        float alpha = clouds * 0.82 * smoothstep(96.0, 260.0, NearAltitude);
        ground = mix(ground, cloudLight, alpha);
    }
    return ground;
}

void main() {
    vec3 view = normalize(worldDirection);
    vec3 sun = normalize(SunDirection);
    vec3 origin = vec3(0.0, EARTH + AirHeight, 0.0);
    vec3 color = scatter(origin, view, sun) * (1.0 - OrbitFade);
    float daytime = smoothstep(-0.12, 0.12, sun.y);
    float lowHaze = exp(-AirHeight / 20.0) * (1.0 - smoothstep(-0.15, 0.05, view.y));
    color += vec3(0.10, 0.15, 0.20) * lowHaze * daytime;
    float sunDisc = smoothstep(cos(0.006), cos(0.0038), dot(view, sun));
    color += vec3(5.5, 4.9, 3.9) * sunDisc * smoothstep(-0.2, 0.0, sun.y);
    float nightStars = mix(0.75, 0.04, daytime) * mix(1.0, smoothstep(15.0, 75.0, AirHeight), daytime);
    color += vec3(0.85, 0.90, 1.0) * starField(view) * nightStars;
    color = mix(color, vec3(dot(color, vec3(0.21, 0.72, 0.07))) * 0.72, RainWeight * exp(-AirHeight / 6.0));
    color = vec3(1.0) - exp(-color * 1.4);
    color = pow(max(color, vec3(0.0)), vec3(1.0 / 2.2));
    if (view.y < 0.0 && TerrainBlend > 0.0 && OrbitFade < 1.0) {
        float groundWeight = TerrainBlend * (1.0 - OrbitFade) * (1.0 - smoothstep(-0.025, 0.0, view.y));
        color = mix(color, groundScene(view, sun), groundWeight);
    }
    fragColor = vec4(color, 1.0);
}
