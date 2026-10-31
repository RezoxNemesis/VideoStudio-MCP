# VideoStudio relay

One Cloudflare Worker + one D1 database (free tier is plenty; no Durable Objects).

1. `npx wrangler d1 create videostudio-relay` and put the id in `wrangler.jsonc`.
2. `npx wrangler deploy`.
3. In the app: Agent → Remote link → paste `https://videostudio-relay.<you>.workers.dev` → Connect.
4. Copy the MCP URL from the app and add it in Claude → Settings → Connectors → Add custom connector.

The app polls every 2.5 s while open (15 s in the background), roughly 35k D1 reads a day per phone, well inside the free 5M.
