#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 SurfacePoseMat;

out vec2 texCoord;
out vec4 vertexColor;
out vec3 surfacePosition;

void main() {
    gl_Position = ProjMat * ModelViewMat * SurfacePoseMat * vec4(Position, 1.0);
    texCoord = UV0;
    vertexColor = Color;
    surfacePosition = Position;
}
