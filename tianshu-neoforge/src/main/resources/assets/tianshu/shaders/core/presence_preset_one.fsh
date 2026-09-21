#version 150

uniform float Time;
uniform float Intensity;
uniform float Speed;
uniform float Convergence;
uniform float Pulse;
uniform float Opacity;
uniform float State;

in vec2 texCoord0;
out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

void main() {
    vec2 p = texCoord0 * 2.0 - 1.0;
    float t = Time * (0.7 + Speed * 0.3);
    float r = length(p);
    float a = atan(p.y, p.x);
    float n = noise(p * 4.0 + vec2(t * 0.6, -t * 0.35));
    float waves = 0.5 + 0.5 * sin(a * 7.0 - t * 2.0 + n * 3.0);
    float surface = 1.0 - smoothstep(0.36 + 0.04 * waves, 0.60 + 0.05 * waves, r);
    float edge = 1.0 - smoothstep(0.42, 0.84, r);
    float core = smoothstep(0.56, 0.10 + Convergence * 0.12, r);
    float pulse = 1.0 + 0.10 * sin(t * 1.4) + Pulse * 0.16;
    vec3 warm = vec3(1.0, 0.42, 0.80);
    vec3 cool = vec3(0.28, 0.88, 1.0);
    vec3 color = mix(cool, warm, 0.5 + 0.5 * sin(a * 0.8 + t * 0.4 + State));
    float alpha = max(surface * 0.88, edge * 0.24) * Intensity;
    fragColor = vec4(color * (0.56 + core * 0.68) * pulse, alpha * Opacity);
}
