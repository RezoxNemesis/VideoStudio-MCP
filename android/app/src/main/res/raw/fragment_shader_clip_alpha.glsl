#version 100
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uAlphaScale;
uniform float uKeyEnabled;
uniform vec3 uKeyColor;
uniform float uKeyTolerance;
uniform float uKeySoftness;
uniform float uSpill;
uniform float uMaskType;
uniform vec2 uMaskCenter;
uniform vec2 uMaskSize;
uniform float uMaskFeather;
uniform float uMaskRadius;
uniform float uMaskInvert;
uniform float uHdr;
varying vec2 vTexSamplingCoord;
void main() {
    vec4 color = texture2D(uTexSampler,vTexSamplingCoord);
    float alpha = color.a * uAlphaScale;
    if(uKeyEnabled > 0.5) {
        vec3 linear = color.rgb;
        if(uHdr > 0.5) linear = mat3(1.6605,-0.1246,-0.0182, -0.5876,1.1329,-0.1006, -0.0728,-0.0083,1.1187) * linear;
        linear = max(linear,vec3(0.0));
        vec3 srgb = mix(12.92 * linear,1.055 * pow(linear,vec3(1.0/2.4)) - 0.055,step(vec3(0.0031308),linear));
        float difference = distance(srgb,uKeyColor) / 1.7320508;
        float keep = smoothstep(uKeyTolerance,uKeyTolerance + max(0.001,uKeySoftness),difference);
        alpha *= keep;
        float spill = (1.0-keep)*uSpill;
        if(uKeyColor.g > uKeyColor.r && uKeyColor.g > uKeyColor.b) color.g = mix(color.g,min(color.g,max(color.r,color.b)),spill);
        else if(uKeyColor.b > uKeyColor.r) color.b = mix(color.b,min(color.b,max(color.r,color.g)),spill);
        else color.r = mix(color.r,min(color.r,max(color.g,color.b)),spill);
    }
    if(uMaskType > 0.5) {
        vec2 uv = vec2(vTexSamplingCoord.x,1.0-vTexSamplingCoord.y);
        vec2 halfSize = max(uMaskSize*0.5,vec2(0.005));
        vec2 local = uv-uMaskCenter;
        float distanceToEdge;
        if(uMaskType > 1.5) distanceToEdge = (length(local/halfSize)-1.0)*min(halfSize.x,halfSize.y);
        else {
            float radius = min(uMaskRadius,min(halfSize.x,halfSize.y));
            vec2 q = abs(local)-halfSize+vec2(radius);
            distanceToEdge = length(max(q,vec2(0.0)))+min(max(q.x,q.y),0.0)-radius;
        }
        float feather = max(0.0001,uMaskFeather*0.5);
        float mask = 1.0-smoothstep(-feather,feather,distanceToEdge);
        if(uMaskInvert > 0.5) mask = 1.0-mask;
        alpha *= mask;
    }
    gl_FragColor = vec4(color.rgb,alpha);
}
