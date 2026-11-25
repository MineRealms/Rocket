#version 150
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float VisualAltitude;
uniform vec3 EyePosition;
uniform vec3 FaceNormal;
uniform vec3 SunDirection;
in vec2 texCoord;
in vec4 vertexColor;
in vec3 surfacePosition;
out vec4 fragColor;

void main() {
    vec3 sunDirection = normalize(SunDirection);
    vec3 viewDirection = normalize(EyePosition - surfacePosition);
    vec3 albedo = texture(Sampler0, texCoord).rgb;
    vec3 normal = normalize(FaceNormal);
    float solar = dot(normal, sunDirection);
    float light = 0.12 + 0.88 * smoothstep(-0.15, 0.85, solar);
    float water = smoothstep(0.015, 0.09, albedo.b - max(albedo.r, albedo.g));
    vec3 halfDirection = normalize(viewDirection + sunDirection);
    float glint = pow(max(dot(normal, halfDirection), 0.0), 48.0) * water * max(solar, 0.0);
    float limb = pow(1.0 - max(dot(normal, viewDirection), 0.0), 3.0);
    vec3 night = albedo * vec3(0.08, 0.12, 0.20);
    vec3 color = mix(night, albedo * light, smoothstep(-0.2, 0.25, solar));
    color += vec3(0.30, 0.44, 0.58) * limb * 0.22 + vec3(0.60, 0.68, 0.72) * glint * 0.12;
    float exposure = mix(1.0, 0.94, smoothstep(1200.0, 3000.0, VisualAltitude));
    fragColor = vec4(color * exposure, vertexColor.a) * ColorModulator;
}
