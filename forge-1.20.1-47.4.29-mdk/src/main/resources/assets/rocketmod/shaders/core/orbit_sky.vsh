#version 150
in vec3 Position;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 SkyViewMat;
out vec3 worldDirection;

void main() {
    vec4 clip = ProjMat * ModelViewMat * SkyViewMat * vec4(Position, 1.0);
    gl_Position = vec4(clip.xy, clip.w * 0.9999, clip.w);
    worldDirection = Position;
}
