const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const html = fs.readFileSync(path.join(__dirname, "..", "prototype.html"), "utf8");
const start = html.indexOf("async function readApiJsonResponse");
const end = html.indexOf("function setCompilerOutput", start);

assert.ok(start >= 0 && end > start, "could not locate readApiJsonResponse source");

const sandbox = {};
vm.createContext(sandbox);
vm.runInContext(html.slice(start, end), sandbox);

function response(status, contentType, body) {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: {
      get(name) {
        return String(name).toLowerCase() === "content-type" ? contentType : "";
      }
    },
    async text() {
      return body;
    }
  };
}

async function main() {
  const valid = await sandbox.readApiJsonResponse(
    response(200, "application/json", '{"output":"ok\\n"}'),
    "代码执行失败"
  );
  assert.equal(valid.output, "ok\n");

  const htmlError = await sandbox.readApiJsonResponse(
    response(502, "text/html; charset=UTF-8", "<html><title>Bad gateway</title></html>"),
    "代码执行失败"
  );
  assert.match(htmlError.error, /HTTP 502/);
  assert.match(htmlError.error, /网页错误/);
  assert.doesNotMatch(htmlError.error, /<html>/);

  const emptyError = await sandbox.readApiJsonResponse(
    response(504, "", ""),
    "代码执行失败"
  );
  assert.match(emptyError.error, /HTTP 504/);
  assert.match(emptyError.error, /没有返回有效内容/);

  const malformed = await sandbox.readApiJsonResponse(
    response(200, "text/plain", "not-json"),
    "代码执行失败"
  );
  assert.match(malformed.error, /无法识别/);

  console.log("compiler-response-behavior-ok cases=4");
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exit(1);
});
