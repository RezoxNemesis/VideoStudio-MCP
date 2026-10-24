import {z} from 'zod';

export const AUDIO_ENVELOPE_CAPABILITIES=Object.freeze({"schema":"videostudio.audio.envelope.capabilities.v1","minimumNativeVersion":"4.10.25","action":"analyse_media","mode":"audio_envelope","resultSchema":"videostudio.audio.envelope.v1","readOnly":true,"source":{"projectRevisionRequired":true,"sha256Required":true,"ownedRegularFilesOnly":true,"mimePrefix":"audio/","maximumBytes":10737418240,"linkedContentSupported":false,"galleryAccess":false},"window":{"sourceMs":[0,86400000],"durationMs":[100,30000],"buckets":[4,720]},"pcm":{"encoding":"pcm16","channels":[1,2],"sampleRate":[8000,192000],"measure":"All-channel root-mean-square energy and absolute PCM sample peak","samplePeakIsTruePeak":false},"normalization":"window_max_rms","coverage":{"sampleCounts":true,"sourceTimeBuckets":true,"distinctFrameCoverage":true,"partialResultsExplicit":true,"missingMetricsAreNull":true,"silenceRequiresSamples":true,"codecTimestampMapping":"Integer codec PTS rounded to the nearest source sample-frame index; no interpolation or gap filling"},"budgets":{"maximumCompressedWindowBytes":67108864,"maximumDecodedFrames":6144000,"maximumInputBuffers":65536,"maximumOutputBuffers":65536,"maximumIdleCycles":2200,"wallDeadlineMs":120000,"hashBufferBytes":65536},"pcmWav":{"maximumSourceBytes":536870912,"maximumSourceDurationMs":600000},"manualControls":"Editor > Audio inspector > Analyze envelope","legacyVisualOmissionUnchanged":true,"newCanonicalAction":false,"limitations":"Measured source amplitude only; no ASR, lyrics, beat detection, pitch, true peak, automatic editing, audio synthesis, codec/GPU or artistic certification. Linked SAF sources require an owned import; very large source hashing may reach the explicit work deadline."});

const audioEnvelopeRequest=z.object({
  mode:z.literal('audio_envelope'),
  projectId:z.string().regex(/^[A-Za-z0-9_-]{1,96}$/),
  expectedUpdatedAt:z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  assetId:z.string().regex(/^[A-Za-z0-9_-]{1,96}$/),
  expectedSha256:z.string().regex(/^[a-f0-9]{64}$/),
  sourceMs:z.number().int().min(0).max(86400000),
  durationMs:z.number().int().min(100).max(30000),
  buckets:z.number().int().min(4).max(720)
}).strict();

export const hasMediaAnalysisMode=parameters=>Object.hasOwn(parameters,'mode');

export function nativeMediaAnalysisParameters(parameters){
  const result=audioEnvelopeRequest.safeParse(parameters);
  if(!result.success)throw new Error('AUDIO_ENVELOPE_INPUT: strict audio envelope mode, fields, source identity and integer bounds required: '+result.error.message);
  // Every allowed field is a primitive. Preserve authored key order as well as
  // values while detaching the request from the caller.
  return {...parameters};
}

// Keep unknown fields until mode validation. A raw MCP field shape would strip
// them before the strict audio contract could reject a mixed/forged request.
// The omitted-mode branch retains each tool's existing visual field schema.
export function mediaAnalysisToolInputSchema(legacyFields,transportFields=[]){
  const fields={...legacyFields};
  for(const [key,schema] of Object.entries(audioEnvelopeRequest.shape))
    fields[key]=(key==='mode'?schema:legacyFields[key]?z.union([schema,legacyFields[key]]):z.unknown()).describe('Required in explicit audio_envelope mode; see strict audio request requirements.').optional();
  return z.object(fields).passthrough().superRefine((input,context)=>{
    if(hasMediaAnalysisMode(input)){
      const parameters={...input};
      for(const key of transportFields)delete parameters[key];
      const result=audioEnvelopeRequest.safeParse(parameters);
      if(!result.success)for(const issue of result.error.issues)context.addIssue({...issue,message:'AUDIO_ENVELOPE_INPUT: '+issue.message});
    }else{
      const result=z.object(legacyFields).safeParse(input);
      if(!result.success)for(const issue of result.error.issues)context.addIssue(issue);
    }
  });
}

export function requireMediaAnalysisNativeVersion(device,parameters){
  nativeMediaAnalysisParameters(parameters);
  const match=String(device?.appVersion||'').match(/^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/);
  if(!match||Number(match[1])*1000000+Number(match[2])*1000+Number(match[3])<4010025)
    throw new Error('NATIVE_APP_UPGRADE_REQUIRED: audio_envelope requires VideoStudio 4.10.25 or newer; native application protocol v3 remains unchanged');
}
