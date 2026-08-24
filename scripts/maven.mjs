import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const backendDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'backend-java');
const wrapper = process.platform === 'win32' ? 'mvnw.cmd' : './mvnw';
const args = process.argv.slice(2);

const result = spawnSync(wrapper, args, {
  cwd: backendDir,
  stdio: 'inherit',
  shell: process.platform === 'win32',
});

if (result.error) {
  console.error(`Failed to start Maven wrapper: ${result.error.message}`);
  process.exit(1);
}

process.exit(result.status ?? 1);
