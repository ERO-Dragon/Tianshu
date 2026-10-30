#version 150

// Preset two: the volumetric "Siri" orb from the reference raymarch. The reference's 200-step march is kept, with a
// far-distance early-out only. Speed scales time, Convergence changes the field of view, Intensity brightens the
// accumulation, State shifts the colour phase, Pulse adds the listening ripple and Task adds the background-work dot.
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

const int STEPS = 200;
const float HANDOFF_DOT = 0.085;

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
    float t = Time * (0.7 + Speed * 0.3) + State * 0.35;
    float field = 1.0 + Convergence * 0.12;
    vec3 direction = normalize(vec3(q * field, -1.0));

    vec3 point = vec3(0.0);
    vec3 axis = vec3(0.0);
    float distance = 0.0;
    float stepSize = 0.0;
    float sphere = 0.0;
    vec3 accumulated = vec3(0.0);

    for (int index = 0; index < STEPS; index++) {
        point = direction * distance;
        point.z += 9.0;

        axis = normalize(cos(vec3(0.0, 2.0, 4.0) - t * 0.5 + sphere * 0.3));
        point = dot(axis, point) * axis - cross(axis, point);
        sphere = length(point);

        float center = sphere - 5.0;
        float swirl = abs(dot(point, sin(point).yzx)) * 0.2 + max(center, 0.1);
        float retreat = abs(center - 1.0) + 0.2;
        stepSize = min(swirl, retreat) * 0.2;
        distance += stepSize;

        vec3 glow = max(cos(point.x * 0.6 + vec3(0.0, 2.0, 4.0)), vec3(5.0 / (sphere * sphere)));
        accumulated += glow / max(stepSize * stepSize, 0.00001);
        if (distance > 60.0) {
            break;
        }
    }

    vec3 color = tanh(accumulated * (0.8 + 0.5 * Intensity) / 30000.0);
    float morph = clamp(Morph, 0.0, 1.0);
    float revealRadius = mix(1.45, HANDOFF_DOT, morph);
    float discRadius = mix(0.92, HANDOFF_DOT, morph);
    float soft = 0.02 + 0.14 * (1.0 - morph);
    float reveal = 1.0 - smoothstep(revealRadius - soft, revealRadius + soft, length(q));
    float alpha = clamp(dot(color, vec3(0.299, 0.587, 0.114)) * 2.2, 0.0, 1.0)
            * (1.0 - smoothstep(1.15, 1.45, length(q))) * reveal;
    vec4 result = vec4(color * (1.0 + 0.08 * Pulse), alpha * Opacity);

    float discEdge = 0.012 + 0.02 * morph;
    float discMask = 1.0 - smoothstep(discRadius - discEdge, discRadius + discEdge, length(q));
    float birth = smoothstep(0.0, 0.35, morph);
    vec3 birthColor = mix(vec3(0.72, 0.62, 1.0), vec3(1.0), morph);
    result = over(vec4(birthColor, discMask * birth * Opacity), result);
    fragColor = overlay(result, q, t, morph);
}
