// Materials: skin, eyes, hair, cloth, leather, metal — physically based three.js materials with procedural detail patched in.
import { THREE, GLSL_NOISE } from './core.js';

/** uniforms shared by every material; shots set the key light so hair/eyes can add their own highlights */
const C = (a) => new THREE.Color().setRGB(a[0], a[1], a[2], THREE.SRGBColorSpace);
export const GU = { uTime: { value: 0 }, uKeyDir: { value: new THREE.Vector3(0.4, 0.8, 0.5) }, uKeyCol: { value: new THREE.Color(1, 1, 1) }, uWet: { value: 0 },
  // anime look: 0 = physically based, 1 = cel shading (two flat tones split where the light falls below uToonAt)
  uToon: { value: 0 }, uToonAt: { value: 0.42 }, uToonSoft: { value: 0.03 }, uToonSun: { value: new THREE.Color(4, 4, 4) } };

const BUMP = /* glsl */`
vec3 bumpN(vec3 n, vec3 vp, float h, float scale){ vec3 dx = dFdx(vp), dy = dFdy(vp); float hx = dFdx(h), hy = dFdy(h); vec3 r1 = cross(dy, n), r2 = cross(n, dx); float det = dot(dx, r1); if(abs(det) < 1e-14) return n; vec3 g = sign(det)*(hx*r1 + hy*r2); vec3 r = abs(det)*n - scale*g; float l = length(r); return l > 1e-14 ? r/l : n; }
float pxSize(vec3 p){ return length(fwidth(p)); }
`;
let patchId = 0;
/** inject code into a standard/physical material. o: {uniforms, attrs:['aux','mask'], decl, color, rough, normal, emissive, alpha, irr} */
export function patch(mat, o) {
  const id = 'p' + (patchId++);
  mat.customProgramCacheKey = () => id + (o.key || '');
  mat.onBeforeCompile = (sh) => {
    Object.assign(sh.uniforms, GU, o.uniforms || {});
    const a = o.attrs || ['aux'];
    const vdecl = (a.includes('aux') ? 'attribute vec3 aux; varying vec3 vAux;\n' : '') + (a.includes('mask') ? 'attribute vec4 mask; varying vec4 vMask;\n' : '') + 'varying vec3 vWPos;\n' + (o.vdecl || '');
    const vmain = (a.includes('aux') ? 'vAux = aux;\n' : '') + (a.includes('mask') ? 'vMask = mask;\n' : '') + (o.vmain || '');
    sh.vertexShader = sh.vertexShader.replace('#include <common>', '#include <common>\n' + vdecl).replace('#include <begin_vertex>', '#include <begin_vertex>\n' + vmain).replace('#include <beginnormal_vertex>', '#include <beginnormal_vertex>\n' + (o.vnormal || ''))
      .replace('#include <project_vertex>', '#include <project_vertex>\n vWPos = (modelMatrix * vec4(transformed, 1.0)).xyz;\n');
    const fdecl = (a.includes('aux') ? 'varying vec3 vAux;\n' : '') + (a.includes('mask') ? 'varying vec4 vMask;\n' : '') + 'varying vec3 vWPos;\nuniform float uTime; uniform vec3 uKeyDir; uniform vec3 uKeyCol; uniform float uWet; uniform float uToon, uToonAt, uToonSoft; uniform vec3 uToonSun;\n' + GLSL_NOISE + BUMP + (o.decl || '');
    let f = sh.fragmentShader.replace('#include <common>', '#include <common>\n' + fdecl);
    if (o.alpha) f = f.replace('#include <clipping_planes_fragment>', '#include <clipping_planes_fragment>\n' + o.alpha);
    if (o.color) f = f.replace('#include <color_fragment>', '#include <color_fragment>\n' + o.color);
    if (o.rough) f = f.replace('#include <metalnessmap_fragment>', '#include <metalnessmap_fragment>\n' + o.rough);
    if (o.normal) f = f.replace('#include <normal_fragment_maps>', '#include <normal_fragment_maps>\n' + o.normal);
    if (o.emissive) f = f.replace('#include <emissivemap_fragment>', '#include <emissivemap_fragment>\n' + o.emissive);
    if (o.irr) {   // subsurface look: only the diffuse term wraps around the terminator (the specular term must stay physical)
      const chunk = THREE.ShaderChunk.lights_physical_pars_fragment; const from = 'reflectedLight.directDiffuse += irradiance * diffuseBRDF * ( 1.0 - F );';
      if (!chunk.includes(from)) console.error('skin patch: lighting chunk changed');
      f = f.replace('#include <lights_physical_pars_fragment>', o.irr + chunk.replace(from, 'reflectedLight.directDiffuse += customIrr( dot( geometryNormal, directLight.direction ) ) * directLight.color * diffuseBRDF * ( 1.0 - F );'));
    }
    // cel shading: the light that reaches the surface (shadows included) is cut into two flat tones; highlights are thinned out
    f = f.replace('#include <lights_fragment_end>', `#include <lights_fragment_end>
      if (uToon > 0.) { vec3 tb = material.diffuseColor; float tl = max(dot(tb, vec3(.2126, .7152, .0722)), 1e-3); float sunL = max(dot(uToonSun, vec3(.2126, .7152, .0722)), 1e-3);
        float litN = dot(reflectedLight.directDiffuse, vec3(.2126, .7152, .0722)) / tl * PI / sunL;
        float band = smoothstep(uToonAt - uToonSoft, uToonAt + uToonSoft, litN);
        reflectedLight.directDiffuse = mix(reflectedLight.directDiffuse, tb * RECIPROCAL_PI * uToonSun * band * 0.82, uToon);
        float al = dot(reflectedLight.indirectDiffuse, vec3(.2126, .7152, .0722)) / tl;
        reflectedLight.indirectDiffuse = mix(reflectedLight.indirectDiffuse, tb * mix(vec3(al), reflectedLight.indirectDiffuse / tl, 0.5) * 1.15, uToon);
        reflectedLight.directSpecular *= mix(1., 0.12, uToon); reflectedLight.indirectSpecular *= mix(1., 0.1, uToon); }`);
    // characters and props mark themselves in alpha so ink lines are drawn on them and not on the painted set
    if (!mat.alphaToCoverage && !mat.transparent) f = f.replace('#include <dithering_fragment>', '#include <dithering_fragment>\n gl_FragColor.a = 0.5;');
    sh.fragmentShader = f;
  };
  return mat;
}

/* ---------- skin ---------- */
export function skinMaterial(look, lm) {
  const k = lm.eyeL[0] / 0.0319;   // head scale relative to the adult the brow curve was drawn on
  const u = {
    uSkin: { value: C(look.skin) }, uLip: { value: C(look.lip || [look.skin[0] * 0.78, look.skin[1] * 0.50, look.skin[2] * 0.50]) },
    uBrowCol: { value: C(look.browColor || look.hair || [0.05, 0.04, 0.04]) }, uBrow: { value: look.brow ?? 1 }, uBeard: { value: look.beard ?? 0 }, uBeardCol: { value: C(look.beardColor || look.hair || [0.1, 0.1, 0.1]) },
    uEye: { value: new THREE.Vector3(...lm.eyeL) }, uK: { value: k }, uMouth: { value: new THREE.Vector3(...lm.mouth) }, uDirt: { value: look.dirt ?? 0 }, uPale: { value: look.pale ?? 0 }, uAge: { value: look.age ?? 0 }, uTears: { value: 0 }, uBlush: { value: look.blush ?? 0.35 },
    uScar: { value: new THREE.Vector4(...(look.scar || [0, 0, 0, 0])) },
  };
  const m = new THREE.MeshPhysicalMaterial({ color: 0xffffff, roughness: 0.52, metalness: 0, specularIntensity: 0.4, ior: 1.4 });
  m.userData.u = u;
  return patch(m, {
    attrs: ['aux', 'mask'], uniforms: u, key: 'skin',
    decl: /* glsl */`uniform vec3 uSkin, uLip, uBrowCol, uBeardCol, uEye, uMouth; uniform float uBrow, uBeard, uK, uDirt, uPale, uAge, uTears, uBlush; uniform vec4 uScar;
      float sdSeg(vec2 p, vec2 a, vec2 b){ vec2 pa = p-a, ba = b-a; float h = clamp(dot(pa,ba)/dot(ba,ba), 0., 1.); return length(pa-ba*h); }
      float browMask(vec3 r){ vec2 p = vec2(abs(r.x), r.y) - vec2(0., uEye.y); p /= uK; if(r.z < uEye.z - 0.035*uK) return 0.;
        vec2 a = vec2(0.0105, 0.0185), b = vec2(0.041, 0.0235), c = vec2(0.0595, 0.0175);
        float d1 = sdSeg(p, a, b), d2 = sdSeg(p, b, c); float t = clamp((p.x-a.x)/(c.x-a.x), 0., 1.); float th = mix(0.0062, 0.0022, t*t);
        float d = min(d1, d2); float n = vnoise3(vec3(p.x*260., p.y*1500. - p.x*520., 3.7)); float n2 = vnoise3(vec3(p.x*900., p.y*900., 1.3));
        return smoothstep(th, th*0.25, d + (n-0.5)*0.0018) * (0.72 + 0.28*n2); }
      vec3 customIrr(float ndl){ vec3 w = vec3(0.34, 0.15, 0.09); vec3 v = clamp((vec3(ndl) + w)/(1. + w), 0., 1.); return v*v*(3. - 2.*v)*0.35 + v*0.65; }`,
    irr: '\n',
    color: /* glsl */`
      { vec3 r = vAux; vec3 sk = uSkin;
        float mott = fbm3(r*55.); float fine = vnoise3(r*900.);
        sk *= 0.93 + 0.14*mott; sk = mix(sk, sk*vec3(1.08, 0.93, 0.90), smoothstep(0.45, 0.8, fbm3(r*23. + 9.)));
        sk = mix(sk, sk*vec3(1.10, 0.80, 0.78), vMask.y*uBlush);                       // cheeks
        sk = mix(sk, sk*vec3(0.80, 0.70, 0.74), vMask.z*0.55);                         // eye sockets, lids
        float lipm = smoothstep(0.30, 0.62, vMask.x); sk = mix(sk, uLip*(0.9 + 0.2*fine), lipm);
        float inner = (1. - smoothstep(0.55, 1.0, length((r - (uMouth - vec3(0., 0.004, 0.036)*uK))/(vec3(0.024, 0.016, 0.028)*uK)))) * smoothstep(-0.006, -0.014, r.z - uMouth.z); sk = mix(sk, vec3(0.10, 0.02, 0.025), inner);
        float st = vMask.w*uBeard; float stn = smoothstep(0.35, 0.75, vnoise3(r*2600.)); sk = mix(sk, mix(sk*0.72 + uBeardCol*0.25, uBeardCol, stn*0.55), st*0.8);
        float bm = browMask(r)*uBrow; sk = mix(sk, uBrowCol, bm*0.93);
        sk = mix(sk, sk*vec3(0.80, 0.72, 0.66), uAge*0.6*smoothstep(0.56, 0.72, fbm3(r*90.)));   // age spots
        sk = mix(sk, vec3(dot(sk, vec3(0.33)))*vec3(1.04, 1.0, 0.99), uPale*(1.0 - 0.55*smoothstep(0.30, 0.62, vMask.x)));
        if(uScar.w > 0.){ float sd = sdSeg(vec2(r.x, r.y), uScar.xy, uScar.xy + vec2(0.012, -0.035)*uK); sk = mix(sk, sk*vec3(1.15, 0.78, 0.78), smoothstep(0.0022, 0.0008, sd + (fine-0.5)*0.001)*uScar.w); }
        float dn = fbm3(r*38. + 4.); sk = mix(sk, vec3(0.05, 0.04, 0.035), uDirt*smoothstep(0.42, 0.7, dn)*0.85);
        diffuseColor.rgb = sk; }`,
    rough: /* glsl */`{ float lipm = smoothstep(0.30, 0.62, vMask.x); roughnessFactor = mix(roughnessFactor, 0.46, lipm); roughnessFactor = mix(roughnessFactor, 0.22, max(uWet, uTears*smoothstep(0.2,0.6,vMask.y+vMask.z))); roughnessFactor += (vnoise3(vAux*400.)-0.5)*0.08; roughnessFactor = max(roughnessFactor, 0.26); }`,
    normal: /* glsl */`{ float px = pxSize(vAux); float fade = smoothstep(0.0022, 0.0006, px);
        float hgt = vnoise3(vAux*1300.); hgt += smoothstep(0.3, 0.6, vMask.x)*sin(vAux.x*2600.)*0.35;
        hgt += uAge*fbm3(vec3(vAux.x*90., vAux.y*380., vAux.z*90.))*1.6;
        float graze = smoothstep(0.15, 0.5, abs(dot(normal, normalize(vViewPosition)))); normal = bumpN(normal, -vViewPosition, hgt, 0.00011*fade*graze); }`,
  });
}

/* ---------- eyes ---------- */
export function eyeMaterial(look) {
  const u = { uIris: { value: C(look.eye || [0.25, 0.14, 0.07]) }, uGlow: { value: look.eyeGlow ?? 0 }, uPupil: { value: 0.13 }, uCatch: { value: 1 } };
  const m = new THREE.MeshPhysicalMaterial({ color: 0xffffff, roughness: 0.08, metalness: 0, clearcoat: 1, clearcoatRoughness: 0.03, specularIntensity: 1 });
  m.userData.u = u;
  return patch(m, {
    uniforms: u, key: 'eye',
    decl: `uniform vec3 uIris; uniform float uGlow, uPupil, uCatch; varying vec3 vVN;`, vdecl: 'varying vec3 vVN;', vmain: 'vVN = normalize(normalMatrix * objectNormal);',
    color: /* glsl */`{ vec3 d = normalize(vAux); float ang = acos(clamp(d.z, -1., 1.)); float th = atan(d.y, d.x); const float RI = 0.40;
        vec3 scl = vec3(0.60, 0.585, 0.57) * (1. - 0.25*smoothstep(0.5, 1.3, ang)) * (0.55 + 0.45*smoothstep(0.55, -0.35, d.y)); scl = mix(scl, vec3(0.85, 0.55, 0.50), 0.25*smoothstep(0.6, 1.4, ang)*fbm3(vec3(th*6., ang*9., 2.)));
        float fib = fbm3(vec3(th*14., ang*30., 5.)); float fib2 = vnoise3(vec3(th*40., ang*8., 9.));
        vec3 ir = uIris*(0.45 + 0.9*fib + 0.35*fib2)*0.6; ir = mix(ir, uIris*1.7 + 0.08, smoothstep(uPupil*2.2, uPupil, ang)*0.55);       // collarette
        ir *= 1. - 0.75*smoothstep(RI*0.78, RI, ang);                                                                                 // limbal ring
        vec3 c = mix(ir, scl, smoothstep(RI*0.96, RI*1.06, ang)); c = mix(vec3(0.004), c, smoothstep(uPupil*0.92, uPupil*1.12, ang));
        diffuseColor.rgb = c; }`,
    emissive: /* glsl */`{ vec3 d = normalize(vAux); float ang = acos(clamp(d.z, -1., 1.)); float irisM = smoothstep(0.42, 0.36, ang)*smoothstep(uPupil, uPupil*1.3, ang);
        totalEmissiveRadiance += uIris*irisM*uGlow;
        vec3 vn = normalize(vVN); float c1 = pow(max(dot(vn, normalize(vec3(-0.30, 0.42, 0.86))), 0.), 900.), c2 = pow(max(dot(vn, normalize(vec3(0.28, -0.16, 0.94))), 0.), 500.);
        totalEmissiveRadiance += (vec3(1.6)*c1 + vec3(0.25)*c2)*uCatch*smoothstep(0.75, 0.3, ang); }`,
  });
}

/* ---------- hair ---------- */
const RAGQ = 1;
export function hairMaterial(look, opts = {}) {
  const u = { uHair: { value: C(look.hair || [0.04, 0.03, 0.03]) }, uGrey: { value: look.grey ?? 0 }, uRag: { value: (opts.rag ?? 1) * RAGQ }, uFlow: { value: opts.flow ?? 0 }, uHC: { value: new THREE.Vector3(...(opts.center || [0, 1.7, 0.03])) }, uFine: { value: opts.fine ? 1 : 0 } };
  const m = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.78, metalness: 0, side: THREE.DoubleSide, alphaToCoverage: opts.mask !== false });
  m.userData.u = u; const SPH = opts.mask !== false && opts.sph !== false;
  return patch(m, {
    attrs: opts.mask === false ? ['aux'] : ['aux', 'mask'], uniforms: u, key: 'hair' + (opts.mask === false ? 'n' : '') + (SPH ? 's' : ''),
    vdecl: SPH ? 'uniform vec3 uHC;\n' : '', vnormal: SPH ? 'objectNormal = normalize(mix(objectNormal, normalize(aux - (uHC + vec3(0., -0.02, -0.02))), 0.8));\n' : '',
    decl: /* glsl */`uniform vec3 uHair, uHC; uniform float uGrey, uRag, uFlow, uFine;
      vec2 hcoord(vec3 r){ vec3 q = r - uHC; float th = atan(q.x, q.z + 0.02); return mix(vec2(th, r.y), vec2(q.x*9., atan(q.y, -q.z)*0.25), uFlow); }
      float strand(vec3 r){ vec2 h = hcoord(r); return fbm3b(vec3(h.x*30., h.y*0.9, 1.)); }
      float strand2(vec3 r){ vec2 h = hcoord(r); float fq = mix(84., 62., uFlow); float cell = mix(0.1, 0.111, uFlow)/fq; float k = smoothstep(0.9, 0.3, pxSize(r)/cell); return uFine > 0.5 ? vnoise3(vec3(h.x*170., h.y*1.4, 7.)) : mix(0.5, vnoise3(vec3(h.x*fq, h.y*1.4, 7.)), k); }`,
    color: /* glsl */`{ float s = strand(vAux); float s2 = strand2(vAux); vec3 c = uHair*(0.55 + 0.9*s) * (0.8 + 0.4*s2);
        ${opts.mask === false ? '' : 'float ee = vMask.x*uRag - (strand2(vAux)*0.45 + s*0.35); diffuseColor.a = 1. - smoothstep(0.05, 0.42, ee); if(diffuseColor.a < 0.04) discard;'}
        c = mix(c, vec3(0.62, 0.62, 0.64)*(0.6 + 0.7*s), uGrey*smoothstep(0.25, 0.75, s2 + uGrey - 0.5)); diffuseColor.rgb = c; }`,
    normal: /* glsl */`{ ${SPH ? 'normal *= faceDirection;' : ''} float s = strand(vAux)*1.0 + strand2(vAux)*0.6; float fade = smoothstep(0.004, 0.001, pxSize(vAux)); normal = bumpN(normal, -vViewPosition, s, 0.0018*fade); }`,
    emissive: /* glsl */`{ vec3 L = normalize((viewMatrix*vec4(uKeyDir, 0.)).xyz); vec3 V = normalize(vViewPosition); vec3 down = normalize((viewMatrix*vec4(0., -1., 0., 0.)).xyz);
        vec3 T = normalize(down - normal*dot(down, normal)); float sh = (strand(vAux) - 0.5)*0.10; T = normalize(T + normal*sh);
        vec3 Hh = normalize(L + V); float dth = dot(T, Hh); float sp = pow(sqrt(max(0., 1. - dth*dth)), 42.); float sp2 = pow(sqrt(max(0., 1. - dth*dth)), 14.);
        float ndl = clamp(dot(normal, L)*0.6 + 0.4, 0., 1.);
        totalEmissiveRadiance += uKeyCol*(sp*0.045 + sp2*0.014)*ndl*(0.5 + uHair*6. + uGrey); }`,
  });
}

/* ---------- cloth ---------- */
export function clothMaterial(col, lm, o = {}) {
  const u = { uCol: { value: C(col) }, uTrim: { value: C(o.trim || col) }, uSash: { value: C(o.sash || col) },
    uNeck: { value: new THREE.Vector4(o.vneck ? lm.neckY - (o.vdepth ?? 0.17) : 99, o.vslope ?? 0.36, lm.hipY + 0.085, o.sashOn ? 1 : 0) }, uHem: { value: o.hem ?? -99 }, uWorn: { value: o.worn ?? 0.3 }, uPattern: { value: o.pattern ?? 0 }, uBlood: { value: 0 }, uSnow: { value: 0 } };
  const m = new THREE.MeshPhysicalMaterial({ color: 0xffffff, roughness: o.rough ?? 0.85, metalness: 0, sheen: o.sheen ?? 0.6, sheenRoughness: 0.6, sheenColor: C(col).multiplyScalar(1.8).addScalar(0.03), side: THREE.DoubleSide });
  m.userData.u = u;
  return patch(m, {
    uniforms: u, key: 'cloth',
    decl: /* glsl */`uniform vec3 uCol, uTrim, uSash; uniform vec4 uNeck; uniform float uHem, uWorn, uPattern, uBlood, uSnow;
      float vedge(vec3 r){ return abs(r.x - 0.004) - (r.y - uNeck.x)*uNeck.y; }`,
    alpha: /* glsl */`{ if(vAux.y > uNeck.x && vAux.z > 0. && vedge(vAux) < 0.) discard; }`,
    color: /* glsl */`{ vec3 r = vAux; vec3 c = uCol; float n = fbm3(r*vec3(30., 9., 30.)); float fine = vnoise3(r*1400.);
        c *= 0.82 + 0.36*n; c *= 0.94 + 0.12*fine;
        c = mix(c, c*1.35 + 0.02, uWorn*smoothstep(0.55, 0.8, fbm3(r*13. + 2.)));                    // sun-faded patches
        c = mix(c, c*0.55, uWorn*smoothstep(0.35, 0.0, r.y)*0.8);                                   // dirt near the ground
        if(uPattern > 0.5){ float st = smoothstep(0.42, 0.5, abs(fract(r.y*uPattern) - 0.5)); c = mix(c, uTrim, st*0.6); }
        float ve = vedge(r); if(r.y > uNeck.x - 0.01 && r.z > 0.) c = mix(c, uTrim, smoothstep(0.016, 0.012, ve)*step(uNeck.x, r.y));
        if(r.z > 0. && r.y < uNeck.x + 0.005 && r.y > uNeck.z) { float xl = 0.004 - (uNeck.x - r.y)*0.22; c = mix(c, uTrim, smoothstep(0.007, 0.004, abs(r.x - xl))*0.9); }
        if(uNeck.w > 0.5) c = mix(c, uSash*(0.75 + 0.5*vnoise3(vec3(r.x*40., r.y*400., r.z*40.))), smoothstep(0.052, 0.046, abs(r.y - uNeck.z)));
        c = mix(c, uTrim, smoothstep(uHem + 0.018, uHem + 0.012, r.y));
        c = mix(c, vec3(0.16, 0.012, 0.01), uBlood*smoothstep(0.5, 0.7, fbm3(r*17. + 5.)));
        diffuseColor.rgb = c; }`,
    normal: /* glsl */`{ float px = pxSize(vAux); float f1 = smoothstep(0.02, 0.004, px), f2 = smoothstep(0.0016, 0.0005, px);
        float folds = fbm3(vAux*vec3(26., 6., 26.) + 3.)*1.0 + fbm3(vAux*vec3(70., 20., 70.))*0.35;
        float weave = sin(vAux.x*3300.)*sin(vAux.y*3300.) + sin(vAux.z*3300.)*sin(vAux.y*3300.);
        normal = bumpN(normal, -vViewPosition, folds, 0.012*f1); normal = bumpN(normal, -vViewPosition, weave, 0.00012*f2); }`,
  });
}

export function leatherMaterial(col, o = {}) {
  const u = { uCol: { value: C(col) }, uBands: { value: o.bands ?? 0 } };
  const m = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: o.rough ?? 0.6, metalness: 0, side: THREE.DoubleSide });
  return patch(m, { uniforms: u, key: 'leather', decl: 'uniform vec3 uCol; uniform float uBands;',
    color: `{ float n = fbm3(vAux*60.); vec3 c = uCol*(0.7 + 0.6*n); if(uBands > 0.) c *= 0.75 + 0.25*smoothstep(0.1, 0.2, abs(fract(length(vAux.xy)*uBands) - 0.5)); c = mix(c, c*0.5, smoothstep(0.1, 0.0, vAux.y)); diffuseColor.rgb = c; }`,
    rough: `roughnessFactor += (fbm3(vAux*120.)-0.5)*0.3;`,
    normal: `{ float f = smoothstep(0.01, 0.002, pxSize(vAux)); float hh = fbm3(vAux*90.) + (uBands > 0. ? smoothstep(0.1, 0.2, abs(fract(length(vAux.xy)*uBands) - 0.5))*0.8 : 0.); normal = bumpN(normal, -vViewPosition, hh, 0.004*f); }` });
}

export function metalMaterial(col = [0.55, 0.56, 0.6], o = {}) {
  const u = { uCol: { value: C(col) }, uRust: { value: o.rust ?? 0.25 }, uBlood: { value: 0 } };
  const m = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: o.rough ?? 0.42, metalness: 1, side: o.side ?? THREE.DoubleSide, envMapIntensity: o.env ?? 0.7 });
  m.userData.u = u;
  return patch(m, { uniforms: u, key: 'metal', decl: 'uniform vec3 uCol; uniform float uRust, uBlood;',
    color: `{ float n = fbm3(vAux*40.); float sc = vnoise3(vec3(vAux.x*900., vAux.y*60., vAux.z*900.)); vec3 c = uCol*(0.8 + 0.35*n + 0.05*sc); float ru = smoothstep(0.55, 0.8, fbm3(vAux*22. + 7.))*uRust; c = mix(c, vec3(0.16, 0.08, 0.045), ru); c = mix(c, vec3(0.18, 0.01, 0.01), uBlood*smoothstep(0.45, 0.7, fbm3(vAux*30. + 2.))); diffuseColor.rgb = c; }`,
    rough: `{ float ru = smoothstep(0.55, 0.8, fbm3(vAux*22. + 7.))*uRust; roughnessFactor = mix(roughnessFactor + (vnoise3(vec3(vAux.x*300., vAux.y*40., vAux.z*300.))-0.5)*0.10, 0.9, ru); metalnessFactor *= 1. - ru; }`,
    normal: `{ float f = smoothstep(0.012, 0.002, pxSize(vAux)); normal = bumpN(normal, -vViewPosition, fbm3(vAux*40.), 0.0007*f); }` });
}

export const teethMaterial = () => new THREE.MeshLambertMaterial({ color: new THREE.Color(0.052, 0.046, 0.038) });
export const tongueMaterial = () => new THREE.MeshLambertMaterial({ color: new THREE.Color(0.045, 0.014, 0.014) });
export const lashMaterial = (look) => new THREE.MeshStandardMaterial({ color: C(look.hair || [0.03, 0.02, 0.02]).multiplyScalar(0.5), roughness: 0.7, side: THREE.DoubleSide });
