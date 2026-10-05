#!/usr/bin/env node
/**
 * 环境初始化脚本：生成开发用的 TLS 证书、信任库，以及全部认证密钥与数据库口令。
 *
 * 用法：
 *   node scripts/setup.mjs            # 已存在则复用，缺失才生成
 *   node scripts/setup.mjs --reset    # 证书与密钥全部重新生成
 *
 * 产物（都在 .runtime/ 下，已被 .gitignore 忽略，不会进版本库）：
 *   localhost.p12 / localhost.key / localhost.crt / truststore.p12   TLS 证书与信任库
 *   secrets.json                                                     密钥表
 *
 * secrets.json 里的每一项都会被启动脚本同时注入为**环境变量**与 **-D 启动参数**：
 *   GATEWAY_KEY / BUSINESS_KEY / DATA_KEY / AUDIT_KEY / LEDGER_KEY / AUDIT_DATA_KEY  服务间 HMAC 与字段加密
 *   TLS_PASSWORD                                                                     PKCS12 与信任库口令
 *   DB_CIPHER_KEY / DB_PASSWORD                                                      H2 整库加密的两段式口令
 *
 * 为什么这两把数据库口令要分开：H2 在 CIPHER=AES 下的会话口令是「文件口令 + 空格 + 用户口令」，
 * 文件口令用于加密整个库文件，用户口令用于账号认证；两段都是随机生成的 32 字节十六进制串，
 * 用户不需要（也无法）手输。见 common/src/main/java/edu/campus/common/ConfigGuard.java。
 */
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const runtime = path.join(root, ".runtime");
fs.mkdirSync(runtime, { recursive: true, mode: 0o700 });
for (const name of ["database", "ledger", "logs"])
    fs.mkdirSync(path.join(runtime, name), { recursive: true, mode: 0o700 });

const reset = process.argv.includes("--reset");

const secretsFile = path.join(runtime, "secrets.json");
/** 必须与 ConfigGuard.REQUIRED_KEYS 一致。 */
const SECRET_KEYS = [
    "GATEWAY_KEY",
    "BUSINESS_KEY",
    "DATA_KEY",
    "AUDIT_KEY",
    "LEDGER_KEY",
    "AUDIT_DATA_KEY",
    "TLS_PASSWORD",
    "DB_PASSWORD",
    "DB_CIPHER_KEY",
];

function randomSecret() {
    return crypto.randomBytes(32).toString("hex");
}

function readSecrets() {
    if (!fs.existsSync(secretsFile)) return {};
    try {
        return JSON.parse(fs.readFileSync(secretsFile, "utf8"));
    } catch {
        return {};
    }
}

const secrets = reset ? {} : readSecrets();
let generated = 0;
for (const key of SECRET_KEYS) {
    if (!secrets[key] || String(secrets[key]).length < 16) {
        secrets[key] = randomSecret();
        generated++;
    }
}
const ordered = {};
for (const key of SECRET_KEYS) ordered[key] = secrets[key];
fs.writeFileSync(secretsFile, JSON.stringify(ordered, null, 2) + "\n", { mode: 0o600 });
console.log(
    generated > 0
        ? `✓ 密钥已生成（${generated} 项，其余复用）：.runtime/secrets.json`
        : "✓ 密钥已存在，全部复用：.runtime/secrets.json",
);

// TLS 证书与信任库使用同一把随机口令（不再用写死的 campus-dev-tls-2024）
const TLS_PASSWORD = ordered.TLS_PASSWORD;

function run(command, args) {
    const r = spawnSync(command, args, {
        stdio: "inherit",
        cwd: root,
        shell: false,
    });
    if (r.status !== 0) throw new Error(`${command} failed`);
}

const p12File = path.join(runtime, "localhost.p12");
// node-forge reads PKCS12 without an OpenSSL executable, including on Windows.
const forge = (await import("../frontend/node_modules/node-forge/lib/index.js"))
    .default;

let passwordMatches = false;
if (fs.existsSync(p12File)) {
    try {
        // 口令是随机的：只有用它真的能打开证书库，才算匹配（否则说明口令换了）
        forge.pkcs12.pkcs12FromAsn1(forge.asn1.fromDer(fs.readFileSync(p12File).toString("binary")), TLS_PASSWORD);
        passwordMatches = true;
    } catch {
        passwordMatches = false;
    }
}
if (!fs.existsSync(p12File) || reset || !passwordMatches) {
    if (fs.existsSync(p12File)) fs.unlinkSync(p12File);
    console.log("生成自签名 TLS 证书（localhost）...");
    run("keytool", [
        "-genkeypair",
        "-alias",
        "campus",
        "-keyalg",
        "RSA",
        "-keysize",
        "3072",
        "-validity",
        "825",
        "-storetype",
        "PKCS12",
        "-keystore",
        p12File,
        "-storepass",
        TLS_PASSWORD,
        "-dname",
        "CN=localhost,OU=Teaching,O=Campus,L=Xian,ST=Shaanxi,C=CN",
        "-ext",
        "SAN=dns:localhost,ip:127.0.0.1",
    ]);
}

const der = fs
    .readFileSync(p12File)
    .toString("binary");
const p12 = forge.pkcs12.pkcs12FromAsn1(
    forge.asn1.fromDer(der),
    TLS_PASSWORD,
);
const key = p12.getBags({ bagType: forge.pki.oids.pkcs8ShroudedKeyBag })[
    forge.pki.oids.pkcs8ShroudedKeyBag
][0].key;
const cert = p12.getBags({ bagType: forge.pki.oids.certBag })[
    forge.pki.oids.certBag
][0].cert;
fs.writeFileSync(
    path.join(runtime, "localhost.key"),
    forge.pki.privateKeyToPem(key),
    { mode: 0o600 },
);
fs.writeFileSync(
    path.join(runtime, "localhost.crt"),
    forge.pki.certificateToPem(cert),
);
const truststoreFile = path.join(runtime, "truststore.p12");
if (!fs.existsSync(truststoreFile) || reset || !passwordMatches) {
    if (fs.existsSync(truststoreFile))
        fs.unlinkSync(truststoreFile);
    run("keytool", [
        "-importcert",
        "-noprompt",
        "-alias",
        "campus-ca",
        "-file",
        path.join(runtime, "localhost.crt"),
        "-keystore",
        truststoreFile,
        "-storetype",
        "PKCS12",
        "-storepass",
        TLS_PASSWORD,
    ]);
}

console.log("✓ TLS 证书就绪（.runtime/localhost.p12，口令为 secrets.json 中的 TLS_PASSWORD）");
console.log("✓ 数据库整库加密已开启（CIPHER=AES），口令为 secrets.json 中的 DB_CIPHER_KEY + DB_PASSWORD");
