import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const JSON_RPC_VERSION = '2.0';
const DEFAULT_TIMEOUT_MS = 8_000;

export function parseJsonRpcLines(input, existingRemainder = '') {
  let buffer = existingRemainder + input;
  const messages = [];
  let newlineIndex = buffer.indexOf('\n');

  while (newlineIndex >= 0) {
    const line = buffer.slice(0, newlineIndex).trim();
    buffer = buffer.slice(newlineIndex + 1);
    if (line) messages.push(JSON.parse(line));
    newlineIndex = buffer.indexOf('\n');
  }

  return { messages, remainder: buffer };
}

export function createAssert() {
  let failures = 0;
  return {
    check(name, condition, detail = '') {
      if (condition) {
        console.log(`  ok  ${name}`);
        return;
      }
      failures += 1;
      console.error(`FAIL  ${name} ${detail}`);
    },
    finish() {
      console.log(failures === 0 ? '\nALL GREEN' : `\n${failures} FAILURE(S)`);
      if (failures > 0) process.exitCode = 1;
    },
  };
}

export async function callMcpServer(serverPath, calls, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const server = spawn(process.execPath, [serverPath], {
    stdio: ['pipe', 'pipe', 'inherit'],
  });

  const responses = [];
  let remainder = '';
  server.stdout.on('data', (chunk) => {
    const parsed = parseJsonRpcLines(String(chunk), remainder);
    responses.push(...parsed.messages);
    remainder = parsed.remainder;
  });

  const send = (message) => {
    server.stdin.write(`${JSON.stringify(message)}\n`);
  };

  for (const call of calls) send(call);

  await delay(timeoutMs);
  server.kill();

  return responses;
}

export const initializeRequest = {
  jsonrpc: JSON_RPC_VERSION,
  id: 1,
  method: 'initialize',
  params: {
    protocolVersion: '2025-03-26',
    capabilities: {},
    clientInfo: { name: 'smoke', version: '0' },
  },
};

export const initializedNotification = {
  jsonrpc: JSON_RPC_VERSION,
  method: 'notifications/initialized',
};

export const listToolsRequest = {
  jsonrpc: JSON_RPC_VERSION,
  id: 2,
  method: 'tools/list',
};

export function toolCall(id, name, args = {}) {
  return {
    jsonrpc: JSON_RPC_VERSION,
    id,
    method: 'tools/call',
    params: { name, arguments: args },
  };
}

export function parseToolPayload(response) {
  return response ? JSON.parse(response.result.content[0].text) : null;
}
