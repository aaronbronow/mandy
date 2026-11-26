import { mkdirSync, writeFileSync, readFileSync, existsSync, readdirSync, rmSync } from 'fs';
import { join } from 'path';
import { Database } from 'bun:sqlite';

export interface VerifyResult {
  passed: boolean;
  reason: string;
}

function buildMinimalPdf(text: string): Buffer {
  const safe = text.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
  const streamContent = `BT\n/F1 14 Tf\n72 720 Td\n(${safe}) Tj\nET\n`;
  const objects = [
    '1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n',
    '2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n',
    '3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n',
    `4 0 obj\n<< /Length ${Buffer.byteLength(streamContent, 'utf8')} >>\nstream\n${streamContent}endstream\nendobj\n`,
    '5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n',
  ];

  let out = '%PDF-1.4\n';
  const offsets: number[] = [0];
  for (const obj of objects) {
    offsets.push(Buffer.byteLength(out, 'utf8'));
    out += obj;
  }
  const xrefPos = Buffer.byteLength(out, 'utf8');
  out += `xref\n0 ${objects.length + 1}\n`;
  out += '0000000000 65535 f \n';
  for (let i = 1; i < offsets.length; i++) {
    out += `${String(offsets[i]).padStart(10, '0')} 00000 n \n`;
  }
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefPos}\n%%EOF\n`;
  return Buffer.from(out, 'utf8');
}

function runSync(cmd: string[], cwd?: string): { exit: number; out: string; err: string } {
  const proc = Bun.spawnSync(cmd, { cwd, stdout: 'pipe', stderr: 'pipe' });
  return {
    exit: proc.exitCode,
    out: new TextDecoder().decode(proc.stdout),
    err: new TextDecoder().decode(proc.stderr),
  };
}

function resetDir(dir: string) {
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
}

function createSampleProject(workdir: string) {
  mkdirSync(join(workdir, 'project', 'src', 'nested'), { recursive: true });
  mkdirSync(join(workdir, 'project', 'node_modules', 'pkg'), { recursive: true });
  mkdirSync(join(workdir, 'project', 'logs'), { recursive: true });
  writeFileSync(join(workdir, 'project', 'package.json'), '{"name":"demo"}\n');
  writeFileSync(join(workdir, 'project', 'src', 'config.json'), '{"env":"prod"}\n');
  writeFileSync(join(workdir, 'project', 'src', 'nested', 'schema.json'), '{"v":1}\n');
  writeFileSync(join(workdir, 'project', 'src', 'app.ts'), 'export const x = 1;\n');
  writeFileSync(join(workdir, 'project', 'logs', 'app.log'), 'log line\n');
  writeFileSync(join(workdir, 'project', 'node_modules', 'pkg', 'index.js'), 'module.exports = {};\n');
}

export function setupFixture(taskId: string, workdir: string): void {
  resetDir(workdir);

  switch (taskId) {
    case 'rg_py_retry': {
      mkdirSync(join(workdir, 'src', 'worker'), { recursive: true });
      writeFileSync(join(workdir, 'src', 'client.py'), 'TIMEOUT = 5\nRETRY_LIMIT = 3\n');
      writeFileSync(join(workdir, 'src', 'worker', 'jobs.py'), 'def run():\n    retry_limit = 7\n    return 0\n');
      writeFileSync(join(workdir, 'src', 'ignore.js'), 'const retry_limit = 99;\n');
      writeFileSync(join(workdir, 'src', 'notes.txt'), 'retry_limit should not match txt\n');
      break;
    }
    case 'jq_error_latency': {
      const events = [
        { id: 'e1', service: 'auth', status: 'ok', latency_ms: 450 },
        { id: 'e2', service: 'billing', status: 'error', latency_ms: 150 },
        { id: 'e3', service: 'ingest', status: 'error', latency_ms: 320 },
        { id: 'e4', service: 'search', status: 'error', latency_ms: 810 },
        { id: 'e5', service: 'edge', status: 'error', latency_ms: 200 },
        { id: 'e6', service: 'notify', status: 'error', latency_ms: 510 },
      ];
      writeFileSync(join(workdir, 'events.json'), JSON.stringify(events, null, 2));
      break;
    }
    case 'ffmpeg_extract_frame': {
      const res = runSync([
        'ffmpeg', '-y', '-f', 'lavfi', '-i', 'testsrc=duration=2:size=160x120:rate=10',
        '-pix_fmt', 'yuv420p', 'clip.mp4',
      ], workdir);
      if (res.exit !== 0) throw new Error(`ffmpeg fixture failed: ${res.err}`);
      break;
    }
    case 'sqlite3_csv_export': {
      const db = new Database(join(workdir, 'inventory.db'));
      db.run('CREATE TABLE items (sku TEXT PRIMARY KEY, name TEXT, stock INTEGER)');
      db.run("INSERT INTO items VALUES ('SKU-300', 'Bolt', 4), ('SKU-100', 'Nut', 9), ('SKU-200', 'Washer', 15), ('SKU-050', 'Pin', 2), ('SKU-400', 'Clamp', 10)");
      db.close();
      break;
    }
    case 'poppler_pdf_text': {
      writeFileSync(join(workdir, 'report.pdf'), buildMinimalPdf('QUARTERLY_TOKEN_8472'));
      break;
    }
    case 'poppler_pdf_merge': {
      writeFileSync(join(workdir, 'part1.pdf'), buildMinimalPdf('ALPHA_SECTION_ONE'));
      writeFileSync(join(workdir, 'part2.pdf'), buildMinimalPdf('OMEGA_SECTION_TWO'));
      break;
    }
    case 'zstd_level9_keep': {
      writeFileSync(join(workdir, 'payload.txt'), 'mandy-zstd-payload-line\n'.repeat(200));
      break;
    }
    case 'tar_exclude_bundle': {
      createSampleProject(workdir);
      break;
    }
    case 'rsync_mirror_delete': {
      mkdirSync(join(workdir, 'src_dir', 'sub'), { recursive: true });
      mkdirSync(join(workdir, 'dst_dir', 'old'), { recursive: true });
      writeFileSync(join(workdir, 'src_dir', 'keep.txt'), 'fresh\n');
      writeFileSync(join(workdir, 'src_dir', 'sub', 'data.txt'), 'nested\n');
      writeFileSync(join(workdir, 'src_dir', 'scratch.tmp'), 'ignore me\n');
      writeFileSync(join(workdir, 'dst_dir', 'keep.txt'), 'stale\n');
      writeFileSync(join(workdir, 'dst_dir', 'stale.txt'), 'remove me\n');
      writeFileSync(join(workdir, 'dst_dir', 'old', 'dead.txt'), 'remove me too\n');
      break;
    }
    case 'tree_depth2_json': {
      createSampleProject(workdir);
      break;
    }
    case 'b2sum_256bit': {
      writeFileSync(join(workdir, 'artifact.bin'), 'deterministic-blake2b-fixture-payload-v1\n');
      break;
    }
    case 'b2sum_verify_file': {
      writeFileSync(join(workdir, 'alpha.txt'), 'alpha-content\n');
      writeFileSync(join(workdir, 'beta.txt'), 'beta-original\n');
      const sumRes = runSync(['b2sum', 'alpha.txt', 'beta.txt'], workdir);
      if (sumRes.exit !== 0) throw new Error(`b2sum fixture failed: ${sumRes.err}`);
      writeFileSync(join(workdir, 'checksums.b2'), sumRes.out);
      writeFileSync(join(workdir, 'beta.txt'), 'beta-corrupted\n');
      break;
    }
    case 'ys_yaml_to_json': {
      writeFileSync(
        join(workdir, 'config.yaml'),
        '!yamlscript/v0/data\nservice: mandy\nport: 11434\nfeatures:\n  - dpkg\n  - help\n',
      );
      break;
    }
    case 'busybox_applet_count': {
      break;
    }
    case 'absent_fd_find_json': {
      createSampleProject(workdir);
      break;
    }
    case 'absent_yq_yaml_port': {
      writeFileSync(
        join(workdir, 'app.yaml'),
        'service:\n  name: api\ndatabase:\n  host: 127.0.0.1\n  port: 5432\n',
      );
      break;
    }
    case 'absent_exiftool_report': {
      const res = runSync([
        'ffmpeg', '-y', '-f', 'lavfi', '-i', 'color=c=blue:s=32x32', '-frames:v', '1', 'photo.jpg',
      ], workdir);
      if (res.exit !== 0) throw new Error(`photo fixture failed: ${res.err}`);
      break;
    }
    case 'absent_7z_fallback': {
      mkdirSync(join(workdir, 'data', 'nested'), { recursive: true });
      writeFileSync(join(workdir, 'data', 'a.txt'), 'alpha\n');
      writeFileSync(join(workdir, 'data', 'nested', 'b.txt'), 'beta\n');
      break;
    }
    default:
      throw new Error(`Unknown taskId: ${taskId}`);
  }
}

export function verifyTask(taskId: string, workdir: string): VerifyResult {
  switch (taskId) {
    case 'rg_py_retry': {
      const p = join(workdir, 'out.txt');
      if (!existsSync(p)) return { passed: false, reason: 'out.txt missing' };
      const lines = readFileSync(p, 'utf8').split(/\r?\n/).map(l => l.trim()).filter(Boolean);
      const hasClient = lines.some(l => l.includes('client.py') && l.includes(':2:') && l.includes('RETRY_LIMIT = 3'));
      const hasJobs = lines.some(l => l.includes('jobs.py') && l.includes(':2:') && l.includes('retry_limit = 7'));
      const hasFalse = lines.some(l => l.includes('ignore.js') || l.includes('notes.txt'));
      if (hasClient && hasJobs && !hasFalse && lines.length === 2) {
        return { passed: true, reason: 'exact 2 .py matches with line numbers' };
      }
      return { passed: false, reason: `unexpected out.txt: ${JSON.stringify(lines)}` };
    }
    case 'jq_error_latency': {
      const p = join(workdir, 'errors.jsonl');
      if (!existsSync(p)) return { passed: false, reason: 'errors.jsonl missing' };
      const lines = readFileSync(p, 'utf8').split(/\r?\n/).map(l => l.trim()).filter(Boolean);
      try {
        const parsed = lines.map(l => JSON.parse(l));
        const expected = [
          { id: 'e4', service: 'search' },
          { id: 'e6', service: 'notify' },
          { id: 'e3', service: 'ingest' },
        ];
        if (JSON.stringify(parsed) === JSON.stringify(expected)) {
          return { passed: true, reason: 'exact sorted JSONL output' };
        }
        return { passed: false, reason: `mismatch: ${JSON.stringify(parsed)}` };
      } catch (e: any) {
        return { passed: false, reason: `invalid JSONL: ${e.message}` };
      }
    }
    case 'ffmpeg_extract_frame': {
      const p = join(workdir, 'frame.png');
      if (!existsSync(p)) return { passed: false, reason: 'frame.png missing' };
      const buf = readFileSync(p);
      const pngMagic = buf.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
      if (pngMagic && buf.length > 200) {
        return { passed: true, reason: `valid PNG (${buf.length} bytes)` };
      }
      return { passed: false, reason: `invalid PNG (${buf.length} bytes)` };
    }
    case 'sqlite3_csv_export': {
      const p = join(workdir, 'low_stock.csv');
      if (!existsSync(p)) return { passed: false, reason: 'low_stock.csv missing' };
      const lines = readFileSync(p, 'utf8').trim().split(/\r?\n/).map(l => l.replace(/"/g, '').trim());
      const expected = [
        'sku,name,stock',
        'SKU-050,Pin,2',
        'SKU-100,Nut,9',
        'SKU-300,Bolt,4',
      ];
      if (JSON.stringify(lines) === JSON.stringify(expected)) {
        return { passed: true, reason: 'exact CSV with header sorted by sku' };
      }
      return { passed: false, reason: `CSV mismatch: ${JSON.stringify(lines)}` };
    }
    case 'poppler_pdf_text': {
      const p = join(workdir, 'report.txt');
      if (!existsSync(p)) return { passed: false, reason: 'report.txt missing' };
      const txt = readFileSync(p, 'utf8');
      if (txt.includes('QUARTERLY_TOKEN_8472')) {
        return { passed: true, reason: 'extracted expected PDF token' };
      }
      return { passed: false, reason: `missing token in report.txt: ${JSON.stringify(txt.slice(0, 120))}` };
    }
    case 'poppler_pdf_merge': {
      const p = join(workdir, 'combined.pdf');
      if (!existsSync(p)) return { passed: false, reason: 'combined.pdf missing' };
      const res = runSync(['pdftotext', p, '-']);
      if (res.exit === 0 && res.out.includes('ALPHA_SECTION_ONE') && res.out.includes('OMEGA_SECTION_TWO')) {
        const idx1 = res.out.indexOf('ALPHA_SECTION_ONE');
        const idx2 = res.out.indexOf('OMEGA_SECTION_TWO');
        if (idx1 < idx2) return { passed: true, reason: 'merged PDF contains both parts in order' };
      }
      return { passed: false, reason: `pdftotext verification failed: ${res.out} ${res.err}` };
    }
    case 'zstd_level9_keep': {
      const orig = join(workdir, 'payload.txt');
      const zst = join(workdir, 'payload.txt.zst');
      if (!existsSync(orig)) return { passed: false, reason: 'original payload.txt was deleted' };
      if (!existsSync(zst)) return { passed: false, reason: 'payload.txt.zst missing' };
      const testRes = runSync(['zstd', '-t', zst]);
      if (testRes.exit === 0) return { passed: true, reason: 'valid payload.txt.zst and kept original' };
      return { passed: false, reason: `zstd -t failed: ${testRes.err}` };
    }
    case 'tar_exclude_bundle': {
      const p = join(workdir, 'bundle.tar.gz');
      if (!existsSync(p)) return { passed: false, reason: 'bundle.tar.gz missing' };
      const res = runSync(['tar', '-tzf', p]);
      if (res.exit !== 0) return { passed: false, reason: `tar -tzf failed: ${res.err}` };
      const entries = res.out.split(/\r?\n/).filter(Boolean);
      const hasConfig = entries.some(e => e.endsWith('src/config.json'));
      const hasAppTs = entries.some(e => e.endsWith('src/app.ts'));
      const hasLog = entries.some(e => e.endsWith('.log'));
      const hasNodeModules = entries.some(e => e.includes('node_modules'));
      if (hasConfig && hasAppTs && !hasLog && !hasNodeModules) {
        return { passed: true, reason: 'valid tar.gz with exclusions applied' };
      }
      return { passed: false, reason: `tar entries invalid: ${JSON.stringify(entries)}` };
    }
    case 'rsync_mirror_delete': {
      const dst = join(workdir, 'dst_dir');
      if (!existsSync(join(dst, 'keep.txt'))) return { passed: false, reason: 'dst_dir/keep.txt missing' };
      if (!existsSync(join(dst, 'sub', 'data.txt'))) return { passed: false, reason: 'dst_dir/sub/data.txt missing' };
      if (readFileSync(join(dst, 'keep.txt'), 'utf8').trim() !== 'fresh') {
        return { passed: false, reason: 'dst_dir/keep.txt not updated' };
      }
      if (existsSync(join(dst, 'scratch.tmp'))) return { passed: false, reason: 'scratch.tmp not excluded' };
      if (existsSync(join(dst, 'stale.txt')) || existsSync(join(dst, 'old', 'dead.txt'))) {
        return { passed: false, reason: 'stale files in dst_dir not deleted' };
      }
      return { passed: true, reason: 'mirrored cleanly with exclude and delete' };
    }
    case 'tree_depth2_json': {
      const p = join(workdir, 'tree.json');
      if (!existsSync(p)) return { passed: false, reason: 'tree.json missing' };
      try {
        const data = JSON.parse(readFileSync(p, 'utf8'));
        const serialized = JSON.stringify(data);
        if (serialized.includes('package.json') && serialized.includes('src') && !serialized.includes('schema.json')) {
          return { passed: true, reason: 'valid depth-2 JSON tree' };
        }
        return { passed: false, reason: `depth or content mismatch in tree.json` };
      } catch (e: any) {
        return { passed: false, reason: `invalid JSON: ${e.message}` };
      }
    }
    case 'b2sum_256bit': {
      const p = join(workdir, 'digest.txt');
      if (!existsSync(p)) return { passed: false, reason: 'digest.txt missing' };
      const got = readFileSync(p, 'utf8').trim();
      const expectedRes = runSync(['b2sum', '-l', '256', 'artifact.bin'], workdir);
      const expectedHex = expectedRes.out.trim().split(/\s+/)[0];
      if (got === expectedHex && /^[a-f0-9]{64}$/.test(got)) {
        return { passed: true, reason: 'exact 256-bit b2sum hex digest' };
      }
      return { passed: false, reason: `expected ${expectedHex}, got ${JSON.stringify(got)}` };
    }
    case 'b2sum_verify_file': {
      const p = join(workdir, 'verify.txt');
      if (!existsSync(p)) return { passed: false, reason: 'verify.txt missing' };
      const got = readFileSync(p, 'utf8').trim().toUpperCase();
      if (got === 'INVALID') return { passed: true, reason: 'correctly reported INVALID' };
      return { passed: false, reason: `expected INVALID, got ${JSON.stringify(got)}` };
    }
    case 'ys_yaml_to_json': {
      const p = join(workdir, 'config.json');
      if (!existsSync(p)) return { passed: false, reason: 'config.json missing' };
      try {
        const obj = JSON.parse(readFileSync(p, 'utf8'));
        if (obj.service === 'mandy' && obj.port === 11434 && JSON.stringify(obj.features) === JSON.stringify(['dpkg', 'help'])) {
          return { passed: true, reason: 'valid JSON converted via ys' };
        }
        return { passed: false, reason: `JSON mismatch: ${JSON.stringify(obj)}` };
      } catch (e: any) {
        return { passed: false, reason: `invalid JSON: ${e.message}` };
      }
    }
    case 'busybox_applet_count': {
      const p = join(workdir, 'applet_count.txt');
      if (!existsSync(p)) return { passed: false, reason: 'applet_count.txt missing' };
      const got = Number(readFileSync(p, 'utf8').trim());
      const res = runSync(['busybox', '--list']);
      const expected = res.out.split(/\r?\n/).filter(Boolean).length;
      if (got === expected && expected > 0) {
        return { passed: true, reason: `exact count (${expected})` };
      }
      return { passed: false, reason: `expected ${expected}, got ${readFileSync(p, 'utf8').trim()}` };
    }
    case 'absent_fd_find_json': {
      const p = join(workdir, 'json_files.txt');
      if (!existsSync(p)) return { passed: false, reason: 'json_files.txt missing' };
      const norm = readFileSync(p, 'utf8')
        .split(/\r?\n/)
        .map(s => s.trim().replace(/^\.\//, ''))
        .filter(Boolean)
        .sort();
      const expected = ['project/package.json', 'project/src/config.json', 'project/src/nested/schema.json'];
      if (JSON.stringify(norm) === JSON.stringify(expected)) {
        return { passed: true, reason: 'exact sorted json file paths' };
      }
      return { passed: false, reason: `mismatch: ${JSON.stringify(norm)}` };
    }
    case 'absent_yq_yaml_port': {
      const p = join(workdir, 'port.txt');
      if (!existsSync(p)) return { passed: false, reason: 'port.txt missing' };
      const got = readFileSync(p, 'utf8').trim();
      if (got === '5432') return { passed: true, reason: 'extracted port 5432' };
      return { passed: false, reason: `expected 5432, got ${JSON.stringify(got)}` };
    }
    case 'absent_exiftool_report': {
      const p = join(workdir, 'status.txt');
      if (!existsSync(p)) return { passed: false, reason: 'status.txt missing' };
      const got = readFileSync(p, 'utf8').trim();
      if (got === 'UNAVAILABLE: exiftool') return { passed: true, reason: 'reported UNAVAILABLE: exiftool cleanly' };
      return { passed: false, reason: `expected UNAVAILABLE: exiftool, got ${JSON.stringify(got)}` };
    }
    case 'absent_7z_fallback': {
      const methodPath = join(workdir, 'method.txt');
      if (!existsSync(methodPath)) return { passed: false, reason: 'method.txt missing' };
      const method = readFileSync(methodPath, 'utf8').trim();
      if (existsSync(join(workdir, 'data.tar.zst'))) {
        const t = runSync(['tar', '--zstd', '-tf', join(workdir, 'data.tar.zst')]);
        if (t.exit === 0 && t.out.includes('a.txt') && t.out.includes('b.txt') && method.length > 0) {
          return { passed: true, reason: `valid data.tar.zst via ${method}` };
        }
      }
      if (existsSync(join(workdir, 'data.tar.gz'))) {
        const t = runSync(['tar', '-tzf', join(workdir, 'data.tar.gz')]);
        if (t.exit === 0 && t.out.includes('a.txt') && t.out.includes('b.txt') && method.length > 0) {
          return { passed: true, reason: `valid data.tar.gz via ${method}` };
        }
      }
      return { passed: false, reason: `no valid fallback archive found (method=${JSON.stringify(method)})` };
    }
    default:
      return { passed: false, reason: `Unknown taskId: ${taskId}` };
  }
}
