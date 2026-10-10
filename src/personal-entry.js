/** Keep the original Worker, Durable Object identity and all native routes intact. */
import legacy, {VideoStudioState as LegacyVideoStudioState} from './index.js';
import {withPersonalStudio} from './personal-state.js';
import {handlePersonal} from './personal-http.js';
export class VideoStudioState extends withPersonalStudio(LegacyVideoStudioState) {}
export default {
  async fetch(request,env,ctx) {
    const path=new URL(request.url).pathname;
    if(path==='/personal'||path.startsWith('/personal/')||path.startsWith('/api/personal/')) {
      return handlePersonal(request,env,env.VIDEO_STATE.getByName('primary'));
    }
    return legacy.fetch(request,env,ctx);
  }
};
