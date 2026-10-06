import { McpServer } from "@modelcontextprotocol/server";
import { createMcpHandler } from "agents/mcp/server";
import { z } from "zod";

function createServer() {
  const server = new McpServer({
    name: "VideoStudio-MCP",
    version: "0.1.0",
  });

  server.registerTool(
    "server_status",
    {
      description: "Check whether the VideoStudio MCP server is online.",
      inputSchema: {},
    },
    async () => ({
      content: [
        {
          type: "text",
          text: JSON.stringify({
            ok: true,
            service: "VideoStudio-MCP",
            version: "0.1.0",
            transport: "Streamable HTTP",
          }),
        },
      ],
    }),
  );

  server.registerTool(
    "video_project_plan",
    {
      description:
        "Create a structured editing plan from a plain-language video request. This is the planning layer used before storage and rendering tools are enabled.",
      inputSchema: {
        projectName: z.string().min(1),
        instruction: z.string().min(1),
      },
    },
    async ({ projectName, instruction }) => ({
      content: [
        {
          type: "text",
          text: JSON.stringify({
            projectName,
            instruction,
            status: "planned",
            next: [
              "ingest_media",
              "analyse_media",
              "build_timeline",
              "render_preview",
              "inspect_render",
              "iterate",
              "export_final",
            ],
          }),
        },
      ],
    }),
  );

  return server;
}

const handleMcp = createMcpHandler(createServer, {
  route: "/mcp",
  responseMode: "auto",
});

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (url.pathname === "/" && request.method === "GET") {
      return new Response("VideoStudio MCP is online", {
        headers: { "content-type": "text/plain; charset=UTF-8" },
      });
    }

    return handleMcp(request, env, ctx);
  },
};
