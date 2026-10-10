const STUDIO_ACTION_TIMELINE_JS = String.raw`(() => {
  "use strict";
  // Deterministic non-neural 2D puppet keyframe director. User supplied region
  // positions are honored; this module never enumerates local device media.
  const clamp=(n,a,b)=>Math.max(a,Math.min(b,Number(n)||0));
  const ease=t=>{const p=clamp(t,0,1);return p*p*(3-2*p);};
  const BODIES=["goku_head","goku_fist","goku_torso","goku_legs","saitama_head","saitama_torso","saitama_cape","saitama_legs"];
  const DUEL=[
    // key: normalized time [0..1], horizontal/vertical shift in screen units,
    // angle in radians. Transitions use eased keyframes, not one vibrating sine.
    [[0,0,0,0],[.2,-.004,-.009,-.025],[.4,.012,-.016,.037],[.53,.006,-.008,.028],[.66,-.012,.005,-.035],[1,0,0,0]],
    [[0,0,0,0],[.18,-.008,.002,-.10],[.34,.025,-.005,-.16],[.46,.094,-.012,-.20],[.53,.106,-.012,-.18],[.65,.022,.008,.02],[.82,-.01,.009,.03],[1,0,0,0]],
    [[0,0,0,0],[.2,-.009,-.008,-.017],[.42,.032,-.012,.040],[.51,.044,-.015,.055],[.65,-.023,.012,-.035],[1,0,0,0]],
    [[0,0,0,0],[.2,-.014,.008,-.005],[.43,.020,.010,.020],[.5,.025,-.005,.014],[.68,-.021,.014,-.027],[1,0,0,0]],
    [[0,0,0,0],[.21,.002,-.006,-.014],[.44,-.007,-.012,-.026],[.51,-.013,-.009,-.047],[.65,.008,.005,.01],[1,0,0,0]],
    [[0,0,0,0],[.3,0,-.006,0],[.47,-.011,-.012,-.02],[.56,-.029,.005,-.048],[.69,.009,.011,.035],[1,0,0,0]],
    [[0,0,0,0],[.15,.006,-.007,-.015],[.33,.032,-.021,-.065],[.49,.055,-.029,-.082],[.61,.078,-.038,-.1],[.77,.038,-.026,-.045],[1,.014,-.012,0]],
    [[0,0,0,0],[.3,-.004,0,0],[.5,-.016,.003,-.015],[.64,.01,.006,.015],[1,0,0,0]]
  ];
  const PORTRAIT=BODIES.map((_,i)=>[[0,0,0,0],[.25,(i%2?.006:-.006),-.008,0],[.5,(i%2?-.003:.003),.006,(i%2?.01:-.01)],[.75,(i%2?.007:-.007),-.007,0],[1,0,0,0]]);
  function readKey(row){
    if(!Array.isArray(row)||row.length<4||row.length>5)throw new Error("Pose keyframe must be [time,x,y,angle,(optional) bend]");
    const vals=row.map(Number);
    if(vals.some(n=>!Number.isFinite(n)))throw new Error("Pose keyframe values must be finite");
    const [at,x,y,angle,bend=0]=vals;
    if(at<0||at>1||Math.abs(x)>.18||Math.abs(y)>.18||Math.abs(angle)>.5||Math.abs(bend)>.5){
      throw new Error("Pose keyframe exceeds safe timeline bounds");
    }
    return {at,x,y,angle,bend};
  }
  function compileTrack(rows){
    if(!Array.isArray(rows)||rows.length<2||rows.length>16)throw new Error("Each pose track needs 2–16 keyframes");
    const keys=rows.map(readKey);
    if(keys[0].at!==0||keys[keys.length-1].at!==1)throw new Error("Pose track must begin at 0 and end at 1");
    for(let i=1;i<keys.length;i++)if(keys[i].at<=keys[i-1].at)throw new Error("Pose keyframes must have strictly increasing times");
    return keys;
  }
  function compile(options={}){
    const type=options.motionPreset==="portrait"?"portrait":"duel";
    const supplied=options.poseTracks;
    if(supplied!==undefined&&(!Array.isArray(supplied)||supplied.length<1||supplied.length>8))throw new Error("poseTracks must contain 1–8 tracks");
    const definitions=supplied|| (type==="duel"?DUEL:PORTRAIT);
    const tracks=definitions.map(compileTrack);
    return Object.freeze({
      preset:supplied?"custom":type,
      count:tracks.length,
      tracks,
      names:BODIES.slice(0,tracks.length),
      phases:Object.freeze([{label:"Anticipation",at:0},{label:"Charge",at:.19},{label:"Forward strike",at:.43},{label:"Impact",at:.52},{label:"Recoil",at:.65},{label:"Recovery",at:.82}])
    });
  }
  function interpolate(track,t){
    const p=clamp(t,0,1);
    for(let i=1;i<track.length;i++){
      const right=track[i],left=track[i-1];
      if(p<=right.at||i===track.length-1){
        const blend=ease((p-left.at)/Math.max(.001,right.at-left.at));
        return ["x","y","angle","bend"].map(key=>left[key]+(right[key]-left[key])*blend);
      }
    }
    return [0,0,0,0];
  }
  function sample(plan,t,seconds,intensity=1){
    const p=clamp(t,0,1),strength=clamp(intensity,.2,1.6);
    const entries=plan.tracks.map((track,i)=>{
      const [dx,dy,rotation,bend]=interpolate(track,p);
      // Low-amplitude secondary motion is time-delayed from the keyed motion.
      const cloth=i===6&&plan.preset!=="portrait";
      const spring=cloth ? .006*Math.sin(seconds*9.7)*(1-p*.25) : .0016*Math.sin(seconds*2.6+i*.7);
      const wind=cloth ? .012*Math.sin(seconds*3.8-.9) : 0;
      return {dx:clamp((dx+wind+spring)*strength,-.16,.16),
        dy:clamp((dy+(cloth ? .006*Math.cos(seconds*5.8) : 0))*strength,-.16,.16),
        rotation:clamp((rotation+(cloth ? .065*Math.sin(seconds*7) : 0))*strength,-.5,.5),
        bend:clamp(bend*strength,-.5,.5)};
    });
    const pulse=(center,width)=>Math.exp(-Math.pow((p-center)/width,2));
    return {
      entries,
      phase:p<.19?"anticipation":p<.42?"charge":p<.52?"strike":p<.62?"impact":p<.82?"recoil":"recovery",
      impact:pulse(.52,.053),
      charge:ease(p/.4)*(1-ease((p-.55)/.22)),
      recoil:pulse(.68,.11),
      progress:p
    };
  }
  window.VideoStudioActionTimeline=Object.freeze({compile,sample,version:"2.0.0",names:BODIES,nonNeural:true});
})();`;

export default STUDIO_ACTION_TIMELINE_JS;
