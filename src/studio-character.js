const STUDIO_CHARACTER_JS = String.raw`(() => {
  "use strict";

  /*
   * Single-still character animation: bounded, local WebGL image deformation.
   * It does NOT hallucinate new character poses or claim diffusion inference.
   * Eight adjustable normalized motion regions let ChatGPT or the owner place
   * meaningful movement on limbs, heads, cloth, or background elements.
   * Media is read from and saved back to the same browser-local IndexedDB.
   */
  const MAX_REGIONS = 8;
  const clamp = (n, min, max) => Math.max(min, Math.min(max, Number(n) || 0));
  const lerp = (a, b, t) => a + (b - a) * t;

  const PRESETS = {
    duel: [
      {x:.32,y:.24,rx:.18,ry:.16,dx:.013,dy:-.014,frequency:3.6,phase:.3},
      {x:.32,y:.44,rx:.21,ry:.15,dx:.075,dy:-.012,frequency:2.2,phase:0},
      {x:.18,y:.55,rx:.28,ry:.25,dx:.026,dy:.014,frequency:2.5,phase:1},
      {x:.75,y:.36,rx:.14,ry:.12,dx:-.016,dy:.009,frequency:2.8,phase:1.5},
      {x:.70,y:.50,rx:.19,ry:.21,dx:-.046,dy:.009,frequency:2.6,phase:2.7},
      {x:.91,y:.40,rx:.22,ry:.26,dx:.048,dy:-.032,frequency:5.2,phase:.8},
      {x:.42,y:.75,rx:.20,ry:.27,dx:.011,dy:.023,frequency:2.1,phase:1.9},
      {x:.78,y:.71,rx:.20,ry:.27,dx:-.012,dy:.022,frequency:2.3,phase:2.4}
    ],
    portrait: [
      {x:.51,y:.24,rx:.25,ry:.20,dx:.013,dy:-.010,frequency:2.4,phase:.3},
      {x:.52,y:.44,rx:.31,ry:.25,dx:.009,dy:.013,frequency:2.8,phase:1.4},
      {x:.28,y:.47,rx:.19,ry:.25,dx:.018,dy:.019,frequency:3.7,phase:.5},
      {x:.75,y:.49,rx:.19,ry:.25,dx:-.017,dy:.017,frequency:3.4,phase:2.7},
      {x:.28,y:.25,rx:.22,ry:.24,dx:.027,dy:-.018,frequency:5.2,phase:1.7},
      {x:.74,y:.25,rx:.23,ry:.23,dx:-.029,dy:-.015,frequency:5.6,phase:.4},
      {x:.51,y:.71,rx:.29,ry:.29,dx:.019,dy:.017,frequency:3.0,phase:.9}
    ]
  };

  function validateRegion(region) {
    if (!region || typeof region !== "object") throw new Error("A motion region must be an object");
    const keys = ["x","y","rx","ry","dx","dy"];
    for (const key of keys) if (!Number.isFinite(Number(region[key]))) throw new Error("Invalid motion region " + key);
    return {
      x:clamp(region.x,0,1),
      y:clamp(region.y,0,1),
      rx:clamp(region.rx,.025,.5),
      ry:clamp(region.ry,.025,.5),
      dx:clamp(region.dx,-.10,.10),
      dy:clamp(region.dy,-.10,.10),
      frequency:clamp(region.frequency === undefined ? 3 : region.frequency,.5,10),
      phase:clamp(region.phase === undefined ? 0 : region.phase,-6.28,6.28)
    };
  }

  function selectRig(options) {
    const prompt = String(options.prompt || "").toLowerCase();
    const preset = options.motionPreset === "duel" || options.motionPreset === "portrait"
      ? options.motionPreset
      : /duel|fight|battle|combat|clash|saitama|goku|punch|anime action/.test(prompt)
        ? "duel" : "portrait";
    const custom = Array.isArray(options.regions) && options.regions.length > 0;
    if (custom && options.regions.length > MAX_REGIONS) throw new Error("At most eight motion regions are supported");
    const regions = (custom ? options.regions : PRESETS[preset]).map(validateRegion);
    return {preset: custom ? "custom" : preset, regions};
  }

  function compile(gl, type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      const message = gl.getShaderInfoLog(shader) || "Unknown shader failure";
      gl.deleteShader(shader);
      throw new Error("Character animation shader failed: " + message);
    }
    return shader;
  }

  function createDeformer(width, height, image, regions, drawCover) {
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const gl = canvas.getContext("webgl", {
      alpha: false,
      antialias: false,
      preserveDrawingBuffer: true,
      premultipliedAlpha: false,
      powerPreference: "high-performance"
    });
    if (!gl) throw new Error("Character deformation requires WebGL. This browser/device does not provide it.");

    const vertexSource = [
      "attribute vec2 aPosition;",
      "attribute vec2 aUV;",
      "varying vec2 vUV;",
      "void main(){vUV=aUV;gl_Position=vec4(aPosition,0.0,1.0);}"
    ].join("\n");
    const fragmentSource = [
      "precision highp float;",
      "uniform sampler2D uImage;",
      "uniform vec4 uRegions[8];",
      "uniform vec4 uMove[8];",
      "uniform float uSeconds;",
      "uniform float uBeat;",
      "uniform float uIntensity;",
      "uniform float uStrike;",
      "uniform float uRegionCount;",
      "varying vec2 vUV;",
      "void main(){",
      "  vec2 p=vec2(vUV.x,1.0-vUV.y);",
      "  vec2 shift=vec2(0.0);",
      "  for(int i=0;i<8;i++){",
      "    if(float(i)<uRegionCount){",
      "      vec2 center=uRegions[i].xy;",
      "      vec2 radius=max(uRegions[i].zw,vec2(.025));",
      "      vec2 d=(p-center)/radius;",
      "      float w=exp(-dot(d,d)*2.8);",
      "      float flutter=sin(uSeconds*uMove[i].z+uMove[i].w);",
      "      float secondary=sin(uSeconds*(uMove[i].z*.53)+uMove[i].w*1.7);",
      "      float motion=.25*flutter+.12*secondary;",
      "      if(i==1)motion+=uStrike*1.12;",
      "      if(i==4)motion+=uStrike*.42;",
      "      if(i==5)motion+=.40*flutter+.28*sin(uSeconds*7.0);",
      "      motion+=uBeat*.09;",
      "      shift+=w*uMove[i].xy*motion*uIntensity;",
      "    }",
      "  }",
      "  vec2 samplePoint=clamp(p-shift,vec2(.001),vec2(.999));",
      "  vec4 color=texture2D(uImage,vec2(samplePoint.x,1.0-samplePoint.y));",
      "  gl_FragColor=vec4(color.rgb,1.0);",
      "}"
    ].join("\n");
    const vert = compile(gl, gl.VERTEX_SHADER, vertexSource);
    const frag = compile(gl, gl.FRAGMENT_SHADER, fragmentSource);
    const program = gl.createProgram();
    gl.attachShader(program, vert);
    gl.attachShader(program, frag);
    gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error("Character animation WebGL program could not link");
    gl.useProgram(program);

    const base = document.createElement("canvas");
    base.width = width;
    base.height = height;
    const baseCtx = base.getContext("2d", {alpha:false});
    baseCtx.fillStyle = "#03040a";
    baseCtx.fillRect(0,0,width,height);
    drawCover(baseCtx,image,width,height,1.018);

    const texture = gl.createTexture();
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D,texture);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,true);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,base);
    if (gl.getError() !== gl.NO_ERROR) throw new Error("Not enough GPU memory for requested animation quality");
    gl.uniform1i(gl.getUniformLocation(program,"uImage"),0);

    const triangles = new Float32Array([
      -1,-1,0,0, 1,-1,1,0, -1,1,0,1,
      -1,1,0,1, 1,-1,1,0, 1,1,1,1
    ]);
    const vertexBuffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER,vertexBuffer);
    gl.bufferData(gl.ARRAY_BUFFER,triangles,gl.STATIC_DRAW);
    const position=gl.getAttribLocation(program,"aPosition");
    const uv=gl.getAttribLocation(program,"aUV");
    gl.enableVertexAttribArray(position);
    gl.enableVertexAttribArray(uv);
    gl.vertexAttribPointer(position,2,gl.FLOAT,false,16,0);
    gl.vertexAttribPointer(uv,2,gl.FLOAT,false,16,8);

    const packedRegions = new Float32Array(MAX_REGIONS * 4);
    const packedMove = new Float32Array(MAX_REGIONS * 4);
    for(let i=0;i<regions.length;i++){
      const r=regions[i],o=i*4;
      packedRegions.set([r.x,r.y,r.rx,r.ry],o);
      packedMove.set([r.dx,r.dy,r.frequency,r.phase],o);
    }
    gl.uniform4fv(gl.getUniformLocation(program,"uRegions[0]"),packedRegions);
    gl.uniform4fv(gl.getUniformLocation(program,"uMove[0]"),packedMove);
    gl.uniform1f(gl.getUniformLocation(program,"uRegionCount"),regions.length);
    const uniforms={
      seconds:gl.getUniformLocation(program,"uSeconds"),
      beat:gl.getUniformLocation(program,"uBeat"),
      intensity:gl.getUniformLocation(program,"uIntensity"),
      strike:gl.getUniformLocation(program,"uStrike")
    };
    gl.viewport(0,0,width,height);

    return {
      canvas,
      frame(seconds,beat,intensity,strike) {
        gl.uniform1f(uniforms.seconds,seconds);
        gl.uniform1f(uniforms.beat,beat);
        gl.uniform1f(uniforms.intensity,intensity);
        gl.uniform1f(uniforms.strike,strike);
        gl.drawArrays(gl.TRIANGLES,0,6);
        if (gl.isContextLost()) throw new Error("GPU context lost while animating still image");
      },
      dispose() {
        gl.deleteBuffer(vertexBuffer);
        gl.deleteTexture(texture);
        gl.deleteProgram(program);
        gl.deleteShader(vert);
        gl.deleteShader(frag);
        const lose=gl.getExtension("WEBGL_lose_context");
        if(lose)lose.loseContext();
      }
    };
  }

  function burstAt(t, center, width) {
    return Math.exp(-Math.pow((t-center)/width,2));
  }

  function drawParticles(ctx,w,h,elapsed,t,rig,intensity) {
    if(rig.preset !== "duel") return;
    const charge=clamp(t/.36,0,1);
    const clash=burstAt(t,.49,.058);
    ctx.save();
    ctx.globalCompositeOperation="screen";
    for(let i=0;i<44;i++){
      const phase=i*2.399963229728653;
      const speed=.48+(i%6)*.16;
      const fly=(elapsed*speed+i*.18)%3.1;
      const px=w*(.17+Math.sin(phase*3.5)*.19+Math.cos(phase)*.075);
      const py=h*(.49+.2*Math.sin(phase*.9));
      const x=px+Math.sin(phase)*fly*w*.035;
      const y=py-Math.abs(Math.cos(phase))*fly*h*.064;
      const radius=(i%4+1)*(1.0+clash*1.8)*intensity;
      ctx.fillStyle="rgba(94,162,255,"+(.18+.5*charge)+")";
      ctx.beginPath();ctx.arc(x,y,radius,0,Math.PI*2);ctx.fill();
    }
    if(clash>.015){
      const x=w*.54,y=h*.49,rad=lerp(w*.03,w*.63,clamp((t-.40)/.20,0,1));
      ctx.strokeStyle="rgba(220,237,255,"+(.76*clash)+")";
      ctx.lineWidth=w*.006*clash;
      ctx.beginPath();ctx.ellipse(x,y,rad,rad*1.35,-.16,0,Math.PI*2);ctx.stroke();
      const glow=ctx.createRadialGradient(x,y,0,x,y,w*.36);
      glow.addColorStop(0,"rgba(248,250,255,"+(.43*clash)+")");
      glow.addColorStop(.32,"rgba(128,180,255,"+(.16*clash)+")");
      glow.addColorStop(1,"rgba(30,80,255,0)");
      ctx.fillStyle=glow;ctx.fillRect(0,0,w,h);
      for(let i=0;i<23;i++){
        const a=i*2.399963,dist=(.05+(i%6)*.035)*w*(1.0+clash);
        ctx.strokeStyle="rgba(255,245,219,"+(.75*clash)+")";
        ctx.lineWidth=Math.max(1,w*.0024);
        ctx.beginPath();
        ctx.moveTo(x+Math.cos(a)*dist,y+Math.sin(a)*dist);
        ctx.lineTo(x+Math.cos(a)*dist*1.7,y+Math.sin(a)*dist*1.7);
        ctx.stroke();
      }
    }
    ctx.restore();
  }

  function drawFrame(ctx,w,h,t,elapsed,deformer,rig,intensity) {
    const strike=burstAt(t,.47,.078);
    const recoil=burstAt(t,.63,.065);
    const beat=.55*strike+.18*recoil;
    deformer.frame(elapsed,beat,intensity,strike);
    ctx.fillStyle="#000";
    ctx.fillRect(0,0,w,h);
    ctx.save();
    const shake=strike*intensity;
    ctx.translate(w*.003*Math.sin(elapsed*61)*shake,h*.004*Math.cos(elapsed*47)*shake);
    const zoom=1.007+.008*Math.sin(elapsed*.7)+.012*strike;
    ctx.translate(w/2,h/2);
    ctx.scale(zoom,zoom);
    ctx.translate(-w/2,-h/2);
    ctx.drawImage(deformer.canvas,0,0,w,h);
    ctx.restore();
    drawParticles(ctx,w,h,elapsed,t,rig,intensity);
    if(strike>.06) {
      ctx.save();
      ctx.fillStyle="rgba(249,252,255,"+(.19*strike)+")";
      ctx.fillRect(0,0,w,h);
      ctx.restore();
    }
  }

  async function animateImage(project,options,services) {
    const asset = project.assets.find(a=>a.id===options.assetId&&a.kind==="image")
      || project.assets.find(a=>a.kind==="image");
    if(!asset)throw new Error("Character animation needs a still image imported into the selected project.");
    const rig=selectRig(options);
    const intensity=clamp(options.intensity === undefined ? 1 : options.intensity,.2,1.6);
    const loaded=await services.loadImageAsset(project,asset);
    const [width,height]=services.dimensions(options.aspect||project.settings.aspect,options.quality||project.settings.quality);
    let deformer;
    try {
      deformer=createDeformer(width,height,loaded.image,rig.regions,services.drawCover);
      const blob=await services.recordCanvas({
        width,height,quality:options.quality||project.settings.quality,
        duration:options.duration,fps:options.fps,onProgress:services.progress,
        draw:(ctx,w,h,t,elapsed)=>drawFrame(ctx,w,h,t,elapsed,deformer,rig,intensity)
      });
      if(!blob.size)throw new Error("The browser generated an empty animation; no output saved.");
      options.duration=Number(options.duration||8);
      return {
        blob,
        engine:"studio-web-character-region-deformation-v1",
        motionPreset:rig.preset,
        regionCount:rig.regions.length,
        neural:false
      };
    } finally {
      if(deformer)deformer.dispose();
      URL.revokeObjectURL(loaded.url);
    }
  }

  window.VideoStudioCharacter = Object.freeze({
    animateImage,
    selectRig,
    version:"1.0.0",
    feature:"bounded independent character-region deformation",
    neural:false
  });
})();`;

export default STUDIO_CHARACTER_JS;
