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

mat2 rotate(float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return mat2(c, -s, s, c);
}

float distanceField(vec3 p, float t) {
    p.xy *= rotate(t * 0.35);
    p.xz *= rotate(-t * 0.23);
    float twist = sin(p.x * 2.2 + t) * sin(p.y * 2.0 - t * 0.7) * 0.22;
    float sphere = length(p) - (0.82 + Convergence * 0.12);
    float ribs = abs(dot(p, sin(p.zxy * 2.4 + t))) * 0.15;
    return max(sphere, ribs + twist);
}

void main() {
    vec2 uv = texCoord0 * 2.0 - 1.0;
    float t = Time * (0.65 + Speed * 0.28);
    vec3 rayOrigin = vec3(0.0, 0.0, 2.6);
    vec3 rayDirection = normalize(vec3(uv * 1.16, -1.8));
    float depth = 0.0;
    float glow = 0.0;
    for (int i = 0; i < 64; i++) {
        vec3 samplePoint = rayOrigin + rayDirection * depth;
        float distance = distanceField(samplePoint, t);
        glow += 0.012 / (0.025 + abs(distance));
        depth += max(0.018, distance * 0.38);
        if (depth > 4.2) {
            break;
        }
    }
    float mask = smoothstep(1.08, 0.02, length(uv));
    vec3 color = mix(vec3(0.35, 0.68, 1.0), vec3(1.0, 0.38, 0.78), 0.5 + 0.5 * sin(t * 0.3 + State));
    float alpha = clamp(glow * 0.012 + Pulse * 0.06, 0.0, 1.0) * mask * Intensity;
    fragColor = vec4(color * (0.20 + glow * 0.0025) * Intensity, alpha * Opacity);
}
