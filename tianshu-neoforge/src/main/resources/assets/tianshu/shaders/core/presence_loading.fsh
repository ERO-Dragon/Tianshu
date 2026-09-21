#version 150

uniform float Time;
uniform float Intensity;
uniform float Speed;
uniform float Opacity;

in vec2 texCoord0;
out vec4 fragColor;

float circle(vec2 p, vec2 center, float radius) {
    return 1.0 - smoothstep(radius * 0.72, radius, length(p - center));
}

void main() {
    vec2 p = texCoord0 * 2.0 - 1.0;
    float spacing = 0.30;
    float value = 0.0;
    for (int i = 0; i < 3; i++) {
        float phase = Time * (2.8 + Speed * 0.5) - float(i) * 0.45;
        float lift = 0.12 * sin(phase);
        value += circle(p, vec2((float(i) - 1.0) * spacing, -lift), 0.16);
    }
    vec3 color = mix(vec3(0.38, 0.88, 1.0), vec3(1.0, 0.48, 0.82), 0.5 + 0.5 * sin(Time * 0.6));
    fragColor = vec4(color * (0.52 + value * 0.68) * Intensity, value * 0.95 * Intensity * Opacity);
}
