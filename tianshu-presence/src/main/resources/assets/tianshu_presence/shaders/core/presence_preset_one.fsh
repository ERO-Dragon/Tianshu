#version 150

// Preset one: the fluid bubble from the reference shader. The whole icon - the iridescent shell, the turbulent
// interior and the bright core - is evaluated per fragment. The HUD uniforms only bias the reference: Speed scales
// time, Convergence shrinks the bubble, Intensity brightens it, State rotates the palette a little, Pulse adds the
// listening ripple and Task adds the background-work dot.
uniform float Time;
uniform float Intensity;
uniform float Speed;
uniform float Convergence;
uniform float Pulse;
uniform float Opacity;
uniform float Morph;
uniform float State;
uniform float Task;

in vec2 texCoord0;
out vec4 fragColor;

const float TAU = 6.28318530718;
const float HANDOFF_DOT = 0.085;

float rand(vec2 n) {
    return fract(sin(dot(n, vec2(12.9898, 4.1414))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 ip = floor(p);
    vec2 u = fract(p);
    u = u * u * (3.0 - 2.0 * u);
    float res = mix(
            mix(rand(ip), rand(ip + vec2(1.0, 0.0)), u.x),
            mix(rand(ip + vec2(0.0, 1.0)), rand(ip + vec2(1.0, 1.0)), u.x),
            u.y);
    return res * res;
}

float fbm(vec2 p, int octaves) {
    float sum = 0.0;
    float weight = 0.0;
    float amplitude = 0.5;
    for (int index = 0; index < 4; index++) {
        if (index >= octaves) {
            break;
        }
        sum += amplitude * noise(p);
        weight += amplitude;
        amplitude *= 0.5;
        p *= 2.0;
    }
    return sum / weight;
}

vec3 palette(float t) {
    return 0.3 + 0.5 * cos(TAU * (vec3(1.0) * t + vec3(0.0, 0.8, 0.8)));
}

float luma(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

vec4 over(vec4 top, vec4 bottom) {
    float alpha = top.a + bottom.a * (1.0 - top.a);
    if (alpha <= 0.0001) {
        return vec4(0.0);
    }
    vec3 rgb = (top.rgb * top.a + bottom.rgb * bottom.a * (1.0 - top.a)) / alpha;
    return vec4(rgb, alpha);
}

vec4 overlay(vec4 base, vec2 q, float t, float morph) {
    if (Pulse > 0.01) {
        float phase = fract(t * 0.30);
        float radius = mix(0.84, 0.99, phase);
        float aa = fwidth(length(q)) + 0.003;
        float ring = 1.0 - smoothstep(0.030, 0.030 + aa, abs(length(q) - radius));
        float strength = (1.0 - phase) * Pulse * 1.4 * (1.0 - morph);
        base = over(vec4(vec3(0.45, 0.92, 1.0), ring * strength * Opacity), base);
    }
    if (Task > 0.5) {
        float blink = 0.55 + 0.45 * sin(t * 6.0);
        float aa = fwidth(length(q)) + 0.002;
        vec2 offset = vec2(0.66, -0.66);
        float halo = 1.0 - smoothstep(0.115, 0.115 + aa, length(q - offset));
        float dot = 1.0 - smoothstep(0.085, 0.085 + aa, length(q - offset));
        base = over(vec4(vec3(0.02, 0.04, 0.06), 0.35 * halo * Opacity), base);
        base = over(vec4(vec3(1.0), (0.85 + 0.15 * blink) * dot * Opacity), base);
    }
    return base;
}

void main() {
    vec2 q = texCoord0 * 2.0 - 1.0;
    float t = Time * (0.65 + Speed * 0.35);
    vec2 uv = q * (1.26 + Convergence * 0.08);
    float l = dot(uv, uv);
    if (l > 2.5) {
        fragColor = vec4(0.0);
        return;
    }

    float sm = smoothstep(1.02, 0.98, l);
    float depth = sm * l * l * l * 2.0;
    vec3 normal = normalize(vec3(uv.x, uv.y, 0.7 - depth));
    float nx = fbm(uv * 2.0 + t * 0.4 + 25.69, 4);
    float ny = fbm(uv * 2.0 + t * 0.4 + 86.31, 4);
    float n = fbm(uv * 3.0 + 2.0 * vec2(nx, ny), 3);

    vec3 color = vec3(n * 0.5 + 0.25);
    float angle = atan(uv.y, uv.x) / TAU + t * 0.1 + State * 0.02;
    color *= palette(angle);
    color *= 2.0;

    vec3 tint = abs(color);
    vec3 lit = color * depth;
    lit += (lit * 0.5 + vec3(1.0) - luma(lit))
            * vec3(max(0.0, pow(dot(normal, vec3(0.0, 0.0, -1.0)), 5.0) * 3.0));
    lit += 1.5 * smoothstep(0.6, 1.0, fbm(normal.xy * 3.0 / (1.0 + normal.z), 2)) * depth;
    color = lit + color * pow(
            (1.0 - smoothstep(1.0, 0.98, l) - pow(max(0.0, length(uv) - 1.0), 0.2)) * 2.0,
            4.0);

    float shell = fbm(normalize(uv) * 2.0 + t, 2) + 0.1;
    vec2 inner = uv * (shell + 0.1) * 0.5;
    vec3 sparkle = normalize(tint + 0.0001) + 0.1;
    float sparkleDepth = 0.2 + pow(smoothstep(0.0, 1.5, sqrt(dot(inner, inner))) * 48.0, 0.25);
    sparkleDepth *= sparkleDepth * sparkleDepth * sparkleDepth;
    sparkleDepth = 1.0 / sparkleDepth;
    sparkle *= sparkleDepth;
    color += sparkle * sparkle * sm * smoothstep(0.7, 1.0, sparkleDepth);
    color += abs(normal) * (1.0 - depth) * sm * 0.25;
    color = max(color, vec3(0.0));

    float gain = 0.6 + 0.9 * Intensity;
    float morph = clamp(Morph, 0.0, 1.0);
    float revealRadius = mix(1.20, HANDOFF_DOT, morph);
    float discRadius = mix(0.80, HANDOFF_DOT, morph);
    float soft = 0.02 + 0.14 * (1.0 - morph);
    float reveal = 1.0 - smoothstep(revealRadius - soft, revealRadius + soft, length(q));
    float alpha = clamp(luma(color) * gain * 1.35, 0.0, 1.0)
            * (1.0 - smoothstep(1.35, 1.75, length(uv))) * reveal;
    vec4 result = vec4(color * gain * (1.0 + 0.08 * Pulse), alpha * Opacity);

    float discEdge = 0.012 + 0.02 * morph;
    float discMask = 1.0 - smoothstep(discRadius - discEdge, discRadius + discEdge, length(q));
    float birth = smoothstep(0.0, 0.35, morph);
    vec3 birthColor = mix(vec3(0.55, 0.86, 1.0), vec3(1.0), morph);
    result = over(vec4(birthColor, discMask * birth * Opacity), result);
    fragColor = overlay(result, q, t, morph);
}
