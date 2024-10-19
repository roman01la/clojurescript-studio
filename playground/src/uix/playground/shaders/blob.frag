precision highp float;

#pragma include: './shaders/noise_classic_2d'

uniform vec3 u_color_1;
uniform vec3 u_color_2;
uniform vec3 u_color_accent;
uniform vec2 u_resolution;
uniform vec2 u_mouse;
uniform float u_blur;
//uniform float uNoise;
//uniform float uOffsetX;
//uniform float uOffsetY;
//uniform float uLinesAmount;
//uniform float uBackgroundScale;

uniform float u_time;
varying vec2 v_uv;

#define PI 3.14159265359

float uNoise = 0.025;
float uOffsetX = 0.34;
float uOffsetY = 0.3;
float uLinesAmount = 4.0;
float uBackgroundScale = 0.9;


float lines(vec2 uv, float offset){
    float a = abs(0.5 * sin(uv.y * uLinesAmount) + offset * u_blur);
    return smoothstep(0.0, u_blur + offset * u_blur, a);
}

mat2 rotate2d(float angle){
    return mat2(cos(angle), -sin(angle),
    sin(angle), cos(angle));
}

float random(vec2 p) {
    vec2 k1 = vec2(
    23.14069263277926, // e^pi (Gelfond's constant)
    2.665144142690225// 2^sqrt(2) (Gelfond–Schneider constant)
    );
    return fract(
    cos(dot(p, k1)) * 12345.6789
    );
}

vec3 fadeLine(vec2 uv, vec2 mouse2D, vec3 col1, vec3 col2, vec3 col3){
    mouse2D = (mouse2D + 1.0) * 0.5;
    float n1 = cnoise(uv);//(*|/ ) -> scale (+|-) -> offset
    float n2 = cnoise(uv + uOffsetX * 20.0);
    float n3 = cnoise(uv * 0.3 + uOffsetY * 10.0);
    float nFinal = mix(mix(n1, n2, mouse2D.x), n3, mouse2D.y);
    vec2 baseUv = vec2(nFinal + 2.05) * uBackgroundScale;// (+|-) -> frequency (*|/ ) -> lines count

    float basePattern = lines(baseUv, 1.0);
    float secondPattern = lines(baseUv, 0.25);

    vec3 baseColor = mix(col1, col2, basePattern);
    vec3 secondBaseColor = mix(baseColor, col3, secondPattern);
    return secondBaseColor;
}
void main()
{
    vec2 uv = v_uv;
    uv.y += uOffsetY;
    uv.x += uOffsetX;
    uv.x *= u_resolution.x / u_resolution.y;

    vec2 pos = (u_mouse / u_resolution) * vec2(cos(u_time * 0.002), sin(u_time * 0.002));
    vec3 color = fadeLine(uv, pos, u_color_1, u_color_2, u_color_accent);

    vec2 uvRandom = v_uv;
    uvRandom.y *= random(vec2(uvRandom.y, 0.5 * u_time * 0.00000001));
    uvRandom.x *= random(vec2(uvRandom.x, 0.5 * u_time * 0.00000001));
    color.rgb += random(uvRandom) * uNoise;

    gl_FragColor = vec4(color, 1.0);
}
