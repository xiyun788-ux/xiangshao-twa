// 在 GitHub Actions runner 内运行：把本地 keystore.b64 用 libsodium 密封加密后写入仓库 Secret。
//
// 为什么不用 `gh secret set`：`gh auth login --with-token` 会强制校验 token 必须带 read:org 权限，
// 而我们这个 PAT 只有 repo(+workflow)，gh 会直接报 "missing required scope 'read:org'"。
// 因此改走 GitHub REST API + libsodium seal（与官方 encrypt/decrypt 流程一致）。
//
// 用法：
//   GH_PAT=<token> GITHUB_REPOSITORY=owner/repo SECRET_NAME=SIGNING_KEYSTORE node scripts/seal-secret.mjs
// 读取当前目录下的 keystore.b64（keystore 文件的 base64 文本）。
import { readFileSync } from 'fs';
import pkg from 'tweetsodium';

const { seal } = pkg;

const token = process.env.GH_PAT;
const repo = process.env.GITHUB_REPOSITORY;
const name = process.env.SECRET_NAME || 'SIGNING_KEYSTORE';

if (!token || !repo) {
  console.error('missing GH_PAT or GITHUB_REPOSITORY');
  process.exit(1);
}

const value = readFileSync('keystore.b64', 'utf8').trim();
if (!value) {
  console.error('keystore.b64 is empty');
  process.exit(1);
}

const headers = {
  Authorization: `token ${token}`,
  Accept: 'application/vnd.github+json',
};

// 1. 取仓库的加密公钥
const pkRes = await fetch(`https://api.github.com/repos/${repo}/actions/secrets/public-key`, { headers });
const pk = await pkRes.json();
if (!pk.key) {
  console.error('public-key fetch failed', pkRes.status, JSON.stringify(pk));
  process.exit(1);
}

// 2. libsodium 密封（crypto_box_seal）
const publicKey = Buffer.from(pk.key, 'base64');
const sealed = seal(Buffer.from(value, 'utf8'), publicKey);
const encrypted = Buffer.from(sealed).toString('base64');

// 3. 写入 Secret
const putRes = await fetch(`https://api.github.com/repos/${repo}/actions/secrets/${name}`, {
  method: 'PUT',
  headers: { ...headers, 'Content-Type': 'application/json' },
  body: JSON.stringify({ encrypted_value: encrypted, key_id: pk.key_id }),
});
const body = await putRes.text();
console.log(`set ${name} -> ${putRes.status} ${body}`);
if (putRes.status !== 201 && putRes.status !== 204) process.exit(1);
