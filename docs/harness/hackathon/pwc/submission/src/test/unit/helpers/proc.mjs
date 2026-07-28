/**
 * 테스트용 프로세스 헬퍼 — CLI 실행(runNode)과 MCP stdio 왕복(callServer).
 * callServer 는 기대 응답 수를 받는 즉시 종료해 고정 sleep 없이 빠르게 끝난다.
 */
import { spawn, spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

export function runNode(args, env = {}) {
  return spawnSync(process.execPath, args, {
    encoding: 'utf8',
    env: { ...process.env, ...env },
  });
}

/** NODE_OPTIONS 에 fetch 프리로드를 덧붙인 env 를 만든다 (기존 NODE_OPTIONS 보존). */
export function withFetchStub(preloadPath, stubFile, extraEnv = {}) {
  const base = process.env.NODE_OPTIONS ? `${process.env.NODE_OPTIONS} ` : '';
  return {
    NODE_OPTIONS: `${base}--import=${pathToFileURL(preloadPath)}`,
    FETCH_STUB_FILE: stubFile,
    ...extraEnv,
  };
}

export function callServer(serverPath, calls, { env = {}, expectedResponses, timeoutMs = 10_000 } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [serverPath], {
      env: { ...process.env, ...env },
      stdio: ['pipe', 'pipe', 'inherit'],
    });
    const responses = [];
    let buffer = '';
    let done = false;
    // kill() 은 V8 커버리지 flush 를 막으므로 stdin 을 닫아 자연 종료를 유도한다
    const finish = () => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      child.stdin.end();
      if (child.exitCode !== null) {
        resolve(responses);
        return;
      }
      const hardKill = setTimeout(() => child.kill(), 3_000);
      child.on('exit', () => {
        clearTimeout(hardKill);
        resolve(responses);
      });
    };
    const timer = setTimeout(finish, timeoutMs);
    child.stdout.on('data', (chunk) => {
      buffer += String(chunk);
      let idx;
      while ((idx = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, idx).trim();
        buffer = buffer.slice(idx + 1);
        if (line) responses.push(JSON.parse(line));
      }
      if (expectedResponses && responses.length >= expectedResponses) finish();
    });
    child.on('error', (e) => { clearTimeout(timer); reject(e); });
    for (const call of calls) {
      child.stdin.write(typeof call === 'string' ? `${call}\n` : `${JSON.stringify(call)}\n`);
    }
  });
}
