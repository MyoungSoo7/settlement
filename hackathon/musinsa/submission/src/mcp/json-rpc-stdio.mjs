import { createInterface } from 'node:readline';
import { safeErrorMessage } from '../common/env.mjs';

const JSON_RPC_VERSION = '2.0';
const PROTOCOL_VERSION = '2025-03-26';

function send(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

function toolResult(id, payload, isError = false) {
  send({
    jsonrpc: JSON_RPC_VERSION,
    id,
    result: {
      content: [{ type: 'text', text: typeof payload === 'string' ? payload : JSON.stringify(payload, null, 2) }],
      isError,
    },
  });
}

function listTools(tools) {
  // 이 패키지의 모든 MCP 도구는 읽기 전용 조회다 — MCP 스펙 annotations 로 명시해
  // 호스트(Codex/Claude)의 승인 UX 가 안전한 도구임을 알 수 있게 한다.
  return tools.map(({ name, description, inputSchema, annotations }) => ({
    name,
    description,
    inputSchema,
    annotations: annotations ?? { readOnlyHint: true, openWorldHint: true },
  }));
}

async function handleToolCall(id, params, tools) {
  const tool = tools.find((candidate) => candidate.name === params?.name);
  if (!tool) {
    toolResult(id, `unknown tool: ${params?.name}`, true);
    return;
  }

  try {
    toolResult(id, await tool.run(params?.arguments ?? {}));
  } catch (error) {
    toolResult(id, `tool error: ${safeErrorMessage(error)}`, true);
  }
}

async function handleRequest(request, { serverName, serverVersion, tools }) {
  const { id, method, params } = request;

  if (method === 'initialize') {
    send({
      jsonrpc: JSON_RPC_VERSION,
      id,
      result: {
        protocolVersion: params?.protocolVersion ?? PROTOCOL_VERSION,
        capabilities: { tools: {} },
        serverInfo: { name: serverName, version: serverVersion },
      },
    });
    return;
  }

  if (method === 'notifications/initialized' || method?.startsWith('notifications/')) return;

  if (method === 'tools/list') {
    send({ jsonrpc: JSON_RPC_VERSION, id, result: { tools: listTools(tools) } });
    return;
  }

  if (method === 'tools/call') {
    await handleToolCall(id, params, tools);
    return;
  }

  if (id !== undefined) {
    send({ jsonrpc: JSON_RPC_VERSION, id, error: { code: -32601, message: `method not found: ${method}` } });
  }
}

export function startJsonRpcServer(config) {
  const rl = createInterface({ input: process.stdin, terminal: false });
  rl.on('line', async (input) => {
    const line = input.trim();
    if (!line) return;

    let request;
    try {
      request = JSON.parse(line);
    } catch {
      return;
    }

    try {
      await handleRequest(request, config);
    } catch (error) {
      if (request.id !== undefined) {
        send({
          jsonrpc: JSON_RPC_VERSION,
          id: request.id,
          error: { code: -32603, message: safeErrorMessage(error) },
        });
      }
    }
  });
}
